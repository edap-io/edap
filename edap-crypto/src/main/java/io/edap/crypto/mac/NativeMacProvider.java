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

import io.edap.crypto.internal.NativeBridge;
import io.edap.crypto.internal.NativeStreamBinding;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * native MAC 实现（OpenSSL libcrypto，via edap-native）。
 *
 * <p><b>算法映射</b>：JCE 名 → native 算法编号。</p>
 * <table>
 *   <caption>算法映射</caption>
 *   <tr><th>JCE</th><th>native 算法编号</th><th>输出长度</th></tr>
 *   <tr><td>HmacSHA256</td><td>3</td><td>32</td></tr>
 *   <tr><td>HmacSHA1</td><td>4</td><td>20</td></tr>
 * </table>
 *
 * <p><b>★★ 流式：native 侧持有 HMAC 上下文</b>：
 * 早先的实现是「native 一次性 API + Java 侧用 {@code ByteAccumulator} 累积」——
 * 整份数据先拷一遍进 Java 堆，等 {@code doFinal()} 才交给 native。
 * 实测（Solaris/SPARC，Oracle JDK 17）：64KB 载荷光这次累积就要 <b>~17.7µs</b>
 * （一次 64KB 分配 + 一次 64KB arraycopy），<b>与一次 HMAC 本体同量级</b>。
 * ⇒ 现在数据喂一次算一次，累积拷贝整个消失。</p>
 *
 * <p><b>为什么用 legacy {@code SHA256_*} 而不是 EVP</b>：见
 * {@code edap-native/doc/NATIVE_DESIGN.md} §6.6 —— OpenSSL 3.x 的 provider dispatch
 * 在多线程下会退化（16t profile 17.5% CPU）。</p>
 */
public class NativeMacProvider implements MacProvider {

    /**
     * edap-native 里的流式实现类。
     */
    public static final String NATIVE_CLASS = NativeStreamBinding.NATIVE_CLASS;

    /**
     * JCE 算法名 → native 算法编号。
     */
    private static final Map<String, Integer> ALG_MAP = new HashMap<>();

    /**
     * JCE 算法名 → native 算法编号（探测用的参照实现）。
     */
    private static final Map<String, String> JCE_ALG_MAP = new HashMap<>();

    /**
     * 探测用的 key。
     */
    private static final byte[] PROBE_KEY = "edap-crypto-mac-probe-key".getBytes(StandardCharsets.UTF_8);

    static {
        ALG_MAP.put("HmacSHA256", 3);
        ALG_MAP.put("HmacSHA1", 4);
        JCE_ALG_MAP.put("HmacSHA256", "HmacSHA256");
        JCE_ALG_MAP.put("HmacSHA1", "HmacSHA1");
    }

    private final String algorithm;

    private final int alg;

    private final NativeStreamBinding binding;

    private NativeMacProvider(String algorithm, int alg, NativeStreamBinding binding) {
        this.algorithm = algorithm;
        this.alg = alg;
        this.binding = binding;
    }

    /**
     * 尝试创建 native MAC provider。
     *
     * @return native 不可用或算法不支持时返回 {@code null}（调用方降级到 JCE）
     */
    public static NativeMacProvider tryCreate(String algorithm) {
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
        // ★ 探测失败必须炸出来 —— 不能静默降级到 JCE（见 NativeDigestProvider 的说明）
        verifySymbol(algorithm, alg, binding);
        return new NativeMacProvider(algorithm, alg, binding);
    }

    /**
     * <b>★ 一次性探测：确认预编译产物里真的有这 3 个 JNI 符号，且算得对。</b>
     *
     * <p>参照值用 JCE 现算（不写死常量）—— 探测的是「native 与 JCE 是否逐字节一致」，
     * 这比「与某个常量一致」更能说明产物是好的。</p>
     */
    private static void verifySymbol(String algorithm, int alg, NativeStreamBinding binding) {
        byte[] expected = jceMac(JCE_ALG_MAP.get(algorithm), PROBE_KEY, new byte[0]);
        byte[] actual;
        long ctx = binding.update(0, alg, PROBE_KEY, PROBE_KEY.length, new byte[0], 0, 0);
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
        if (!Arrays.equals(expected, actual)) {
            throw new IllegalStateException("native mac 探测结果不对（" + algorithm + "）：期望 "
                    + HexFormat.of().formatHex(expected) + "，实际 "
                    + (actual == null ? "null" : HexFormat.of().formatHex(actual))
                    + " —— 预编译产物与 Java 侧不匹配");
        }
        // free 符号也要探：它在「放弃 / 异常」路径上，正常流程永远不碰
        long freeCtx = binding.update(0, alg, PROBE_KEY, PROBE_KEY.length, new byte[0], 0, 0);
        binding.free(freeCtx);
    }

    /**
     * 用 JCE 算一次（探测参照值）。
     */
    private static byte[] jceMac(String jceAlgorithm, byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(jceAlgorithm);
            mac.init(new SecretKeySpec(key, jceAlgorithm));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("JCE 参照计算失败（" + jceAlgorithm + "）", e);
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
        return new NativeMacSession(binding, alg, key);
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
     * native MAC 会话 —— <b>数据喂一次算一次</b>，不落 Java 堆。
     *
     * <p><b>上下文句柄的生命周期</b>：句柄在 {@code finish} 时被释放
     * （native 侧 finish 会顺手 free）⇒ Java 侧必须把句柄置 0，否则会重复释放。
     * key 留在 Java 侧（拷贝一份），收尾后重新建上下文时再传给 native。</p>
     */
    static final class NativeMacSession implements MacSession {

        private final NativeStreamBinding binding;

        private final int alg;

        /**
         * key 的副本 —— 调用方可能复用/改写原数组。
         */
        private final byte[] key;

        /**
         * native 上下文句柄；0 表示当前没有上下文（还没喂数据 / 已收尾）。
         */
        private long ctx;

        NativeMacSession(NativeStreamBinding binding, int alg, byte[] key) {
            this.binding = binding;
            this.alg = alg;
            this.key = key.clone();
        }

        @Override
        public void update(byte[] data, int offset, int len) {
            if (len <= 0) {
                return;
            }
            // ctx == 0 时 native 侧顺便建上下文（用 key 展开 ipad/opad）⇒ 一次 JNI 调用搞定
            long c = binding.update(ctx, alg, key, key.length, data, offset, len);
            if (c == 0) {
                throw new IllegalStateException("native mac 上下文创建失败");
            }
            ctx = c;
        }

        @Override
        public byte[] doFinal() {
            long c = ctx;
            ctx = 0;
            if (c == 0) {
                // 没喂过数据 ⇒ 空数据的 MAC（仍走一次 native，保证与 JCE 逐字节一致）
                c = binding.update(0, alg, key, key.length, new byte[0], 0, 0);
                if (c == 0) {
                    throw new IllegalStateException("native mac 上下文创建失败");
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
