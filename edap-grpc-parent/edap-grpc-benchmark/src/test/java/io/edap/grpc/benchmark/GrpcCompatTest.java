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

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 跨框架兼容性矩阵 —— edap ↔ grpc-java 的双向互操作。
 *
 * <p>与 {@link GrpcInteropTest} 的区别：那个测「能不能通」，这个测「通得对不对」——
 * 覆盖负载尺寸（含跨流控窗口的大包）、并发多路复用、三种流式形态、
 * 错误状态码、元数据往返、未知方法。</p>
 *
 * <p>★ 断言的核心是「两边的行为必须一致」：edap 客户端打 google 服务端，
 * 与 google 客户端打 edap 服务端，得到的响应必须逐字节相同。</p>
 */
public class GrpcCompatTest {

    /**
     * 负载尺寸扫描 —— 刻意包含 {@code 65535}（老连接级窗口的上限）与
     * {@code 65536}（刚好越界），以及更大的包（验证窗口扩容后的流控）。
     */
    private static final int[] BLOB_SIZES = {0, 1, 127, 1024, 16 * 1024, 65535, 65536, 512 * 1024};

    /** 并发多路复用的路数。 */
    private static final int CONCURRENT_STREAMS = 32;

    private EdapBenchServer edapServer;
    private GoogleBenchServer googleServer;
    private int edapPort;
    private int googlePort;

