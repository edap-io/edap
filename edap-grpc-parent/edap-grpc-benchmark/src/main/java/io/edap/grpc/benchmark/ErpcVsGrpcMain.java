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

import io.edap.erpc.server.ErpcOutboundWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * eRPC vs edap 原生 gRPC 的性能对比。
 *
 * <p>★ <b>为什么不是 2×2 矩阵</b>：gRPC 基准里的 2×2 是为了把「客户端慢」与「服务端慢」拆开，
 * 前提是<b>两边 wire 格式互通</b>（edap 的 gRPC 与 grpc-java 可以互调）。
 * eRPC 与 gRPC 是<b>两套不同的线协议</b>（32 字节自定义帧头 vs HTTP/2）⇒ 交叉臂根本连不上
 * ⇒ 只能做「各自端到端」的对比。<b>这是本对比的口径边界，必须说清楚。</b></p>
 *
 * <p><b>对齐了什么</b>（保证差距只能来自协议/传输层）：</p>
 * <ol>
 *   <li><b>同一个 DTO</b>（{@link Payload}：name / value / blob）；</li>
 *   <li><b>同一套 protobuf 编解码实现</b>（edap 自研）⇒ 编解码成本互相抵消，
 *       剩下的差距归协议与传输层（micro 对比见第三节）；</li>
 *   <li><b>同样的业务逻辑</b>（name 加前缀、value ×2、blob 原样回显）；</li>
 *   <li><b>同样的连接数</b>（1 条连接被 N 个 worker 共享；eRPC 的连接池也设成 poolSize=1）；</li>
 *   <li><b>同样的测量代码</b>（{@link BenchRunner}，与 gRPC 基准共用）；</li>
 *   <li><b>拉丁方轮换</b>：两个臂轮流先跑，把「先跑的那一方」这个位置偏差摊掉。</li>
 * </ol>
 *
 * <p><b>用法</b>（必须用独立 JVM + {@code --add-opens}，理由见 {@link BenchMain}）：</p>
 * <pre>{@code
 *   java --add-opens java.base/java.lang=ALL-UNNAMED \
 *        --add-opens java.base/java.nio=ALL-UNNAMED \
 *        --add-opens java.base/java.util=ALL-UNNAMED \
 *        --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
 *        -cp <classpath> io.edap.grpc.benchmark.ErpcVsGrpcMain --out report.md
 * }</pre>
 */
public final class ErpcVsGrpcMain {

