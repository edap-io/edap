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
import io.edap.grpc.benchmark.thrift.ThriftPayload;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * ★ 归因探针：**eRPC 在单并发下比 Thrift 慢，差在「线程模型」还是「协议栈」？**
 *
 * <p><b>三臂，同一批、同一 JVM、拉丁方轮换</b>：</p>
 * <table border="1">
 *   <caption>三臂</caption>
 *   <tr><th>臂</th><th>客户端/服务端</th><th>服务端线程模型</th><th>序列化</th></tr>
 *   <tr><td>A</td><td>Thrift（tpool）</td>
 *       <td><b>阻塞 socket + 每连接一个线程</b>：线程阻塞在 read 上，<b>读满就地处理</b></td>
 *       <td>thrift binary</td></tr>
 *   <tr><td>B</td><td>eRPC（派发型）</td>
 *       <td>NIO IO 线程切帧 → <b>投递 worker 池</b> → worker 处理</td>
 *       <td>protobuf</td></tr>
 *   <tr><td>C</td><td>eRPC（inline 型）</td>
 *       <td>NIO IO 线程切帧 → <b>IO 线程就地调用业务</b>（去掉线程切换）</td>
 *       <td>protobuf</td></tr>
 * </table>
 *
 * <p><b>怎么读</b>：</p>
 * <ul>
 *   <li><b>A vs B</b> = 「eRPC 比 Thrift 慢多少」（用户看到的那条差距）；</li>
 *   <li><b>C vs B</b> = <b>线程切换这一项值多少</b>（同框架同序列化，只换 dispatch 方式）；</li>
 *   <li><b>C vs A</b> = 去掉线程切换之后 eRPC 还差多少 ⇒ <b>剩下的就是协议栈/框架本身的固定成本</b>。</li>
 * </ul>
 *
 * <p>★ 臂 C 的服务端是<b>另一个进程内的 ErpcServer 实例</b>（独立端口），
 * 所以三臂互不干扰；但 B 与 C 跑的是<b>同一套</b> eRPC 代码，只差 {@code inlineDispatch} 一个开关。</p>
 *
 * <p>★ 编解码差异（thrift binary vs protobuf）在 A vs C 之间仍然存在，
 * 所以本探针只能给<b>上界</b>（「差在协议栈」的量），要精确切分得再叠加 micro 数据。</p>
 */
public final class ProbeErpcDispatchMain {

    /** 臂下标。 */
    private static final int ARM_THRIFT = 0;
    private static final int ARM_ERPC_DISPATCH = 1;
    private static final int ARM_ERPC_INLINE = 2;

    private static final String[] ARM_LABELS = {"Thrift(tpool)", "eRPC(dispatch)", "eRPC(inline)"};