    @BeforeEach
    void startServers() throws Exception {
        edapPort = EdapBenchServer.freePort();
        googlePort = EdapBenchServer.freePort();
        // echoMetadata = true —— 元数据往返要用
        edapServer = new EdapBenchServer(edapPort, true);
        googleServer = new GoogleBenchServer(googlePort, true);
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

    // ------------------------------------------------------------------
    // ① 一元调用：负载尺寸扫描
    // ------------------------------------------------------------------

    @Test
    void edapClientCallsGoogleServerAtEverySize() throws Exception {
        try (EdapBenchClient client = new EdapBenchClient("127.0.0.1", googlePort)) {
            for (int size : BLOB_SIZES) {
                Payload request = BenchPayloads.edap(size);
                Payload resp = client.unary(request);

                assertNotNull(resp, "size=" + size);
                assertEquals(BenchConstants.NAME_PREFIX + BenchConstants.REQUEST_NAME, resp.getName(),
                        "size=" + size);
                assertEquals(BenchConstants.REQUEST_VALUE * 2, resp.getValue(), "size=" + size);
                assertBlobEquals(BenchPayloads.blob(size), resp.getBlob(), "size=" + size);
            }
        }
    }

    @Test
    void googleClientCallsEdapServerAtEverySize() throws Exception {
        try (GoogleBenchClient client = new GoogleBenchClient("127.0.0.1", edapPort)) {
            for (int size : BLOB_SIZES) {
                Bench.Payload request = BenchPayloads.google(size);
                Bench.Payload resp = client.unary(request);

                assertNotNull(resp, "size=" + size);
                assertEquals(BenchConstants.NAME_PREFIX + BenchConstants.REQUEST_NAME, resp.getName(),
                        "size=" + size);
                assertEquals(BenchConstants.REQUEST_VALUE * 2, resp.getValue(), "size=" + size);
                assertBlobEquals(BenchPayloads.blob(size), resp.getBlob().toByteArray(), "size=" + size);
            }
        }
    }

    /**
     * blob 的比较 —— <b>null 与空数组视为等价</b>。
     *
     * <p>这是两种表示法的差异，不是 wire 不兼容：</p>
     * <ul>
     *   <li>edap 的解码器用 {@code null} 表示「这个字段不存在」；</li>
     *   <li>protobuf-java 用空的 {@code ByteString} 表示同样的意思；</li>
     *   <li>wire 上两者都是「没有第 3 个字段」—— proto3 的默认值不落盘。</li>
     * </ul>
     */
    private static void assertBlobEquals(byte[] expected, byte[] actual, String message) {
        assertArrayEquals(expected, actual == null ? new byte[0] : actual, message);
    }

    /**
     * 请求的 wire 字节必须逐字节相同 —— 这是「两个框架说的是同一种语言」最直接的证据。
     */
    @Test
    void requestWireBytesAreIdentical() {
        for (int size : BLOB_SIZES) {
            if (size == 0) {
                continue;                       // 0 字节的差异见 GrpcInteropTest（语义等价、字节不同）
            }
            byte[] edapBytes = io.edap.grpc.GrpcCodec.encode(Payload.class, BenchPayloads.edap(size));
            byte[] googleBytes = BenchPayloads.google(size).toByteArray();
            assertArrayEquals(googleBytes, edapBytes, "size=" + size);
        }
    }

    // ------------------------------------------------------------------
    // ② 并发多路复用
    // ------------------------------------------------------------------

    @Test
    void concurrentStreamsDoNotCrossTalkAgainstGoogleServer() throws Exception {
        try (EdapBenchClient client = new EdapBenchClient("127.0.0.1", googlePort)) {
            runConcurrently(i -> {
                Payload request = BenchPayloads.edap(1024 + i);
                request.setValue(i);
                Payload resp = client.unary(request);
                assertEquals(BenchConstants.NAME_PREFIX + BenchConstants.REQUEST_NAME, resp.getName());
                assertEquals(i * 2, resp.getValue(), "第 " + i + " 条流的响应串了");
                assertArrayEquals(BenchPayloads.blob(1024 + i), resp.getBlob(), "第 " + i + " 条流");
            });
        }
    }

    @Test
    void concurrentStreamsDoNotCrossTalkAgainstEdapServer() throws Exception {
        try (GoogleBenchClient client = new GoogleBenchClient("127.0.0.1", edapPort)) {
            runConcurrently(i -> {
                Bench.Payload request = BenchPayloads.google(1024 + i);
                request = request.toBuilder().setValue(i).build();
                Bench.Payload resp = client.unary(request);
                assertEquals(BenchConstants.NAME_PREFIX + BenchConstants.REQUEST_NAME, resp.getName());
                assertEquals(i * 2, resp.getValue(), "第 " + i + " 条流的响应串了");
                assertArrayEquals(BenchPayloads.blob(1024 + i), resp.getBlob().toByteArray(), "第 " + i + " 条流");
            });
        }
    }

    /**
     * 在<b>一条连接</b>上并发发起 {@link #CONCURRENT_STREAMS} 路调用，
     * 每路的内容各不相同 —— 任何「流 id 分配错了」「响应串流了」都会被抓出来。
     */
    private void runConcurrently(IndexedTask task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_STREAMS);
        try {
            CountDownLatch ready = new CountDownLatch(CONCURRENT_STREAMS);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(CONCURRENT_STREAMS);
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < CONCURRENT_STREAMS; i++) {
                final int index = i;
                pool.execute(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        task.run(index);
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            ready.await();
            go.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "并发调用超时");
            if (!failures.isEmpty()) {
                AssertionError error = new AssertionError("并发调用里有 " + failures.size() + " 个失败");
                error.initCause(failures.get(0));
                throw error;
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** 带下标的任务。 */
    private interface IndexedTask {
        void run(int index) throws Exception;
    }

    // ------------------------------------------------------------------
    // ③ 三种流式形态
    // ------------------------------------------------------------------

    @Test
    void serverStreamWorksBothDirections() throws Exception {
        try (EdapBenchClient edapClient = new EdapBenchClient("127.0.0.1", googlePort)) {
            List<Payload> responses = edapClient.serverStream(BenchPayloads.edap(1024));
            assertServerStream(responses.stream().mapToInt(Payload::getValue).toArray());
        }
        try (GoogleBenchClient googleClient = new GoogleBenchClient("127.0.0.1", edapPort)) {
            List<Bench.Payload> responses = googleClient.serverStream(BenchPayloads.google(1024));
            assertServerStream(responses.stream().mapToInt(Bench.Payload::getValue).toArray());
        }
    }

    private static void assertServerStream(int[] values) {
        assertEquals(BenchConstants.SERVER_STREAM_COUNT, values.length, "服务端流式的响应条数");
        for (int i = 0; i < values.length; i++) {
            assertEquals(BenchConstants.REQUEST_VALUE * 2 + i, values[i], "第 " + i + " 条响应");
        }
    }

    @Test
    void clientStreamWorksBothDirections() throws Exception {
        List<Payload> edapRequests = new ArrayList<>();
        List<Bench.Payload> googleRequests = new ArrayList<>();
        int sum = 0;
        for (int i = 1; i <= 3; i++) {
            edapRequests.add(BenchPayloads.edap(64 * i));
            googleRequests.add(BenchPayloads.google(64 * i));
            sum += BenchConstants.REQUEST_VALUE;
        }

        try (EdapBenchClient client = new EdapBenchClient("127.0.0.1", googlePort)) {
            Payload resp = client.clientStream(edapRequests);
            assertEquals(sum, resp.getValue(), "客户端流式应该求和");
            assertEquals(BenchConstants.NAME_PREFIX + BenchConstants.REQUEST_NAME, resp.getName());
        }
        try (GoogleBenchClient client = new GoogleBenchClient("127.0.0.1", edapPort)) {
            Bench.Payload resp = client.clientStream(googleRequests);
            assertEquals(sum, resp.getValue(), "客户端流式应该求和");
            assertEquals(BenchConstants.NAME_PREFIX + BenchConstants.REQUEST_NAME, resp.getName());
        }
    }

    @Test
    void bidiWorksBothDirections() throws Exception {
        List<Payload> edapRequests = new ArrayList<>();
        List<Bench.Payload> googleRequests = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            edapRequests.add(BenchPayloads.edap(64 * i));
            googleRequests.add(BenchPayloads.google(64 * i));
        }

        try (EdapBenchClient client = new EdapBenchClient("127.0.0.1", googlePort)) {
            List<Payload> responses = client.bidi(edapRequests);
            assertBidi(responses.stream().mapToInt(Payload::getValue).toArray());
        }
        try (GoogleBenchClient client = new GoogleBenchClient("127.0.0.1", edapPort)) {
            List<Bench.Payload> responses = client.bidi(googleRequests);
            assertBidi(responses.stream().mapToInt(Bench.Payload::getValue).toArray());
        }
    }

    private static void assertBidi(int[] values) {
        assertEquals(3, values.length, "双向流式的响应条数");
        for (int i = 0; i < values.length; i++) {
            assertEquals(BenchConstants.REQUEST_VALUE * BenchConstants.BIDI_MULTIPLIER, values[i],
                    "第 " + i + " 条响应");
        }
    }

    // ------------------------------------------------------------------
    // ④ 状态码 / trailers / 元数据
    // ------------------------------------------------------------------

    /**
     * 未知方法必须回 {@code UNIMPLEMENTED}（gRPC 规范要求，不是 HTTP 404）。
     */
    @Test
    void unknownMethodReturnsUnimplemented() throws Exception {
        String unknownPath = "/" + BenchConstants.SERVICE_NAME + "/NoSuchMethod";

        // edap 客户端 → google 服务端
        try (EdapBenchClient client = new EdapBenchClient("127.0.0.1", googlePort)) {
            try {
                client.unary(unknownPath, BenchPayloads.edap(16));
                fail("应该抛 UNIMPLEMENTED");
            } catch (RuntimeException e) {
                assertTrue(String.valueOf(e.getMessage()).contains("UNIMPLEMENTED"),
                        "实际错误: " + e.getMessage());
            }
        }

        // google 客户端 → edap 服务端
        ManagedChannel channel = ManagedChannelBuilder.forAddress("127.0.0.1", edapPort).usePlaintext().build();
        try {
            ClientCalls.blockingUnaryCall(
                    channel.newCall(unknownMethod(), CallOptions.DEFAULT), BenchPayloads.google(16));
            fail("应该抛 UNIMPLEMENTED");
        } catch (StatusRuntimeException e) {
            assertEquals(Status.Code.UNIMPLEMENTED, e.getStatus().getCode());
        } finally {
            channel.shutdownNow();
        }
    }

    /**
     * {@code grpc-status} 必须落在<b>trailers</b> 里 —— 这是 gRPC 与普通 HTTP 的分界线。
     */
    @Test
    void grpcStatusArrivesInTrailers() throws Exception {
        try (EdapBenchClient client = new EdapBenchClient("127.0.0.1", googlePort)) {
            io.edap.grpc.client.GrpcClientCall call =
                    client.unaryCall(BenchPayloads.edap(64), null);
            assertNotNull(call.responseTrailers(), "必须收到 trailers");
            assertEquals("0", call.responseTrailers().get("grpc-status"),
                    "trailers 里必须有 grpc-status=0");
        }
    }

    /**
     * 自定义元数据必须从请求头原样传到响应头。
     */
    @Test
    void metadataRoundTripsBothDirections() throws Exception {
        String value = "hello-edap";

        try (EdapBenchClient client = new EdapBenchClient("127.0.0.1", googlePort)) {
            io.edap.grpc.client.GrpcClientCall call = client.unaryCall(
                    BenchPayloads.edap(64), Map.of(BenchConstants.ECHO_HEADER, value));
            assertNotNull(call.responseHeaders(), "必须收到响应头");
            assertEquals(value, call.responseHeaders().get(BenchConstants.ECHO_HEADER),
                    "edap 客户端 → google 服务端：响应头里的回显");
        }

        try (GoogleBenchClient client = new GoogleBenchClient("127.0.0.1", edapPort)) {
            AtomicReference<Metadata> headers = new AtomicReference<>();
            AtomicReference<Metadata> trailers = new AtomicReference<>();
            client.unaryCall(BenchPayloads.google(64), Map.of(BenchConstants.ECHO_HEADER, value),
                    headers, trailers);
            assertNotNull(headers.get(), "必须捕获到响应头");
            assertEquals(value, headers.get().get(GoogleBenchServer.ECHO_KEY),
                    "google 客户端 → edap 服务端：响应头里的回显");
        }
    }

    /**
     * 造一个「服务端没有的方法」的描述符 —— grpc-java 的 stub 里只有编译期已知的方法。
     */
    private static MethodDescriptor<Bench.Payload, Bench.Payload> unknownMethod() {
        return MethodDescriptor.<Bench.Payload, Bench.Payload>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(
                        BenchConstants.SERVICE_NAME, "NoSuchMethod"))
                .setRequestMarshaller(ProtoUtils.marshaller(Bench.Payload.getDefaultInstance()))
                .setResponseMarshaller(ProtoUtils.marshaller(Bench.Payload.getDefaultInstance()))
                .build();
    }
}
