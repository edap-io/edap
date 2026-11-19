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

package io.edap.grpc.server.test;

import io.edap.grpc.GrpcCall;
import io.edap.grpc.GrpcCodec;
import io.edap.grpc.GrpcHandler;
import io.edap.grpc.GrpcMethod;
import io.edap.grpc.GrpcServerInterceptor;
import io.edap.grpc.GrpcStatus;
import io.edap.grpc.GrpcStatusException;
import io.edap.protobuf.annotation.ProtoGrpc;
import io.edap.rpc.RpcContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * gRPC 服务端拦截器的行为测试。
 *
 * <p>覆盖：请求头可见、拒绝（业务不执行 + 状态码）、收尾钩子只跑一次、
 * 顺序（before 正序 / after 逆序）、响应头能改。</p>
 */
public class GrpcServerInterceptorTest extends GrpcTestBase {

    private static final String PATH = "/test.Svc/Unary";

    /** 业务 handler 被调用的次数 */
    private final AtomicInteger handlerCalls = new AtomicInteger();

    /** 拦截器看到的请求头 */
    private final AtomicReference<String> tokenSeen = new AtomicReference<>();

    /** 拦截器写进响应头的值 */
    private final AtomicReference<String> responseHeaderWritten = new AtomicReference<>();

    /** before / after 的执行顺序 */
    private final List<String> order = new CopyOnWriteArrayList<>();

    /** afterCall 收到的错误 */
    private final AtomicReference<Throwable> afterError = new AtomicReference<>();

    /** afterCall 被调用的次数 */
    private final AtomicInteger afterCalls = new AtomicInteger();

    /** 拦截器里看到的上下文方法名 */
    private final AtomicReference<String> ctxMethod = new AtomicReference<>();

    @BeforeEach
    void reset() {
        handlerCalls.set(0);
        tokenSeen.set(null);
        responseHeaderWritten.set(null);
        order.clear();
        afterError.set(null);
        afterCalls.set(0);
        ctxMethod.set(null);
    }

    /**
     * 业务 handler —— 一元调用，正常返回。
     */
    private GrpcHandler okHandler() {
        return call -> {
            handlerCalls.incrementAndGet();
            byte[] raw = call.requestBytes();
            TestMessages.Req req = GrpcCodec.decode(TestMessages.Req.class, raw, 0, raw.length);
            TestMessages.Resp resp = new TestMessages.Resp();
            resp.name = "hello " + req.name;
            resp.value = req.value * 2;
            call.sendOne(resp);
            call.close(GrpcStatus.OK, null);
        };
    }

    private void startWith(GrpcServerInterceptor... interceptors) throws Exception {
        startServer(Collections.singletonMap(PATH, new GrpcMethod(PATH, okHandler(),
            ProtoGrpc.MethodType.UNARY, TestMessages.Req.class, TestMessages.Resp.class)),
            server -> {
                for (GrpcServerInterceptor interceptor : interceptors) {
                    server.addInterceptor(interceptor);
                }
            });
    }

    // ------------------------------------------------------------------
    // 请求侧
    // ------------------------------------------------------------------

