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

import io.edap.crypto.internal.NativeBridge;
import io.edap.crypto.internal.NativeStreamBinding;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * native 摘要实现（OpenSSL libcrypto，via edap-native）。
 *
 * <p><b>算法映射</b>：JCE 名 → native 算法编号。native 支持这三个：</p>
 * <table>
 *   <caption>算法映射</caption>
 *   <tr><th>JCE</th><th>native 算法编号</th><th>输出长度</th></tr>
 *   <tr><td>SHA-256</td><td>0</td><td>32</td></tr>
 *   <tr><td>SHA-1</td><td>1</td><td>20</td></tr>
 *   <tr><td>MD5</td><td>2</td><td>16</td></tr>
 * </table>
 *
 * <p><b>★ 为什么按算法绑定不同的编号（而不是传算法名字符串）</b>：字符串参数会让每次调用
 * 都付 {@code GetStringUTFChars} + {@code strcmp} + {@code ReleaseStringUTFChars} 的固定成本 ——
 * 在 Solaris/SPARC 上实测约 <b>9.5µs/次</b>。算法名在这里一次性解析成编号并缓存，
 * 热路径上零字符串。</p>
 *
 * <p><b>★★ 流式：native 侧持有摘要上下文</b>：
 * 早先的实现是「native 一次性 API + Java 侧用 {@code ByteAccumulator} 累积」——
 * 整份数据先拷一遍进 Java 堆，等 {@code digest()} 才交给 native。
 * 实测（Solaris/SPARC，Oracle JDK 17）：64KB 载荷光这次累积就要 <b>~17.7µs</b>
 * （一次 64KB 分配 + 一次 64KB arraycopy），<b>与一次 SHA-256 本体同量级</b>。
 * ⇒ 现在数据喂一次算一次，累积拷贝整个消失。</p>
 *
 * <p><b>为什么用 legacy {@code SHA256_*} 而不是 EVP</b>：见
 * {@code edap-native/doc/NATIVE_DESIGN.md} §6.6 —— native HMAC 相对 Java
 * 7.81x~8.24x，而 OpenSSL 3.x 的 provider dispatch（{@code inner_evp_generic_fetch}
 * 吃 15.1% CPU）在多线程下会退化。</p>
 */
public class NativeDigestProvider implements DigestProvider {

    /**
     * edap-native 里的流式实现类。
     */
    public static final String NATIVE_CLASS = NativeStreamBinding.NATIVE_CLASS;

    /**
     * JCE 算法名 → native 算法编号。
     */
    private static final Map<String, Integer> ALG_MAP = new HashMap<>();

    /**
     * JCE 算法名 → <b>空输入</b>的摘要常量（探测用，见 {@link #verifySymbol}）。
     */
    private static final Map<String, byte[]> EMPTY_DIGEST = new HashMap<>();

    /**
     * 探测用的空数组。
     */
    private static final byte[] EMPTY = new byte[0];

