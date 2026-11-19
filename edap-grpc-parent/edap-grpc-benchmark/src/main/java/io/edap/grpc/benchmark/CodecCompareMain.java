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

import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.codec.ErpcCodecRegistry;
import io.edap.erpc.codec.ErpcWriter;
import io.edap.erpc.codec.fastprotobuf.FastProtobufCodec;
import io.edap.erpc.codec.protobuf.ProtobufErpcCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * <b>两套序列化框架的端到端对比</b>：edap 自研 protobuf 的标准版 vs FAST 变体，
 * 外加 edap-gRPC 与 grpc-java 两个参照臂。
 *
 * <p><b>★ 四臂的口径分层（读数前必看）</b>：</p>
 * <table border="1">
 *   <caption>臂</caption>
 *   <tr><th>臂</th><th>载荷 codec</th><th>与 eRPC-PROTOBUF 的关系</th></tr>
 *   <tr><td>{@code eRPC-PROTOBUF}</td><td>edap protobuf 标准版（wire=标准 protobuf）</td>
 *       <td>★ <b>同源对照组</b></td></tr>
 *   <tr><td>{@code eRPC-FAST}</td><td>edap protobuf FAST 变体（<b>另一种 wire</b>）</td>
 *       <td>★ <b>同源对照组</b> —— 同一个 {@code ProtoBufCodecRegister}、同一个 DTO，
 *           差在 writer/reader 实现 ⇒ 差距可以干净地归到序列化框架上</td></tr>
 *   <tr><td>{@code edap-gRPC}</td><td>edap protobuf 标准版</td>
 *       <td>编解码同源，但<b>协议/传输/线程模型</b>都不同</td></tr>
 *   <tr><td>{@code grpc-java}</td><td><b>protobuf-java</b>（不同源）</td>
 *       <td>★ <b>只作量级参照</b> —— 不同源 ⇒ 不参与「谁更快」的结论</td></tr>
 * </table>
 *
 * <p><b>★ 为什么 FAST 臂不需要改服务端</b>：帧头里带着 codec 字节，服务端按帧协商
 * （{@code ErpcServerSession} 用请求帧的 codec 回抄响应帧）⇒ 同一个 eRPC 服务端
 * 可以同时接标准版与 FAST 两个客户端，<b>服务端环境完全一致</b>，差异只来自 codec。</p>
 *
 * <p><b>★ 为什么 name 要分 ASCII / 中文两种</b>：字符串是这两个框架唯一有实质差异的字段 ——
 * 标准版一律 UTF-8（ASCII 走零搬移的 Latin1 快路径，中文必须转换），
 * FAST 按 String 的内部字节写（永不搬移）⇒ <b>优劣会随语言翻转</b>，只测一种语言的结论是错的。</p>
 *
 * <p><b>用法</b>（独立 JVM + {@code --add-opens}）：</p>
 * <pre>{@code
 *   java --add-opens java.base/java.lang=ALL-UNNAMED \
 *        --add-opens java.base/java.nio=ALL-UNNAMED \
 *        --add-opens java.base/java.util=ALL-UNNAMED \
 *        --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
 *        -cp <classpath> io.edap.grpc.benchmark.CodecCompareMain --out report.md
 * }</pre>
 */
public final class CodecCompareMain {

    /** 臂下标 —— 顺序固定（拉丁方轮转依赖它）。 */
    private static final int ARM_ERPC_STD = 0;
    private static final int ARM_ERPC_FAST = 1;
    private static final int ARM_EDAP_GRPC = 2;
    private static final int ARM_GRPC_JAVA = 3;

    /** 臂名（报告里用）。 */
    private static final String[] ARM_LABELS = {
        "eRPC-PROTOBUF", "eRPC-FAST", "edap-gRPC", "grpc-java"
    };

