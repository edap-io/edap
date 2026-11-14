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
import io.edap.grpc.GrpcCodec;
import io.edap.grpc.GrpcException;
import io.edap.grpc.GrpcHandler;
import io.edap.grpc.GrpcMethod;
import io.edap.grpc.GrpcStatus;
import io.edap.grpc.client.GrpcChannel;
import io.edap.grpc.client.GrpcClientCall;
import io.edap.grpc.server.GrpcServer;
import io.edap.pool.SimpleFastBufPool;
import io.edap.protobuf.annotation.ProtoField;
import io.edap.protobuf.annotation.ProtoGrpc;
import io.edap.protobuf.wire.Field;
import io.edap.rpc.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * gRPC 客户端的端到端测试 —— 真的起一个 {@link GrpcServer}，用 {@link GrpcChannel} 打过去。
 *
 * <p>覆盖四种调用形态 + 错误状态 + 大消息（跨流控窗口）。</p>
 */
public class GrpcClientEndToEndTest {

    // ---- 测试消息 --------------------------------------------------------

    public static class Req {
        @ProtoField(tag = 1, type = Field.Type.STRING)
        public String name;

        @ProtoField(tag = 2, type = Field.Type.INT32)
        public int value;
    }

    public static class Resp {
        @ProtoField(tag = 1, type = Field.Type.STRING)
        public String name;

        @ProtoField(tag = 2, type = Field.Type.INT32)
        public int value;

        @ProtoField(tag = 3, type = Field.Type.STRING)
        public String payload;
    }

    private static final String UNARY_PATH = "/demo.Svc/Unary";
    private static final String SERVER_STREAM_PATH = "/demo.Svc/ServerStream";
    private static final String CLIENT_STREAM_PATH = "/demo.Svc/ClientStream";
    private static final String BIDI_PATH = "/demo.Svc/Bidi";
    private static final String ERROR_PATH = "/demo.Svc/Error";

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

    // ---- 用例 ------------------------------------------------------------

    @Test
    void unaryCallReturnsResponse() throws Exception {
        startServer();
        openChannel();

        Req req = new Req();
        req.name = "edap";
        req.value = 21;

        Resp resp = channel.unary(UNARY_PATH, Req.class, Resp.class, req);

        assertNotNull(resp);
        assertEquals("edap", resp.name);
        assertEquals(42, resp.value, "服务端把 value 翻倍");
    }

    @Test
    void unaryCallWithDerivedRequestType() throws Exception {
        startServer();
        openChannel();

        Req req = new Req();
        req.name = "derived";
        req.value = 1;

        Resp resp = channel.unary(UNARY_PATH, Resp.class, req);

        assertEquals("derived", resp.name);
        assertEquals(2, resp.value);
    }

    @Test
    void serverStreamDeliversEveryMessage() throws Exception {
        startServer();
        openChannel();

        List<Resp> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);

        Req req = new Req();
        req.name = "stream";
        req.value = 1;

        GrpcClientCall call = channel.serverStream(SERVER_STREAM_PATH,
                Req.class, Resp.class, req, new StreamObserver<Resp>() {
                    @Override
                    public void onNext(Resp value) {
                        received.add(value);
                    }

                    @Override
                    public void onError(Throwable t) {
                        done.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        done.countDown();
                    }
                });

