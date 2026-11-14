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

import io.edap.grpc.GrpcCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨框架互操作测试 —— edap ↔ google grpc-java。
 *
 * <p>同一份 {@code bench.proto} 喂给两个生成器，得到两套 Java 代码：
 * edap 生成 {@link Payload} + {@link BenchService}，protoc 生成
 * {@link Bench} + {@link BenchServiceGrpc}。</p>
 *
 * <p><b>这些用例是整个基准模块的地基</b> —— 如果两边不能互相调用，
 * 后面测出来的吞吐/延迟数字就没有意义。</p>
 */
public class GrpcInteropTest {

    private static final int BLOB_SIZE = 64;

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

    // ---- :path 必须逐字符一致 ------------------------------------------

    @Test
    void bothSidesUseTheSameServiceNameAndPath() {
        assertEquals("edap.bench.BenchService", BenchServiceGrpc.SERVICE_NAME,
                "protoc 生成的 service 全名");
        assertEquals(BenchConstants.SERVICE_NAME, BenchServiceGrpc.SERVICE_NAME,
                "edap 侧与 google 侧的 service 全名必须一致");
        assertEquals("/" + BenchServiceGrpc.SERVICE_NAME + "/Unary", BenchConstants.PATH,
                "两边的 :path 必须逐字符一致，否则调不到对方的方法");
        assertTrue(BenchServiceGrpc.getUnaryMethod().getFullMethodName()
                        .equals(BenchConstants.SERVICE_NAME + "/Unary"),
                "grpc-java 的 fullMethodName: " + BenchServiceGrpc.getUnaryMethod().getFullMethodName());
    }

    // ---- 跨框架调用 ---------------------------------------------------

    @Test
    void edapClientCallsGoogleServer() throws Exception {
        try (EdapBenchClient client = new EdapBenchClient("127.0.0.1", googlePort)) {
            Payload resp = client.unary(BenchPayloads.edap(BLOB_SIZE));

            assertNotNull(resp);
            assertEquals(BenchConstants.NAME_PREFIX + BenchConstants.REQUEST_NAME, resp.getName());
            assertEquals(BenchConstants.REQUEST_VALUE * 2, resp.getValue());
            assertNotNull(resp.getBlob(), "google 服务端必须把 blob 原样回显");
            assertEquals(BLOB_SIZE, resp.getBlob().length);
            assertArrayEquals(expectedBlob(BLOB_SIZE), resp.getBlob(),
                    "blob 的内容必须逐字节相同");
        }
    }

    @Test
    void googleClientCallsEdapServer() throws Exception {
        try (GoogleBenchClient client = new GoogleBenchClient("127.0.0.1", edapPort)) {
            Bench.Payload resp = client.unary(BenchPayloads.google(BLOB_SIZE));

            assertNotNull(resp);
            assertEquals(BenchConstants.NAME_PREFIX + BenchConstants.REQUEST_NAME, resp.getName());
            assertEquals(BenchConstants.REQUEST_VALUE * 2, resp.getValue());
            assertEquals(BLOB_SIZE, resp.getBlob().size());
            assertArrayEquals(expectedBlob(BLOB_SIZE), resp.getBlob().toByteArray(),
                    "blob 的内容必须逐字节相同");
        }
    }

    // ---- 编解码互认 ---------------------------------------------------

    @Test
    void requestBytesAreIdenticalForNonEmptyBlob() {
        byte[] edapBytes = GrpcCodec.encode(Payload.class, BenchPayloads.edap(BLOB_SIZE));
        byte[] googleBytes = BenchPayloads.google(BLOB_SIZE).toByteArray();

        assertArrayEquals(googleBytes, edapBytes,
                "同一份消息，两边编出来的字节必须逐字节相同（wire 格式一致的直接证据）");
    }

    @Test
    void edapDecodesGoogleEncodedPayload() {
        byte[] googleBytes = BenchPayloads.google(BLOB_SIZE).toByteArray();
        Payload decoded = GrpcCodec.decode(Payload.class, googleBytes, 0, googleBytes.length);

        assertEquals(BenchConstants.REQUEST_NAME, decoded.getName());
        assertEquals(BenchConstants.REQUEST_VALUE, decoded.getValue());
        assertArrayEquals(expectedBlob(BLOB_SIZE), decoded.getBlob());
    }

    @Test
    void googleDecodesEdapEncodedPayload() throws Exception {
        byte[] edapBytes = GrpcCodec.encode(Payload.class, BenchPayloads.edap(BLOB_SIZE));
        Bench.Payload decoded = Bench.Payload.parseFrom(edapBytes);

        assertEquals(BenchConstants.REQUEST_NAME, decoded.getName());
        assertEquals(BenchConstants.REQUEST_VALUE, decoded.getValue());
        assertArrayEquals(expectedBlob(BLOB_SIZE), decoded.getBlob().toByteArray());
    }

    @Test
    void emptyBlobIsSemanticallyEqualOnBothSides() {
        // edap 的编码器会写出空的 bytes 字段（1a 00），protobuf-java 按 proto3
        // 默认值规则省略它 —— 字节不同，但解码后的语义必须一致。
        byte[] edapBytes = GrpcCodec.encode(Payload.class, BenchPayloads.edap(0));
        byte[] googleBytes = BenchPayloads.google(0).toByteArray();

        Payload decodedByEdap = GrpcCodec.decode(Payload.class, googleBytes, 0, googleBytes.length);
        assertEquals(BenchConstants.REQUEST_NAME, decodedByEdap.getName());
        assertEquals(0, decodedByEdap.getBlob() == null ? 0 : decodedByEdap.getBlob().length);

        try {
            Bench.Payload decodedByGoogle = Bench.Payload.parseFrom(edapBytes);
            assertEquals(BenchConstants.REQUEST_NAME, decodedByGoogle.getName());
            assertEquals(0, decodedByGoogle.getBlob().size());
        } catch (Exception e) {
            throw new AssertionError("edap 编出来的空 blob 载荷 google 解不了", e);
        }
    }

    private static byte[] expectedBlob(int size) {
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i & 0xFF);
        }
        return data;
    }
}