    /** 一个场景的结果。 */
    private static final class Scenario {
        int blobSize;
        int concurrency;
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
        long measureMillis = 2000;
        int rounds = 6;
        String out = "target/probe-erpc-dispatch.md";
        int[] blobSizes = BenchConstants.BLOB_SIZES;
        int[] concurrencies = new int[] {1};

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--warmup":
                    warmupMillis = Long.parseLong(args[++i]);
                    break;
                case "--measure":
                    measureMillis = Long.parseLong(args[++i]);
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
                    System.out.println("未知参数: " + args[i] + "（忽略）");
                    break;
            }
        }

        System.out.println("=== 归因探针：eRPC 单并发为什么比 Thrift 慢 ===");
        System.out.println("预热 " + warmupMillis + "ms / 测量 " + measureMillis + "ms / "
            + "3 个臂 × " + rounds + " 轮（拉丁方轮换）");

        int thriftPort = ThriftBenchServer.freePort();
        int erpcDispatchPort = ErpcBenchServer.freePort();
        int erpcInlinePort = ErpcBenchServer.freePort();

        List<Scenario> scenarios = new ArrayList<>();

        try (ThriftBenchServer thriftServer = new ThriftBenchServer(thriftPort,
                    ThriftBenchServer.MODE_TPOOL, ThriftBenchServer.PROTOCOL_BINARY, 0);
             ErpcBenchServer erpcDispatchServer = new ErpcBenchServer(erpcDispatchPort, 0, false);
             ErpcBenchServer erpcInlineServer = new ErpcBenchServer(erpcInlinePort, 0, true)) {

            thriftServer.start();
            erpcDispatchServer.start();
            erpcInlineServer.start();
            System.out.println("Thrift: " + thriftPort + " / eRPC(dispatch): " + erpcDispatchPort
                + " / eRPC(inline): " + erpcInlinePort);

            try (ThriftBenchClient thriftClient = new ThriftBenchClient("127.0.0.1", thriftPort,
                        ThriftBenchServer.MODE_TPOOL, ThriftBenchServer.PROTOCOL_BINARY, 64);
                 ErpcBenchClient dispatchClient = new ErpcBenchClient("127.0.0.1", erpcDispatchPort);
                 ErpcBenchClient inlineClient = new ErpcBenchClient("127.0.0.1", erpcInlinePort)) {

                List<BenchArm> arms = List.of(
                    new BenchArm("Thrift(tpool)", "thrift", "thrift", thriftClient,
                        blob -> {
                            ThriftPayload request = thriftPayload(blob);
                            return () -> thriftClient.unary(request).getValue();
                        }),
                    new BenchArm("eRPC(dispatch)", "erpc", "erpc", dispatchClient,
                        blob -> {
                            Payload request = BenchPayloads.edap(blob);
                            return () -> dispatchClient.unary(request).getValue();
                        }),
                    new BenchArm("eRPC(inline)", "erpc", "erpc", inlineClient,
                        blob -> {
                            Payload request = BenchPayloads.edap(blob);
                            return () -> inlineClient.unary(request).getValue();
                        }));

                checkArms(thriftClient, dispatchClient, inlineClient);

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

                        // ★ 拉丁方轮换：第 r 轮里第 k 个位置跑第 (r+k)%3 个臂
                        for (int round = 0; round < rounds; round++) {
                            for (int k = 0; k < arms.size(); k++) {
                                int armIndex = (round + k) % arms.size();
                                BenchArm arm = arms.get(armIndex);
                                scenario.armRounds.get(armIndex).add(BenchRunner.run(arm.label, blobSize,
                                    concurrency, warmupMillis, measureMillis, arm.op(blobSize)));
                            }
                        }

                        scenarios.add(scenario);
                        System.out.printf("Thrift=%.0f  eRPC(dispatch)=%.0f  eRPC(inline)=%.0f req/s  (%.1fs)%n",
                            scenario.rps(ARM_THRIFT), scenario.rps(ARM_ERPC_DISPATCH),
                            scenario.rps(ARM_ERPC_INLINE), (System.nanoTime() - t0) / 1e9);
                    }
                }
            }
        }

        String report = render(scenarios, warmupMillis, measureMillis, rounds);
        System.out.println();
        System.out.println(report);

        Path path = Paths.get(out);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.write(path, report.getBytes(StandardCharsets.UTF_8));
        System.out.println("报告已写入: " + path.toAbsolutePath());
    }

    private static ThriftPayload thriftPayload(int blobSize) {
        Payload payload = BenchPayloads.edap(blobSize);
        return new ThriftPayload(payload.getName(), payload.getValue(), payload.getBlob());
    }

    private static void checkArms(ThriftBenchClient thriftClient, ErpcBenchClient dispatchClient,
                                  ErpcBenchClient inlineClient) throws org.apache.thrift.TException {
        final int blobSize = 64;
        String expectedName = BenchConstants.NAME_PREFIX + BenchConstants.REQUEST_NAME;
        int expectedValue = BenchConstants.REQUEST_VALUE * 2;

        ThriftPayload t = thriftClient.unary(thriftPayload(blobSize));
        expect(expectedName.equals(t.getName()) && t.getValue() == expectedValue,
            "Thrift 臂自检不过");
        Payload d = dispatchClient.unary(BenchPayloads.edap(blobSize));
        expect(expectedName.equals(d.getName()) && d.getValue() == expectedValue,
            "eRPC(dispatch) 臂自检不过");
        Payload i = inlineClient.unary(BenchPayloads.edap(blobSize));
        expect(expectedName.equals(i.getName()) && i.getValue() == expectedValue,
            "eRPC(inline) 臂自检不过");
        System.out.println("自检通过");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static String render(List<Scenario> scenarios, long warmupMillis, long measureMillis, int rounds) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 归因探针：eRPC 单并发为什么比 Thrift 慢\n\n");
        sb.append("生成时间: ").append(new java.util.Date()).append("\n\n");
        sb.append("运行环境: ").append(System.getProperty("os.name")).append(' ')
            .append(System.getProperty("os.version")).append(" / ")
            .append(System.getProperty("os.arch")).append(" / JDK ")
            .append(System.getProperty("java.version")).append(" / ")
            .append(Runtime.getRuntime().availableProcessors()).append(" 核\n\n");
        sb.append("测量口径: 预热 ").append(warmupMillis).append("ms + 测量窗口 ")
            .append(measureMillis).append("ms；3 个臂 × ").append(rounds)
            .append(" 轮，**拉丁方轮换**；延迟单位微秒\n\n");
        sb.append("三臂（同一批次、同一 JVM）：\n\n");
        sb.append("| 臂 | 服务端线程模型 | 序列化 |\n|---|---|---|\n");
        sb.append("| Thrift(tpool) | 阻塞 socket，**每连接一个线程，读满就地处理** | thrift binary |\n");
        sb.append("| eRPC(dispatch) | NIO IO 线程切帧 → **投递 worker 池** | protobuf |\n");
        sb.append("| eRPC(inline) | NIO IO 线程切帧 → **IO 线程就地调用业务** | protobuf |\n\n");

        sb.append("## 一、结果（p50 越小越好）\n\n");
        sb.append("| 负载 | 并发 | Thrift p50 | eRPC(dispatch) p50 | eRPC(inline) p50 | A→B 差 | B→C 差 | C vs A |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (Scenario s : scenarios) {
            double a = s.p50(ARM_THRIFT);
            double b = s.p50(ARM_ERPC_DISPATCH);
            double c = s.p50(ARM_ERPC_INLINE);
            sb.append(String.format("| %s | %d | %.0f | %.0f | %.0f | %+.0f us | %+.0f us | %+.1f%% |%n",
                blobLabel(s.blobSize), s.concurrency, a, b, c, b - a, c - b,
                a <= 0 ? 0 : (c - a) / a * 100));
        }

        sb.append("\n## 二、吞吐（req/s）\n\n");
        sb.append("| 负载 | 并发 | Thrift | eRPC(dispatch) | eRPC(inline) |\n|---|---|---|---|---|\n");
        for (Scenario s : scenarios) {
            sb.append(String.format("| %s | %d | %.0f | %.0f | %.0f |%n",
                blobLabel(s.blobSize), s.concurrency, s.rps(ARM_THRIFT),
                s.rps(ARM_ERPC_DISPATCH), s.rps(ARM_ERPC_INLINE)));
        }

        sb.append("\n## 三、原始轮次\n\n");
        sb.append("| 负载 | 并发 | 臂 | 轮次 req/s | 轮次 p50(us) | 轮次 p99(us) | 错误数 |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (Scenario s : scenarios) {
            for (int arm = 0; arm < ARM_LABELS.length; arm++) {
                for (BenchResult r : s.rounds(arm)) {
                    sb.append(String.format("| %s | %d | %s | %.0f | %.0f | %.0f | %d |%n",
                        blobLabel(s.blobSize), s.concurrency, ARM_LABELS[arm],
                        r.rps(), r.p50, r.p99, r.errors));
                }
            }
        }

        sb.append("\n## 四、怎么读这张表\n\n");
        sb.append("- **A→B 差** = eRPC(dispatch) 比 Thrift 慢多少 —— 用户看到的那条差距；\n");
        sb.append("- **B→C 差** = **线程切换这一项值多少**（同框架、同序列化，只换 dispatch 方式）；\n");
        sb.append("- **C vs A** = 去掉线程切换之后 eRPC 还比 Thrift 慢多少 "
            + "⇒ 剩下的部分是**协议栈/框架固定成本 + 编解码差异**（thrift binary vs protobuf）。\n");
        sb.append("★ 编解码差异（thrift binary vs protobuf）在本探针里**没有**被隔离出来 —— "
            + "要看它得叠加 micro 数据。\n");
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