        assertTrue(done.await(5, TimeUnit.SECONDS), "服务端流式必须在 5s 内结束");
        call.awaitCompletion(5000);
        assertEquals(3, received.size(), "服务端推了 3 条消息");
        assertEquals("msg-0", received.get(0).name);
        assertEquals("msg-2", received.get(2).name);
        assertEquals(GrpcStatus.OK, call.status());
    }

    @Test
    void clientStreamSendsEveryMessage() throws Exception {
        startServer();
        openChannel();

        List<Resp> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);

        GrpcClientCall call = channel.clientStream(CLIENT_STREAM_PATH, Req.class, Resp.class,
                new StreamObserver<Resp>() {
                    @Override
                    public void onNext(Resp value) {
                        received.add(value);
                    }

                    @Override
                    public void onError(Throwable t) {
                        done.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        done.countDown();
                    }
                });

        StreamObserver<Object> requestObserver = call.requestObserver();
        for (int i = 1; i <= 3; i++) {
            Req req = new Req();
            req.name = "n" + i;
            req.value = i;
            requestObserver.onNext(req);
        }
        requestObserver.onCompleted();

        assertTrue(done.await(5, TimeUnit.SECONDS), "客户端流式必须在 5s 内结束");
        call.awaitCompletion(5000);
        assertEquals(1, received.size());
        assertEquals("sum:6", received.get(0).name, "服务端把 1+2+3 收齐后回一条");
    }

    @Test
    void bidiStreamsBothWays() throws Exception {
        startServer();
        openChannel();

        List<Resp> received = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);

        GrpcClientCall call = channel.bidi(BIDI_PATH, Req.class, Resp.class, new StreamObserver<Resp>() {
            @Override
            public void onNext(Resp value) {
                received.add(value);
            }

            @Override
            public void onError(Throwable t) {
                done.countDown();
            }

            @Override
            public void onCompleted() {
                done.countDown();
            }
        });

        StreamObserver<Object> requestObserver = call.requestObserver();
        for (int i = 1; i <= 2; i++) {
            Req req = new Req();
            req.name = "b" + i;
            req.value = i;
            requestObserver.onNext(req);
        }
        requestObserver.onCompleted();

        assertTrue(done.await(5, TimeUnit.SECONDS), "双向流式必须在 5s 内结束");
        call.awaitCompletion(5000);
        assertEquals(2, received.size(), "每条请求对应一条响应");
        assertEquals("echo:b1", received.get(0).name);
        assertEquals("echo:b2", received.get(1).name);
    }

    @Test
    void errorStatusIsPropagatedToCaller() throws Exception {
        startServer();
        openChannel();

        Req req = new Req();
        req.name = "boom";
        req.value = 1;

        GrpcException ex = assertThrows(GrpcException.class,
                () -> channel.unary(ERROR_PATH, Req.class, Resp.class, req));
        assertTrue(ex.getMessage().contains("FAILED_PRECONDITION"), "错误信息里要带状态码: " + ex.getMessage());
    }

    @Test
    void unknownPathFailsWithUnimplemented() throws Exception {
        startServer();
        openChannel();

        Req req = new Req();
        req.name = "nobody";
        req.value = 1;

        GrpcException ex = assertThrows(GrpcException.class,
                () -> channel.unary("/demo.Svc/NoSuchMethod", Req.class, Resp.class, req));
        assertTrue(ex.getMessage().contains("UNIMPLEMENTED"), ex.getMessage());
    }

    @Test
    void largeMessageCrossesFlowControlWindow() throws Exception {
        startServer();
        openChannel();

        // 200KB —— 远超默认的 64KB 流控窗口，必须靠 WINDOW_UPDATE 才能发完
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200 * 1024; i++) {
            sb.append('x');
        }
        Req req = new Req();
        req.name = "big";
        req.value = 1;

        Resp resp = channel.unary(UNARY_PATH, Req.class, Resp.class, req);
        assertEquals("big", resp.name);

        // 单独验一条大响应：服务端回一条大消息
        GrpcClientCall call = channel.newCall("/demo.Svc/Large", Req.class, Resp.class, null);
        call.sendMessage(req);
        call.halfClose();
        call.awaitCompletion(10000);
        List<Object> responses = call.responses();
        assertEquals(1, responses.size());
        Resp large = (Resp) responses.get(0);
        assertEquals(200 * 1024, large.payload.length(), "大响应必须完整送达");
    }

    @Test
    void concurrentCallsOnSameChannel() throws Exception {
        startServer();
        openChannel();

        final int n = 8;
        CountDownLatch done = new CountDownLatch(n);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < n; i++) {
            final int idx = i;
            Thread t = new Thread(() -> {
                try {
                    Req req = new Req();
                    req.name = "c" + idx;
                    req.value = idx;
                    Resp resp = channel.unary(UNARY_PATH, Req.class, Resp.class, req);
                    if (!("c" + idx).equals(resp.name)) {
                        errors.add("响应串了: " + resp.name);
                    }
                } catch (Throwable e) {
                    errors.add(e.toString());
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }
        assertTrue(done.await(10, TimeUnit.SECONDS), "并发调用必须在 10s 内结束");
        assertTrue(errors.isEmpty(), "并发调用出错: " + errors);
    }

    @Test
    void sequentialUnaryCallsBeyondServerMaxConcurrentStreamsSucceed() throws Exception {
        startServer();
        openChannel();

        // ★ 回归：服务端 HTTP/2 的 MAX_CONCURRENT_STREAMS 是 128。
        //   gRPC 的响应是在 DATA 帧处理过程中同步写出的，那时对端还没半关闭；
        //   服务端如果不在标记 END_STREAM 之后再查一次「两侧都关闭」，
        //   流就会永久留在连接里 —— 累积到 128 条之后新流全被 REFUSED_STREAM。
        //   顺序发起 200 次调用正好越过那道坎。
        final int n = 200;
        for (int i = 0; i < n; i++) {
            Req req = new Req();
            req.name = "seq" + i;
            req.value = i;

            Resp resp = channel.unary(UNARY_PATH, Req.class, Resp.class, req);
            assertEquals("seq" + i, resp.name, "第 " + i + " 次调用的响应不对");
            assertEquals(i * 2, resp.value);
        }
    }

    // ---- 服务端与客户端的搭建 -------------------------------------------

    private void startServer() throws Exception {
        port = freePort();

        Map<String, GrpcMethod> methods = new HashMap<>();
        methods.put(UNARY_PATH, new GrpcMethod(UNARY_PATH, unaryHandler(),
                ProtoGrpc.MethodType.UNARY, Req.class, Resp.class));
        methods.put(SERVER_STREAM_PATH, new GrpcMethod(SERVER_STREAM_PATH, serverStreamHandler(),
                ProtoGrpc.MethodType.SERVER_STREAM, Req.class, Resp.class));
        methods.put(CLIENT_STREAM_PATH, new GrpcMethod(CLIENT_STREAM_PATH, clientStreamHandler(),
                ProtoGrpc.MethodType.CLIENT_STREAM, Req.class, Resp.class));
        methods.put(BIDI_PATH, new GrpcMethod(BIDI_PATH, bidiHandler(),
                ProtoGrpc.MethodType.BIDIRECTIONAL, Req.class, Resp.class));
        methods.put(ERROR_PATH, new GrpcMethod(ERROR_PATH, errorHandler(),
                ProtoGrpc.MethodType.UNARY, Req.class, Resp.class));
        methods.put("/demo.Svc/Large", new GrpcMethod("/demo.Svc/Large", largeHandler(),
                ProtoGrpc.MethodType.UNARY, Req.class, Resp.class));

        GrpcServer server = new GrpcServer();
        server.setBufPool(new SimpleFastBufPool());
        server.setMethods(methods);
        server.listen(port);

        edap = new Edap();
        edap.addServer(server);
        edap.run();
        awaitListening(port);
    }

    private void openChannel() throws IOException {
        channel = new GrpcChannel("127.0.0.1", port, 10_000, 5_000);
    }

    private static GrpcHandler unaryHandler() {
        return call -> {
            Req req = decodeReq(call);
            Resp resp = new Resp();
            resp.name = req.name;
            resp.value = req.value * 2;
            call.sendOne(resp);
            call.close(GrpcStatus.OK, null);
        };
    }

    private static GrpcHandler serverStreamHandler() {
        return call -> {
            decodeReq(call);
            for (int i = 0; i < 3; i++) {
                Resp resp = new Resp();
                resp.name = "msg-" + i;
                resp.value = i;
                call.sendOne(resp);
            }
            call.close(GrpcStatus.OK, null);
        };
    }

    private static GrpcHandler clientStreamHandler() {
        return call -> {
            AtomicInteger sum = new AtomicInteger();
            call.setRequestObserver(new StreamObserver<Object>() {
                @Override
                public void onNext(Object value) {
                    sum.addAndGet(((Req) value).value);
                }

                @Override
                public void onError(Throwable t) {
                }

                @Override
                public void onCompleted() {
                    Resp resp = new Resp();
                    resp.name = "sum:" + sum.get();
                    resp.value = sum.get();
                    call.sendOne(resp);
                    call.close(GrpcStatus.OK, null);
                }
            });
        };
    }

    private static GrpcHandler bidiHandler() {
        return call -> {
            call.setRequestObserver(new StreamObserver<Object>() {
                @Override
                public void onNext(Object value) {
                    Req req = (Req) value;
                    Resp resp = new Resp();
                    resp.name = "echo:" + req.name;
                    resp.value = req.value;
                    call.sendOne(resp);
                }

                @Override
                public void onError(Throwable t) {
                }

                @Override
                public void onCompleted() {
                    call.close(GrpcStatus.OK, null);
                }
            });
        };
    }

    private static GrpcHandler errorHandler() {
        return call -> {
            decodeReq(call);
            call.close(GrpcStatus.FAILED_PRECONDITION, "不满足前置条件");
        };
    }

    private static GrpcHandler largeHandler() {
        return call -> {
            decodeReq(call);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 200 * 1024; i++) {
                sb.append('x');
            }
            Resp resp = new Resp();
            resp.name = "large";
            resp.payload = sb.toString();
            call.sendOne(resp);
            call.close(GrpcStatus.OK, null);
        };
    }

    private static Req decodeReq(GrpcCall call) {
        byte[] raw = call.requestBytes();
        return GrpcCodec.decode(Req.class, raw, 0, raw.length);
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
}
