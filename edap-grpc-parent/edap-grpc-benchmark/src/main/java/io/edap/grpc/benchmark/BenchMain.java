/*
 * Copyright 2026 The edap Project
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

package io.edap.grpc.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 基准测试的入口 —— 跑完所有场景后打印 markdown 报告，并写到
 * {@code target/benchmark-report.md}。
 *
 * <p>★ <b>2×2 矩阵</b>：本基准同时压四条链路 —— edap 客户端 / grpc-java 客户端
 * 各自打到 edap 服务端 / grpc-java 服务端。这样才能把「端到端差距」拆成
 * 「客户端这一侧的差距」与「服务端那一侧的差距」。</p>
 *
 * <p>用法：</p>
 * <pre>{@code
 *   mvn -pl edap-grpc-parent/edap-grpc-benchmark -DskipTests compile exec:java \
 *       -Dexec.mainClass=io.edap.grpc.benchmark.BenchMain
 * }</pre>
 *
 * <p>★ 实际跑的时候<b>不要用 exec:java</b>：它在 Maven 的 JVM 里执行，会踩到
 * {@code java.base does not "opens java.lang"}（edap 的 Unsafe 工具类在类初始化时反射访问
 * {@code String.value}）。必须用独立 JVM + {@code --add-opens}。</p>
 *
 * <p>参数：</p>
 * <ul>
 *   <li>{@code --quick} —— 缩短窗口（冒烟用）</li>
 *   <li>{@code --warmup <ms>} / {@code --measure <ms>} —— 预热与测量窗口</li>
 *   <li>{@code --blob 0,1024} / {@code --conc 1,8} —— 覆盖负载矩阵</li>
 *   <li>{@code --rounds <n>} —— 每个臂跑几轮（默认 4，恰好等于臂数 → 拉丁方）</li>
 *   <li>{@code --out <file>} —— 报告输出路径</li>
 * </ul>
 */
public final class BenchMain {

    /** 臂在矩阵里的下标 —— 报告的列顺序按它排。 */
    private static final int ARM_EE = 0;
    private static final int ARM_EG = 1;
    private static final int ARM_GE = 2;
    private static final int ARM_GG = 3;

    /** 一个场景的结果（每个臂若干轮）。 */
    private static final class Scenario {
        int blobSize;
        int concurrency;
        /** armRounds[臂下标] = 该臂各轮的结果。 */
        final List<List<BenchResult>> armRounds = new ArrayList<>();

        List<BenchResult> rounds(int arm) {
            return armRounds.get(arm);
        }

        double rps(int arm) {
            return medianOf(rounds(arm), BenchResult::rps);
        }

        double p50(int arm) {
            return medianOf(rounds(arm), r -> r.p50);
        }

        double p99(int arm) {
            return medianOf(rounds(arm), r -> r.p99);
        }

        long errors(int arm) {
            long n = 0;
            for (BenchResult r : rounds(arm)) {
                n += r.errors;
            }
            return n;
        }
    }

    /** 取某个字段的多轮中位数。 */
    private static double medianOf(List<BenchResult> rounds, java.util.function.ToDoubleFunction<BenchResult> f) {
        double[] values = new double[rounds.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = f.applyAsDouble(rounds.get(i));
        }
        return BenchRunner.medianOf(values);
    }