    /**
     * 一个场景（name 变体 × 负载 × 并发）的结果。
     */
    private static final class Scenario {
        int variant;
        int blobSize;
        int concurrency;
        /** armRounds[臂下标] = 该臂各轮的结果。 */
        final List<List<BenchResult>> armRounds = new ArrayList<>();
        /** 各臂的线上载荷字节数（不含帧头）。 */
        final long[] payloadBytes = new long[ARM_LABELS.length];

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
        int rounds = 4;
        String out = "target/erpc-codec-compare-report.md";
        int[] blobSizes = BenchConstants.BLOB_SIZES;
        int[] concurrencies = BenchConstants.CONCURRENCIES;
        int[] variants = {0, 1};

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--quick":
                    warmupMillis = 300;
                    measureMillis = 800;
                    break;
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
                case "--names":
                    variants = parseVariants(args[++i]);
                    break;
                case "--out":
                    out = args[++i];
                    break;
                default:
                    System.out.println("未知参数: " + arg + "（忽略）");
                    break;
            }
        }

        System.out.println("=== eRPC 两套序列化框架 vs 参照臂 —— 端到端 unary 对比 ===");
        System.out.println("预热 " + warmupMillis + "ms / 测量 " + measureMillis + "ms / "
            + ARM_LABELS.length + " 个臂 × " + rounds + " 轮（拉丁方轮换）");

        // ★ FAST 不是内置 codec ⇒ 必须先注册，服务端才认得帧头里的 codec=2
        ErpcCodecRegistry.instance().register(new FastProtobufCodec());

        ErpcCodec stdCodec = new ProtobufErpcCodec();
        ErpcCodec fastCodec = new FastProtobufCodec();

        int grpcPort = EdapBenchServer.freePort();
        int googlePort = ErpcBenchServer.freePort();
        int erpcPort = ErpcBenchServer.freePort();

        List<Scenario> scenarios = new ArrayList<>();

        try (EdapBenchServer grpcServer = new EdapBenchServer(grpcPort);
             GoogleBenchServer googleServer = new GoogleBenchServer(googlePort);
             ErpcBenchServer erpcServer = new ErpcBenchServer(erpcPort)) {

            grpcServer.start();
            googleServer.start();
            erpcServer.start();
            System.out.println("edap-gRPC server: " + grpcPort + " / grpc-java server: " + googlePort
                + " / eRPC server: " + erpcPort);

            try (EdapBenchClient grpcClient = new EdapBenchClient("127.0.0.1", grpcPort);
                 GoogleBenchClient googleClient = new GoogleBenchClient("127.0.0.1", googlePort);
                 ErpcBenchClient erpcStdClient = new ErpcBenchClient("127.0.0.1", erpcPort, stdCodec);
                 ErpcBenchClient erpcFastClient = new ErpcBenchClient("127.0.0.1", erpcPort, fastCodec)) {

                // 自检 —— 四个臂都要能正确返回，否则测出来的数字没有意义
                checkArms(grpcClient, googleClient, erpcStdClient, erpcFastClient);

                for (int variant : variants) {
                    final String name = BenchConstants.requestName(variant);
                    for (int blobSize : blobSizes) {
                        for (int concurrency : concurrencies) {
                            Scenario scenario = new Scenario();
                            scenario.variant = variant;
                            scenario.blobSize = blobSize;
                            scenario.concurrency = concurrency;
                            for (int i = 0; i < ARM_LABELS.length; i++) {
                                scenario.armRounds.add(new ArrayList<>());
                            }
                            scenario.payloadBytes[ARM_ERPC_STD] = payloadSize(stdCodec, name, blobSize);
                            scenario.payloadBytes[ARM_ERPC_FAST] = payloadSize(fastCodec, name, blobSize);
                            scenario.payloadBytes[ARM_EDAP_GRPC] = scenario.payloadBytes[ARM_ERPC_STD];
                            scenario.payloadBytes[ARM_GRPC_JAVA] = googlePayloadSize(name, blobSize);

                            List<BenchArm> arms = armsFor(variant, blobSize,
                                grpcClient, googleClient, erpcStdClient, erpcFastClient);

                            System.out.print("  场景 " + BenchConstants.requestNameLabel(variant)
                                + " blob=" + blobSize + " conc=" + concurrency + " ... ");
                            long t0 = System.nanoTime();

                            // ★ 拉丁方轮换：第 r 轮里第 k 个位置跑第 (r+k)%N 个臂 ——
                            //   每轮每臂各跑一次，臂的先后顺序逐轮轮转，
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
                            System.out.printf("STD=%.0f FAST=%.0f edapGRPC=%.0f grpcJava=%.0f req/s"
                                + "  (FAST/STD=%.2fx, %.1fs)%n",
                                scenario.rps(ARM_ERPC_STD), scenario.rps(ARM_ERPC_FAST),
                                scenario.rps(ARM_EDAP_GRPC), scenario.rps(ARM_GRPC_JAVA),
                                ratio(scenario.rps(ARM_ERPC_STD), scenario.rps(ARM_ERPC_FAST)),
                                (System.nanoTime() - t0) / 1e9);
                            for (int i = 0; i < ARM_LABELS.length; i++) {
                                if (scenario.errors(i) > 0) {
                                    System.out.println("    ⚠ " + ARM_LABELS[i] + " 有失败调用: "
                                        + scenario.rounds(i).get(0).firstError);
                                }
                            }
                        }
                    }
                }
            }
        }

        String report = render(scenarios, warmupMillis, measureMillis, rounds, variants);
        System.out.println();
        System.out.println(report);

        Path path = Paths.get(out);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.write(path, report.getBytes(StandardCharsets.UTF_8));
        System.out.println("报告已写入: " + path.toAbsolutePath());

        // ── 出图（PNG 与报告同目录 ⇒ md 里用相对路径引用） ────────────────
        Path dir = path.getParent() == null ? Paths.get(".") : path.getParent();
        for (int variant : variants) {
            List<BenchChart.Group> groups = chartGroups(scenarios, variant);
            if (groups.isEmpty()) {
                continue;
            }
            String label = BenchConstants.requestNameLabel(variant);
            Path png = dir.resolve("erpc-codec-compare-end2end-" + label + ".png");
            BenchChart.png(groups, "End-to-end unary throughput (" + label + " name)",
                "requests per second", png);
            System.out.println("图已写入: " + png.toAbsolutePath());
        }
    }

    // =====================================================================
    // 臂
    // =====================================================================

    /**
     * 造出某个 name 变体下的 4 个臂。
     */
    private static List<BenchArm> armsFor(int variant, int blobSize,
                                          EdapBenchClient grpcClient,
                                          GoogleBenchClient googleClient,
                                          ErpcBenchClient erpcStdClient,
                                          ErpcBenchClient erpcFastClient) {
        final String name = BenchConstants.requestName(variant);
        return List.of(
            new BenchArm(ARM_LABELS[ARM_ERPC_STD], "erpc", "erpc", erpcStdClient,
                blob -> {
                    Payload request = BenchPayloads.edap(blob, name);
                    return () -> erpcStdClient.unary(request).getValue();
                }),
            new BenchArm(ARM_LABELS[ARM_ERPC_FAST], "erpc", "erpc", erpcFastClient,
                blob -> {
                    Payload request = BenchPayloads.edap(blob, name);
                    return () -> erpcFastClient.unary(request).getValue();
                }),
            new BenchArm(ARM_LABELS[ARM_EDAP_GRPC], "edap", "edap", grpcClient,
                blob -> {
                    Payload request = BenchPayloads.edap(blob, name);
                    return () -> grpcClient.unary(request).getValue();
                }),
            new BenchArm(ARM_LABELS[ARM_GRPC_JAVA], "google", "google", googleClient,
                blob -> {
                    Bench.Payload request = BenchPayloads.google(blob, name);
                    return () -> googleClient.unary(request).getValue();
                }));
    }

    // =====================================================================
    // 自检
    // =====================================================================

    /**
     * 自检 —— 四个臂各调一次（两种 name 变体都验），校验 name / value / blob。
     */
    private static void checkArms(EdapBenchClient grpcClient, GoogleBenchClient googleClient,
                                  ErpcBenchClient erpcStdClient, ErpcBenchClient erpcFastClient) {
        for (int variant = 0; variant < BenchConstants.REQUEST_NAME_VARIANTS.length; variant++) {
            String name = BenchConstants.requestName(variant);
            String expectedName = BenchConstants.NAME_PREFIX + name;
            int expectedValue = BenchConstants.REQUEST_VALUE * 2;
            final int blobSize = 64;

            Payload grpcResp = grpcClient.unary(BenchPayloads.edap(blobSize, name));
            expect(expectedName.equals(grpcResp.getName())
                    && grpcResp.getValue() == expectedValue
                    && blobLength(grpcResp.getBlob()) == blobSize,
                "edap-gRPC 臂自检不过: name=" + grpcResp.getName() + " value=" + grpcResp.getValue());

            Bench.Payload googleResp = googleClient.unary(BenchPayloads.google(blobSize, name));
            expect(expectedName.equals(googleResp.getName())
                    && googleResp.getValue() == expectedValue
                    && googleResp.getBlob().size() == blobSize,
                "grpc-java 臂自检不过: name=" + googleResp.getName() + " value=" + googleResp.getValue());

            Payload stdResp = erpcStdClient.unary(BenchPayloads.edap(blobSize, name));
            expect(expectedName.equals(stdResp.getName())
                    && stdResp.getValue() == expectedValue
                    && blobLength(stdResp.getBlob()) == blobSize,
                "eRPC-PROTOBUF 臂自检不过: name=" + stdResp.getName() + " value=" + stdResp.getValue());

            Payload fastResp = erpcFastClient.unary(BenchPayloads.edap(blobSize, name));
            expect(expectedName.equals(fastResp.getName())
                    && fastResp.getValue() == expectedValue
                    && blobLength(fastResp.getBlob()) == blobSize,
                "eRPC-FAST 臂自检不过: name=" + fastResp.getName() + " value=" + fastResp.getValue());
        }
        System.out.println("自检通过（四个臂 × 两种 name 变体都返回正确结果）");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /** ★ edap 的解码器对「零长度的 bytes 字段」给 null ⇒ 空与 null 都要当成「长度 0」。 */
    private static int blobLength(byte[] blob) {
        return blob == null ? 0 : blob.length;
    }

    // =====================================================================
    // 线上字节数
    // =====================================================================

    private static long payloadSize(ErpcCodec codec, String name, int blobSize) {
        ErpcWriter writer = codec.encode(BenchPayloads.edap(blobSize, name));
        return writer == null ? 0 : writer.size();
    }

    private static long googlePayloadSize(String name, int blobSize) {
        return BenchPayloads.google(blobSize, name).toByteArray().length;
    }

    private static double ratio(double a, double b) {
        return a == 0 ? 0 : b / a;
    }

    // =====================================================================
    // 柱状图数据
    // =====================================================================

    private static List<BenchChart.Group> chartGroups(List<Scenario> scenarios, int variant) {
        List<BenchChart.Group> groups = new ArrayList<>();
        for (Scenario s : scenarios) {
            if (s.variant != variant) {
                continue;
            }
            double[] values = new double[ARM_LABELS.length];
            for (int i = 0; i < ARM_LABELS.length; i++) {
                values[i] = s.rps(i);
            }
            groups.add(new BenchChart.Group(
                BenchConstants.requestNameLabel(variant) + "-" + blobLabel(s.blobSize)
                    + "-c" + s.concurrency,
                ARM_LABELS.clone(), values));
        }
        return groups;
    }

    // =====================================================================
    // 报告
    // =====================================================================

    private static String render(List<Scenario> scenarios, long warmupMillis, long measureMillis,
                                 int rounds, int[] variants) {
        StringBuilder sb = new StringBuilder();
        sb.append("# eRPC 两套序列化框架（PROTOBUF vs FAST）端到端性能对比\n\n");
        sb.append("生成时间: ").append(new java.util.Date()).append("\n\n");
        sb.append("运行环境: ").append(System.getProperty("os.name")).append(' ')
            .append(System.getProperty("os.version")).append(" / ")
            .append(System.getProperty("os.arch")).append(" / JDK ")
            .append(System.getProperty("java.version")).append(" / ")
            .append(Runtime.getRuntime().availableProcessors()).append(" 核\n\n");
        sb.append("测量口径: 预热 ").append(warmupMillis).append("ms + 测量窗口 ")
            .append(measureMillis).append("ms（时间制）；").append(ARM_LABELS.length)
            .append(" 个臂 × ").append(rounds)
            .append(" 轮，**拉丁方轮换**（臂的先后顺序逐轮轮转）；N 个 worker 线程共享一条连接\n\n");

        // ---- 口径分层（放最前面：读数前必看） ----
        sb.append("## 〇、四个臂的口径分层（★ 读数前必看）\n\n");
        sb.append("| 臂 | 载荷 codec | 定位 |\n|---|---|---|\n");
        sb.append("| `eRPC-PROTOBUF` | edap protobuf **标准版**（标准 wire） | ★ **同源对照组** |\n");
        sb.append("| `eRPC-FAST` | edap protobuf **FAST 变体**（另一种 wire） | ★ **同源对照组** —— "
            + "同一个 `ProtoBufCodecRegister`、同一个 DTO，差在 writer/reader 实现 |\n");
        sb.append("| `edap-gRPC` | edap protobuf 标准版 | 编解码同源，但协议/传输/线程模型都不同 |\n");
        sb.append("| `grpc-java` | **protobuf-java**（不同源） | ★ **只作量级参照** —— "
            + "不同源，不参与「谁更快」的结论 |\n\n");
        sb.append("> ★ **只有 `eRPC-PROTOBUF` ↔ `eRPC-FAST` 这一对是「同源可比」的**："
            + "同一个 `ProtoBufCodecRegister`、同一套生成字节码骨架、同一个 DTO，"
            + "差别只在 **writer/reader 实现**（字符串编码方式 + 嵌套消息分帧）\n");
        sb.append("> ⇒ 它们之间的差距可以**干净地归到序列化框架上**。\n");
        sb.append("> `grpc-java` 用的是 protobuf-java，**不同源**，只能当量级参照。\n\n");
        sb.append("> ★ **wire 不互通**：FAST 不是标准 protobuf（字符串 tag wireType=7、"
            + "嵌套消息用 GROUP 分帧）⇒ 两条 eRPC 链路各自自洽，**不能交叉**。\n");
        sb.append("> 服务端**一行没改**：帧头带 codec 字节，服务端按帧协商"
            + "（`ErpcServerSession` 用请求帧的 codec 回抄响应帧）⇒ 两个 eRPC 客户端共用一个服务端。\n\n");

        // ---- 一、端到端吞吐 ----
        sb.append("## 一、端到端吞吐（req/s，每臂 ").append(rounds).append(" 轮中位数）\n\n");
        sb.append("| name | 负载 | 并发 | eRPC-PROTOBUF | eRPC-FAST | FAST/STD | edap-gRPC | grpc-java |"
            + " 线上字节 STD/FAST/gRPC-java |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (Scenario s : scenarios) {
            double std = s.rps(ARM_ERPC_STD);
            double fast = s.rps(ARM_ERPC_FAST);
            sb.append(String.format("| %s | %s | %d | %.0f | %.0f | **%.2fx** | %.0f | %.0f | %d / %d / %d |%n",
                BenchConstants.requestNameLabel(s.variant), blobLabel(s.blobSize), s.concurrency,
                std, fast, ratio(std, fast),
                s.rps(ARM_EDAP_GRPC), s.rps(ARM_GRPC_JAVA),
                s.payloadBytes[ARM_ERPC_STD], s.payloadBytes[ARM_ERPC_FAST],
                s.payloadBytes[ARM_GRPC_JAVA]));
        }
        sb.append("\n> ★ **线上字节** = 载荷本身（不含帧头）。FAST 与标准版的体积差有两个分支\n");
        sb.append("> （FAST 按 String 的内部 coder 一刀切）：\n");
        sb.append("> - **整串是 Latin1**（每个字符 < 256）⇒ 内部 1 字节/字：`fast − std = 1 − H`\n");
        sb.append(">   （H = 0x80..0xFF 的字符数）；**纯 ASCII 时就是 +1**（FAST 多一个 charset 标记字节）；\n");
        sb.append("> - **含 ≥ U+0100 的字符** ⇒ 内部是 UTF-16：`fast − std = (A − B) + 1`\n");
        sb.append(">   （A=ASCII 字数，B=BMP 非 ASCII 字数）。\n");
        sb.append(">\n");
        sb.append("> ⇒ ASCII 变体 FAST 大 1 字节；中文变体 FAST 小 7 字节（8 汉字：UTF-16 16 字节 vs UTF-8 24 字节）。\n");
        sb.append("> **这不是 bug，是两种格式的设计差异**（FAST 用「体积换 CPU」：解码永不搬移）。\n\n");

        // ---- 二、延迟 ----
        sb.append("## 二、延迟（微秒，每臂 ").append(rounds).append(" 轮中位数）\n\n");
        sb.append("| name | 负载 | 并发 | STD p50 | FAST p50 | STD p99 | FAST p99 | edap-gRPC p99 | grpc-java p99 |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|\n");
        for (Scenario s : scenarios) {
            sb.append(String.format("| %s | %s | %d | %.0f | %.0f | %.0f | %.0f | %.0f | %.0f |%n",
                BenchConstants.requestNameLabel(s.variant), blobLabel(s.blobSize), s.concurrency,
                s.p50(ARM_ERPC_STD), s.p50(ARM_ERPC_FAST),
                s.p99(ARM_ERPC_STD), s.p99(ARM_ERPC_FAST),
                s.p99(ARM_EDAP_GRPC), s.p99(ARM_GRPC_JAVA)));
        }

        // ---- 三、柱状图 ----
        sb.append("\n## 三、柱状图\n\n");
        for (int variant : variants) {
            String label = BenchConstants.requestNameLabel(variant);
            sb.append("### name 变体 = ").append(label).append("\n\n");
            sb.append("![end-to-end ").append(label).append("](erpc-codec-compare-end2end-")
                .append(label).append(".png)\n\n");
            List<BenchChart.Group> groups = chartGroups(scenarios, variant);
            sb.append("```text\n");
            sb.append(BenchChart.ascii(groups, "%.0f"));
            sb.append("```\n\n");
        }

        // ---- 四、原始轮次 ----
        sb.append("## 四、原始轮次（每臂每场景 ").append(rounds).append(" 轮）\n\n");
        sb.append("| name | 负载 | 并发 | 臂 | 轮次 req/s | 轮次 p50(us) | 轮次 p99(us) | 错误数 |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        for (Scenario s : scenarios) {
            for (int arm = 0; arm < ARM_LABELS.length; arm++) {
                List<BenchResult> rs = s.rounds(arm);
                for (int r = 0; r < rs.size(); r++) {
                    BenchResult result = rs.get(r);
                    sb.append(String.format("| %s | %s | %d | %s | %.0f | %.0f | %.0f | %d |%n",
                        BenchConstants.requestNameLabel(s.variant), blobLabel(s.blobSize),
                        s.concurrency, ARM_LABELS[arm],
                        result.rps(), result.p50, result.p99, result.errors));
                }
            }
        }

        // ---- 五、错误数 ----
        sb.append("\n## 五、错误数（必须全为 0）\n\n");
        long totalErrors = 0;
        for (Scenario s : scenarios) {
            for (int arm = 0; arm < ARM_LABELS.length; arm++) {
                totalErrors += s.errors(arm);
            }
        }
        sb.append("全部场景、全部臂的失败调用总数 = **").append(totalErrors).append("**\n");

        // ---- 六、口径 ----
        sb.append("\n## 六、口径与已知差异（读数前必看）\n\n");
        sb.append("1. **★ 只有 `eRPC-PROTOBUF` ↔ `eRPC-FAST` 是同源可比对**；`grpc-java` 不同源"
            + "（protobuf-java）⇒ 只作量级参照，**不参与「谁更快」的结论**。\n");
        sb.append("2. **wire 不互通**：FAST 与标准版线上字节不同 ⇒ 端到端对比是「两条独立链路各自自洽」。\n");
        sb.append("3. **服务端零改动**：帧头带 codec 字节，服务端按帧协商 ⇒ 两个 eRPC 臂共用一个服务端，"
            + "服务端环境（worker 池、JVM、机器）完全一致。\n");
        sb.append("4. **连接数对齐**：每个臂一条连接，被 N 个 worker 共享（eRPC 的连接池 `poolSize=1`）。\n");
        sb.append("5. **业务逻辑逐行对齐**（name 加前缀、value ×2、blob 原样回显）⇒ 业务成本相同。\n");
        sb.append("6. **name 变体分 ASCII / 中文**：字符串是这两个框架唯一有实质差异的字段 ⇒ "
            + "优劣会随语言翻转，两种都要测。\n");
        sb.append("7. **拉丁方轮换**：臂的先后顺序逐轮轮转；但三个服务端跑在<b>同一个 JVM</b>里，"
            + "绝对值仍受机器负载影响 ⇒ **只看相对关系，不要跨批次比绝对值**。\n");
        sb.append("8. **编解码 micro 档**另跑（一个候选一个 JVM），见 micro 报告。\n");
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

    /**
     * 解析 name 变体列表（{@code ascii} / {@code cn}，也接受数字下标）。
     */
    private static int[] parseVariants(String csv) {
        String[] parts = csv.split(",");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if ("ascii".equalsIgnoreCase(p)) {
                values[i] = 0;
            } else if ("cn".equalsIgnoreCase(p) || "zh".equalsIgnoreCase(p)) {
                values[i] = 1;
            } else {
                values[i] = Integer.parseInt(p);
            }
        }
        return values;
    }
}
