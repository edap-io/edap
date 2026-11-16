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

package io.edap.crypto.test;

import io.edap.crypto.benchmark.JvmVsJniProbe;
import io.edap.crypto.benchmark.ProbeLauncher;
import io.edap.crypto.digest.DigestProvider;
import io.edap.crypto.digest.DigestSession;
import io.edap.crypto.digest.JceDigestProvider;
import io.edap.crypto.digest.NativeDigestProvider;
import io.edap.crypto.internal.NativeBridge;
import io.edap.crypto.mac.JceMacProvider;
import io.edap.crypto.mac.MacProvider;
import io.edap.crypto.mac.MacSession;
import io.edap.crypto.mac.NativeMacProvider;
import io.edap.jni.Platform;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * <b>JVM 内置加密（JCE）vs JNI（edap-native）性能对比</b> —— 面向 Solaris 的对比用例。
 *
 * <p>回答一个问题：<b>在目标生产机（Solaris/SPARC）上，走 JNI 的 OpenSSL 实现
 * 比走 JVM 内置的 JCE 快多少？</b></p>
 *
 * <p><b>三个平台闸门</b>：</p>
 * <ol>
 *   <li><b>Solaris 上没有可用的 JNI 实现 ⇒ 直接失败</b>（而不是静默跳过）——
 *       目标机上这件事必须有人管：缺 {@code edap-native-sunos_&lt;arch&gt;.o}，
 *       意味着「以为在用 JNI 加速、其实一直在跑 JCE」。失败信息里给出构建命令。</li>
 *   <li>其它平台没有预编译产物 ⇒ 跳过 JNI 对比（不失败）。</li>
 *   <li>JNI 可用 ⇒ 两条实现必须<b>逐字节相同</b>（否则性能数字没有意义，先失败）。</li>
 * </ol>
 *
 * <p><b>测量口径</b>（memory {@code feedback_benchmark_isolated_jvm}）：</p>
 * <ul>
 *   <li><b>一个候选一个 JVM</b>：每个候选在<b>子进程</b>里单独测
 *       （{@link JvmVsJniProbe}）—— 同 JVM 里连测两个候选会让调用点 megamorphic，
 *       JIT 按先跑者的形态编译后跑者，读出来的是「谁排在后面谁吃亏」。</li>
 *   <li><b>候选顺序按 launch 轮转</b>：每轮把 JCE / JNI 的先后对调，
 *       抵消机器负载的单边漂移。</li>
 *   <li><b>单 JVM 内取 min</b>（压掉 GC / 调度抖动），<b>跨 launch 取中位数</b>
 *       —— 跨 launch 取 min 等于「挑运气」。</li>
 * </ul>
 *
 * <p><b>性能不作为断言</b>：机器噪声会让「快多少倍」变成 flaky 的失败。
 * 默认只断言正确性；需要硬门槛时用
 * {@code -Dedap.crypto.perf.minSpeedup=1.2}（要求 JNI 至少快 1.2 倍）打开。</p>
 *
 * <p><b>报告落盘</b>：{@code target/crypto-perf/jvm-vs-jni-<os>-<arch>-<ts>.json}
 * —— Solaris 上跑完把这份文件收回来即可，不必抄屏幕。</p>
 */
@Tag("perf")
public class JvmVsJniPerfTest {

    /** 被测算法。 */
    private static final String[] ALGS = {"sha256", "hmacsha256"};

    /** 被测载荷长度（字节）。 */
    private static final int[] SIZES = {64, 1024, 8192, 65536};

    /** 候选：JCE / JNI。 */
    private static final String[] IMPLS = {"jce", "native"};

    /**
     * 测量阶段 —— 把「一次操作」拆成可归因的几段。
     *
     * <p><b>为什么要拆</b>：总时间可以写成「固定成本 + 每字节成本」，
     * 但哪一段占大头只有拆开才知道。Solaris/SPARC 上实测 JCE 的 HMAC 有
     * 一段<b>与载荷大小无关的 ~130μs 固定开销</b>，不知道它在哪一段，
     * 就无法判断「该不该开 JNI」—— 因为那笔开销可能是 edap-crypto 封装的问题，
     * 而不是 JCE 本身慢。</p>
     *
     * <ul>
     *   <li>{@code full}：建 session + update + doFinal（= 生产用法，头条数字）；</li>
     *   <li>{@code hash}：建 session + update + 关闭（⇒ 差值 = 纯喂数据成本）；</li>
     *   <li>{@code setup}：建 session + update(0 字节) + 关闭（⇒ 差值 = 实例初始化成本）；</li>
     *   <li>{@code session}：建 session + 关闭（⇒ 差值 = session 对象分配成本）。</li>
     * </ul>
     */
    private static final String[] PHASES = {
            JvmVsJniProbe.PHASE_FULL,
            JvmVsJniProbe.PHASE_HASH,
            JvmVsJniProbe.PHASE_SETUP,
            JvmVsJniProbe.PHASE_SESSION
    };

