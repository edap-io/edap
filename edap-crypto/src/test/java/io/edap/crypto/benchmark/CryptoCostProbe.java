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

package io.edap.crypto.benchmark;

import io.edap.crypto.digest.DigestProvider;
import io.edap.crypto.digest.DigestSession;
import io.edap.crypto.digest.NativeDigestProvider;
import io.edap.crypto.mac.MacProvider;
import io.edap.crypto.mac.MacSession;
import io.edap.crypto.mac.NativeMacProvider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.security.Provider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * <b>「一次加密操作」的固定成本归因探针</b>。
 *
 * <p><b>它回答什么问题</b>：{@code JvmVsJniPerfTest} 量到的是「一次操作总共多久」，
 * 但 Solaris/SPARC 上 JCE 的 HMAC 比 JNI 慢 <b>43 倍</b>（64B 载荷）——
 * 这个差距是「OpenSSL 慢」还是「JDK 每次操作都要付一笔固定开销」？
 * 光看总数分不出来，必须把一次操作拆到<b>JDK 的具体 API 调用</b>上：</p>
 *
 * <ul>
 *   <li>{@code md.getInstance} —— 每次 {@code MessageDigest.getInstance} 多少钱？</li>
 *   <li>{@code mac.init} —— <b>每次 {@code Mac.init(SecretKeySpec)} 多少钱？</b>
 *       （JCE 的 HMAC 每次操作都要重新 init —— 这一步会重算 ipad/opad）</li>
 *   <li>{@code mac.doFinal64} —— 64B 载荷的 HMAC 收尾 + 数据处理多少钱？</li>
 *   <li>{@code native.mac64} —— 同样 64B 载荷走 JNI 多少钱？</li>
 * </ul>
 *
 * <p><b>还有一组「key 导入归因」项</b>（{@code mac.initFreshKey} /
 * {@code mac.initNewInstanceSameKey} / {@code mac.initNewInstanceFreshKey} /
 * {@code mac.realPathPooled} / {@code mac.realPathCachedKeySpec} /
 * {@code mac.realPathNewInstance}）—— 用来回答：
 * Solaris 上 JCE HMAC 那 ~130μs 到底付在<b>哪一次 key 相关调用</b>上。
 * 变量只有三个：Mac 实例是不是新的、{@code SecretKeySpec} 对象是不是同一个、
 * key 字节是不是同一份。逐项交叉就能定位。</p>
 *
 * <p><b>还顺手回答一个更基础的问题：用的哪个 provider？</b>
 * Solaris 的 JDK 可能把 {@code HmacSHA256} 解析到 {@code SunPKCS11}（走加密守护进程的
 * IPC），也可能解析到 {@code SUN}（纯软件）。两者的每次操作开销能差一个数量级 ——
 * 而「用哪个 provider」决定了上面所有数字的解释。
 * 所以探针会把 provider 名字一并打出来。</p>
 *
 * <p><b>测量口径</b>（遵 memory {@code feedback_benchmark_isolated_jvm}）：
 * <b>一个被测项一个 JVM</b>（父进程起子 JVM）—— 同 JVM 里连测多项，
 * 调用点会 megamorphic，读出来的是「谁排在后面谁吃亏」。
 * 单 JVM 内取 min（压掉 GC / 调度抖动），批数 5。</p>
 *
 * <p><b>用法</b>（父进程模式，输出一张表）：</p>
 * <pre>
 * java -cp &lt;classpath&gt; io.edap.crypto.benchmark.CryptoCostProbe
 * </pre>
 *
 * <p><b>用法</b>（子进程模式，只测一项）：</p>
 * <pre>
 * java -cp &lt;classpath&gt; io.edap.crypto.benchmark.CryptoCostProbe --op=mac.init
 * </pre>
 */
public final class CryptoCostProbe {

    /**
     * 结果行前缀 —— 父进程靠它从混有日志的 stdout 里捞出结果。
     */
    public static final String MARKER = "@@EDAP_COST@@";

    /**
     * 单次被测操作 —— 返回值参与 checksum，防止 JIT 把整段消掉。
     */
    @FunctionalInterface
    interface Op {
        long once();
    }

