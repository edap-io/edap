/*
 * Copyright 2026 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.grpc.benchmark;

import io.edap.grpc.benchmark.thrift.ThriftBenchClient;
import io.edap.grpc.benchmark.thrift.ThriftBenchServer;
import io.edap.grpc.benchmark.thrift.ThriftCodecCompare;
import io.edap.grpc.benchmark.thrift.ThriftPayload;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Thrift vs eRPC vs edap-gRPC vs grpc-java —— 四臂端到端 unary 性能对比。
 *
 * <p><b>四臂</b>：</p>
 * <table border="1">
 *   <caption>四个臂</caption>
 *   <tr><th>臂</th><th>客户端</th><th>服务端</th><th>序列化</th><th>连接模型</th></tr>
 *   <tr><td>Thrift</td><td>{@link ThriftBenchClient}</td><td>{@link ThriftBenchServer}</td>
 *       <td>thrift binary / compact</td><td><b>N 条连接，每条 1 个在途请求</b></td></tr>
 *   <tr><td>eRPC</td><td>{@link ErpcBenchClient}</td><td>{@link ErpcBenchServer}</td>
 *       <td>protobuf</td><td>1 条连接，N 路并发复用</td></tr>
 *   <tr><td>edap-gRPC</td><td>{@link EdapBenchClient}</td><td>{@link EdapBenchServer}</td>
 *       <td>protobuf</td><td>1 条连接，N 路并发复用</td></tr>
 *   <tr><td>grpc-java</td><td>{@link GoogleBenchClient}</td><td>{@link GoogleBenchServer}</td>
 *       <td>protobuf（protobuf-java）</td><td>1 条连接，N 路并发复用</td></tr>
 * </table>
 *
 * <p>★ <b>这个对比最大的口径差异：thrift 没有「一条连接上多路复用请求」这回事。</b>
 * Thrift 官方服务端（{@code TThreadPoolServer} / {@code TThreadedSelectorServer}）
 * <b>每条连接同一时刻只处理 1 个请求</b> ⇒ N 路并发在途请求必须用 N 条连接。
 * 其它三臂都是「1 条连接 + N 路并发复用」（HTTP/2 流 / eRPC 自定义帧头）。
 * <b>这是架构固有约束，不是本基准人为削弱 Thrift</b> —— 报告里必须写清楚。</p>
 *
 * <p><b>用法</b>（必须用独立 JVM + {@code --add-opens}，理由见 {@link BenchMain}）：</p>
 * <pre>{@code
 *   java --add-opens java.base/java.lang=ALL-UNNAMED \
 *        --add-opens java.base/java.nio=ALL-UNNAMED \
 *        --add-opens java.base/java.util=ALL-UNNAMED \
 *        --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
 *        -cp <classpath> io.edap.grpc.benchmark.ThriftVsErpcVsGrpcMain --out report.md
 * }</pre>
 */
public final class ThriftVsErpcVsGrpcMain {

    /** 臂下标。 */
    private static final int ARM_THRIFT = 0;
    private static final int ARM_ERPC = 1;
    private static final int ARM_EDAP_GRPC = 2;
    private static final int ARM_GRPC_JAVA = 3;

    /** 四个臂的名字（报告与日志里用）。 */
    private static final String[] ARM_LABELS = {"Thrift", "eRPC", "edap-gRPC", "grpc-java"};

