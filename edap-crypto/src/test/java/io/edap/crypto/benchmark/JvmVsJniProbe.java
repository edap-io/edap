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
import io.edap.crypto.digest.JceDigestProvider;
import io.edap.crypto.digest.NativeDigestProvider;
import io.edap.crypto.mac.JceMacProvider;
import io.edap.crypto.mac.MacProvider;
import io.edap.crypto.mac.MacSession;
import io.edap.crypto.mac.NativeMacProvider;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * 单候选 / 单 JVM 的加密微基准探针（不依赖 JUnit）。
 *
 * <p><b>一次进程只测一个候选</b>（{@code jce} 或 {@code native}），结果打成一行 JSON
 * 交给 {@code io.edap.crypto.test.JvmVsJniPerfTest} 汇总。为什么不一个 JVM 里把两个
 * 候选都测了：调用点会变成 megamorphic，JIT 会按「先跑的那个」的形态编译后跑的那个，
 * 读出来的是「谁排在后面谁吃亏」而不是真实差距 —— 见 memory
 * {@code feedback_benchmark_isolated_jvm}。</p>
 *
 * <p>用法（classpath 需含 edap-crypto 与 edap-native）：</p>
 * <pre>
 * java -cp ... io.edap.crypto.benchmark.JvmVsJniProbe --impl=native --alg=sha256 --size=1024
 * </pre>
 *
 * <p>输出：stdout 里带 {@link #MARKER} 前缀的那一行（前面可能有 native 库加载日志）：</p>
 * <pre>
 * @@EDAP_PERF@@{"impl":"native","alg":"sha256","size":1024,"iters":4096,...}
 * </pre>
 *
 * <p><b>逃逸锚点</b>：每次迭代的摘要字节都会被累进 {@code checksum} 并随结果输出，
 * 防止 JIT 把整段计算当成死代码消掉。</p>
 *
 * <p><b>测量口径</b>：一个 JVM 内跑若干「批」，每批 N 次迭代取平均 ns/op；
 * 批内取 min（单 JVM 内取 min 是为了压掉 GC / 调度抖动），
 * 跨 JVM 的汇总由父测试取中位数。</p>
 */
public final class JvmVsJniProbe {

    /**
     * 结果行前缀 —— 父进程靠它从混有日志的 stdout 里捞出结果。
     */
    public static final String MARKER = "@@EDAP_PERF@@";

    /**
     * MAC 用的 key（≥ 32 字节，够 SHA-256 的 block size）。
     */
    private static final byte[] KEY = ("edap-perf-key-0123456789abcdef-0123456789abcdef")
            .getBytes(StandardCharsets.UTF_8);

    private JvmVsJniProbe() {
        // 工具类
    }

    /**
     * 一次加密操作 —— 输入载荷，返回摘要 / MAC 字节。
     */
    @FunctionalInterface
    interface CryptoOp {
        byte[] run(byte[] payload);
    }

    public static void main(String[] args) {
        String impl = arg(args, "impl", "jce");
        String alg = arg(args, "alg", "sha256");
        int size = Integer.parseInt(arg(args, "size", "1024"));
        int batches = Integer.parseInt(arg(args, "batches", "5"));
        // 每批处理的总字节数 —— 固定「每批处理多少数据」而不是固定迭代次数，
        // 这样 64B 与 64KB 两种载荷的每批耗时量级可比。
        long batchBytes = Long.parseLong(arg(args, "bytes", "4194304"));
        int minIters = Integer.parseInt(arg(args, "minIters", "64"));

        byte[] payload = payload(size);

        CryptoOp op = opFor(impl, alg);

        // 迭代次数：每批按字节预算折算，并设下限避免小载荷的批太短（噪声压不住）
        int iters = (int) Math.max(minIters, batchBytes / Math.max(1, size));
        // 每批的字节预算要落在实际迭代数上（iters 被下限抬高时批次更大）
        long actualBatchBytes = (long) iters * size;

        // ---- 预热：让 JIT 把这条路径编译到 C2，否则第一批全是解释执行 ----
        int warmupIters = Math.max(iters / 4, 1000);
        long checksum = 0;
        byte[] first = null;
        for (int i = 0; i < warmupIters; i++) {
            byte[] r = op.run(payload);
            checksum += r[0];
            if (first == null) {
                first = r;
            }
        }

        // ---- 正式测量：若干批，每批一次 nanoTime 区间 ----
        double[] nsPerOp = new double[batches];
        for (int b = 0; b < batches; b++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < iters; i++) {
                byte[] r = op.run(payload);
                checksum += r[0];
            }
            long t1 = System.nanoTime();
            nsPerOp[b] = (double) (t1 - t0) / iters;
        }

        double min = Arrays.stream(nsPerOp).min().orElse(0d);
        double max = Arrays.stream(nsPerOp).max().orElse(0d);
        double[] sorted = nsPerOp.clone();
        Arrays.sort(sorted);
        double median = sorted[sorted.length / 2];

        System.out.println(MARKER
                + "{\"impl\":\"" + impl + "\""
                + ",\"alg\":\"" + alg + "\""
                + ",\"size\":" + size
                + ",\"iters\":" + iters
                + ",\"batches\":" + batches
                + ",\"batchBytes\":" + actualBatchBytes
                + ",\"minNs\":" + fmt(min)
                + ",\"medianNs\":" + fmt(median)
                + ",\"maxNs\":" + fmt(max)
                + ",\"checksum\":" + checksum
                + ",\"digest\":\"" + hex(first) + "\"}");
    }

    /**
     * 造出「被测候选」对应的操作函数。
     *
     * @param impl {@code jce} 或 {@code native}
     * @param alg  {@code sha256} 或 {@code hmacsha256}
     */
    private static CryptoOp opFor(String impl, String alg) {
        boolean nativeImpl = "native".equals(impl);
        if ("sha256".equals(alg)) {
            DigestProvider provider = nativeImpl
                    ? NativeDigestProvider.tryCreate("SHA-256")
                    : new JceDigestProvider("SHA-256");
            if (provider == null) {
                throw new IllegalStateException("native digest 不可用：edap-native 不在 classpath，"
                        + "或当前平台没有对应的预编译产物（" + platformHint() + "）");
            }
            return payload -> {
                DigestSession session = provider.newSession();
                session.update(payload, 0, payload.length);
                return session.digest();
            };
        }
        if ("hmacsha256".equals(alg)) {
            MacProvider provider = nativeImpl
                    ? NativeMacProvider.tryCreate("HmacSHA256")
                    : new JceMacProvider("HmacSHA256");
            if (provider == null) {
                throw new IllegalStateException("native mac 不可用：edap-native 不在 classpath，"
                        + "或当前平台没有对应的预编译产物（" + platformHint() + "）");
            }
            return payload -> {
                MacSession session = provider.newSession(KEY);
                session.update(payload, 0, payload.length);
                return session.doFinal();
            };
        }
        throw new IllegalArgumentException("未知 alg: " + alg + "（支持 sha256 / hmacsha256）");
    }

    /**
     * 确定性载荷（各次运行内容完全一致，便于跨实现比对结果字节）。
     */
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

    private static String hex(byte[] data) {
        if (data == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    /**
     * 平台提示 —— 出错时告诉用户缺哪个 .o。
     *
     * <p><b>不直接引用 {@code io.edap.jni.Platform}</b>（编译期硬依赖）：edap-native 是
     * edap-crypto 的 test scope 依赖，探针若被单独拷到别的机器上跑（classpath 里没有
     * edap-native），硬引用会抛 NoClassDefFoundError 把真正的失败原因盖掉。这里反射调用，
     * 拿不到就退回本地估算 —— <b>只是提示文本，不参与加载决策</b>。</p>
     */
    private static String platformHint() {
        String osName = System.getProperty("os.name", "");
        String osArch = System.getProperty("os.arch", "?");
        String name = platformResourceName(osName, osArch);
        if (name == null) {
            name = "edap-native-" + osSegment(osName) + "_" + osArch.toLowerCase(Locale.ROOT) + ".o";
        }
        return "期望资源 " + name;
    }

    /**
     * 反射调用 {@code io.edap.jni.Platform.resourceName} —— 与运行时/构建脚本同一份规则。
     *
     * @return 产物名；edap-native 不在 classpath 上时返回 null
     */
    private static String platformResourceName(String osName, String osArch) {
        try {
            Class<?> platform = Class.forName("io.edap.jni.Platform");
            Method method = platform.getMethod("resourceName", String.class, String.class);
            return (String) method.invoke(null, osName, osArch);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * os 段的小写估算（仅在 edap-native 不在 classpath 上时用于提示文本）。
     */
    private static String osSegment(String osName) {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        }
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        if (os.contains("sunos") || os.contains("solaris")) {
            return "sunos";
        }
        return "unsupported";
    }
}