    /**
     * 一个被测项。
     */
    static final class Item {

        final String name;

        /** 每批的迭代次数（贵的项少、便宜的项多）。 */
        final int iters;

        final String desc;

        /** 附注（例如 provider 名）；没有则为空串。 */
        final String info;

        final Op op;

        Item(String name, int iters, String desc, String info, Op op) {
            this.name = name;
            this.iters = iters;
            this.desc = desc;
            this.info = info;
            this.op = op;
        }
    }

    /**
     * 64B 载荷。
     */
    private static final byte[] PAYLOAD_64 = payload(64);

    /**
     * 64KB 载荷。
     */
    private static final byte[] PAYLOAD_64K = payload(65536);

    /**
     * MAC 用的 key（≥ 32 字节，够 SHA-256 的 block size）。
     */
    private static final byte[] KEY = ("edap-perf-key-0123456789abcdef-0123456789abcdef")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /**
     * 预先建好的 key spec —— 测 {@code Mac.init} 时不把 key spec 的构造算进去。
     */
    private static final SecretKeySpec KEY_SPEC = new SecretKeySpec(KEY, "HmacSHA256");

    /**
     * 池化的 MessageDigest —— 放在静态字段里，JIT 逃逸分析消不掉它。
     */
    private static MessageDigest md;

    /**
     * 池化的 Mac —— 同上。
     */
    private static Mac mac;

    /**
     * native provider（不可用时为 null，对应的项标记为「跳过」）。
     */
    private static DigestProvider nativeDigest;

    private static MacProvider nativeMac;

    private CryptoCostProbe() {
        // 工具类
    }

    public static void main(String[] args) throws Exception {
        String op = arg(args, "op", null);
        if (op == null) {
            parent();
        } else {
            child(op);
        }
    }

    // ------------------------------------------------------------------
    // 父进程：逐项起子 JVM
    // ------------------------------------------------------------------

    private static void parent() throws Exception {
        System.out.println("================ 加密固定成本归因 ================");
        System.out.println("os.name     : " + System.getProperty("os.name"));
        System.out.println("os.arch     : " + System.getProperty("os.arch"));
        System.out.println("java.version: " + System.getProperty("java.version"));
        System.out.println("java.vendor : " + System.getProperty("java.vendor"));
        System.out.println("==================================================");
        System.out.println();
        System.out.println("provider 归属（JCE 每次操作的固定成本由它决定）：");
        System.out.println("  MessageDigest(SHA-256) -> " + providerOf("SHA-256"));
        System.out.println("  Mac(HmacSHA256)        -> " + providerOf("HmacSHA256"));
        System.out.println();

        List<Item> items = items();
        System.out.printf(Locale.ROOT, "%-18s %12s %12s  %s%n",
                "op", "ns/op", "warmup ns", "说明");
        System.out.println("----------------------------------------------------------------");

        for (Item item : items) {
            String result;
            try {
                String output = ProbeLauncher.run(CryptoCostProbe.class.getName(),
                        Arrays.asList("--op=" + item.name));
                result = ProbeLauncher.marker(output, MARKER);
            } catch (Exception e) {
                result = null;
                System.out.println("  " + item.name + " 启动失败: " + e.getMessage());
            }
            if (result == null) {
                System.out.printf(Locale.ROOT, "%-18s %12s %12s  %s%n",
                        item.name, "SKIP", "-", item.desc);
                continue;
            }
            String ns = field(result, "minNs");
            String warmupNs = field(result, "warmupNs");
            System.out.printf(Locale.ROOT, "%-18s %12s %12s  %s%n",
                    item.name, ns, warmupNs, item.desc
                    + (item.info.isEmpty() ? "" : "  [" + item.info + "]"));
        }
        System.out.println("----------------------------------------------------------------");
        System.out.println("（ns/op = 单次 API 调用的平均耗时，批内取 min；"
                + "warmup ns = 预热阶段的每次调用耗时 —— 预热不足时它会明显偏大）");
    }

