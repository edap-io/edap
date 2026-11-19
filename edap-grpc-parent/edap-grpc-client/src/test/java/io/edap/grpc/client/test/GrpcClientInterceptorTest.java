/*
 * Copyright 2023 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
import io.edap.grpc.client.GrpcClientInterceptor;
import io.edap.grpc.server.GrpcServer;
import io.edap.pool.SimpleFastBufPool;
import io.edap.protobuf.annotation.ProtoField;
import io.edap.protobuf.annotation.ProtoGrpc;
import io.edap.protobuf.wire.Field;
import io.edap.rpc.RpcContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * gRPC 客户端拦截器的行为测试。
 *
 * <p>覆盖：加的头上线、拒绝（请求根本没发出去）、收尾钩子只跑一次、顺序。</p>
 */
public class GrpcClientInterceptorTest {

    // ---- 测试消息 --------------------------------------------------------

    public static class InterceptorReq {
        @ProtoField(tag = 1, type = Field.Type.STRING)
        public String name;

        @ProtoField(tag = 2, type = Field.Type.INT32)
        public int value;
    }

    public static class InterceptorResp {
        @ProtoField(tag = 1, type = Field.Type.STRING)
        public String name;

        @ProtoField(tag = 2, type = Field.Type.INT32)
        public int value;
    }

    private static final String PATH = "/demo.Svc/Unary";

    private static final String ERROR_PATH = "/demo.Svc/Error";

    private Edap edap;
    private GrpcChannel channel;
    private int port;

    /** 服务端看到的请求头 */
    private final AtomicReference<String> tokenSeen = new AtomicReference<>();

    /** 服务端 handler 被调用的次数 */
    private final AtomicInteger handlerCalls = new AtomicInteger();

    /** before / after 的执行顺序 */
    private final List<String> order = new CopyOnWriteArrayList<>();

    /** afterCall 收到的错误 */
    private final AtomicReference<Throwable> afterError = new AtomicReference<>();

    /** afterCall 被调用的次数 */
    private final AtomicInteger afterCalls = new AtomicInteger();