    /**
     * 轮次（每轮 = 每个候选各起一次子 JVM）。
     */
    private static final int ROUNDS = Integer.getInteger("edap.crypto.perf.rounds", 3);

    /** 可选的硬门槛：JNI 至少要比 JCE 快多少倍（不设置 = 只断言正确性）。 */
    private static final Double MIN_SPEEDUP = minSpeedup();

    private static Double minSpeedup() {
        String value = System.getProperty("edap.crypto.perf.minSpeedup");
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return Double.valueOf(value.trim());
    }

    /** 子进程的 classpath（懒加载 + 缓存）。 */
    private static String childClasspath;

    @Test
    public void jvmCryptoVsJniPerf() throws Exception {
        String osName = System.getProperty("os.name", "");
        String osArch = System.getProperty("os.arch", "");

        boolean nativeAvail = NativeBridge.isNativeAvailable(NativeDigestProvider.NATIVE_CLASS)
                && NativeBridge.isNativeAvailable(NativeMacProvider.NATIVE_CLASS);

        printBanner(osName, osArch, nativeAvail);

        // ---- 闸门 1：Solaris 上缺 JNI ⇒ 失败（这是必须有人处理的事） ----
        if (!nativeAvail) {
            String gate = solarisGateMessage(osName, osArch);
            if (gate != null) {
                fail(gate);
            }
            // ---- 闸门 2：其它平台没有预编译产物 ⇒ 跳过 ----
            System.out.println("SKIP: 当前平台没有 edap-native 预编译产物，跳过 JNI 对比"
                    + "（本平台不需要 JNI 加速）");
            return;
        }

        // ---- 闸门 3：两条实现必须逐字节一致，否则性能数字没有意义 ----
        assertSameDigest();
        assertSameMac();

        // ---- 测量：每个候选在独立子 JVM 里测，候选顺序按轮次轮转 ----
        Map<String, List<Double>> samples = new LinkedHashMap<>();
        Map<String, String> digests = new LinkedHashMap<>();

        for (int round = 0; round < ROUNDS; round++) {
            String[] order = rotate(IMPLS, round);
            System.out.println("--- round " + (round + 1) + "/" + ROUNDS + "  候选顺序: " + Arrays.toString(order) + " ---");
            for (String impl : order) {
                for (String alg : ALGS) {
                    for (int size : SIZES) {
                        for (String phase : PHASES) {
                            ProbeResult result = runProbe(impl, alg, size, phase);
                            String key = key(impl, alg, size, phase);
                            samples.computeIfAbsent(key, k -> new ArrayList<>()).add(result.minNs);
                            // 只有 full 阶段产出真实摘要字节 —— 其余阶段是占位结果，不参与比对
                            if (!JvmVsJniProbe.PHASE_FULL.equals(phase)) {
                                continue;
                            }
                            String previous = digests.putIfAbsent(key, result.digest);
                            if (previous != null) {
                                assertEquals(previous, result.digest,
                                        "同一负载下 " + impl + "/" + alg + "/" + size + " 的结果字节在不同轮次间不一致");
                            }
                        }
                    }
                }
            }
        }

        // ---- 跨实现比对：JCE 与 JNI 必须算出同一个值 ----
        for (String alg : ALGS) {
            for (int size : SIZES) {
                String jce = digests.get(key("jce", alg, size, JvmVsJniProbe.PHASE_FULL));
                String jni = digests.get(key("native", alg, size, JvmVsJniProbe.PHASE_FULL));
                assertEquals(jce, jni, alg + "/" + size + "：JNI 与 JCE 的结果不一致");
            }
        }

        // ---- 汇总 ----
        List<String> table = new ArrayList<>();
        System.out.println();
        System.out.println("================ JVM 内置加密(JCE) vs JNI(edap-native) ================");
        System.out.printf(Locale.ROOT, "%-11s %8s %12s %12s %9s %11s %11s%n",
                "alg", "size", "JCE ns/op", "JNI ns/op", "JNI/JCE", "JCE MB/s", "JNI MB/s");
        System.out.println("----------------------------------------------------------------------------");

        List<String> jsonResults = new ArrayList<>();
        for (String alg : ALGS) {
            for (int size : SIZES) {
                Stats jce = stats(samples.get(key("jce", alg, size, JvmVsJniProbe.PHASE_FULL)));
                Stats jni = stats(samples.get(key("native", alg, size, JvmVsJniProbe.PHASE_FULL)));
                double ratio = jni.median > 0 ? jce.median / jni.median : 0d;
                double jceMbps = jce.median > 0 ? size * 1000d / jce.median : 0d;
                double jniMbps = jni.median > 0 ? size * 1000d / jni.median : 0d;
                System.out.printf(Locale.ROOT, "%-11s %8d %12.1f %12.1f %8.2fx %11.1f %11.1f%n",
                        alg, size, jce.median, jni.median, ratio, jceMbps, jniMbps);
                System.out.printf(Locale.ROOT, "%-11s %8s %12s %12s   (CV %5.1f%% / %5.1f%%)%n",
                        "", "", "min " + fmt(jce.min), "min " + fmt(jni.min), jce.cv, jni.cv);
                StringBuilder phasesJson = new StringBuilder();
                for (String phase : PHASES) {
                    Stats ps = stats(samples.get(key("jce", alg, size, phase)));
                    Stats pn = stats(samples.get(key("native", alg, size, phase)));
                    if (phasesJson.length() > 0) {
                        phasesJson.append(",");
                    }
                    phasesJson.append("\"").append(phase).append("\":{\"jceNs\":").append(fmt(ps.median))
                            .append(",\"jniNs\":").append(fmt(pn.median)).append("}");
                }
                jsonResults.add("{\"alg\":\"" + alg + "\",\"size\":" + size
                        + ",\"jceNs\":" + fmt(jce.median) + ",\"jniNs\":" + fmt(jni.median)
                        + ",\"jniOverJce\":" + fmt(ratio)
                        + ",\"jceCv\":" + fmt(jce.cv) + ",\"jniCv\":" + fmt(jni.cv)
                        + ",\"phases\":{" + phasesJson + "}}");
                table.add(alg + "/" + size + " ratio=" + String.format(Locale.ROOT, "%.2f", ratio));

                if (MIN_SPEEDUP != null) {
                    assertTrue(ratio >= MIN_SPEEDUP,
                            alg + "/" + size + "：JNI 只比 JCE 快 " + String.format(Locale.ROOT, "%.2f", ratio)
                                    + " 倍，达不到要求的 " + MIN_SPEEDUP + " 倍");
                }
            }
        }
        System.out.println("----------------------------------------------------------------------------");
        System.out.println("（JNI/JCE > 1 表示 JNI 更快；ns/op = 单次摘要/MAC 的平均耗时，"
                + "跨 " + ROUNDS + " 轮取中位数，CV = 变异系数）");

        // ---- 阶段分解：把「一次操作」拆开，看固定成本落在哪一段 ----
        System.out.println();
        System.out.println("================ 阶段分解（ns/op，中位数）================");
        System.out.printf(Locale.ROOT, "%-11s %8s %-7s %12s %12s %12s %12s%n",
                "alg", "size", "impl", "session", "setup", "hash", "full");
        System.out.println("----------------------------------------------------------------------------");
        for (String alg : ALGS) {
            for (int size : SIZES) {
                for (String impl : IMPLS) {
                    System.out.printf(Locale.ROOT, "%-11s %8d %-7s %12.1f %12.1f %12.1f %12.1f%n",
                            alg, size, impl,
                            med(samples, impl, alg, size, JvmVsJniProbe.PHASE_SESSION),
                            med(samples, impl, alg, size, JvmVsJniProbe.PHASE_SETUP),
                            med(samples, impl, alg, size, JvmVsJniProbe.PHASE_HASH),
                            med(samples, impl, alg, size, JvmVsJniProbe.PHASE_FULL));
                }
            }
        }
        System.out.println("----------------------------------------------------------------------------");
        System.out.println("session=只建/销毁 session；setup=+取到实例并初始化好；hash=+喂入全部数据；full=完整一次操作");
        System.out.println("（full − hash ≈ 收尾成本；hash − setup ≈ 纯数据处理成本；setup − session ≈ 实例初始化成本）");

        // ---- 报告落盘 ----
        Path report = writeReport(osName, osArch, nativeAvail, jsonResults);
        System.out.println("报告: " + report.toAbsolutePath());
    }