    // ------------------------------------------------------------------
    // 子进程：只测一项
    // ------------------------------------------------------------------

    private static void child(String name) {
        Item item = null;
        for (Item candidate : items()) {
            if (candidate.name.equals(name)) {
                item = candidate;
                break;
            }
        }
        if (item == null) {
            System.out.println(MARKER + "{\"op\":\"" + name + "\",\"minNs\":\"SKIP\"}");
            return;
        }

        // 预热：与被测项无关的固定次数，让 JIT 把它编译到 C2
        int warmup = Math.max(2000, item.iters);
        long checksum = 0;
        long t0 = System.nanoTime();
        for (int i = 0; i < warmup; i++) {
            checksum += item.op.once();
        }
        long t1 = System.nanoTime();
        double warmupNs = (double) (t1 - t0) / warmup;

        // 正式测量：5 批，取批内 min
        double[] nsPerOp = new double[5];
        for (int b = 0; b < nsPerOp.length; b++) {
            long s0 = System.nanoTime();
            for (int i = 0; i < item.iters; i++) {
                checksum += item.op.once();
            }
            long s1 = System.nanoTime();
            nsPerOp[b] = (double) (s1 - s0) / item.iters;
        }
        Arrays.sort(nsPerOp);

        System.out.println(MARKER + "{\"op\":\"" + item.name + "\""
                + ",\"minNs\":\"" + fmt(nsPerOp[0]) + "\""
                + ",\"medianNs\":\"" + fmt(nsPerOp[nsPerOp.length / 2]) + "\""
                + ",\"warmupNs\":\"" + fmt(warmupNs) + "\""
                + ",\"iters\":" + item.iters
                + ",\"checksum\":" + checksum + "}");
    }

    // ------------------------------------------------------------------
    // 被测项清单
    // ------------------------------------------------------------------