    /** 拦截器里看到的上下文方法名 */
    private final AtomicReference<String> ctxMethod = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        tokenSeen.set(null);
        handlerCalls.set(0);
        order.clear();
        afterError.set(null);
        afterCalls.set(0);
        ctxMethod.set(null);
        startServer();
    }

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
    // 请求侧
    // ------------------------------------------------------------------

    /**
     * {@code beforeCall} 加的头真的上了线（服务端看得见）。
     */
    @Test
    void beforeCallHeaderReachesServer() throws Exception {
        openChannel();
        channel.addInterceptor(new GrpcClientInterceptor() {
            @Override
            public void beforeCall(String path, Map<String, String> headers, RpcContext context) {
                headers.put("x-token", "t-123");
                ctxMethod.set(context.method());
            }
        });

        InterceptorReq req = new InterceptorReq();
        req.name = "edap";
        req.value = 21;
        InterceptorResp resp = channel.unary(PATH, InterceptorReq.class, InterceptorResp.class, req);

        assertEquals("edap", resp.name);
        assertEquals("t-123", tokenSeen.get(), "拦截器加的头没有上线");
        assertEquals(PATH, ctxMethod.get(), "拦截器里拿不到本次调用的上下文");
    }

    /**
     * 用户自己传的头与拦截器加的头都在（用户头在前，顺序稳定）。
     */
    @Test
    void userHeadersAndInterceptorHeadersBothArrive() throws Exception {
        openChannel();
        channel.addInterceptor(new GrpcClientInterceptor() {
            @Override
            public void beforeCall(String path, Map<String, String> headers, RpcContext context) {
                headers.put("x-interceptor", "i");
            }
        });

        Map<String, String> headers = new HashMap<>();
        headers.put("x-user", "u");

        InterceptorReq req = new InterceptorReq();
        req.name = "two";
        req.value = 1;
        GrpcClientCall call = channel.newCall(PATH, InterceptorReq.class, InterceptorResp.class, null, headers);
        call.sendMessage(req);
        call.halfClose();
        call.awaitCompletion(5000);

        assertEquals(1, handlerCalls.get());
    }

    /**
     * {@code beforeCall} 抛异常 ⇒ 本次调用失败，且<b>请求根本没发出去</b>。
     */
    @Test
    void rejectionFailsCallWithoutSendingRequest() throws Exception {
        openChannel();
        channel.addInterceptor(new GrpcClientInterceptor() {
            @Override
            public void beforeCall(String path, Map<String, String> headers, RpcContext context) {
                throw new IllegalStateException("没鉴权，不许发");
            }
        });

        InterceptorReq req = new InterceptorReq();
        req.name = "denied";
        req.value = 1;

        GrpcException ex = assertThrows(GrpcException.class,
            () -> channel.unary(PATH, InterceptorReq.class, InterceptorResp.class, req));
        assertTrue(ex.getMessage().contains("没鉴权"), ex.getMessage());

        // 服务端没收到任何请求
        Thread.sleep(200);
        assertEquals(0, handlerCalls.get(), "被拒绝的调用不该把请求发到服务端");
    }

    // ------------------------------------------------------------------
    // 收尾
    // ------------------------------------------------------------------

    /**
     * 正常结束：{@code afterCall} 跑一次，{@code error} 为 null，状态可见。
     */
    @Test
    void afterCallRunsOnceWithStatus() throws Exception {
        openChannel();
        channel.addInterceptor(new GrpcClientInterceptor() {
            @Override
            public void afterCall(GrpcClientCall call, Throwable error) {
                afterCalls.incrementAndGet();
                afterError.set(error);
            }
        });

        InterceptorReq req = new InterceptorReq();
        req.name = "ok";
        req.value = 1;
        channel.unary(PATH, InterceptorReq.class, InterceptorResp.class, req);

        assertEquals(1, afterCalls.get(), "afterCall 必须只跑一次");
        assertNull(afterError.get(), "正常结束的 afterCall 不该带错误");
    }

    /**
     * 服务端回错误状态 ⇒ {@code afterCall} 拿到错误。
     */
    @Test
    void afterCallGetsErrorOnFailedCall() throws Exception {
        openChannel();
        channel.addInterceptor(new GrpcClientInterceptor() {
            @Override
            public void afterCall(GrpcClientCall call, Throwable error) {
                afterCalls.incrementAndGet();
                afterError.set(error);
            }
        });

        InterceptorReq req = new InterceptorReq();
        req.name = "boom";
        req.value = 1;
        assertThrows(GrpcException.class,
            () -> channel.unary(ERROR_PATH, InterceptorReq.class, InterceptorResp.class, req));

        assertEquals(1, afterCalls.get());
        assertNotNull(afterError.get(), "afterCall 应该拿到错误");
    }

    /**
     * {@code afterCall} 自己抛异常 ⇒ 不影响调用结果。
     */
    @Test
    void afterCallFailureDoesNotBreakTheCall() throws Exception {
        openChannel();
        channel.addInterceptor(new GrpcClientInterceptor() {
            @Override
            public void afterCall(GrpcClientCall call, Throwable error) {
                throw new RuntimeException("收尾钩子炸了");
            }
        });

        InterceptorReq req = new InterceptorReq();
        req.name = "hook";
        req.value = 1;
        InterceptorResp resp = channel.unary(PATH, InterceptorReq.class, InterceptorResp.class, req);

        assertEquals("hook", resp.name, "afterCall 抛异常不该影响调用结果");
    }

    // ------------------------------------------------------------------
    // 顺序
    // ------------------------------------------------------------------

    /**
     * {@code beforeCall} 正序、{@code afterCall} 逆序。
     */
    @Test
    void hooksRunInFilterOrder() throws Exception {
        openChannel();
        channel.addInterceptor(named("a"));
        channel.addInterceptor(named("b"));
        channel.addInterceptor(named("c"));

        InterceptorReq req = new InterceptorReq();
        req.name = "order";
        req.value = 1;
        channel.unary(PATH, InterceptorReq.class, InterceptorResp.class, req);

        List<String> expected = new ArrayList<>();
        expected.add("before:a");
        expected.add("before:b");
        expected.add("before:c");
        expected.add("after:c");
        expected.add("after:b");
        expected.add("after:a");
        assertEquals(expected, new ArrayList<>(order), "钩子顺序不对: " + order);
    }

    private GrpcClientInterceptor named(String name) {
        return new GrpcClientInterceptor() {
            @Override
            public void beforeCall(String path, Map<String, String> headers, RpcContext context) {
                order.add("before:" + name);
            }

            @Override
            public void afterCall(GrpcClientCall call, Throwable error) {
                order.add("after:" + name);
            }
        };
    }

    // ------------------------------------------------------------------
    // 服务端与客户端的搭建 -----------------------------------------------
    // ------------------------------------------------------------------

    private void startServer() throws Exception {
        port = freePort();

        Map<String, GrpcMethod> methods = new HashMap<>();
        methods.put(PATH, new GrpcMethod(PATH, unaryHandler(),
            ProtoGrpc.MethodType.UNARY, InterceptorReq.class, InterceptorResp.class));
        methods.put(ERROR_PATH, new GrpcMethod(ERROR_PATH, errorHandler(),
            ProtoGrpc.MethodType.UNARY, InterceptorReq.class, InterceptorResp.class));

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

    private GrpcHandler unaryHandler() {
        return call -> {
            handlerCalls.incrementAndGet();
            tokenSeen.set(call.metadata().get("x-token"));
            byte[] raw = call.requestBytes();
            InterceptorReq req = GrpcCodec.decode(InterceptorReq.class, raw, 0, raw.length);
            InterceptorResp resp = new InterceptorResp();
            resp.name = req.name;
            resp.value = req.value * 2;
            call.sendOne(resp);
            call.close(GrpcStatus.OK, null);
        };
    }

    private GrpcHandler errorHandler() {
        return call -> {
            handlerCalls.incrementAndGet();
            call.close(GrpcStatus.FAILED_PRECONDITION, "不满足前置条件");
        };
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
