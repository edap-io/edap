/*
 * Copyright 2020 The edap Project
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

package io.edap.grpc.client.test;

import io.edap.Edap;
import io.edap.grpc.GrpcCall;
import io.edap.grpc.GrpcException;
import io.edap.grpc.GrpcHandler;
import io.edap.grpc.GrpcMethod;
import io.edap.grpc.GrpcStatus;
import io.edap.grpc.client.GrpcChannel;
import io.edap.grpc.client.GrpcClientCall;
import io.edap.grpc.server.GrpcServer;
import io.edap.http.http2.Http2FrameType;
import io.edap.http.http2.hpack.HpackDecoder;
import io.edap.http.http2.hpack.HpackHeader;
import io.edap.pool.SimpleFastBufPool;
import io.edap.protobuf.annotation.ProtoGrpc;
import io.edap.rpc.RpcContext;
import io.edap.rpc.RpcProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * gRPC 客户端的 deadline / 取消测试。
 *
 * <p>★ <b>三条不变式</b>由这个测试守住：</p>
 * <ol>
 *   <li><b>线上带标准的 {@code grpc-timeout} 头</b> —— 头值是「还剩多久」，
 *       不是绝对时刻 ⇒ 两端的 deadline 不受时钟偏移影响；</li>
 *   <li><b>取消信号真的被 trip</b> —— 客户端取消 / 等待超时之后，
 *       业务在别处观察 {@code ctx.cancellation()} 能立刻看到；</li>
 *   <li><b>等待上限 = min(调用方给的, 本次调用自己的 deadline)</b>。</li>
 * </ol>
 */
public class GrpcClientDeadlineTest {

    /** HTTP/2 连接前言的长度（"PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"） */
    private static final int PREFACE_LENGTH = 24;

    private static final String PATH = "/demo.Svc/Unary";