    // ------------------------------------------------------------------
    // 平台闸门
    // ------------------------------------------------------------------

    /**
     * Solaris 闸门 —— <b>不是 Solaris 返回 null（不拦）</b>；是 Solaris 则返回失败原因。
     *
     * <p>抽成纯函数是为了能直接单测这条规则：真机上「Solaris + 缺 .o」这个组合
     * 在开发机（macOS）上永远走不到，没有单测就等于这条规则没人验证过。</p>
     */
    static String solarisGateMessage(String osName, String osArch) {
        String lower = osName.toLowerCase(Locale.ROOT);
        if (!lower.contains("sunos") && !lower.contains("solaris")) {
            return null;
        }
        return "Solaris 上没有可用的 JNI 实现：edap-native 没有为 " + osName + "/" + osArch
                + " 提供预编译产物。" + System.lineSeparator()
                + "  期望资源：" + Platform.resourceName(osName, osArch) + System.lineSeparator()
                + "  构建方式：在目标机上执行 edap-native/scripts/build-native.sh --commit"
                + "（需要该机的 OpenSSL 开发包与 JDK 17；产物名由脚本直接问 JVM 的 os.arch 决定）"
                + System.lineSeparator()
                + "  在产物就位之前，本机的所有「加密」都在跑 JVM 内置实现 —— "
                + "以为在用 JNI 加速、其实没有，是最难发现的一类问题。";
    }

