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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * native 摘要实现（OpenSSL libcrypto，via edap-native）。
 *
 * <p><b>算法映射</b>：JCE 名 → native 方法名。native 只支持这三个：</p>
 * <table>
 *   <caption>算法映射</caption>
 *   <tr><th>JCE</th><th>native 方法</th><th>输出长度</th></tr>
 *   <tr><td>SHA-256</td><td>digestSha2560</td><td>32</td></tr>
 *   <tr><td>SHA-1</td><td>digestSha10</td><td>20</td></tr>
 *   <tr><td>MD5</td><td>digestMd50</td><td>16</td></tr>
 * </table>
 *
 * <p><b>★ 为什么按算法绑定不同的 native 方法</b>（而不是一个带 algorithm 字符串的
 * 统一入口）：字符串参数会让每次调用都付 {@code GetStringUTFChars} + {@code strcmp}
 * + {@code ReleaseStringUTFChars} 的固定成本 —— 在 Solaris/SPARC 上实测约
 * <b>9.5µs/次</b>。算法名在这里一次性解析成方法句柄并缓存，热路径上零字符串。</p>
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

    /**
     * JCE 算法名 → native 方法名。
     */
    private static final Map<String, String> METHOD_MAP = new HashMap<>();

    /**
     * JCE 算法名 → <b>空输入</b>的摘要常量（探测用，见 {@link #verifySymbol}）。
     */
    private static final Map<String, byte[]> EMPTY_DIGEST = new HashMap<>();

    /**
     * native 方法名 → 绑定好的方法句柄（每个算法只绑定一次）。
     */
    private static final Map<String, MethodHandle> HANDLES = new ConcurrentHashMap<>();

    /**
     * 探测用的空数组。
     */
    private static final byte[] EMPTY = new byte[0];

    static {
        METHOD_MAP.put("SHA-256", "digestSha2560");
        METHOD_MAP.put("SHA-1", "digestSha10");
        METHOD_MAP.put("MD5", "digestMd50");
        EMPTY_DIGEST.put("SHA-256", hex("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
        EMPTY_DIGEST.put("SHA-1", hex("da39a3ee5e6b4b0d3255bfef95601890afd80709"));
        EMPTY_DIGEST.put("MD5", hex("d41d8cd98f00b204e9800998ecf8427e"));
    }

    private final String algorithm;

    private final MethodHandle digestHandle;

    private NativeDigestProvider(String algorithm, MethodHandle digestHandle) {
        this.algorithm = algorithm;
        this.digestHandle = digestHandle;
    }

    /**
     * 尝试创建 native 摘要 provider。
     *
     * @return native 不可用或算法不支持时返回 {@code null}（调用方降级到 JCE）
     */
    public static NativeDigestProvider tryCreate(String algorithm) {
        String method = METHOD_MAP.get(algorithm);
        if (method == null) {
            return null;
        }
        if (!NativeBridge.isNativeAvailable(NATIVE_CLASS)) {
            return null;
        }
        MethodHandle handle = HANDLES.get(method);
        if (handle == null) {
            MethodHandle bound;
            try {
                bound = NativeBridge.findStatic(NATIVE_CLASS, method,
                        byte[].class, byte[].class, int.class, int.class);
            } catch (Throwable t) {
                // 绑不上（类不在 / 签名不匹配）⇒ native 不可用 ⇒ 调用方降级到 JCE
                return null;
            }
            // ★ 探测失败必须炸出来 —— 不能被上面的 catch 吞掉变成「静默降级到 JCE」
            verifySymbol(method, bound, EMPTY_DIGEST.get(algorithm));
            HANDLES.put(method, bound);
            handle = bound;
        }
        return new NativeDigestProvider(algorithm, handle);
    }

    /**
     * <b>★ 一次性探测：确认预编译产物里真的有这个 JNI 符号。</b>
     *
     * <p><b>为什么必须探测</b>：JNI 符号缺失（预编译产物是<b>旧版本</b>）不会在
     * {@code findStatic} 时暴露 —— Java 侧的 native 方法声明一直都在，
     * 只有真正调用时才会抛 {@link UnsatisfiedLinkError}。那时排查的人看到的是
     * 一句「UnsatisfiedLinkError」，而不是「产物过期了，重新构建」，
     * 而且报错位置离原因十万八千里。</p>
     *
     * <p><b>为什么探测失败要抛而不是返回 null</b>：返回 null 会被
     * {@code CryptoRegistry} 当成「native 不可用」<b>静默降级到 JCE</b> ——
     * 在 Solaris 上这正是「以为在用 JNI 加速、其实一直在跑 JCE」那类最难查的问题。
     * 库能加载说明平台是支持的，符号缺失只可能是<b>产物与代码不是同一版本</b>
     * ⇒ 配置/构建错误，应当立刻炸出来。</p>
     *
     * <p>探测用空输入：结果必须是已知的空摘要常量（顺便校验长度与算法没串）。</p>
     *
     * @param method   native 方法名（报错里要点名）
     * @param handle   已绑定的句柄
     * @param expected 空输入的期望摘要
     */
    private static void verifySymbol(String method, MethodHandle handle, byte[] expected) {
        byte[] actual;
        try {
            actual = (byte[]) handle.invokeExact(EMPTY, 0, 0);
        } catch (UnsatisfiedLinkError e) {
            throw new IllegalStateException(staleArtifactMessage(method), e);
        } catch (Throwable t) {
            throw new IllegalStateException("native digest 探测失败（" + method + "）: " + t, t);
        }
        String mismatch = probeMismatchMessage(method, expected, actual);
        if (mismatch != null) {
            throw new IllegalStateException(mismatch);
        }
    }

    /**
     * 「预编译产物过期」的报错文案（诊断/测试用）。
     *
     * <p>抽成公开静态方法是为了能单测文案本身：真正触发它需要一份<b>旧版本</b>
     * 的 {@code .o}，在正常构建里永远构造不出来 —— 而这条文案的措辞
     * （点名符号 + 给出重建命令）恰恰是排查时最需要的东西，不能没人验证。</p>
     *
     * @param method 缺失的 native 方法名
     */
    public static String staleArtifactMessage(String method) {
        return "edap-native 预编译产物过期：缺少 JNI 符号 " + method
                + "（当前 .o 与 Java 侧不是同一版本）。"
                + "请在目标机上重新构建：edap-native/scripts/build-native.sh --commit";
    }

    /**
     * 「探测结果不对」的报错文案（诊断/测试用）。
     *
     * @return 通过时返回 {@code null}；不通过时返回可读的说明
     */
    public static String probeMismatchMessage(String method, byte[] expected, byte[] actual) {
        if (expected == null || Arrays.equals(expected, actual)) {
            return null;
        }
        return "native digest 探测结果不对（" + method + "）：期望 "
                + HexFormat.of().formatHex(expected) + "，实际 "
                + (actual == null ? "null" : HexFormat.of().formatHex(actual))
                + " —— 预编译产物与 Java 侧不匹配";
    }

    /**
     * 十六进制串 → 字节数组。
     */
    private static byte[] hex(String s) {
        return HexFormat.of().parseHex(s);
    }

    @Override
    public String algorithm() {
        return algorithm;
    }

    @Override
    public DigestSession newSession() {
        return new NativeDigestSession(digestHandle, algorithm);
    }

    @Override
    public int getDigestLength() {
        switch (algorithm) {
            case "SHA-256":
                return 32;
            case "SHA-1":
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

        private final String algorithm;

        private final ByteAccumulator accumulator = new ByteAccumulator();

        NativeDigestSession(MethodHandle digestHandle, String algorithm) {
            this.digestHandle = digestHandle;
            this.algorithm = algorithm;
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
                byte[] result = (byte[]) digestHandle.invokeExact(data, 0, len);
                accumulator.reset();
                return result;
            } catch (Throwable t) {
                throw new RuntimeException("native digest failed: " + algorithm, t);
            }
        }

        @Override
        public void reset() {
            accumulator.reset();
        }
    }
}