    /**
     * {@code beforeCall} 能读到请求头，也能往响应头里写东西。
     */
    @Test
    public void beforeCallSeesMetadataAndCanWriteResponseHeader() throws Exception {
        startWith(new GrpcServerInterceptor() {
            @Override
            public void beforeCall(GrpcCall call) {
                tokenSeen.set(call.metadata().get("x-token"));
                call.responseMetadata().set("x-server", "edap");
            }
        });

        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.start();
            client.sendHeaders(1, PATH, "localhost", Collections.singletonList(
                new io.edap.http.http2.hpack.HpackHeader("x-token", "t-123")));

            TestMessages.Req req = new TestMessages.Req();
            req.name = "edap";
            req.value = 21;
            client.sendMessage(1, GrpcCodec.encode(TestMessages.Req.class, req), true);

            GrpcTestClient.Response response = client.readResponse(1);

            assertEquals("t-123", tokenSeen.get(), "拦截器看不到请求头");
            assertEquals("edap", response.headerValue("x-server"), "响应头没上线: " + response.headers);
            assertEquals("0", response.trailerValue("grpc-status"));
        }
    }

    /**
     * 拦截器里 {@code RpcContext.current()} 拿得到本次调用的上下文。
     */
    @Test
    public void beforeCallRunsInsideRpcContext() throws Exception {
        startWith(new GrpcServerInterceptor() {
            @Override
            public void beforeCall(GrpcCall call) {
                ctxMethod.set(RpcContext.current().method());
            }
        });

        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.start();
            client.sendHeaders(1, PATH, "localhost");

            TestMessages.Req req = new TestMessages.Req();
            req.name = "ctx";
            req.value = 1;
            client.sendMessage(1, GrpcCodec.encode(TestMessages.Req.class, req), true);

            client.readResponse(1);

            assertEquals(PATH, ctxMethod.get(), "拦截器里拿不到本次调用的上下文");
        }
    }

    // ------------------------------------------------------------------
    // 拒绝
    // ------------------------------------------------------------------

    /**
     * 抛 {@link GrpcStatusException} ⇒ 业务不执行，trailers 里回对应状态码。
     */
    @Test
    public void rejectionSkipsHandlerAndReturnsStatus() throws Exception {
        startWith(new GrpcServerInterceptor() {
            @Override
            public void beforeCall(GrpcCall call) {
                throw new GrpcStatusException(GrpcStatus.UNAUTHENTICATED, "缺少 token");
            }
        });

        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.start();
            client.sendHeaders(1, PATH, "localhost");

            GrpcTestClient.Response response = client.readResponse(1);

            assertEquals("16", response.trailerValue("grpc-status"),
                "状态码不对: " + response.trailers);
            // gRPC 规范要求 grpc-message 百分号编码（非可打印 ASCII）
            assertEquals("%E7%BC%BA%E5%B0%91 token", response.trailerValue("grpc-message"),
                "错误描述不对: " + response.trailers);
            assertTrue(response.messages.isEmpty(), "被拒绝的调用不应该有响应消息");
            assertEquals(0, handlerCalls.get(), "被拒绝的调用不应该执行业务");
        }
    }

    /**
     * 抛别的异常 ⇒ {@code INTERNAL}。
     */
    @Test
    public void rejectionWithPlainExceptionMapsToInternal() throws Exception {
        startWith(new GrpcServerInterceptor() {
            @Override
            public void beforeCall(GrpcCall call) {
                throw new IllegalStateException("boom");
            }
        });

        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.start();
            client.sendHeaders(1, PATH, "localhost");

            GrpcTestClient.Response response = client.readResponse(1);

            assertEquals("13", response.trailerValue("grpc-status"),
                "状态码不对: " + response.trailers);
            assertEquals(0, handlerCalls.get(), "被拒绝的调用不应该执行业务");
        }
    }

    /**
     * 拒绝时 {@code afterCall} 也会跑一次，且带着错误。
     */
    @Test
    public void rejectionStillRunsAfterCall() throws Exception {
        startWith(new GrpcServerInterceptor() {
            @Override
            public void beforeCall(GrpcCall call) {
                throw new GrpcStatusException(GrpcStatus.PERMISSION_DENIED, "no");
            }

            @Override
            public void afterCall(GrpcCall call, Throwable error) {
                afterCalls.incrementAndGet();
                afterError.set(error);
            }
        });

        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.start();
            client.sendHeaders(1, PATH, "localhost");
            client.readResponse(1);
        }

        assertEquals(1, afterCalls.get(), "被拒绝的调用也应该跑一次 afterCall");
        assertNotNull(afterError.get(), "afterCall 应该拿到错误");
        assertTrue(afterError.get() instanceof GrpcStatusException);
        assertEquals(GrpcStatus.PERMISSION_DENIED,
            ((GrpcStatusException) afterError.get()).status());
    }

    // ------------------------------------------------------------------
    // 收尾
    // ------------------------------------------------------------------

    /**
     * 正常结束：{@code afterCall} 跑一次，{@code error} 为 null。
     */
    @Test
    public void afterCallRunsOnceWithNullErrorOnSuccess() throws Exception {
        startWith(new GrpcServerInterceptor() {
            @Override
            public void afterCall(GrpcCall call, Throwable error) {
                afterCalls.incrementAndGet();
                afterError.set(error);
            }
        });

        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.start();
            client.sendHeaders(1, PATH, "localhost");

            TestMessages.Req req = new TestMessages.Req();
            req.name = "ok";
            req.value = 1;
            client.sendMessage(1, GrpcCodec.encode(TestMessages.Req.class, req), true);

            client.readResponse(1);
        }

        assertEquals(1, afterCalls.get(), "afterCall 必须只跑一次");
        assertNull(afterError.get(), "正常结束的 afterCall 不该带错误");
        assertEquals(1, handlerCalls.get());
    }

    /**
     * 业务抛异常 ⇒ {@code afterCall} 拿到错误。
     */
    @Test
    public void afterCallGetsErrorWhenHandlerThrows() throws Exception {
        startServer(Collections.singletonMap(PATH, new GrpcMethod(PATH,
            (GrpcHandler) call -> {
                handlerCalls.incrementAndGet();
                throw new IllegalStateException("业务炸了");
            }, ProtoGrpc.MethodType.UNARY, TestMessages.Req.class, TestMessages.Resp.class)),
            server -> server.addInterceptor(new GrpcServerInterceptor() {
                @Override
                public void afterCall(GrpcCall call, Throwable error) {
                    afterCalls.incrementAndGet();
                    afterError.set(error);
                }
            }));

        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.start();
            client.sendHeaders(1, PATH, "localhost");

            TestMessages.Req req = new TestMessages.Req();
            req.name = "boom";
            req.value = 1;
            client.sendMessage(1, GrpcCodec.encode(TestMessages.Req.class, req), true);

            GrpcTestClient.Response response = client.readResponse(1);

            assertEquals("2", response.trailerValue("grpc-status"),
                "业务异常应该回 UNKNOWN: " + response.trailers);
        }

        assertEquals(1, afterCalls.get());
        assertNotNull(afterError.get(), "afterCall 应该拿到错误");
        assertTrue(afterError.get() instanceof GrpcStatusException);
        assertEquals(GrpcStatus.UNKNOWN, ((GrpcStatusException) afterError.get()).status());
    }

    /**
     * {@code afterCall} 自己抛异常 ⇒ 不影响调用结果。
     */
    @Test
    public void afterCallFailureDoesNotBreakTheCall() throws Exception {
        startWith(new GrpcServerInterceptor() {
            @Override
            public void afterCall(GrpcCall call, Throwable error) {
                throw new RuntimeException("收尾钩子炸了");
            }
        });

        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.start();
            client.sendHeaders(1, PATH, "localhost");

            TestMessages.Req req = new TestMessages.Req();
            req.name = "hook";
            req.value = 1;
            client.sendMessage(1, GrpcCodec.encode(TestMessages.Req.class, req), true);

            GrpcTestClient.Response response = client.readResponse(1);

            assertEquals("0", response.trailerValue("grpc-status"),
                "afterCall 抛异常不该影响调用结果: " + response.trailers);
        }
    }

    // ------------------------------------------------------------------
    // 顺序
    // ------------------------------------------------------------------

    /**
     * {@code beforeCall} 正序、{@code afterCall} 逆序。
     */
    @Test
    public void hooksRunInFilterOrder() throws Exception {
        startWith(
            named("a"),
            named("b"),
            named("c"));

        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.start();
            client.sendHeaders(1, PATH, "localhost");

            TestMessages.Req req = new TestMessages.Req();
            req.name = "order";
            req.value = 1;
            client.sendMessage(1, GrpcCodec.encode(TestMessages.Req.class, req), true);

            client.readResponse(1);
        }

        List<String> expected = new ArrayList<>();
        expected.add("before:a");
        expected.add("before:b");
        expected.add("before:c");
        expected.add("after:c");
        expected.add("after:b");
        expected.add("after:a");
        assertEquals(expected, new ArrayList<>(order), "钩子顺序不对: " + order);
    }

    private GrpcServerInterceptor named(String name) {
        return new GrpcServerInterceptor() {
            @Override
            public void beforeCall(GrpcCall call) {
                order.add("before:" + name);
            }

            @Override
            public void afterCall(GrpcCall call, Throwable error) {
                order.add("after:" + name);
            }
        };
    }
}