    /**
     * 一个场景的结果（每个臂若干轮）。
     */
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
        String out = "target/thrift-vs-erpc-vs-grpc-report.md";
        int[] blobSizes = BenchConstants.BLOB_SIZES;
        int[] concurrencies = BenchConstants.CONCURRENCIES;
        String thriftServerMode = ThriftBenchServer.MODE_TPOOL;
        String thriftProtocol = ThriftBenchServer.PROTOCOL_BINARY;
        int thriftConnections = 64;
        int thriftWorkers = 0;
        boolean microOnly = false;

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
                case "--thrift-server":
                    thriftServerMode = args[++i];
                    break;
                case "--thrift-protocol":
                    thriftProtocol = args[++i];
                    break;
                case "--thrift-conns":
                    thriftConnections = Integer.parseInt(args[++i]);
                    break;
                case "--thrift-workers":
                    thriftWorkers = Integer.parseInt(args[++i]);
                    break;
                case "--micro-only":
                    // 只跑编解码 micro（不跑端到端、不起服务端）——
                    // 用来单独重生成第三节，或修正历史报告里净增列的口径
                    microOnly = true;
                    break;
                default:
                    System.out.println("未知参数: " + arg + "（忽略）");
                    break;
            }
        }

        System.out.println("=== Thrift vs eRPC vs edap-gRPC vs grpc-java —— 端到端 unary 对比 ===");
        System.out.println("预热 " + warmupMillis + "ms / 测量 " + measureMillis + "ms / "
            + "4 个臂 × " + rounds + " 轮（拉丁方轮换）");
        System.out.println("Thrift 服务端形态: " + thriftServerMode + " / 协议: " + thriftProtocol);

        if (microOnly) {
            // 只跑编解码 micro —— 不起任何服务端
            List<ThriftCodecCompare.CompareResult> codecResults = ThriftCodecCompare.run(blobSizes, codecMillis);
            String section = renderCodecSection(codecResults, thriftProtocol);
            System.out.println();
            System.out.println(section);
            Path microPath = Paths.get(out);
            if (microPath.getParent() != null) {
                Files.createDirectories(microPath.getParent());
            }
            Files.write(microPath, section.getBytes(StandardCharsets.UTF_8));
            System.out.println("micro 报告已写入: " + microPath.toAbsolutePath());
            return;
        }

        int thriftPort = ThriftBenchServer.freePort();
        int erpcPort = ErpcBenchServer.freePort();
        int edapGrpcPort = EdapBenchServer.freePort();
        int grpcJavaPort = EdapBenchServer.freePort();

        List<Scenario> scenarios = new ArrayList<>();
        List<ThriftCodecCompare.CompareResult> codecResults;
        int thriftConnectionsUsed = 0;

        try (ThriftBenchServer thriftServer = new ThriftBenchServer(thriftPort, thriftServerMode,
                    thriftProtocol, thriftWorkers);
             ErpcBenchServer erpcServer = new ErpcBenchServer(erpcPort);
             EdapBenchServer edapGrpcServer = new EdapBenchServer(edapGrpcPort);
             GoogleBenchServer grpcJavaServer = new GoogleBenchServer(grpcJavaPort)) {

            thriftServer.start();
            erpcServer.start();
            edapGrpcServer.start();
            grpcJavaServer.start();
            System.out.println("Thrift server: " + thriftPort + " / eRPC server: " + erpcPort
                + " / edap-gRPC server: " + edapGrpcPort + " / grpc-java server: " + grpcJavaPort);

            try (ThriftBenchClient thriftClient = new ThriftBenchClient("127.0.0.1", thriftPort,
                        thriftServerMode, thriftProtocol, thriftConnections);
                 ErpcBenchClient erpcClient = new ErpcBenchClient("127.0.0.1", erpcPort);
                 EdapBenchClient edapGrpcClient = new EdapBenchClient("127.0.0.1", edapGrpcPort);
                 GoogleBenchClient grpcJavaClient = new GoogleBenchClient("127.0.0.1", grpcJavaPort)) {

                List<BenchArm> arms = List.of(
                    new BenchArm("Thrift", "thrift", "thrift", thriftClient,
                        blob -> {
                            ThriftPayload request = thriftPayload(blob);
                            return () -> thriftClient.unary(request).getValue();
                        }),
                    new BenchArm("eRPC", "erpc", "erpc", erpcClient,
                        blob -> {
                            Payload request = BenchPayloads.edap(blob);
                            return () -> erpcClient.unary(request).getValue();
                        }),
                    new BenchArm("edap-gRPC", "edap-grpc", "edap-grpc", edapGrpcClient,
                        blob -> {
                            Payload request = BenchPayloads.edap(blob);
                            return () -> edapGrpcClient.unary(request).getValue();
                        }),
                    new BenchArm("grpc-java", "grpc-java", "grpc-java", grpcJavaClient,
                        blob -> {
                            Bench.Payload request = BenchPayloads.google(blob);
                            return () -> grpcJavaClient.unary(request).getValue();
                        }));

                // 自检 —— 四个臂都要能正确返回，否则测出来的数字没有意义
                checkArms(thriftClient, erpcClient, edapGrpcClient, grpcJavaClient);

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

                        // ★ 拉丁方轮换：第 r 轮里第 k 个位置跑第 (r+k)%4 个臂 ——
                        //   四个臂、rounds 是 4 的倍数时，每个臂在每个位置上各跑一次，
                        //   「先跑的那一方」这个位置偏差被完全摊掉。
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
                        System.out.printf("Thrift=%.0f  eRPC=%.0f  edap-gRPC=%.0f  grpc-java=%.0f req/s  (%.1fs)%n",
                            scenario.rps(ARM_THRIFT), scenario.rps(ARM_ERPC),
                            scenario.rps(ARM_EDAP_GRPC), scenario.rps(ARM_GRPC_JAVA),
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

                codecResults = ThriftCodecCompare.run(blobSizes, codecMillis);
                // Thrift 臂实际建了多少条连接（= 需要的并发连接数）—— 报告里要写清楚
                thriftConnectionsUsed = thriftClient.createdConnections();
            }
        }

        String report = render(scenarios, codecResults, warmupMillis, measureMillis, codecMillis, rounds,
            thriftServerMode, thriftProtocol, thriftConnections, thriftConnectionsUsed);
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
     * 造一个 thrift 侧的请求消息（与 protobuf 侧逐字段等价）。
     */
    private static ThriftPayload thriftPayload(int blobSize) {
        Payload payload = BenchPayloads.edap(blobSize);
        return new ThriftPayload(payload.getName(), payload.getValue(), payload.getBlob());
    }

    /**
     * 自检 —— 四个臂各调一次，校验 name / value / blob。
     */
    private static void checkArms(ThriftBenchClient thriftClient, ErpcBenchClient erpcClient,
                                  EdapBenchClient edapGrpcClient, GoogleBenchClient grpcJavaClient)
        throws org.apache.thrift.TException {
        final int blobSize = 64;
        String expectedName = BenchConstants.NAME_PREFIX + BenchConstants.REQUEST_NAME;
        int expectedValue = BenchConstants.REQUEST_VALUE * 2;

        ThriftPayload thriftResp = thriftClient.unary(thriftPayload(blobSize));
        expect(expectedName.equals(thriftResp.getName())
                && thriftResp.getValue() == expectedValue
                && thriftResp.getBlob() != null && thriftResp.getBlob().length == blobSize,
            "Thrift 臂自检不过: name=" + thriftResp.getName() + " value=" + thriftResp.getValue());

        Payload erpcResp = erpcClient.unary(BenchPayloads.edap(blobSize));
        expect(expectedName.equals(erpcResp.getName())
                && erpcResp.getValue() == expectedValue
                && erpcResp.getBlob() != null && erpcResp.getBlob().length == blobSize,
            "eRPC 臂自检不过: name=" + erpcResp.getName() + " value=" + erpcResp.getValue());

        Payload edapResp = edapGrpcClient.unary(BenchPayloads.edap(blobSize));
        expect(expectedName.equals(edapResp.getName())
                && edapResp.getValue() == expectedValue
                && edapResp.getBlob() != null && edapResp.getBlob().length == blobSize,
            "edap-gRPC 臂自检不过: name=" + edapResp.getName() + " value=" + edapResp.getValue());

        Bench.Payload googleResp = grpcJavaClient.unary(BenchPayloads.google(blobSize));
        expect(expectedName.equals(googleResp.getName())
                && googleResp.getValue() == expectedValue
                && googleResp.getBlob() != null && googleResp.getBlob().size() == blobSize,
            "grpc-java 臂自检不过: name=" + googleResp.getName() + " value=" + googleResp.getValue());

        System.out.println("自检通过（四个臂都返回正确结果）");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * 渲染 markdown 报告。
     */
    private static String render(List<Scenario> scenarios, List<ThriftCodecCompare.CompareResult> codecResults,
                                 long warmupMillis, long measureMillis, long codecMillis, int rounds,
                                 String thriftServerMode, String thriftProtocol, int thriftConnections,
                                 int thriftConnectionsUsed) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Thrift vs eRPC vs edap-gRPC vs grpc-java —— 端到端 unary 性能对比\n\n");
        sb.append("生成时间: ").append(new java.util.Date()).append("\n\n");
        sb.append("运行环境: ").append(System.getProperty("os.name")).append(' ')
            .append(System.getProperty("os.version")).append(" / ")
            .append(System.getProperty("os.arch")).append(" / JDK ")
            .append(System.getProperty("java.version")).append(" / ")
            .append(Runtime.getRuntime().availableProcessors()).append(" 核\n\n");
        sb.append("测量口径: 预热 ").append(warmupMillis).append("ms + 测量窗口 ")
            .append(measureMillis).append("ms（时间制）；4 个臂 × ").append(rounds)
            .append(" 轮，**拉丁方轮换**；N 个 worker 线程共享一个客户端；延迟单位微秒\n\n");
        sb.append("Thrift 服务端形态: **").append(thriftServerMode).append("** / 协议: **")
            .append(thriftProtocol).append("** / 连接池上限: ").append(thriftConnections)
            .append(" / 本次实际用到 **").append(thriftConnectionsUsed).append(" 条连接**"
            + "（= Thrift 实现 N 路并发所必需；其余三臂都是 1 条连接）\n\n");

        // ---- 一、端到端 ----
        sb.append("## 一、端到端 unary 性能\n\n");
        sb.append("| 负载 | 并发 | Thrift req/s | eRPC req/s | edap-gRPC req/s | grpc-java req/s | eRPC/Thrift | edap-gRPC/Thrift | grpc-java/Thrift | Thrift p50(us) | eRPC p50(us) | edap-gRPC p50(us) | grpc-java p50(us) |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (Scenario s : scenarios) {
            double thrift = s.rps(ARM_THRIFT);
            double erpc = s.rps(ARM_ERPC);
            double edap = s.rps(ARM_EDAP_GRPC);
            double google = s.rps(ARM_GRPC_JAVA);
            sb.append(String.format("| %s | %d | %.0f | %.0f | %.0f | %.0f | %.2fx | %.2fx | %.2fx | %.0f | %.0f | %.0f | %.0f |%n",
                blobLabel(s.blobSize), s.concurrency, thrift, erpc, edap, google,
                thrift == 0 ? 0 : erpc / thrift, thrift == 0 ? 0 : edap / thrift, thrift == 0 ? 0 : google / thrift,
                s.p50(ARM_THRIFT), s.p50(ARM_ERPC), s.p50(ARM_EDAP_GRPC), s.p50(ARM_GRPC_JAVA)));
        }

        // ---- 二、原始轮次 ----
        sb.append("\n## 二、原始轮次（每臂 ").append(rounds).append(" 轮）\n\n");
        sb.append("| 负载 | 并发 | 臂 | 轮次 req/s | 轮次 p50(us) | 轮次 p99(us) | 错误数 |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (Scenario s : scenarios) {
            for (int arm = 0; arm < ARM_LABELS.length; arm++) {
                List<BenchResult> rs = s.rounds(arm);
                for (int r = 0; r < rs.size(); r++) {
                    BenchResult result = rs.get(r);
                    sb.append(String.format("| %s | %d | %s | %.0f | %.0f | %.0f | %d |%n",
                        blobLabel(s.blobSize), s.concurrency, ARM_LABELS[arm],
                        result.rps(), result.p50, result.p99, result.errors));
                }
            }
        }

        // ---- 三、编解码 micro ----
        sb.append(renderCodecSection(codecResults, thriftProtocol));

        // ---- 四、口径 ----
        sb.append("\n## 四、口径与已知差异（读数前必看）\n\n");
        sb.append("1. **★ 最大的口径差异：thrift 没有「一条连接上多路复用请求」这回事。**\n");
        sb.append("   - Thrift 官方服务端（`TThreadPoolServer` / `TThreadedSelectorServer`）"
            + "**每条连接同一时刻只处理 1 个请求**：\n");
        sb.append("     `TThreadPoolServer.WorkerProcess.run()` 是 `while(true) processor.process(in, out);`（连接内串行）；\n");
        sb.append("     `AbstractNonblockingServer.FrameBuffer.read()` 帧读满后 `selectionKey_.interestOps(0)`"
            + "（摘掉读兴趣），响应写出后才重新挂上。\n");
        sb.append("   - ⇒ **N 路并发在途请求 ⇒ Thrift 侧必须用 N 条连接**（客户端连接池懒创建）；"
            + "其余三臂都是 **1 条连接 + N 路并发复用**。\n");
        sb.append("   - ⇒ **Thrift 的吞吐天然受「每条连接一次一个请求」的限制**，"
            + "这是架构固有约束，不是本基准人为削弱它。\n");
        sb.append("2. **业务逻辑逐行对齐**（name 加前缀、value ×2、blob 原样回显）⇒ 业务成本相同。\n");
        sb.append("3. **序列化体系不同**：Thrift 臂用 thrift 协议（binary/compact），"
            + "另外三臂用 protobuf ⇒ 端到端差距里**含编解码差异**（见第三节单独量化）。\n");
        sb.append("4. **线程模型不同**：\n");
        sb.append("   - Thrift（`tpool`）：**1 连接 = 1 线程**（线程池），连接内串行；\n");
        sb.append("   - Thrift（`tselector`）：NIO selector + worker 池，连接内仍是串行；\n");
        sb.append("   - eRPC：IO 线程只切帧投递，业务在自带 worker 池里跑；\n");
        sb.append("   - edap-gRPC / grpc-java：IO 线程直调 handler（grpc-java 是 `ServerCalls` 直接调）。\n");
        sb.append("5. **拉丁方轮换**：四个臂轮流先跑，位置偏差被摊掉（**rounds 最好是 4 的倍数**，"
            + "否则位置分配不完全均衡）；\n");
        sb.append("   四个服务端跑在**同一个 JVM**里，绝对值仍会受机器负载影响 ⇒ **只看相对关系，不要跨批次比绝对值**。\n");
        sb.append("6. 报告里的数字**没有做绝对阈值断言**，只作横向对比用。\n");
        return sb.toString();
    }

    /**
     * 渲染第三节（编解码 micro 对比）。
     *
     * <p>★ <b>净增分两列</b>（binary / compact）：本批 Thrift 臂用的是哪个协议，
     * 就该看哪一列 —— 早先只有一个「净增」列且恒按 binary 算，
     * 跑 compact 的批次会被算成 binary 的口径（已修）。</p>
     *
     * <p>★ <b>2026-11-15 修正：净增列改为「公平口径」</b>——
     * 原口径有两处偏差，把「编解码逻辑」和「内存拷贝」混在了一起：
     * <ol>
     *   <li>protobuf 编码用 {@code GrpcCodec.encode}（末尾 {@code writer.toByteArray()}）
     *       ⇒ 多付「1 分配 + 1 整块拷贝」，<b>而 eRPC/gRPC 真实路径都不付</b>
     *       （eRPC 借出线程本地缓冲；gRPC 帧头内联在同一块缓冲里）；</li>
     *   <li>thrift 解码用本基准<b>手写</b>的 DTO（blob 声明成 {@code byte[]}）
     *       ⇒ 多付「1 分配 + 1 整块拷贝」，<b>thrift 0.14 生成代码不会</b>。</li>
     * </ol>
     * ⇒ 修正后量到的才是「编解码逻辑」本身的差异。</p>
     */
    private static String renderCodecSection(List<ThriftCodecCompare.CompareResult> codecResults,
                                             String thriftProtocol) {
        boolean compact = ThriftBenchServer.PROTOCOL_COMPACT.equals(thriftProtocol);
        StringBuilder sb = new StringBuilder();
        sb.append("\n## 三、编解码 micro 对比（thrift vs protobuf）\n\n");
        sb.append("| 负载 | protobuf 字节 | thrift binary 字节 | thrift compact 字节 | protobuf 编码 ns | **protobuf 编码 ns（只 encode，不 toByteArray）** | thrift bin 编码 ns | thrift cmp 编码 ns | protobuf 解码 ns | thrift bin 解码 ns | thrift cmp 解码 ns | **thrift bin 解码 ns（0.14 形态）** | **thrift cmp 解码 ns（0.14 形态）** | **binary 净增 ns/边（公平口径）** | **compact 净增 ns/边（公平口径）** | binary 净增（原口径） | compact 净增（原口径） |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (ThriftCodecCompare.CompareResult r : codecResults) {
            sb.append(String.format("| %s | %d | %d | %d | %.0f | %.0f | %.0f | %.0f | %.0f | %.0f | %.0f | %.0f | %.0f | %+.0f | %+.0f | %+.0f | %+.0f |%n",
                blobLabel(r.blobSize), r.protobufBytes, r.thriftBinaryBytes, r.thriftCompactBytes,
                r.protobufEncodeNs, r.protobufEncodeNoCopyNs,
                r.thriftBinaryEncodeNs, r.thriftCompactEncodeNs,
                r.protobufDecodeNs, r.thriftBinaryDecodeNs, r.thriftCompactDecodeNs,
                r.thriftBinaryDecodeBbNs, r.thriftCompactDecodeBbNs,
                r.fairBinaryDeltaNs(), r.fairCompactDeltaNs(),
                r.rawBinaryDeltaNs(), r.rawCompactDeltaNs()));
        }
        sb.append("\n> ★ **本批 Thrift 臂用的是 **").append(compact ? "compact" : "binary")
            .append("** 协议 ⇒ 端到端里由编解码解释的部分看「")
            .append(compact ? "compact 净增（公平口径）" : "binary 净增（公平口径）").append("」那一列。\n");
        sb.append("> ★ **口径提示 1（2026-11-15 修正）**：两侧都按**各自的真实线上路径**量 —\n");
        sb.append("> · protobuf 编码取「**只 encode、不 toByteArray**」那一列：eRPC 的 `ProtobufErpcCodec.encode` "
            + "借出 `ProtoBufWriter` 的线程本地缓冲、gRPC 的 `GrpcFrameEncoder.encode` 把 5 字节帧头内联在同一块缓冲里\n");
        sb.append("> ⇒ **两者的载荷都只被拷一次**（拷进出站 FastBuf 那一次）；"
            + "`GrpcCodec.encode` 末尾那次 `writer.toByteArray()`（1 分配 + 1 整块拷贝）**在线上路径上并不存在**\n");
        sb.append("> · thrift 解码取「**0.14 形态**」那一列：thrift 0.14 的 `TProtocol.readBinary()` 返回 `ByteBuffer` "
            + "⇒ 生成代码里 binary 字段直接赋值；本基准手写的 DTO 多付了一次 `new byte[]` + `get()`\n");
        sb.append("> ⇒ **修正后量到的才是「编解码逻辑」本身的差异**（原口径把内存拷贝混进来了）。\n");
        sb.append("> ★ **口径提示 2（thrift 的真实成本在「写」的次数上）**：thrift 的 binary 协议"
            + "每个字段要写多次（字段头 1+2 字节、值各一次），\n");
        sb.append("> 而 thrift 官方传输栈（`TSocket → BufferedOutputStream`）的写方法是 **synchronized** "
            + "⇒ 每写一次都要过一次 monitor；\n");
        sb.append("> protobuf 侧则是往池化缓冲里写（无锁）⇒ 这是两套栈的**结构性差异**，不是测量误差。\n");
        sb.append("> ★ **怎么用这一节**：「净增」是**单边**（编或解）的净增 ns；"
            + "一次往返要编解码 4 次（客户端编 + 服务端解 + 服务端编 + 客户端解）\n");
        sb.append("> ⇒ 端到端里由编解码解释的部分 ≈ **净增 ×2**；把它和第一节同一负载的 p50 差对照，"
            + "剩下的差就是连接模型 / 线程模型带来的。\n");
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
