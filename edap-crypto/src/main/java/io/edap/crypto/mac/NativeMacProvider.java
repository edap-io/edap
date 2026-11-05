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

package io.edap.crypto.mac;

import io.edap.crypto.internal.ByteAccumulator;
import io.edap.crypto.internal.NativeBridge;

import java.lang.invoke.MethodHandle;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * native MAC 实现（OpenSSL libcrypto，via edap-native）。
 *
 * <p><b>算法映射</b>：JCE 名 → native 方法名。</p>
 * <table>
 *   <caption>算法映射</caption>
 *   <tr><th>JCE</th><th>native 方法</th><th>输出长度</th></tr>
 *   <tr><td>HmacSHA256</td><td>hmacSha2560</td><td>32</td></tr>
 *   <tr><td>HmacSHA1</td><td>hmacSha10</td><td>20</td></tr>
 * </table>
 *
 * <p><b>流式语义</b>：与 {@link NativeDigestProvider} 同理 —— native 侧一次性，
 * 累加在 Java 侧。</p>
 */
public class NativeMacProvider implements MacProvider {

    /**
     * edap-native 里的实现类。
     */
    public static final String NATIVE_CLASS = "io.edap.jni.crypto.NativeMac";

    private static final Map<String, String> METHOD_MAP = new HashMap<>();

    private static final Map<String, MethodHandle> HANDLES = new ConcurrentHashMap<>();

    static {
        METHOD_MAP.put("HmacSHA256", "hmacSha2560");
        METHOD_MAP.put("HmacSHA1", "hmacSha10");
    }

    private final String algorithm;

    private final MethodHandle macHandle;

    private NativeMacProvider(String algorithm, MethodHandle macHandle) {
        this.algorithm = algorithm;
        this.macHandle = macHandle;
    }

    /**
     * 尝试创建 native MAC provider。
     *
     * @return native 不可用或算法不支持时返回 {@code null}（调用方降级到 JCE）
     */
    public static NativeMacProvider tryCreate(String algorithm) {
        String method = METHOD_MAP.get(algorithm);
        if (method == null) {
            return null;
        }
        if (!NativeBridge.isNativeAvailable(NATIVE_CLASS)) {
            return null;
        }
        try {
            MethodHandle handle = HANDLES.get(method);
            if (handle == null) {
                handle = NativeBridge.findStatic(NATIVE_CLASS, method,
                        byte[].class, byte[].class, byte[].class, int.class, int.class);
                HANDLES.put(method, handle);
            }
            return new NativeMacProvider(algorithm, handle);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public String algorithm() {
        return algorithm;
    }

    @Override
    public MacSession newSession(byte[] key) {
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("mac key required");
        }
        return new NativeMacSession(macHandle, key);
    }

    @Override
    public int getMacLength() {
        switch (algorithm) {
            case "HmacSHA256":
                return 32;
            case "HmacSHA1":
                return 20;
            default:
                return -1;
        }
    }

    /**
     * native MAC 会话 —— Java 侧累积，{@code doFinal()} 时一次性进 native。
     */
    static final class NativeMacSession implements MacSession {

        private final MethodHandle macHandle;

        private final byte[] key;

        private final ByteAccumulator accumulator = new ByteAccumulator();

        NativeMacSession(MethodHandle macHandle, byte[] key) {
            this.macHandle = macHandle;
            // 拷贝一份 —— 调用方可能复用/改写原数组
            this.key = key.clone();
        }

        @Override
        public void update(byte[] data, int offset, int len) {
            accumulator.append(data, offset, len);
        }

        @Override
        public byte[] doFinal() {
            // 零拷贝：直接用累加器的内部缓冲 + 实际长度，不再 toArray() 拷一份
            byte[] data = accumulator.buffer();
            int len = accumulator.size();
            try {
                byte[] result = (byte[]) macHandle.invokeExact(key, data, 0, len);
                accumulator.reset();
                return result;
            } catch (Throwable t) {
                throw new RuntimeException("native mac failed", t);
            }
        }

        @Override
        public void reset() {
            accumulator.reset();
        }
    }
}