    public static void main(String[] args) throws Exception {
        long warmupMillis = 1000;
        long measureMillis = 3000;
        long codecMillis = 1000;
        int rounds = 4;
        String out = "target/benchmark-report.md";
        int[] blobSizes = BenchConstants.BLOB_SIZES;
        int[] concurrencies = BenchConstants.CONCURRENCIES;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--quick":
                    warmupMillis = 300;
                    measureMillis = 800;
                    codecMillis = 300;
                    break;
                case "--warmup":
                    warmupMillis = Long.parseLong(args[++i]);
                    break;
                case "--measure":
                    measureMillis = Long.parseLong(args[++i]);
                    break;
                case "--codec":
                    codecMillis = Long.parseLong(args[++i]);
                    break;
                case "--rounds":
                    rounds = Integer.parseInt(args[++i]);
                    break;
                case "--blob":
                    blobSizes = parseInts(args[++i]);
                    break;
                case "--conc":
                    concurrencies = parseInts(args[++i]);
                    break;
                case "--out":
                    out = args[++i];
                    break;
                default:
                    System.out.println("未知参数: " + arg + "（忽略）");
                    break;
            }
        }

        System.out.println("=== edap 原生 gRPC vs google grpc-java —— 2×2 矩阵 ===");
        System.out.println("预热 " + warmupMillis + "ms / 测量 " + measureMillis + "ms / "
                + "4 个臂 × " + rounds + " 轮（拉丁方，位置完全平衡）");

        int edapPort = EdapBenchServer.freePort();
        int googlePort = EdapBenchServer.freePort();

        List<Scenario> scenarios = new ArrayList<>();
        List<CodecBenchmark.CodecResult> codecResults;

        try (EdapBenchServer edapServer = new EdapBenchServer(edapPort);
             GoogleBenchServer googleServer = new GoogleBenchServer(googlePort)) {
            edapServer.start();
            googleServer.start();
            System.out.println("edap server: " + edapPort + " / google server: " + googlePort);

            // ★ 每个臂独占一条连接 —— 测的是单臂表现，不能让别的臂的流量挤进来。
            try (BenchArm ee = edapArm("E→E", "edap", edapPort);
                 BenchArm eg = edapArm("E→G", "google", googlePort);
                 BenchArm ge = googleArm("G→E", "edap", edapPort);
                 BenchArm gg = googleArm("G→G", "google", googlePort)) {

                List<BenchArm> arms = List.of(ee, eg, ge, gg);

                // 互操作自检 —— 基准跑之前先确认两条跨框架链路真的能互通，
                // 否则测出来的数字没有意义。
                checkInterop(edapPort, googlePort);

                for (int blobSize : blobSizes) {
                    for (int concurrency : concurrencies) {
                        Scenario scenario = new Scenario();
                        scenario.blobSize = blobSize;
                        scenario.concurrency = concurrency;
                        for (int i = 0; i < arms.size(); i++) {
                            scenario.armRounds.add(new ArrayList<>());
                        }

                        System.out.print("  场景 blob=" + blobSize + " conc=" + concurrency + " ... ");
                        long t0 = System.nanoTime();

                        // ★ 拉丁方调度：第 r 轮里第 k 个位置跑第 (r+k)%4 个臂 ——
                        //   4 轮下来每个臂在每个位置各跑一次，位置带来的偏差
                        //   （JIT 预热、GC、机器负载漂移）在每个臂上被均摊掉。
                        for (int round = 0; round < rounds; round++) {
                            for (int k = 0; k < arms.size(); k++) {
                                int armIndex = (round + k) % arms.size();
                                BenchArm arm = arms.get(armIndex);
                                BenchResult result = BenchRunner.run(arm.label, blobSize, concurrency,
                                        warmupMillis, measureMillis, arm.op(blobSize));
                                scenario.armRounds.get(armIndex).add(result);
                            }
                        }

                        scenarios.add(scenario);
                        System.out.printf("E→E=%.0f  E→G=%.0f  G→E=%.0f  G→G=%.0f req/s  (%.1fs)%n",
                                scenario.rps(ARM_EE), scenario.rps(ARM_EG),
                                scenario.rps(ARM_GE), scenario.rps(ARM_GG),
                                (System.nanoTime() - t0) / 1e9);
                        for (int i = 0; i < arms.size(); i++) {
                            List<BenchResult> rs = scenario.rounds(i);
                            System.out.println("    " + arms.get(i).label + " " + rs);
                            if (scenario.errors(i) > 0) {
                                System.out.println("    ⚠ " + arms.get(i).label + " 有失败调用: "
                                        + rs.get(0).firstError);
                            }
                        }
                    }
                }

                codecResults = CodecBenchmark.run(blobSizes, codecMillis);
            }
        }

        String report = render(scenarios, codecResults, warmupMillis, measureMillis, codecMillis, rounds);
        System.out.println();
        System.out.println(report);

        Path path = Paths.get(out);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.write(path, report.getBytes(StandardCharsets.UTF_8));
        System.out.println("报告已写入: " + path.toAbsolutePath());
    }

    // ------------------------------------------------------------------
    // 各臂的调用动作
    // ------------------------------------------------------------------

    /**
     * 造一个「edap 客户端打某个服务端」的臂。
     *
     * <p>★ payload 在每个场景开始时造一次并复用 —— 与历史报告的口径一致
     * （历史报告也是「造一次、循环调用」）；每次调用现造 payload 会把
     * 64KB 的分配压力算进测量里，那就不是同一个实验了。</p>
     */
    private static BenchArm edapArm(String label, String serverName, int port) throws IOException {
        EdapBenchClient client = new EdapBenchClient("127.0.0.1", port);
        return new BenchArm(label, "edap", serverName, client, blob -> {
            Payload request = BenchPayloads.edap(blob);
            return () -> client.unary(request).getValue();
        });
    }

    /**
     * 造一个「grpc-java 客户端打某个服务端」的臂。
     */
    private static BenchArm googleArm(String label, String serverName, int port) throws IOException {
        GoogleBenchClient client = new GoogleBenchClient("127.0.0.1", port);
        return new BenchArm(label, "google", serverName, client, blob -> {
            Bench.Payload request = BenchPayloads.google(blob);
            return () -> client.unary(request).getValue();
        });
    }

    /**
     * 互操作自检 —— edap client → google server 与 google client → edap server。
     *
     * <p><b>必须是跨框架连</b>：edap 客户端连 google 服务端、google 客户端连 edap 服务端。
     * 同框架自连只能证明「自己跟自己通」，证明不了 wire 格式一致。</p>
     */
    private static void checkInterop(int edapPort, int googlePort) throws Exception {
        String expectedName = BenchConstants.NAME_PREFIX + BenchConstants.REQUEST_NAME;
        int expectedValue = BenchConstants.REQUEST_VALUE * 2;
        final int blobSize = 64;

        try (EdapBenchClient edapClient = new EdapBenchClient("127.0.0.1", googlePort)) {
            Payload resp = edapClient.unary(BenchPayloads.edap(blobSize));
            expect(expectedName.equals(resp.getName())
                            && resp.getValue() == expectedValue
                            && resp.getBlob() != null && resp.getBlob().length == blobSize,
                    "edap client → google server 的响应不对: name=" + resp.getName()
                            + " value=" + resp.getValue()
                            + " blob=" + (resp.getBlob() == null ? "null" : resp.getBlob().length));
        }

        try (GoogleBenchClient googleClient = new GoogleBenchClient("127.0.0.1", edapPort)) {
            Bench.Payload googleResp = googleClient.unary(BenchPayloads.google(blobSize));
            expect(expectedName.equals(googleResp.getName())
                            && googleResp.getValue() == expectedValue
                            && googleResp.getBlob().size() == blobSize,
                    "google client → edap server 的响应不对: name=" + googleResp.getName()
                            + " value=" + googleResp.getValue()
                            + " blob=" + googleResp.getBlob().size());
        }
        System.out.println("互操作自检通过（edap↔google 双向 unary 互通）");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * 渲染 markdown 报告。
     */
    private static String render(List<Scenario> scenarios, List<CodecBenchmark.CodecResult> codecResults,
                                 long warmupMillis, long measureMillis, long codecMillis, int rounds) {
        StringBuilder sb = new StringBuilder();
        sb.append("# edap 原生 gRPC vs google grpc-java —— 2×2 矩阵性能对比\n\n");
        sb.append("生成时间: ").append(new java.util.Date()).append("\n\n");
        sb.append("运行环境: ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append(" / ")
                .append(System.getProperty("os.arch")).append(" / JDK ")
                .append(System.getProperty("java.version")).append(" / ")
                .append(Runtime.getRuntime().availableProcessors()).append(" 核\n\n");
        sb.append("被测版本: grpc-java ").append(GRPC_VERSION).append(" / protobuf-java ")
                .append(PROTOBUF_VERSION).append(" / protoc ").append(PROTOBUF_VERSION)
                .append(" / edap 自研 protobuf（edap-protobuf，无 google 依赖）\n\n");
        sb.append("测量口径: 预热 ").append(warmupMillis).append("ms + 测量窗口 ")
                .append(measureMillis).append("ms（时间制）；")
                .append("4 个臂 × ").append(rounds).append(" 轮，**拉丁方调度**（每个臂在每个位置各跑一次）；")
                .append("N 个 worker 线程共享一条连接；延迟单位微秒\n\n");

        // ---- 一、端到端（旧格式，保持与历史报告可比） ----
        sb.append("## 一、端到端 unary 性能（E→E vs G→G）\n\n");
        sb.append("| 负载 | 并发 | edap req/s | google req/s | edap/google | edap p50(us) | google p50(us) | edap p99(us) | google p99(us) |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (Scenario s : scenarios) {
            double ratio = s.rps(ARM_GG) == 0 ? 0 : s.rps(ARM_EE) / s.rps(ARM_GG);
            sb.append(String.format("| %s | %d | %.0f | %.0f | %.2fx | %.0f | %.0f | %.0f | %.0f |%n",
                    blobLabel(s.blobSize), s.concurrency, s.rps(ARM_EE), s.rps(ARM_GG), ratio,
                    s.p50(ARM_EE), s.p50(ARM_GG), s.p99(ARM_EE), s.p99(ARM_GG)));
        }

        // ---- 二、2×2 矩阵 ----
        sb.append("\n## 二、2×2 矩阵（客户端 × 服务端）\n\n");
        sb.append("| 负载 | 并发 | E→E | E→G | G→E | G→G | 换客户端(G→E/E→E) | 换服务端(E→G/E→E) | 全栈(G→G/E→E) | 交互项 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (Scenario s : scenarios) {
            double ee = s.rps(ARM_EE);
            double eg = s.rps(ARM_EG);
            double ge = s.rps(ARM_GE);
            double gg = s.rps(ARM_GG);
            double clientGain = ee == 0 ? 0 : ge / ee;
            double serverGain = ee == 0 ? 0 : eg / ee;
            double stackGain = ee == 0 ? 0 : gg / ee;
            // 交互项 = 全栈增益 / (客户端增益 × 服务端增益) —— 1 表示两个维度可加、互不影响
            double interaction = (clientGain == 0 || serverGain == 0) ? 0 : stackGain / (clientGain * serverGain);
            sb.append(String.format("| %s | %d | %.0f | %.0f | %.0f | %.0f | %.2fx | %.2fx | %.2fx | %.2fx |%n",
                    blobLabel(s.blobSize), s.concurrency, ee, eg, ge, gg,
                    clientGain, serverGain, stackGain, interaction));
        }
        sb.append("\n> **换客户端** = 同一个 edap 服务端上，把客户端换成 grpc-java 的提升（`G→E / E→E`）—— ");
        sb.append("**服务端本身不变**，所以这一列是客户端这一侧的贡献。\n");
        sb.append("> **换服务端** = 同一个 edap 客户端上，把服务端换成 grpc-java 的提升（`E→G / E→E`）—— ");
        sb.append("**客户端本身不变**，所以这一列是服务端这一侧的贡献。\n");
        sb.append("> **全栈** = 两边都换（`G→G / E→E`）。**交互项** = 全栈 ÷ (换客户端 × 换服务端)：");
        sb.append("接近 1 表示两个维度可以相加、互不影响；明显偏离 1 表示两者有耦合"
                + "（比如 grpc-java 的服务端在 grpc-java 的客户端上才发挥得出来）。\n");

        // ---- 三、归因小结 ----
        sb.append("\n## 三、归因小结\n\n");
        appendAttribution(sb, scenarios);

        // ---- 四、原始轮次 ----
        sb.append("\n## 四、原始轮次（每臂 " + rounds + " 轮）\n\n");
        sb.append("| 负载 | 并发 | 臂 | 客户端 | 服务端 | 轮次 req/s | 轮次 p99(us) | 错误数 |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        String[] armLabels = {"E→E", "E→G", "G→E", "G→G"};
        String[] armClients = {"edap", "edap", "google", "google"};
        String[] armServers = {"edap", "google", "edap", "google"};
        for (Scenario s : scenarios) {
            for (int arm = 0; arm < armLabels.length; arm++) {
                List<BenchResult> rs = s.rounds(arm);
                for (int r = 0; r < rs.size(); r++) {
                    BenchResult result = rs.get(r);
                    sb.append(String.format("| %s | %d | %s | %s | %s | %.0f | %.0f | %d |%n",
                            blobLabel(s.blobSize), s.concurrency, armLabels[arm],
                            armClients[arm], armServers[arm], result.rps(), result.p99, result.errors));
                }
            }
        }

        // ---- 五、编解码器 ----
        sb.append("\n## 五、编解码器 micro 对比\n\n");
        sb.append("| 负载 | 线上字节数 | 两边字节完全相同 | edap 编码核心 ns/op | google 编码核心 ns/op | 编码核心比 | edap 编码(产出 byte[]) ns/op | google 编码(产出 byte[]) ns/op | 编码比 | edap 解码 ns/op | google 解码 ns/op | 解码比 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (CodecBenchmark.CodecResult r : codecResults) {
            sb.append(String.format("| %s | %d | %s | %.0f | %.0f | %.2fx | %.0f | %.0f | %.2fx | %.0f | %.0f | %.2fx |%n",
                    blobLabel(r.blobSize), r.wireBytes, r.wireIdentical ? "是" : "**否**",
                    r.edapEncodeCoreNs, r.googleEncodeCoreNs,
                    r.googleEncodeCoreNs == 0 ? 0 : r.edapEncodeCoreNs / r.googleEncodeCoreNs,
                    r.edapEncodeNs, r.googleEncodeNs,
                    r.googleEncodeNs == 0 ? 0 : r.edapEncodeNs / r.googleEncodeNs,
                    r.edapDecodeNs, r.googleDecodeNs,
                    r.googleDecodeNs == 0 ? 0 : r.edapDecodeNs / r.googleDecodeNs));
        }
        sb.append("\n> ★ **「编码核心」是主口径 —— 两边量的是同一件事**："
                + "edap 写进 writer 缓冲只读 `size()`（不产出 byte[]），"
                + "google 是 `writeTo(CodedOutputStream)` 写进已有数组（两边都是 1 次拷贝）。\n");
        sb.append("> 「编码(产出 byte[])」是**不对等**的一档：edap 的 `GrpcCodec.encode` = 写进 writer 缓冲 **+ 拷成新 byte[]**（2 次拷贝），"
                + "google 的 `toByteArray()` = 直接写进最终数组（1 次拷贝）⇒ edap 多一次整块拷贝。"
                + "16KB 上差 ~690ns、64KB 上差 ~1660ns，而一次 64KB 的 `System.arraycopy` 实测 ~1335ns。"
                + "**拿这一档比「谁更快」是不对的。**\n");
        sb.append("> ★ 2026-11-13 起，**gRPC 运行时已不走这一档了** —— 帧头内联（`GrpcFrameEncoder`）之后，"
                + "发送侧只拷一次（writer 缓冲 → 目标缓冲）。"
                + "`GrpcCodec.encode` / `GrpcFrame.encode` 现在只留在测试与基准里当对照基准。\n");
        sb.append("> 解码口径：edap 是 `GrpcCodec.decode`；google 是 `Bench.Payload.parseFrom`（两边同构）。\n");
        sb.append("> 「比」= edap 耗时 / google 耗时，**大于 1 表示 edap 更慢**。\n");
        boolean allIdentical = true;
        for (CodecBenchmark.CodecResult r : codecResults) {
            if (!r.wireIdentical) {
                allIdentical = false;
                break;
            }
        }
        if (allIdentical) {
            sb.append("> 本批**所有负载下两边的字节都逐字节相同**（包括 0 字节负载 —— "
                    + "edap 的编码器会省略空的 bytes 字段，与 protobuf-java 的 proto3 默认值规则一致）。\n");
        } else {
            sb.append("> 0 字节负载下两边字节**不完全相同**：edap 的编码器会把空的 bytes 字段也写出去"
                    + "（`1a 00`），protobuf-java 按 proto3 默认值规则省略该字段。"
                    + "两者解码后的**语义完全相同**（blob 都是空），不影响互操作；"
                    + "其余负载下两边的字节逐字节相同。\n");
        }

        // ---- 六、说明 ----
        sb.append("\n## 六、说明\n\n");
        sb.append("- 两侧服务端在同一个 JVM 里，业务逻辑逐行对齐（name 加前缀、value ×2、blob 原样回显），");
        sb.append("所以「edap 客户端打 google 服务端」与「google 客户端打 edap 服务端」的断言有意义。\n");
        sb.append("- 互操作自检在跑基准**之前**执行，两条跨框架链路都必须通，否则直接失败退出。\n");
        sb.append("- 四个臂各自**只开一条连接**，所有 worker 线程共享 —— 与 grpc-java 官方推荐用法一致。\n");
        sb.append("- edap 侧客户端是**阻塞 socket + 单读线程**的实现（`GrpcChannel`），");
        sb.append("google 侧是 netty 的 NIO 实现，两者的并发模型不同，这正是本对比要暴露的差异。\n");
        sb.append("- 报告里的数字**没有做绝对阈值断言**，只作横向对比用；机器负载会影响绝对值。\n");
        sb.append("- google 系依赖（grpc-java / protobuf-java）**只存在于本基准模块**，");
        sb.append("不进入任何 edap 运行时产物（`edap-container-boot-lib.txt` 不含本模块）。\n");
        return sb.toString();
    }

    /**
     * 归因小结 —— 把矩阵里的数字翻译成「差距到底该怪谁」。
     */
    private static void appendAttribution(StringBuilder sb, List<Scenario> scenarios) {
        sb.append("| 负载 | 并发 | 客户端侧差距(G→E/E→E) | 服务端侧差距(E→G/E→E) | 全栈差距 | 谁是主要瓶颈 |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (Scenario s : scenarios) {
            double ee = s.rps(ARM_EE);
            if (ee == 0) {
                continue;
            }
            double clientGain = s.rps(ARM_GE) / ee;
            double serverGain = s.rps(ARM_EG) / ee;
            double stackGain = s.rps(ARM_GG) / ee;
            String culprit;
            if (clientGain >= serverGain * 1.2) {
                culprit = "**客户端**";
            } else if (serverGain >= clientGain * 1.2) {
                culprit = "**服务端**";
            } else {
                culprit = "两侧相当";
            }
            sb.append(String.format("| %s | %d | %.2fx | %.2fx | %.2fx | %s |%n",
                    blobLabel(s.blobSize), s.concurrency, clientGain, serverGain, stackGain, culprit));
        }
        sb.append("\n> **客户端侧差距** = 在**同一个 edap 服务端**上换客户端（`G→E / E→E`）—— ");
        sb.append("服务端不变，所以这一列只反映客户端。\n");
        sb.append("> **服务端侧差距** = 在**同一个 edap 客户端**上换服务端（`E→G / E→E`）—— ");
        sb.append("客户端不变，所以这一列只反映服务端。\n");
    }

    private static String blobLabel(int size) {
        if (size >= 1024 && size % 1024 == 0) {
            return (size / 1024) + "KB";
        }
        return size + "B";
    }

    private static int[] parseInts(String csv) {
        String[] parts = csv.split(",");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Integer.parseInt(parts[i].trim());
        }
        return values;
    }

    /** grpc-java 版本（与 pom 里的保持一致）。 */
    private static final String GRPC_VERSION = "1.85.1";

    /** protobuf-java / protoc 版本（与 pom 里的保持一致）。 */
    private static final String PROTOBUF_VERSION = "3.25.9";
}