    private static List<Item> items() {
        List<Item> list = new ArrayList<>();

        // ---- MessageDigest (SHA-256) ----
        list.add(new Item("md.getInstance", 2000,
                "MessageDigest.getInstance(\"SHA-256\") 每次的代价", "",
                CryptoCostProbe::newMessageDigest));

        list.add(new Item("md.reset", 20000,
                "MessageDigest.reset()（清状态）", "",
                () -> {
                    md().reset();
                    return 0;
                }));

        list.add(new Item("md.update64", 20000,
                "MessageDigest.update(64B)", "",
                () -> {
                    md().update(PAYLOAD_64, 0, PAYLOAD_64.length);
                    return 0;
                }));

        list.add(new Item("md.digestEmpty", 20000,
                "MessageDigest.digest()（空输入 ⇒ 只测收尾固定成本）", "",
                () -> md().digest()[0] & 0xff));

        list.add(new Item("md.digest64", 20000,
                "一次完整 JCE 摘要（64B）", "",
                () -> {
                    md().update(PAYLOAD_64, 0, PAYLOAD_64.length);
                    return md().digest()[0] & 0xff;
                }));

        list.add(new Item("md.digest64k", 500,
                "一次完整 JCE 摘要（64KB）", "",
                () -> {
                    md().update(PAYLOAD_64K, 0, PAYLOAD_64K.length);
                    return md().digest()[0] & 0xff;
                }));

        // ---- Mac (HmacSHA256) ----
        list.add(new Item("mac.getInstance", 2000,
                "Mac.getInstance(\"HmacSHA256\") 每次的代价", "",
                CryptoCostProbe::newMac));

        list.add(new Item("mac.keySpec", 20000,
                "new SecretKeySpec(key, alg)", "",
                () -> new SecretKeySpec(KEY, "HmacSHA256").hashCode()));

        list.add(new Item("mac.init", 20000,
                "★ Mac.init(SecretKeySpec)（每次操作都要做一遍）", "",
                CryptoCostProbe::initMac));

        list.add(new Item("mac.reset", 20000,
                "Mac.reset()", "",
                () -> {
                    mac().reset();
                    return 0;
                }));

        list.add(new Item("mac.doFinal64", 5000,
                "一次完整 JCE HMAC（64B，不含 init）", "",
                () -> {
                    mac().update(PAYLOAD_64, 0, PAYLOAD_64.length);
                    return mac().doFinal()[0] & 0xff;
                }));

        list.add(new Item("mac.doFinal64k", 200,
                "一次完整 JCE HMAC（64KB，不含 init）", "",
                () -> {
                    mac().update(PAYLOAD_64K, 0, PAYLOAD_64K.length);
                    return mac().doFinal()[0] & 0xff;
                }));

        // ---- key 导入归因（Solaris 的 SunPKCS11 会把 key 导进 token）----
        // 背景：JCE HMAC 在 Solaris 上有 ~130μs 的固定开销，而探针量到的
        // mac.init 只有 ~10μs ⇒ 差的 ~107μs 藏在 init 里面。
        // edap-crypto 的真实写法每次操作都 new 一个 SecretKeySpec（同一个 key 字节），
        // 而探针的 mac.init 用的是同一个静态对象 ⇒ 下面几项用来分叉：
        //   「key 对象是不是同一个」 vs 「key 字节是不是同一份」 vs 「Mac 实例是不是新的」。
        list.add(new Item("mac.initFreshKey", 5000,
                "★ Mac.init(每次新建 SecretKeySpec，字节相同)", "",
                CryptoCostProbe::initMacFreshKey));

        list.add(new Item("mac.initNewInstanceSameKey", 2000,
                "Mac.getInstance + Mac.init(同一个 SecretKeySpec 对象)", "",
                CryptoCostProbe::initNewInstanceSameKey));

        list.add(new Item("mac.initNewInstanceFreshKey", 2000,
                "Mac.getInstance + Mac.init(每次新建 SecretKeySpec)", "",
                CryptoCostProbe::initNewInstanceFreshKey));

        list.add(new Item("mac.realPathPooled", 2000,
                "★ edap-crypto 真实路径：池化实例 + 每次新建 SecretKeySpec + update + doFinal", "",
                CryptoCostProbe::realPathPooled));

        list.add(new Item("mac.realPathCachedKeySpec", 2000,
                "★ 同上，但 SecretKeySpec 复用同一个对象（候选修法的效果）", "",
                CryptoCostProbe::realPathCachedKeySpec));

        list.add(new Item("mac.realPathNewInstance", 2000,
                "不池化：每次 getInstance + 每次新建 SecretKeySpec + update + doFinal", "",
                CryptoCostProbe::realPathNewInstance));

        // ---- native 对照 ----
        list.add(new Item("native.digest64", 20000,
                "一次完整 native 摘要（64B）", "",
                () -> {
                    DigestProvider provider = nativeDigestProvider();
                    if (provider == null) {
                        return 0;
                    }
                    DigestSession session = provider.newSession();
                    session.update(PAYLOAD_64, 0, PAYLOAD_64.length);
                    return session.digest()[0] & 0xff;
                }));

        list.add(new Item("native.mac64", 20000,
                "一次完整 native HMAC（64B）", "",
                () -> {
                    MacProvider provider = nativeMacProvider();
                    if (provider == null) {
                        return 0;
                    }
                    MacSession session = provider.newSession(KEY);
                    session.update(PAYLOAD_64, 0, PAYLOAD_64.length);
                    return session.doFinal()[0] & 0xff;
                }));

        return list;
    }

    // ------------------------------------------------------------------
    // 共享状态
    // ------------------------------------------------------------------

