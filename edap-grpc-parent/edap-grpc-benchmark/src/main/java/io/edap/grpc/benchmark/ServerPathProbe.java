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
 * 服务端写路径的<b>专项探针</b> —— 只起 edap 服务端，用 edap 客户端打，
 * 把 {@link Http2WriteStats} 的计数打出来。
 *
 * <p>★ 目的：回答「一条 64KB 响应在服务端到底被拆成几次 socket 写、
 * 有多少次半写要等 OP_WRITE、入站被复制了几遍」。</p>
 *
 * <p>★ 计数<b>默认关闭</b>（热路径上，JIT 会把关闭状态下的分支整个消掉），
 * 所以跑探针必须<b>显式打开</b>：</p>
 * <pre>{@code
 *   java -Dedap.http2.writeStats=true -Dedap.grpc.diag=true ... \
 *        io.edap.grpc.benchmark.ServerPathProbe --blob 65536 --conc 32 --measure 5000
 * }</pre>
 */
public final class ServerPathProbe {

    public static void main(String[] args) throws Exception {
        int blobSize = 65536;
        int concurrency = 32;
        long warmupMillis = 2000;
        long measureMillis = 5000;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--blob":
                    blobSize = Integer.parseInt(args[++i]);
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
                default:
                    System.out.println("未知参数: " + args[i]);
                    break;
            }
        }

        int port = EdapBenchServer.freePort();
        try (EdapBenchServer server = new EdapBenchServer(port)) {
            server.start();
            System.out.println("edap server: " + port + "  blob=" + blobSize + " conc=" + concurrency);
            try (EdapBenchClient client = new EdapBenchClient("127.0.0.1", port)) {

            Payload request = BenchPayloads.edap(blobSize);

            // 预热（不计入统计）
            BenchRunner.run("warmup", blobSize, concurrency, warmupMillis, 100, () -> client.unary(request).getValue());

            Http2WriteStats.reset();
            io.edap.grpc.client.GrpcClientDiag.reset();

            BenchResult r = BenchRunner.run("probe", blobSize, concurrency, 0, measureMillis,
                    () -> client.unary(request).getValue());

            double seconds = measureMillis / 1000.0;
            long responses = (long) (r.rps() * seconds);
            System.out.println();
            System.out.println("=== 吞吐 ===");
            System.out.printf("req/s = %.0f   p50 = %.0fus  p99 = %.0fus  错误 = %d%n",
                    r.rps(), r.p50, r.p99, r.errors);
            System.out.printf("窗口内响应数 ≈ %d%n", responses);
            System.out.println();
            System.out.println("=== 原始计数 ===");
            if (!Http2WriteStats.ENABLED || !io.edap.grpc.client.GrpcClientDiag.ENABLED) {
                System.out.println("★ 计数未开启 —— 请加 -Dedap.http2.writeStats=true -Dedap.grpc.diag=true"
                        + "（计数在热路径上，默认关闭以免影响生产性能）");
            }
            System.out.println(Http2WriteStats.dump());
            System.out.println(io.edap.grpc.client.GrpcClientDiag.dump());
            System.out.println();
            System.out.println("=== 每条响应摊到多少 ===");
            if (responses > 0) {
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
                System.out.printf("出站字节率    : %.1f MB/s%n",
                        Http2WriteStats.frameBytes.sum() / seconds / (1024.0 * 1024.0));
                System.out.printf("入站字节率    : %.1f MB/s%n",
                        Http2WriteStats.inboundBytes.sum() / seconds / (1024.0 * 1024.0));
            }
            }
        }
    }
}