    /**
     * 闸门规则本身：Solaris 必须拦，别的平台不拦。
     */
    @Test
    public void solarisGateOnlyBlocksSolaris() {
        assertTrue(solarisGateMessage("Mac OS X", "aarch64") == null,
                "非 Solaris 平台不该被拦");
        assertTrue(solarisGateMessage("Linux", "amd64") == null,
                "非 Solaris 平台不该被拦");

        String message = solarisGateMessage("SunOS", "sparcv9");
        assertTrue(message != null, "Solaris 上缺 JNI 必须拦下来");
        assertTrue(message.contains("edap-native-sunos_sparcv9.o"),
                "报错里要点名缺哪个产物，实际: " + message);
        assertTrue(message.contains("build-native.sh"),
                "报错里要给出构建命令，实际: " + message);

        // Solaris/SPARC 的 os.arch 有多种形态（sparcv9 / sparc / sun4v），
        // 期望的产物名必须由 Platform 算出来，不能手拼。
        assertTrue(solarisGateMessage("SunOS", "sun4v").contains("edap-native-sunos_sun4v.o"),
                "os.arch=sun4v 时也要点名正确的产物名");
        assertTrue(solarisGateMessage("SunOS", "sparc").contains("edap-native-sunos_sparc.o"),
                "os.arch=sparc 时也要点名正确的产物名");
    }

    // ------------------------------------------------------------------
    // 正确性
    // ------------------------------------------------------------------