    /** 臂下标。 */
    private static final int ARM_GRPC = 0;
    private static final int ARM_ERPC = 1;

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
        String out = "target/erpc-vs-grpc-report.md";
        int[] blobSizes = BenchConstants.BLOB_SIZES;
        int[] concurrencies = BenchConstants.CONCURRENCIES;
        int erpcWorkers = 0;
        boolean erpcInline = false;
        boolean erpcLockStats = false;

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
                case "--erpc-workers":
                    erpcWorkers = Integer.parseInt(args[++i]);
                    break;
                case "--erpc-inline":
                    erpcInline = true;
                    break;
                case "--erpc-lock-stats":
                    erpcLockStats = true;
                    break;
                default:
                    System.out.println("未知参数: " + arg + "（忽略）");
                    break;
            }
        }

        System.out.println("=== eRPC vs edap 原生 gRPC —— 端到端 unary 对比 ===");
        System.out.println("预热 " + warmupMillis + "ms / 测量 " + measureMillis + "ms / "
            + "2 个臂 × " + rounds + " 轮（拉丁方轮换）");

        int grpcPort = EdapBenchServer.freePort();
        int erpcPort = ErpcBenchServer.freePort();

        List<Scenario> scenarios = new ArrayList<>();
        List<ErpcCodecCompare.CompareResult> codecResults;

        try (EdapBenchServer grpcServer = new EdapBenchServer(grpcPort);
             ErpcBenchServer erpcServer = new ErpcBenchServer(erpcPort, erpcWorkers, erpcInline)) {
            grpcServer.start();
            erpcServer.start();
            System.out.println("gRPC server: " + grpcPort + " / eRPC server: " + erpcPort);

            try (EdapBenchClient grpcClient = new EdapBenchClient("127.0.0.1", grpcPort);
                 ErpcBenchClient erpcClient = new ErpcBenchClient("127.0.0.1", erpcPort)) {

                List<BenchArm> arms = List.of(
                    new BenchArm("gRPC", "grpc", "grpc", grpcClient,
                        blob -> {
                            Payload request = BenchPayloads.edap(blob);
                            return () -> grpcClient.unary(request).getValue();
                        }),
                    new BenchArm("eRPC", "erpc", "erpc", erpcClient,
                        blob -> {
                            Payload request = BenchPayloads.edap(blob);
                            return () -> erpcClient.unary(request).getValue();
                        }));

                // 自检 —— 两个臂都要能正确返回，否则测出来的数字没有意义
                checkArms(grpcClient, erpcClient);

                for (int blobSize : blobSizes) {
                    for (int concurrency : concurrencies) {
                        Scenario scenario = new Scenario();
                        scenario.blobSize = blobSize;
                        scenario.concurrency = concurrency;
                        scenario.armRounds.add(new ArrayList<>());
                        scenario.armRounds.add(new ArrayList<>());

                        System.out.print("  场景 blob=" + blobSize + " conc=" + concurrency + " ... ");
                        long t0 = System.nanoTime();

                        if (erpcLockStats) {
                            ErpcOutboundWriter.resetLockStats();
                        }

                        // ★ 拉丁方轮换：第 r 轮里第 k 个位置跑第 (r+k)%2 个臂 ——
                        //   偶数个臂时每轮只跑一遍所有臂，轮次之间交换先后顺序，
                        //   「先跑的那一方」这个位置偏差被摊掉。
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
                        System.out.printf("gRPC=%.0f  eRPC=%.0f req/s  (eRPC/gRPC=%.2fx, %.1fs)%n",
                            scenario.rps(ARM_GRPC), scenario.rps(ARM_ERPC),
                            scenario.rps(ARM_GRPC) == 0 ? 0 : scenario.rps(ARM_ERPC) / scenario.rps(ARM_GRPC),
                            (System.nanoTime() - t0) / 1e9);
                        for (int i = 0; i < arms.size(); i++) {
                            List<BenchResult> rs = scenario.rounds(i);
                            System.out.println("    " + arms.get(i).label + " " + rs);
                            if (scenario.errors(i) > 0) {
                                System.out.println("    ⚠ " + arms.get(i).label + " 有失败调用: "
                                    + rs.get(0).firstError);
                            }
                        }
                        if (erpcLockStats) {
                            long ops = 0;
                            for (BenchResult r : scenario.rounds(ARM_ERPC)) {
                                ops += r.ops;
                            }
                            System.out.println("    ★ 出站锁: " + ErpcOutboundWriter.lockStats(ops));
                        }
                    }
                }

                codecResults = ErpcCodecCompare.run(blobSizes, codecMillis);
            }
        }

        String report = render(scenarios, codecResults, warmupMillis, measureMillis, codecMillis, rounds,
            erpcWorkers, erpcInline);
        System.out.println();
        System.out.println(report);

        Path path = Paths.get(out);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.write(path, report.getBytes(StandardCharsets.UTF_8));
        System.out.println("报告已写入: " + path.toAbsolutePath());
    }

    /**
     * 自检 —— 两个臂各调一次，校验 name / value / blob。
     */
    private static void checkArms(EdapBenchClient grpcClient, ErpcBenchClient erpcClient) {
        final int blobSize = 64;
        String expectedName = BenchConstants.NAME_PREFIX + BenchConstants.REQUEST_NAME;
        int expectedValue = BenchConstants.REQUEST_VALUE * 2;

        Payload grpcResp = grpcClient.unary(BenchPayloads.edap(blobSize));
        expect(expectedName.equals(grpcResp.getName())
                && grpcResp.getValue() == expectedValue
                && grpcResp.getBlob() != null && grpcResp.getBlob().length == blobSize,
            "gRPC 臂自检不过: name=" + grpcResp.getName() + " value=" + grpcResp.getValue());

        Payload erpcResp = erpcClient.unary(BenchPayloads.edap(blobSize));
        expect(expectedName.equals(erpcResp.getName())
                && erpcResp.getValue() == expectedValue
                && erpcResp.getBlob() != null && erpcResp.getBlob().length == blobSize,
            "eRPC 臂自检不过: name=" + erpcResp.getName() + " value=" + erpcResp.getValue());

        System.out.println("自检通过（两个臂都返回正确结果）");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * 渲染 markdown 报告。
     */
    private static String render(List<Scenario> scenarios, List<ErpcCodecCompare.CompareResult> codecResults,
                                 long warmupMillis, long measureMillis, long codecMillis, int rounds,
                                 int erpcWorkers, boolean erpcInline) {
        StringBuilder sb = new StringBuilder();
        sb.append("# eRPC vs edap 原生 gRPC —— 端到端 unary 性能对比\n\n");
        sb.append("生成时间: ").append(new java.util.Date()).append("\n\n");
        sb.append("运行环境: ").append(System.getProperty("os.name")).append(' ')
            .append(System.getProperty("os.version")).append(" / ")
            .append(System.getProperty("os.arch")).append(" / JDK ")
            .append(System.getProperty("java.version")).append(" / ")
            .append(Runtime.getRuntime().availableProcessors()).append(" 核\n\n");
        sb.append("测量口径: 预热 ").append(warmupMillis).append("ms + 测量窗口 ")
            .append(measureMillis).append("ms（时间制）；2 个臂 × ").append(rounds)
            .append(" 轮，**拉丁方轮换**（两臂轮流先跑）；N 个 worker 线程共享一条连接；延迟单位微秒\n\n");

        // ---- 一、端到端 ----
        sb.append("## 一、端到端 unary 性能\n\n");
        sb.append("| 负载 | 并发 | gRPC req/s | eRPC req/s | eRPC/gRPC | gRPC p50(us) | eRPC p50(us) | gRPC p99(us) | eRPC p99(us) |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (Scenario s : scenarios) {
            double grpc = s.rps(ARM_GRPC);
            double erpc = s.rps(ARM_ERPC);
            sb.append(String.format("| %s | %d | %.0f | %.0f | %.2fx | %.0f | %.0f | %.0f | %.0f |%n",
                blobLabel(s.blobSize), s.concurrency, grpc, erpc, grpc == 0 ? 0 : erpc / grpc,
                s.p50(ARM_GRPC), s.p50(ARM_ERPC), s.p99(ARM_GRPC), s.p99(ARM_ERPC)));
        }

        // ---- 二、原始轮次 ----
        sb.append("\n## 二、原始轮次（每臂 ").append(rounds).append(" 轮）\n\n");
        sb.append("| 负载 | 并发 | 臂 | 轮次 req/s | 轮次 p50(us) | 轮次 p99(us) | 错误数 |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        String[] labels = {"gRPC", "eRPC"};
        for (Scenario s : scenarios) {
            for (int arm = 0; arm < labels.length; arm++) {
                List<BenchResult> rs = s.rounds(arm);
                for (int r = 0; r < rs.size(); r++) {
                    BenchResult result = rs.get(r);
                    sb.append(String.format("| %s | %d | %s | %.0f | %.0f | %.0f | %d |%n",
                        blobLabel(s.blobSize), s.concurrency, labels[arm],
                        result.rps(), result.p50, result.p99, result.errors));
                }
            }
        }

        // ---- 三、编解码与帧写出路径 ----
        sb.append("\n## 三、编解码与帧写出路径 micro 对比\n\n");
        sb.append("| 负载 | 载荷字节 | 两边字节相同 | gRPC 编码 ns | eRPC 编码 ns | 编码比 | gRPC 解码 ns | eRPC 解码 ns | 解码比 | gRPC 帧写出 ns | eRPC 帧写出 ns | 帧写出比 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (ErpcCodecCompare.CompareResult r : codecResults) {
            sb.append(String.format("| %s | %d | %s | %.0f | %.0f | %.2fx | %.0f | %.0f | %.2fx | %.0f | %.0f | %.2fx |%n",
                blobLabel(r.blobSize), r.payloadBytes, r.bytesIdentical ? "是" : "**否**",
                r.grpcEncodeNs, r.erpcEncodeNs,
                r.grpcEncodeNs == 0 ? 0 : r.erpcEncodeNs / r.grpcEncodeNs,
                r.grpcDecodeNs, r.erpcDecodeNs,
                r.grpcDecodeNs == 0 ? 0 : r.erpcDecodeNs / r.grpcDecodeNs,
                r.grpcFrameWriteNs, r.erpcFrameWriteNs,
                r.grpcFrameWriteNs == 0 ? 0 : r.erpcFrameWriteNs / r.grpcFrameWriteNs));
        }
        sb.append("\n> ★ **「编码」「解码」两档比的是同一件事** —— 两边用的是<b>同一套</b> protobuf 实现\n");
        sb.append("> （edap 自研的 `ProtoBufCodecRegister` + `ProtoBufWriter`），连代码都是逐行相同的\n");
        sb.append("> （`GrpcCodec.encode` 与 `ProtobufErpcCodec.encode` 结构完全一致）。\n");
        sb.append("> ⇒ 这两档接近 1.00x 属正常；**它证明端到端的差距不是编解码器造成的**。\n\n");
        sb.append("> ★ **「帧写出」这一档比的是「同一件事」**：两边都是「帧头内联」——\n");
        sb.append("> 编码器直写自己的缓冲，载荷<b>只拷一次</b>进目标缓冲。\n");
        sb.append("> gRPC 侧 `GrpcFrameEncoder`（2026-11-13 实施），eRPC 侧 `ErpcFrameEncoder`\n");
        sb.append("> （2026-11-14 实施，2026-11-15 重构为「编码器交出借来的缓冲」）\n");
        sb.append("> ⇒ 这一档的差距只剩**每帧固定的几 ns**（eRPC 帧头 32 字节 vs gRPC 5 字节），\n");
        sb.append("> 与载荷大小无关。\n");

        // ---- 四、口径 ----
        sb.append("\n## 四、口径与已知差异（读数前必看）\n\n");
        sb.append("1. **不是 2×2 矩阵**：eRPC 与 gRPC 是两套不同的线协议（32 字节自定义帧头 vs HTTP/2），\n");
        sb.append("   交叉臂**连不上** ⇒ 只能做「各自端到端」的对比，无法像 gRPC 基准那样把差距\n");
        sb.append("   拆成「客户端侧 / 服务端侧」。**本报告的差距是一个整体数字。**\n");
        sb.append("2. **编解码被对齐掉了**：同一个 DTO、同一套 protobuf 实现 ⇒ 编码/解码成本在对比里\n");
        sb.append("   互相抵消，剩下的差距只能来自帧格式、拷贝次数、流控、线程模型。\n");
        sb.append("3. **连接数对齐**：两边都是「1 条连接被 N 个 worker 共享」（eRPC 的连接池设成\n");
        sb.append("   `poolSize=1`）⇒ 比的是协议，不是连接数。\n");
        sb.append("4. **业务逻辑逐行对齐**（name 加前缀、value ×2、blob 原样回显）⇒ 业务成本相同。\n");
        sb.append("5. **★ 架构差异（不是 bug，是设计取舍）**：\n");
        sb.append("   - gRPC 侧：**IO 线程直调 handler**（edap gRPC 的已知缺陷）；\n");
        sb.append("   - eRPC 侧：**IO 线程只切帧投递，业务在自带 worker 池里跑**；\n");
        sb.append("   - gRPC 侧有 **HTTP/2 流控窗口**（连接级 8MB / 流级 1MB），eRPC **没有流控**；\n");
        sb.append("   - gRPC 帧头 **5 字节**，eRPC 帧头 **32 字节** ⇒ 小消息时 eRPC 的固定开销更大。\n");
        sb.append("   - eRPC 服务端业务线程池 = ").append(erpcWorkers > 0 ? erpcWorkers + "（命令行指定）" : "默认 max(4, 核数×2)")
            .append("。\n");
        if (erpcInline) {
            sb.append("   - ★ **诊断模式**：eRPC 服务端被切成「IO 线程直调 handler」（gRPC 的模型）——\n");
            sb.append("     只用于归因实验（量化线程切换成本），**不是本框架的推荐形态**。\n");
        }
        sb.append("6. **拉丁方轮换**：两臂轮流先跑，位置偏差被摊掉；但两个服务端跑在<b>同一个 JVM</b>里，\n");
        sb.append("   绝对值仍会受机器负载影响 ⇒ **只看相对关系，不要跨批次比绝对值**。\n");
        sb.append("7. 报告里的数字**没有做绝对阈值断言**，只作横向对比用。\n");
        return sb.toString();
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
}
