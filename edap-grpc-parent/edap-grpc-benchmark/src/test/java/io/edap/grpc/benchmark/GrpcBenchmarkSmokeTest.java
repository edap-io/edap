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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基准流程的冒烟测试 —— 短窗口跑一轮，只断言<b>功能正确</b>。
 *
 * <p><b>不做性能断言</b>：绝对吞吐受机器负载影响，CI 上必然 flaky。
 * 性能数字由 {@link BenchMain} 生成的报告负责，这里只保证「压测链路本身是通的」。</p>
 */
public class GrpcBenchmarkSmokeTest {

    /** 冒烟用的短窗口（毫秒） */
    private static final long WARMUP_MILLIS = 200;
    private static final long MEASURE_MILLIS = 500;

    private EdapBenchServer edapServer;
    private GoogleBenchServer googleServer;
    private int edapPort;
    private int googlePort;

    @BeforeEach
    void startServers() throws Exception {
        edapPort = EdapBenchServer.freePort();
        googlePort = EdapBenchServer.freePort();
        edapServer = new EdapBenchServer(edapPort);
        googleServer = new GoogleBenchServer(googlePort);
        edapServer.start();
        googleServer.start();
    }

    @AfterEach
    void stopServers() {
        if (edapServer != null) {
            edapServer.close();
            edapServer = null;
        }
        if (googleServer != null) {
            googleServer.close();
            googleServer = null;
        }
    }

    @Test
    void edapSideRunsWithoutErrors() throws Exception {
        Payload request = BenchPayloads.edap(1024);
        try (EdapBenchClient client = new EdapBenchClient("127.0.0.1", edapPort)) {
            BenchResult result = BenchRunner.run("edap", 1024, 4,
                    WARMUP_MILLIS, MEASURE_MILLIS, () -> {
                        Payload resp = client.unary(request);
                        assertEquals(BenchConstants.REQUEST_VALUE * 2, resp.getValue());
                        return resp.getValue();
                    });
            assertEquals(0, result.errors, "edap 侧不能有失败调用: " + result.firstError);
            assertTrue(result.ops > 0, "edap 侧必须真的跑出调用量");
            assertTrue(result.rps() > 0, "edap 侧的吞吐必须是正数");
        }
    }

    @Test
    void googleSideRunsWithoutErrors() throws Exception {
        Bench.Payload request = BenchPayloads.google(1024);
        try (GoogleBenchClient client = new GoogleBenchClient("127.0.0.1", googlePort)) {
            BenchResult result = BenchRunner.run("google", 1024, 4,
                    WARMUP_MILLIS, MEASURE_MILLIS, () -> {
                        Bench.Payload resp = client.unary(request);
                        assertEquals(BenchConstants.REQUEST_VALUE * 2, resp.getValue());
                        return resp.getValue();
                    });
            assertEquals(0, result.errors, "google 侧不能有失败调用: " + result.firstError);
            assertTrue(result.ops > 0, "google 侧必须真的跑出调用量");
            assertTrue(result.rps() > 0, "google 侧的吞吐必须是正数");
        }
    }

    @Test
    void codecComparisonProducesResultsForEveryBlobSize() {
        java.util.List<CodecBenchmark.CodecResult> results =
                CodecBenchmark.run(new int[]{0, 1024}, 200);
        assertEquals(2, results.size());
        for (CodecBenchmark.CodecResult r : results) {
            assertTrue(r.wireBytes > 0, "blob=" + r.blobSize + " 的线上字节数必须是正数");
            assertTrue(r.edapEncodeNs > 0);
            assertTrue(r.googleEncodeNs > 0);
            assertTrue(r.edapDecodeNs > 0);
            assertTrue(r.googleDecodeNs > 0);
            // 对等口径（编码核心）：edap 只写进 writer 缓冲，google writeTo 写进已有数组
            assertTrue(r.edapEncodeCoreNs > 0, "blob=" + r.blobSize + " 的 edap 编码核心耗时必须是正数");
            assertTrue(r.googleEncodeCoreNs > 0, "blob=" + r.blobSize + " 的 google 编码核心耗时必须是正数");
        }
        // 非空 blob 下两边的字节必须完全相同
        CodecBenchmark.CodecResult nonEmpty = results.get(1);
        assertTrue(nonEmpty.wireIdentical,
                "blob=1024 时两边的编码字节必须完全相同");
    }
}
