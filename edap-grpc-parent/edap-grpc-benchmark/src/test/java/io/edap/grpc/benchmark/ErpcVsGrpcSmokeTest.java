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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * eRPC vs gRPC 对比基准的冒烟测试 —— 短窗口跑一轮，只断言<b>功能正确</b>。
 *
 * <p><b>不做性能断言</b>：绝对吞吐受机器负载影响，CI 上必然 flaky。
 * 这里只保证「两个臂的压测链路本身都通、结果都对」。</p>
 */
public class ErpcVsGrpcSmokeTest {

    /** 冒烟用的短窗口（毫秒） */
    private static final long WARMUP_MILLIS = 200;
    private static final long MEASURE_MILLIS = 500;

    private EdapBenchServer grpcServer;
    private ErpcBenchServer erpcServer;
    private int grpcPort;
    private int erpcPort;

    @BeforeEach
    void startServers() throws Exception {
        grpcPort = EdapBenchServer.freePort();
        erpcPort = ErpcBenchServer.freePort();
        grpcServer = new EdapBenchServer(grpcPort);
        erpcServer = new ErpcBenchServer(erpcPort);
        grpcServer.start();
        erpcServer.start();
    }

    @AfterEach
    void stopServers() {
        if (grpcServer != null) {
            grpcServer.close();
            grpcServer = null;
        }
        if (erpcServer != null) {
            erpcServer.close();
            erpcServer = null;
        }
    }

    @Test
    void grpcArmRunsWithoutErrors() throws Exception {
        Payload request = BenchPayloads.edap(1024);
        try (EdapBenchClient client = new EdapBenchClient("127.0.0.1", grpcPort)) {
            BenchResult result = BenchRunner.run("gRPC", 1024, 4,
                WARMUP_MILLIS, MEASURE_MILLIS, () -> {
                    Payload resp = client.unary(request);
                    assertEquals(BenchConstants.REQUEST_VALUE * 2, resp.getValue());
                    return resp.getValue();
                });
            assertEquals(0, result.errors, "gRPC 臂不能有失败调用: " + result.firstError);
            assertTrue(result.ops > 0, "gRPC 臂必须真的跑出调用量");
            assertTrue(result.rps() > 0, "gRPC 臂的吞吐必须是正数");
        }
    }

    @Test
    void erpcArmRunsWithoutErrors() throws Exception {
        Payload request = BenchPayloads.edap(1024);
        try (ErpcBenchClient client = new ErpcBenchClient("127.0.0.1", erpcPort)) {
            BenchResult result = BenchRunner.run("eRPC", 1024, 4,
                WARMUP_MILLIS, MEASURE_MILLIS, () -> {
                    Payload resp = client.unary(request);
                    assertEquals(BenchConstants.REQUEST_VALUE * 2, resp.getValue());
                    return resp.getValue();
                });
            assertEquals(0, result.errors, "eRPC 臂不能有失败调用: " + result.firstError);
            assertTrue(result.ops > 0, "eRPC 臂必须真的跑出调用量");
            assertTrue(result.rps() > 0, "eRPC 臂的吞吐必须是正数");
        }
    }

    /**
     * ★ 两个臂的<b>业务结果必须一致</b> —— 否则「谁的数字更快」就没有意义。
     */
    @Test
    void bothArmsReturnTheSameResult() throws Exception {
        Payload request = BenchPayloads.edap(1024);
        Payload grpcResp;
        Payload erpcResp;
        try (EdapBenchClient grpcClient = new EdapBenchClient("127.0.0.1", grpcPort);
             ErpcBenchClient erpcClient = new ErpcBenchClient("127.0.0.1", erpcPort)) {
            grpcResp = grpcClient.unary(request);
            erpcResp = erpcClient.unary(request);
        }
        assertEquals(grpcResp.getName(), erpcResp.getName());
        assertEquals(grpcResp.getValue(), erpcResp.getValue());
        assertEquals(grpcResp.getBlob().length, erpcResp.getBlob().length);
        assertEquals(BenchConstants.NAME_PREFIX + BenchConstants.REQUEST_NAME, erpcResp.getName());
        assertEquals(BenchConstants.REQUEST_VALUE * 2, erpcResp.getValue());
    }

    /**
     * ★ micro 对比必须给出结果，且<b>两边编出来的字节完全相同</b>
     * （同一套 protobuf 实现 ⇒ 端到端差距不可能来自编解码器）。
     */
    @Test
    void codecComparisonProducesIdenticalBytes() {
        List<ErpcCodecCompare.CompareResult> results =
            ErpcCodecCompare.run(new int[]{0, 1024}, 200);
        assertEquals(2, results.size());
        for (ErpcCodecCompare.CompareResult r : results) {
            assertTrue(r.payloadBytes > 0, "blob=" + r.blobSize + " 的线上字节数必须是正数");
            assertTrue(r.grpcEncodeNs > 0);
            assertTrue(r.erpcEncodeNs > 0);
            assertTrue(r.grpcDecodeNs > 0);
            assertTrue(r.erpcDecodeNs > 0);
            assertTrue(r.grpcFrameWriteNs > 0);
            assertTrue(r.erpcFrameWriteNs > 0);
        }
        assertTrue(results.get(1).bytesIdentical,
            "blob=1024 时 eRPC 与 gRPC 的编码字节必须完全相同");
    }
}