    private Edap edap;
    private GrpcChannel channel;
    private int port;

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.close();
            channel = null;
        }
        if (edap != null) {
            edap.stop();
            edap = null;
        }
    }

    // ------------------------------------------------------------------
    // 线上的 grpc-timeout 头
    // ------------------------------------------------------------------

    /**
     * ★ 客户端确实发出了 {@code grpc-timeout} 头，值是 gRPC 规范的形式。
     */
    @Test
    void clientSendsTheGrpcTimeoutHeader() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            BlockingQueue<List<HpackHeader>> headers = new ArrayBlockingQueue<>(4);
            startHeadCapture(serverSocket, headers);

            try (GrpcChannel ch = new GrpcChannel("127.0.0.1", port, 5_000, 5_000)) {
                // 2 秒 ⇒ 头值 "2S"（gRPC 规范：最多 8 位数字 + 1 个单位字符）
                ch.newCall(PATH, GrpcClientEndToEndTest.Req.class,
                        GrpcClientEndToEndTest.Resp.class, null, null, 2_000);
            }

            List<HpackHeader> hdrs = headers.poll(5, TimeUnit.SECONDS);
            assertNotNull(hdrs, "必须抓到客户端发的 HEADERS 帧");
            assertEquals("2S", headerValue(hdrs, "grpc-timeout"),
                    "grpc-timeout 的值不对: " + hdrs);
        }
    }

    /**
     * ★ 超时为 0 ⇒ 不带 {@code grpc-timeout} 头（服务端就没有 deadline）。
     */
    @Test
    void noTimeoutHeaderWhenTimeoutIsNotPositive() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            BlockingQueue<List<HpackHeader>> headers = new ArrayBlockingQueue<>(4);
            startHeadCapture(serverSocket, headers);

            try (GrpcChannel ch = new GrpcChannel("127.0.0.1", port, 5_000, 5_000)) {
                ch.newCall(PATH, GrpcClientEndToEndTest.Req.class,
                        GrpcClientEndToEndTest.Resp.class, null, null, 0);
            }

            List<HpackHeader> hdrs = headers.poll(5, TimeUnit.SECONDS);
            assertNotNull(hdrs, "必须抓到客户端发的 HEADERS 帧");
            assertNull(headerValue(hdrs, "grpc-timeout"),
                    "超时为 0 时不该带 grpc-timeout 头: " + hdrs);
        }
    }

    // ------------------------------------------------------------------
    // 取消信号
    // ------------------------------------------------------------------

    /**
     * ★ {@code cancel()} 会 trip 取消信号（原因非空）。
     */
    @Test
    void cancelTripsTheCancellationSignal() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            try (GrpcChannel ch = new GrpcChannel("127.0.0.1", port, 5_000, 5_000)) {
                GrpcClientCall call = ch.newCall(PATH, GrpcClientEndToEndTest.Req.class,
                        GrpcClientEndToEndTest.Resp.class, null);
                RpcContext ctx = call.context();
                assertFalse(ctx.cancellation().isCancelled(), "刚建好的调用不该是已取消");

                GrpcException cause = new GrpcException("测试用的取消");
                call.cancel(cause);

                assertTrue(ctx.cancellation().isCancelled(), "cancel 之后取消信号必须被 trip");
                assertEquals(cause, ctx.cancellation().reason(), "取消原因要对得上");
                assertEquals(GrpcStatus.CANCELLED, call.status());
            }
        }
    }

    /**
     * ★ 等待超时 ⇒ 取消信号被 trip（业务侧能观察到「被取消」）。
     */
    @Test
    void awaitCompletionTimeoutTripsTheCancellation() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            // 接受连接但不回任何东西 ⇒ 调用方只能等到超时
            Thread acceptor = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    Thread.sleep(5_000);
                } catch (Exception ignore) {
                    // 测试结束时会关掉 socket —— 忽略
                }
            }, "silent-server");
            acceptor.setDaemon(true);
            acceptor.start();

            try (GrpcChannel ch = new GrpcChannel("127.0.0.1", port, 5_000, 5_000)) {
                GrpcClientCall call = ch.newCall(PATH, GrpcClientEndToEndTest.Req.class,
                        GrpcClientEndToEndTest.Resp.class, null);
                RpcContext ctx = call.context();

                assertThrows(GrpcException.class, () -> call.awaitCompletion(100));
                assertTrue(ctx.cancellation().isCancelled(),
                        "等待超时就该 trip 取消信号（而不是只在本地放弃）");
            }
        }
    }

    /**
     * ★ 等待上限取 min(调用方给的, 本次调用自己的 deadline)。
     */
    @Test
    void awaitCompletionUsesTheOwnDeadlineAsUpperBound() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            Thread acceptor = new Thread(() -> {
                try (Socket socket = serverSocket.accept()) {
                    Thread.sleep(5_000);
                } catch (Exception ignore) {
                    // 测试结束时会关掉 socket —— 忽略
                }
            }, "silent-server");
            acceptor.setDaemon(true);
            acceptor.start();

            try (GrpcChannel ch = new GrpcChannel("127.0.0.1", port, 5_000, 5_000)) {
                // 本次调用的 deadline 只有 100ms，但调用方要等 10 秒
                GrpcClientCall call = ch.newCall(PATH, GrpcClientEndToEndTest.Req.class,
                        GrpcClientEndToEndTest.Resp.class, null, null, 100);
                RpcContext ctx = call.context();
                assertTrue(ctx.deadlineNanos() > 0, "本次调用应当带 deadline");

                long t0 = System.nanoTime();
                assertThrows(GrpcException.class, () -> call.awaitCompletion(10_000));
                long elapsed = (System.nanoTime() - t0) / 1_000_000L;
                // 上限是 100ms（自己的 deadline），不是调用方给的 10s
                assertTrue(elapsed < 3_000,
                        "等待上限应当是本次调用的 deadline，实际等了 " + elapsed + "ms");
                assertTrue(ctx.cancellation().isCancelled());
            }
        }
    }

    // ------------------------------------------------------------------
    // 端到端：服务端看到的 deadline
    // ------------------------------------------------------------------

    /**
     * ★ 客户端给的超时 ⇒ 服务端 handler 能看到<b>自己的</b> deadline。
     */
    @Test
    void serverSeesTheClientDeadline() throws Exception {
        AtomicReference<RpcContext> seen = new AtomicReference<>();
        startServer(seen);

        channel = new GrpcChannel("127.0.0.1", port, 10_000, 5_000);
        GrpcClientCall call = channel.newCall(PATH, GrpcClientEndToEndTest.Req.class,
                GrpcClientEndToEndTest.Resp.class, null, null, 2_000);
        GrpcClientEndToEndTest.Req req = new GrpcClientEndToEndTest.Req();
        req.name = "edap";
        req.value = 1;
        call.sendMessage(req);
        call.halfClose();
        call.awaitCompletion(5_000);

        RpcContext serverCtx = seen.get();
        assertNotNull(serverCtx, "服务端 handler 应当拿到上下文");
        assertEquals(RpcProtocol.GRPC, serverCtx.protocol());
        assertEquals(PATH, serverCtx.method());
        assertTrue(serverCtx.deadlineNanos() > 0, "服务端应当算出自己的 deadline");
        long left = serverCtx.remainingMillis();
        assertTrue(left > 0 && left <= 2_000,
                "服务端的剩余时间应落在 (0, 2000] 区间，实际 " + left);
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 起一个「只抓 HEADERS 帧」的假服务端。
     */
    private static void startHeadCapture(ServerSocket serverSocket,
                                         BlockingQueue<List<HpackHeader>> headers) {
        Thread acceptor = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                DataInputStream in = new DataInputStream(socket.getInputStream());
                in.skipNBytes(PREFACE_LENGTH);
                HpackDecoder decoder = new HpackDecoder();
                while (true) {
                    Frame f = readFrame(in);
                    if (f == null) {
                        return;
                    }
                    if (f.type == Http2FrameType.HEADERS) {
                        headers.offer(decoder.decode(f.payload, 0, f.payload.length));
                        return;
                    }
                }
            } catch (Exception ignore) {
                // 测试结束时会关掉 socket —— 忽略
            }
        }, "head-capture");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private static String headerValue(List<HpackHeader> headers, String name) {
        for (HpackHeader h : headers) {
            if (name.equals(h.getName())) {
                return h.getValue();
            }
        }
        return null;
    }

    private static Frame readFrame(DataInputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) {
            return null;
        }
        int b1 = in.read();
        int b2 = in.read();
        int type = in.read();
        int flags = in.read();
        int s0 = in.read();
        int s1 = in.read();
        int s2 = in.read();
        int s3 = in.read();
        int length = (b0 << 16) | (b1 << 8) | b2;
        int streamId = ((s0 & 0x7F) << 24) | ((s1 & 0xFF) << 16) | ((s2 & 0xFF) << 8) | (s3 & 0xFF);
        byte[] payload = new byte[length];
        in.readFully(payload);
        return new Frame(type, flags, streamId, payload);
    }

    private void startServer(AtomicReference<RpcContext> seen) throws Exception {
        port = freePort();
        GrpcHandler handler = call -> {
            seen.set(RpcContext.current());
            GrpcClientEndToEndTest.Resp resp = new GrpcClientEndToEndTest.Resp();
            resp.name = "ok";
            resp.value = 1;
            call.sendOne(resp);
            call.close(GrpcStatus.OK, null);
        };
        Map<String, GrpcMethod> methods = new HashMap<>();
        methods.put(PATH, new GrpcMethod(PATH, handler, ProtoGrpc.MethodType.UNARY,
                GrpcClientEndToEndTest.Req.class, GrpcClientEndToEndTest.Resp.class));

        GrpcServer server = new GrpcServer();
        server.setBufPool(new SimpleFastBufPool());
        server.setMethods(methods);
        server.listen(port);

        edap = new Edap();
        edap.addServer(server);
        edap.run();
        awaitListening(port);
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static void awaitListening(int port) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException e) {
                TimeUnit.MILLISECONDS.sleep(20);
            }
        }
        throw new IllegalStateException("端口 " + port + " 未在 5s 内开始监听");
    }

    private static final class Frame {
        final int type;
        final int flags;
        final int streamId;
        final byte[] payload;

        Frame(int type, int flags, int streamId, byte[] payload) {
            this.type = type;
            this.flags = flags;
            this.streamId = streamId;
            this.payload = payload;
        }
    }
}
