/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.crypto.digest;

import io.edap.crypto.internal.ByteAccumulator;
import io.edap.crypto.internal.NativeBridge;

import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * native 摘要实现（OpenSSL libcrypto，via edap-native）。
 *
 * <p><b>算法映射</b>：JCE 名 → OpenSSL 名。native 只支持这三个：</p>
 * <table>
 *   <caption>算法映射</caption>
 *   <tr><th>JCE</th><th>OpenSSL</th></tr>
 *   <tr><td>SHA-256</td><td>SHA256</td></tr>
 *   <tr><td>SHA-1</td><td>SHA1</td></tr>
 *   <tr><td>MD5</td><td>MD5</td></tr>
 * </table>
 *
 * <p><b>流式语义</b>：native 侧是一次性 API，SPI 要求流式 ⇒ 累加在 Java 侧做，
 * {@code digest()} 时一次性交给 native。</p>
 *
 * <p><b>为什么用 legacy {@code SHA256_*} 而不是 EVP</b>：见
 * {@code edap-native/doc/NATIVE_DESIGN.md} §6.6 —— native HMAC 相对 Java
 * 7.81x~8.24x，而 OpenSSL 3.x 的 provider dispatch（{@code inner_evp_generic_fetch}
 * 吃 15.1% CPU）在多线程下会退化。</p>
 */
public class NativeDigestProvider implements DigestProvider {

    /**
     * edap-native 里的实现类。
     */
    public static final String NATIVE_CLASS = "io.edap.jni.crypto.NativeDigest";

    private static final Map<String, String> ALGORITHM_MAP = new HashMap<>();

    /**
     * 算法名 → 绑定好的 native 方法句柄（每个算法只绑定一次）。
     */
    private static final Map<String, MethodHandle> HANDLES = new ConcurrentHashMap<>();

    static {
        ALGORITHM_MAP.put("SHA-256", "SHA256");
        ALGORITHM_MAP.put("SHA-1", "SHA1");
        ALGORITHM_MAP.put("MD5", "MD5");
    }

    private final String algorithm;

    private final String nativeAlgorithm;

    private final MethodHandle digestHandle;

    private NativeDigestProvider(String algorithm, String nativeAlgorithm, MethodHandle digestHandle) {
        this.algorithm = algorithm;
        this.nativeAlgorithm = nativeAlgorithm;
        this.digestHandle = digestHandle;
    }

    /**
     * 尝试创建 native 摘要 provider。
     *
     * @return native 不可用或算法不支持时返回 {@code null}（调用方降级到 JCE）
     */
    public static NativeDigestProvider tryCreate(String algorithm) {
        String nativeAlg = ALGORITHM_MAP.get(algorithm);
        if (nativeAlg == null) {
            return null;
        }
        if (!NativeBridge.isNativeAvailable(NATIVE_CLASS)) {
            return null;
        }
        try {
            MethodHandle handle = HANDLES.get(nativeAlg);
            if (handle == null) {
                handle = NativeBridge.findStatic(NATIVE_CLASS, "digest0",
                        byte[].class, String.class, byte[].class, int.class, int.class);
                HANDLES.put(nativeAlg, handle);
            }
            return new NativeDigestProvider(algorithm, nativeAlg, handle);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public String algorithm() {
        return algorithm;
    }

    @Override
    public DigestSession newSession() {
        return new NativeDigestSession(digestHandle, nativeAlgorithm);
    }

    @Override
    public int getDigestLength() {
        switch (nativeAlgorithm) {
            case "SHA256":
                return 32;
            case "SHA1":
                return 20;
            case "MD5":
                return 16;
            default:
                return -1;
        }
    }

    /**
     * native 摘要会话 —— Java 侧累积，{@code digest()} 时一次性进 native。
     */
    static final class NativeDigestSession implements DigestSession {

        private final MethodHandle digestHandle;

        private final String nativeAlgorithm;

        private final ByteAccumulator accumulator = new ByteAccumulator();

        NativeDigestSession(MethodHandle digestHandle, String nativeAlgorithm) {
            this.digestHandle = digestHandle;
            this.nativeAlgorithm = nativeAlgorithm;
        }

        @Override
        public void update(byte[] data, int offset, int len) {
            accumulator.append(data, offset, len);
        }

        @Override
        public byte[] digest() {
            // 零拷贝：直接用累加器的内部缓冲 + 实际长度，不再 toArray() 拷一份
            byte[] data = accumulator.buffer();
            int len = accumulator.size();
            try {
                byte[] result = (byte[]) digestHandle.invokeExact(nativeAlgorithm, data, 0, len);
                accumulator.reset();
                return result;
            } catch (Throwable t) {
                throw new RuntimeException("native digest failed: " + nativeAlgorithm, t);
            }
        }

        @Override
        public void reset() {
            accumulator.reset();
        }
    }
}
