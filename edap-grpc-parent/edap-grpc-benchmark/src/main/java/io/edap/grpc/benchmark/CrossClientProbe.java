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

import io.edap.http.server.http2.Http2WriteStats;

import java.io.IOException;

/**
 * <b>换客户端探针</b> —— 同一个 edap 服务端，先后用 edap 客户端和 grpc-java 客户端打，
 * 把服务端侧的计数各自打一遍。
 *
 * <p>★ 目的：回答「G→E 明显慢于 E→E，差别到底出在客户端给了多少流控额度、
 * 服务端因此把响应拆成了几段」。</p>
 *
 * <p>★ 计数默认关闭，必须显式打开：</p>
 * <pre>{@code
 *   java -Dedap.http2.writeStats=true -Dedap.grpc.diag=true ... \
 *        io.edap.grpc.benchmark.CrossClientProbe --blob 65536 --conc 32 --measure 5000
 * }</pre>
 */
public final class CrossClientProbe {

    public static void main(String[] args) throws Exception {
        int[] blobSizes = {65536};
        int concurrency = 32;
        long warmupMillis = 2000;
        long measureMillis = 5000;
        boolean freshClient = false;
        boolean googleServer = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--blob":
                    blobSizes = parseInts(args[++i]);
                    break;
                case "--conc":
                    concurrency = Integer.parseInt(args[++i]);
                    break;
                case "--warmup":
                    warmupMillis = Long.parseLong(args[++i]);
                    break;
                case "--measure":
                    measureMillis = Long.parseLong(args[++i]);
                    break;
                case "--fresh-client":
                    // 每个阶段用一条新连接 —— 用来区分「连接级状态残留」与「全局状态」
                    freshClient = true;
                    break;
                case "--server":
                    // google = 打 grpc-java 服务端（对照组：它会不会同样退化？）
                    googleServer = "google".equals(args[++i]);
                    break;
                default:
                    System.out.println("未知参数: " + args[i]);
                    break;
            }
        }

        int port = EdapBenchServer.freePort();
        if (googleServer) {
            try (GoogleBenchServer server = new GoogleBenchServer(port)) {
                server.start();
                System.out.println("grpc-java server: " + port + "  conc=" + concurrency);
                runEdapClient(port, blobSizes, concurrency, warmupMillis, measureMillis, freshClient);
                runGoogleClient(port, blobSizes, concurrency, warmupMillis, measureMillis, freshClient);
            }
            return;
        }
        try (EdapBenchServer server = new EdapBenchServer(port)) {
            server.start();
            System.out.println("edap server: " + port + "  conc=" + concurrency);

            // ---- edap 客户端 ----
            runEdapClient(port, blobSizes, concurrency, warmupMillis, measureMillis, freshClient);

            // ---- grpc-java 客户端 ----
            runGoogleClient(port, blobSizes, concurrency, warmupMillis, measureMillis, freshClient);
        }
    }

    private static void runEdapClient(int port, int[] blobSizes, int concurrency,
                                      long warmupMillis, long measureMillis, boolean freshClient)
            throws Exception {
        if (freshClient) {
            for (int blobSize : blobSizes) {
                try (EdapBenchClient client = new EdapBenchClient("127.0.0.1", port)) {
                    Payload request = BenchPayloads.edap(blobSize);
                    BenchRunner.run("warmup-edap", blobSize, concurrency, warmupMillis, 100,
                            () -> client.unary(request).getValue());
                    Http2WriteStats.reset();
                    BenchResult r = BenchRunner.run("edap", blobSize, concurrency, 0, measureMillis,
                            () -> client.unary(request).getValue());
                    report("edap 客户端 → edap 服务端 / blob=" + blobSize, r, measureMillis);
                }
            }
            return;
        }
        try (EdapBenchClient client = new EdapBenchClient("127.0.0.1", port)) {
            for (int blobSize : blobSizes) {
                Payload request = BenchPayloads.edap(blobSize);
                BenchRunner.run("warmup-edap", blobSize, concurrency, warmupMillis, 100,
                        () -> client.unary(request).getValue());
                Http2WriteStats.reset();
                BenchResult r = BenchRunner.run("edap", blobSize, concurrency, 0, measureMillis,
                        () -> client.unary(request).getValue());
                report("edap 客户端 → edap 服务端 / blob=" + blobSize, r, measureMillis);
            }
        }
    }

    private static void runGoogleClient(int port, int[] blobSizes, int concurrency,
                                        long warmupMillis, long measureMillis, boolean freshClient)
            throws Exception {
        if (freshClient) {
            for (int blobSize : blobSizes) {
                try (GoogleBenchClient client = new GoogleBenchClient("127.0.0.1", port)) {
                    Bench.Payload request = BenchPayloads.google(blobSize);
                    BenchRunner.run("warmup-google", blobSize, concurrency, warmupMillis, 100,
                            () -> client.unary(request).getValue());
                    Http2WriteStats.reset();
                    BenchResult r = BenchRunner.run("google", blobSize, concurrency, 0, measureMillis,
                            () -> client.unary(request).getValue());
                    report("grpc-java 客户端 → edap 服务端 / blob=" + blobSize, r, measureMillis);
                }
            }
            return;
        }
        try (GoogleBenchClient client = new GoogleBenchClient("127.0.0.1", port)) {
            for (int blobSize : blobSizes) {
                Bench.Payload request = BenchPayloads.google(blobSize);
                BenchRunner.run("warmup-google", blobSize, concurrency, warmupMillis, 100,
                        () -> client.unary(request).getValue());
                Http2WriteStats.reset();
                BenchResult r = BenchRunner.run("google", blobSize, concurrency, 0, measureMillis,
                        () -> client.unary(request).getValue());
                report("grpc-java 客户端 → edap 服务端 / blob=" + blobSize, r, measureMillis);
            }
        }
    }

    private static int[] parseInts(String csv) {
        String[] parts = csv.split(",");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Integer.parseInt(parts[i].trim());
        }
        return values;
    }

    private static void report(String title, BenchResult r, long measureMillis) {
        double seconds = measureMillis / 1000.0;
        long responses = (long) (r.rps() * seconds);
        System.out.println();
        System.out.println("=== " + title + " ===");
        System.out.printf("req/s = %.0f   p50 = %.0fus  p99 = %.0fus  错误 = %d%n",
                r.rps(), r.p50, r.p99, r.errors);
        System.out.printf("窗口内响应数 ≈ %d%n", responses);
        if (!Http2WriteStats.ENABLED) {
            System.out.println("★ 计数未开启 —— 请加 -Dedap.http2.writeStats=true");
            return;
        }
        System.out.println(Http2WriteStats.dump());
        if (responses <= 0) {
            return;
        }
        System.out.println("--- 每条响应摊到多少 ---");
        System.out.printf("socket 写     : %.2f 次/响应%n",
                (double) Http2WriteStats.socketWrites.sum() / responses);
        System.out.printf("半写(等OP_WRITE): %.2f 次/响应%n",
                (double) Http2WriteStats.partialWrites.sum() / responses);
        System.out.printf("OP_WRITE 注册 : %.2f 次/响应%n",
                (double) Http2WriteStats.opWriteEnables.sum() / responses);
        System.out.printf("帧            : %.2f 个/响应%n",
                (double) Http2WriteStats.framesEnqueued.sum() / responses);
        System.out.printf("帧字节        : %.0f 字节/响应%n",
                (double) Http2WriteStats.frameBytes.sum() / responses);
        System.out.printf("入站追加      : %.2f 次/响应%n",
                (double) Http2WriteStats.inboundAppends.sum() / responses);
        System.out.printf("流控挂起      : %.2f 次/响应%n",
                (double) Http2WriteStats.fcStalls.sum() / responses);
        System.out.printf("  其中连接级挡: %d  流级挡: %d%n",
                Http2WriteStats.connStalls.sum(), Http2WriteStats.streamStalls.sum());
        System.out.printf("流控等待总时长: %.1f ms (平均 %.0f us/次)%n",
                Http2WriteStats.fcStallNanos.sum() / 1e6,
                Http2WriteStats.fcStalls.sum() == 0 ? 0
                        : Http2WriteStats.fcStallNanos.sum() / 1000.0 / Http2WriteStats.fcStalls.sum());
        System.out.printf("连接级 WU     : %.3f 个/响应 (共 %d 个 / %d 字节)%n",
                (double) Http2WriteStats.connWuCount.sum() / responses,
                Http2WriteStats.connWuCount.sum(), Http2WriteStats.connWuBytes.sum());
        System.out.printf("窗口见底次数  : %d (占流控挂起的 %.1f%%)%n",
                Http2WriteStats.connWindowEmpty.sum(),
                Http2WriteStats.connStalls.sum() == 0 ? 0
                        : 100.0 * Http2WriteStats.connWindowEmpty.sum() / Http2WriteStats.connStalls.sum());
        System.out.printf("见底→有额度空等: 总 %.1f ms, 平均 %.0f us/次%n",
                Http2WriteStats.connIdleNanos.sum() / 1e6,
                Http2WriteStats.connWindowEmpty.sum() == 0 ? 0
                        : Http2WriteStats.connIdleNanos.sum() / 1000.0 / Http2WriteStats.connWindowEmpty.sum());
        System.out.printf("流级 WU       : %.3f 个/响应 (共 %d 个 / %d 字节)%n",
                (double) Http2WriteStats.streamWuCount.sum() / responses,
                Http2WriteStats.streamWuCount.sum(), Http2WriteStats.streamWuBytes.sum());
        System.out.printf("出站字节率    : %.1f MB/s%n",
                Http2WriteStats.frameBytes.sum() / seconds / (1024.0 * 1024.0));
        System.out.printf("入站字节率    : %.1f MB/s%n",
                Http2WriteStats.inboundBytes.sum() / seconds / (1024.0 * 1024.0));
        System.out.printf("存活流数      : %d%n", Http2WriteStats.liveStreams.get());
        System.out.printf("handler       : %d 次, 平均 %.0f us/次%n",
                Http2WriteStats.handlerCalls.sum(),
                Http2WriteStats.handlerCalls.sum() == 0 ? 0
                        : Http2WriteStats.handlerNanos.sum() / 1000.0 / Http2WriteStats.handlerCalls.sum());
    }
}