    static {
        ALG_MAP.put("SHA-256", 0);
        ALG_MAP.put("SHA-1", 1);
        ALG_MAP.put("MD5", 2);
        EMPTY_DIGEST.put("SHA-256", hex("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));
        EMPTY_DIGEST.put("SHA-1", hex("da39a3ee5e6b4b0d3255bfef95601890afd80709"));
        EMPTY_DIGEST.put("MD5", hex("d41d8cd98f00b204e9800998ecf8427e"));
    }

    private final String algorithm;

    private final int alg;

    private final NativeStreamBinding binding;

    private NativeDigestProvider(String algorithm, int alg, NativeStreamBinding binding) {
        this.algorithm = algorithm;
        this.alg = alg;
        this.binding = binding;
    }

    /**
     * 尝试创建 native 摘要 provider。
     *
     * @return native 不可用或算法不支持时返回 {@code null}（调用方降级到 JCE）
     */
    public static NativeDigestProvider tryCreate(String algorithm) {
        Integer alg = ALG_MAP.get(algorithm);
        if (alg == null) {
            return null;
        }
        if (!NativeBridge.isNativeAvailable(NATIVE_CLASS)) {
            return null;
        }
        NativeStreamBinding binding;
        try {
            binding = NativeStreamBinding.get();
        } catch (Throwable t) {
            // 类/方法不在（edap-native 版本太老，或根本不在 classpath 上）
            // ⇒ native 不可用 ⇒ 调用方降级到 JCE
            return null;
        }
        // ★ 探测失败必须炸出来 —— 不能被上面的 catch 吞掉变成「静默降级到 JCE」
        verifySymbol(algorithm, alg, binding, EMPTY_DIGEST.get(algorithm));
        return new NativeDigestProvider(algorithm, alg, binding);
    }

    /**
     * <b>★ 一次性探测：确认预编译产物里真的有这 3 个 JNI 符号。</b>
     *
     * <p><b>为什么必须探测</b>：JNI 符号缺失（预编译产物是<b>旧版本</b>）不会在
     * 绑定时暴露 —— Java 侧的 native 方法声明一直都在，只有真正调用时才会抛
     * {@link UnsatisfiedLinkError}。那时排查的人看到的是一句「UnsatisfiedLinkError」，
     * 而不是「产物过期了，重新构建」，而且报错位置离原因十万八千里。</p>
     *
     * <p><b>为什么探测失败要抛而不是返回 null</b>：返回 null 会被
     * {@code CryptoRegistry} 当成「native 不可用」<b>静默降级到 JCE</b> ——
     * 在 Solaris 上这正是「以为在用 JNI 加速、其实一直在跑 JCE」那类最难查的问题。
     * 库能加载说明平台是支持的，符号缺失只可能是<b>产物与代码不是同一版本</b>
     * ⇒ 配置/构建错误，应当立刻炸出来。</p>
     *
     * <p>探测用空输入：结果必须是已知的空摘要常量（顺便校验长度与算法没串）。
     * {@code free} 符号单独探一次（探测失败要能被发现，不能等到异常路径才暴露）。</p>
     *
     * @param algorithm 算法名（报错里要点名）
     * @param alg       native 算法编号
     * @param binding   已绑定的流式入口
     * @param expected  空输入的期望摘要
     */
    private static void verifySymbol(String algorithm, int alg, NativeStreamBinding binding, byte[] expected) {
        byte[] actual;
        long ctx = binding.update(0, alg, null, 0, EMPTY, 0, 0);
        try {
            actual = binding.finish(ctx);
            ctx = 0;
        } finally {
            if (ctx != 0) {
                try {
                    binding.free(ctx);
                } catch (RuntimeException ignored) {
                    // 探测失败路径上的清理异常不掩盖主异常
                }
            }
        }
        String mismatch = probeMismatchMessage(algorithm, expected, actual);
        if (mismatch != null) {
            throw new IllegalStateException(mismatch);
        }
        // free 符号也要探：它在「放弃 / 异常」路径上，正常流程永远不碰
        long freeCtx = binding.update(0, alg, null, 0, EMPTY, 0, 0);
        binding.free(freeCtx);
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
        return NativeStreamBinding.staleArtifactMessage(method);
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
        return new NativeDigestSession(binding, alg, algorithm);
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
     * native 摘要会话 —— <b>数据喂一次算一次</b>，不落 Java 堆。
     *
     * <p><b>上下文句柄的生命周期</b>：句柄在 {@code finish} 时被释放
     * （native 侧 finish 会顺手 free）⇒ Java 侧必须把句柄置 0，否则会重复释放。
     * 没喂过数据时 {@code digest()} 走「建一个空上下文再收尾」⇒ 结果是空输入的摘要，
     * 与 JCE 的 {@code MessageDigest.digest()} 语义一致。</p>
     */
    static final class NativeDigestSession implements DigestSession {

        private final NativeStreamBinding binding;

        private final int alg;

        private final String algorithm;

        /**
         * native 上下文句柄；0 表示当前没有上下文（还没喂数据 / 已收尾）。
         */
        private long ctx;

        NativeDigestSession(NativeStreamBinding binding, int alg, String algorithm) {
            this.binding = binding;
            this.alg = alg;
            this.algorithm = algorithm;
        }

        @Override
        public void update(byte[] data, int offset, int len) {
            if (len <= 0) {
                return;
            }
            // ctx == 0 时 native 侧顺便建上下文 ⇒ 一次 JNI 调用搞定
            long c = binding.update(ctx, alg, null, 0, data, offset, len);
            if (c == 0) {
                throw new IllegalStateException("native 摘要上下文创建失败（" + algorithm + "）");
            }
            ctx = c;
        }

        @Override
        public byte[] digest() {
            long c = ctx;
            ctx = 0;
            if (c == 0) {
                // 没喂过数据 ⇒ 空输入的摘要（仍走一次 native，保证与 JCE 逐字节一致）
                c = binding.update(0, alg, null, 0, EMPTY, 0, 0);
                if (c == 0) {
                    throw new IllegalStateException("native 摘要上下文创建失败（" + algorithm + "）");
                }
            }
            // finish 在 native 侧释放上下文
            return binding.finish(c);
        }

        @Override
        public void reset() {
            long c = ctx;
            ctx = 0;
            if (c != 0) {
                binding.free(c);
            }
        }

        @Override
        public void close() {
            reset();
        }
    }
}