    private static void assertSameDigest() {
        DigestProvider jce = new JceDigestProvider("SHA-256");
        DigestProvider jni = NativeDigestProvider.tryCreate("SHA-256");
        assertTrue(jni != null, "native digest provider 创建失败");
        byte[] data = new byte[1000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 31 + 17);
        }
        DigestSession js = jce.newSession();
        js.update(data, 0, data.length);
        DigestSession ns = jni.newSession();
        ns.update(data, 0, data.length);
        assertArrayEquals(js.digest(), ns.digest(), "SHA-256：JNI 与 JCE 结果不一致");
    }

    private static void assertSameMac() {
        byte[] key = "edap-perf-key-0123456789abcdef-0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        MacProvider jce = new JceMacProvider("HmacSHA256");
        MacProvider jni = NativeMacProvider.tryCreate("HmacSHA256");
        assertTrue(jni != null, "native mac provider 创建失败");
        byte[] data = new byte[1000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) (i * 17 + 3);
        }
        MacSession js = jce.newSession(key);
        js.update(data, 0, data.length);
        MacSession ns = jni.newSession(key);
        ns.update(data, 0, data.length);
        assertArrayEquals(js.doFinal(), ns.doFinal(), "HmacSHA256：JNI 与 JCE 结果不一致");
    }

    // ------------------------------------------------------------------
    // 子进程测量
    // ------------------------------------------------------------------

    /**
     * 起一个子 JVM 跑 {@link JvmVsJniProbe}，只测一个候选的一个阶段。
     */
    private static ProbeResult runProbe(String impl, String alg, int size, String phase) throws Exception {
        List<String> args = new ArrayList<>();
        args.add("--impl=" + impl);
        args.add("--alg=" + alg);
        args.add("--size=" + size);
        args.add("--phase=" + phase);
        // ★ 批次数与预热次数对所有阶段一律相同：
        //   「取批内 min」是小样本上的统计量，批次数不同 ⇒ 两个阶段的 min 不在同一口径，
        //   full − hash 这种差值会被系统性偏置（实测：辅助阶段用 2 批时，
        //   JCE 的 hash 阶段一度读出比 full 还慢 —— 荒谬但完全由口径造成）。
        //   字节预算（--bytes）与迭代数也一样，差值里才只剩「多做/少做的那点工作」。

        String output = ProbeLauncher.run(JvmVsJniProbe.class.getName(), args);
        String json = ProbeLauncher.marker(output, JvmVsJniProbe.MARKER);
        if (json == null) {
            throw new IllegalStateException("探针没有产出结果行（" + impl + "/" + alg + "/" + size + "）: " + output);
        }
        return new ProbeResult(Double.parseDouble(field(json, "minNs")), field(json, "digest"));
    }

    // ------------------------------------------------------------------
    // 汇总
    // ------------------------------------------------------------------

    /**
     * 跨 launch 的汇总统计：<b>中位数</b>（不是 min —— 跨 launch 取 min 等于挑运气），
     * 同时给出 min 与变异系数（CV）供判断可信度。
     */
    private static Stats stats(List<Double> values) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double median = sorted[sorted.length / 2];
        double min = sorted[0];
        double max = sorted[sorted.length - 1];
        double mean = 0;
        for (double v : sorted) {
            mean += v;
        }
        mean /= sorted.length;
        double variance = 0;
        for (double v : sorted) {
            variance += (v - mean) * (v - mean);
        }
        variance /= sorted.length;
        double cv = mean > 0 ? Math.sqrt(variance) / mean * 100d : 0d;
        return new Stats(median, min, max, cv);
    }

    private static final class Stats {
        final double median;
        final double min;
        final double max;
        final double cv;

        Stats(double median, double min, double max, double cv) {
            this.median = median;
            this.min = min;
            this.max = max;
            this.cv = cv;
        }
    }

    private static final class ProbeResult {
        final double minNs;
        final String digest;

        ProbeResult(double minNs, String digest) {
            this.minNs = minNs;
            this.digest = digest;
        }
    }

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------

    private static String key(String impl, String alg, int size, String phase) {
        return impl + "|" + alg + "|" + size + "|" + phase;
    }

    /**
     * 某个阶段的 ns/op 中位数（没有样本时返回 0）。
     */
    private static double med(Map<String, List<Double>> samples, String impl, String alg,
            int size, String phase) {
        List<Double> values = samples.get(key(impl, alg, size, phase));
        if (values == null || values.isEmpty()) {
            return 0d;
        }
        return stats(values).median;
    }

    private static String[] rotate(String[] values, int round) {
        int offset = round % values.length;
        String[] result = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = values[(i + offset) % values.length];
        }
        return result;
    }

    /**
     * 从扁平 JSON 里取一个字段（探针输出的 JSON 只有顶层字符串 / 数字字段）。
     */
    private static String field(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"?([^,\"}]*)").matcher(json);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static void printBanner(String osName, String osArch, boolean nativeAvail) {
        System.out.println("================ 平台信息 ================");
        System.out.println("os.name     : " + osName);
        System.out.println("os.arch     : " + osArch);
        System.out.println("java.version: " + System.getProperty("java.version"));
        System.out.println("java.vendor : " + System.getProperty("java.vendor"));
        System.out.println("JNI (edap-native): " + (nativeAvail ? "已加载" : "不可用"));
        System.out.println("===========================================");
    }

    /**
     * 把结果写成 JSON 报告（Solaris 上跑完把这份文件收回来即可）。
     */
    private static Path writeReport(String osName, String osArch, boolean nativeAvail, List<String> results)
            throws Exception {
        File dir = new File("target/crypto-perf");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建报告目录: " + dir.getAbsolutePath());
        }
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String fileName = "jvm-vs-jni-" + osName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                + "-" + osArch.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-") + "-" + ts + ".json";
        Path file = dir.toPath().resolve(fileName);
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"osName\": \"").append(osName).append("\",\n");
        sb.append("  \"osArch\": \"").append(osArch).append("\",\n");
        sb.append("  \"javaVersion\": \"").append(System.getProperty("java.version")).append("\",\n");
        sb.append("  \"nativeEnabled\": ").append(nativeAvail).append(",\n");
        sb.append("  \"rounds\": ").append(ROUNDS).append(",\n");
        sb.append("  \"results\": [\n");
        for (int i = 0; i < results.size(); i++) {
            sb.append("    ").append(results.get(i));
            sb.append(i == results.size() - 1 ? "\n" : ",\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