    /**
     * 每次都新建一个 MessageDigest（测 getInstance 的代价）。
     */
    private static long newMessageDigest() {
        try {
            return MessageDigest.getInstance("SHA-256").getDigestLength();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 每次都新建一个 Mac（测 getInstance 的代价）。
     */
    private static long newMac() {
        try {
            return Mac.getInstance("HmacSHA256").getMacLength();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 对池化实例重新 init（测 init 的代价 —— JCE 的 HMAC 每次操作都要付这一笔）。
     */
    private static long initMac() {
        try {
            mac().init(KEY_SPEC);
            return 0;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 对池化实例 init，但<b>每次都新建</b>一个 {@link SecretKeySpec}（key 字节相同）。
     *
     * <p>这是 edap-crypto 的真实写法 —— 用来分辨「JDK 缓存的是 key 对象本身
     * 还是 key 字节」。</p>
     */
    private static long initMacFreshKey() {
        try {
            mac().init(new SecretKeySpec(KEY, "HmacSHA256"));
            return 0;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 全新 Mac 实例 + 同一个 {@link SecretKeySpec} 对象。
     */
    private static long initNewInstanceSameKey() {
        try {
            Mac fresh = Mac.getInstance("HmacSHA256");
            fresh.init(KEY_SPEC);
            return 0;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 全新 Mac 实例 + 每次新建 {@link SecretKeySpec}。
     */
    private static long initNewInstanceFreshKey() {
        try {
            Mac fresh = Mac.getInstance("HmacSHA256");
            fresh.init(new SecretKeySpec(KEY, "HmacSHA256"));
            return 0;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * edap-crypto 的真实路径：池化实例 + 每次新建 SecretKeySpec + 喂数据 + 收尾。
     *
     * <p>这个数应当与 {@code JvmVsJniPerfTest} 里 JCE HMAC 的 full 段对上 ——
     * 对上了才说明归因模型闭合。</p>
     */
    private static long realPathPooled() {
        try {
            Mac m = mac();
            m.init(new SecretKeySpec(KEY, "HmacSHA256"));
            m.update(PAYLOAD_64, 0, PAYLOAD_64.length);
            return m.doFinal()[0] & 0xff;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 同 {@link #realPathPooled()}，但复用同一个 {@link SecretKeySpec} 对象
     * —— 这是「缓存 SecretKeySpec」这个候选修法的实测效果。
     */
    private static long realPathCachedKeySpec() {
        try {
            Mac m = mac();
            m.init(KEY_SPEC);
            m.update(PAYLOAD_64, 0, PAYLOAD_64.length);
            return m.doFinal()[0] & 0xff;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 完全不池化：每次 {@code getInstance} + 每次新建 {@link SecretKeySpec}。
     */
    private static long realPathNewInstance() {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(KEY, "HmacSHA256"));
            m.update(PAYLOAD_64, 0, PAYLOAD_64.length);
            return m.doFinal()[0] & 0xff;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MessageDigest md() {        if (md == null) {
            try {
                md = MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return md;
    }

    private static Mac mac() {
        if (mac == null) {
            try {
                mac = Mac.getInstance("HmacSHA256");
                mac.init(KEY_SPEC);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return mac;
    }

    private static DigestProvider nativeDigestProvider() {
        if (nativeDigest == null) {
            nativeDigest = NativeDigestProvider.tryCreate("SHA-256");
        }
        return nativeDigest;
    }

    private static MacProvider nativeMacProvider() {
        if (nativeMac == null) {
            nativeMac = NativeMacProvider.tryCreate("HmacSHA256");
        }
        return nativeMac;
    }

    /**
     * provider 归属（JCE 每次操作的固定成本由它决定）。
     */
    private static String providerOf(String algorithm) {
        try {
            Provider provider = algorithm.startsWith("Hmac")
                    ? Mac.getInstance(algorithm).getProvider()
                    : MessageDigest.getInstance(algorithm).getProvider();
            return provider.getName() + " (" + provider.getClass().getSimpleName() + ")";
        } catch (Exception e) {
            return "未知: " + e;
        }
    }

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------

    private static byte[] payload(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) ((i * 31 + 17) & 0x7f);
        }
        return data;
    }

    private static String arg(String[] args, String name, String defaultValue) {
        String prefix = "--" + name + "=";
        for (String a : args) {
            if (a.startsWith(prefix)) {
                return a.substring(prefix.length());
            }
        }
        return defaultValue;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    /**
     * 从扁平 JSON 里取一个字段（探针输出的 JSON 只有顶层字符串 / 数字字段）。
     */
    private static String field(String json, String name) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(name) + "\"\\s*:\\s*\"?([^,}\"]*)")
                .matcher(json);
        return matcher.find() ? matcher.group(1).trim() : null;
    }
}
