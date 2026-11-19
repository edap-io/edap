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

package io.edap.erpc.server;

import io.edap.erpc.ErpcServerInterceptor;
import io.edap.erpc.ErpcStatus;
import io.edap.erpc.ErpcStatusException;
import io.edap.erpc.ErpcMethod;
import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.codec.ErpcCodecIds;
import io.edap.erpc.codec.ErpcCodecRegistry;
import io.edap.erpc.codec.ErpcWriter;
import io.edap.erpc.frame.ErpcFrameType;
import io.edap.erpc.ErpcRequest;
import io.edap.erpc.ErpcResponse;
import io.edap.rpc.RpcChannelContext;
import io.edap.rpc.RpcContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * eRPC 服务端拦截器的行为测试。
 *
 * <p>覆盖：请求可见、拒绝（业务不执行 + 状态码）、收尾钩子只跑一次、
 * 顺序（before 正序 / after 逆序）、上下文绑定。</p>
 */
public class ErpcServerInterceptorTest {

    private static final int METHOD_ECHO = 2001;
    private static final int METHOD_BOOM = 2002;

    private ErpcTestServer server;

    /** 业务 handler 被调用的次数 */
    private final AtomicInteger handlerCalls = new AtomicInteger();

    /** 拦截器看到的 methodId */
    private final AtomicReference<Integer> seenMethodId = new AtomicReference<>();

    /** 拦截器里 {@code RpcContext.current()} 的方法名 */
    private final AtomicReference<String> ctxMethod = new AtomicReference<>();

    /** afterCall 被调用的次数 */
    private final AtomicInteger afterCalls = new AtomicInteger();

    /** afterCall 收到的错误 */
    private final AtomicReference<Throwable> afterError = new AtomicReference<>();

    /** before / after 的执行顺序 */
    private final List<String> order = new CopyOnWriteArrayList<>();

    @BeforeEach
    void reset() {
        handlerCalls.set(0);
        seenMethodId.set(null);
        ctxMethod.set(null);
        afterCalls.set(0);
        afterError.set(null);
        order.clear();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    // ------------------------------------------------------------------
    // 请求侧
    // ------------------------------------------------------------------

    /**
     * {@code beforeCall} 能拿到本次请求（methodId / traceId / 上下文）。
     */
    @Test
    public void beforeCallSeesTheRequest() throws Exception {
        start(new ErpcServerInterceptor() {
            @Override
            public void beforeCall(ErpcRequest request) {
                seenMethodId.set(request.getMethodId());
                ctxMethod.set(RpcContext.current().method());
            }
        });

        call(METHOD_ECHO);

        assertEquals(METHOD_ECHO, seenMethodId.get(), "拦截器看不到 methodId");
        assertNotNull(ctxMethod.get(), "拦截器里拿不到本次调用的上下文");
    }

    /**
     * 拦截器能改 traceId（eRPC 没有请求头通道，只能碰 traceId / 超时 / 属性）。
     */
    @Test
    public void beforeCallCanChangeTraceId() throws Exception {
        AtomicReference<Long> traceIdSeen = new AtomicReference<>();
        server = ErpcTestServer.start(methods(), s -> {
            s.addInterceptor(new ErpcServerInterceptor() {
                @Override
                public void beforeCall(ErpcRequest request) {
                    request.setTraceId(4242L);
                }
            });
            s.addInterceptor(new ErpcServerInterceptor() {
                @Override
                public void beforeCall(ErpcRequest request) {
                    traceIdSeen.set(request.getTraceId());
                }
            });
        });

        call(METHOD_ECHO);

        assertEquals(4242L, traceIdSeen.get(), "拦截器改的 traceId 没生效");
    }

    // ------------------------------------------------------------------
    // 拒绝
    // ------------------------------------------------------------------

    /**
     * 抛 {@link ErpcStatusException} ⇒ 业务不执行，ERROR 帧里回对应状态码。
     */
    @Test
    public void rejectionSkipsHandlerAndReturnsStatus() throws Exception {
        start(new ErpcServerInterceptor() {
            @Override
            public void beforeCall(ErpcRequest request) {
                throw new ErpcStatusException(ErpcStatus.UNAUTHENTICATED, "缺少 token");
            }
        });

        ErpcCodec pb = serializer("protobuf");
        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            byte[] payload = bytesOf(pb, new ErpcTestDto("x", 1));
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_ECHO, 0, 1, 0L, payload);

            ErpcRawClient.RawFrame err = raw.readFrame();
            assertEquals(ErpcFrameType.ERROR.getValue(), err.header.getType(),
                "被拒绝的调用应该回 ERROR 帧");
            io.edap.erpc.RpcException body = pb.decode(err.payload, 0, err.payload.length,
                io.edap.erpc.RpcException.class);
            assertEquals(ErpcStatus.UNAUTHENTICATED.code(), body.getStatus(),
                "状态码不对: " + body.getStatus());
            assertEquals("缺少 token", body.getMessage(), "错误描述不对: " + body.getMessage());
        }

        assertEquals(0, handlerCalls.get(), "被拒绝的调用不应该执行业务");
    }

    /**
     * 抛别的异常 ⇒ {@link ErpcStatus#INTERNAL}。
     */
    @Test
    public void rejectionWithPlainExceptionMapsToInternal() throws Exception {
        start(new ErpcServerInterceptor() {
            @Override
            public void beforeCall(ErpcRequest request) {
                throw new IllegalStateException("boom");
            }
        });

        ErpcCodec pb = serializer("protobuf");
        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            byte[] payload = bytesOf(pb, new ErpcTestDto("x", 1));
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_ECHO, 0, 1, 0L, payload);

            ErpcRawClient.RawFrame err = raw.readFrame();
            assertEquals(ErpcFrameType.ERROR.getValue(), err.header.getType());
            io.edap.erpc.RpcException body = pb.decode(err.payload, 0, err.payload.length,
                io.edap.erpc.RpcException.class);
            assertEquals(ErpcStatus.INTERNAL.code(), body.getStatus(),
                "状态码不对: " + body.getStatus());
        }

        assertEquals(0, handlerCalls.get(), "被拒绝的调用不应该执行业务");
    }

    /**
     * 拒绝时 {@code afterCall} 也会跑一次，且带着错误。
     */
    @Test
    public void rejectionStillRunsAfterCall() throws Exception {
        start(new ErpcServerInterceptor() {
            @Override
            public void beforeCall(ErpcRequest request) {
                throw new ErpcStatusException(ErpcStatus.PERMISSION_DENIED, "no");
            }

            @Override
            public void afterCall(ErpcRequest request, ErpcResponse response, Throwable error) {
                afterCalls.incrementAndGet();
                afterError.set(error);
            }
        });

        call(METHOD_ECHO);
        awaitAfterCalls(1);

        assertEquals(1, afterCalls.get(), "被拒绝的调用也应该跑一次 afterCall");
        assertNotNull(afterError.get(), "afterCall 应该拿到错误");
        assertTrue(afterError.get() instanceof ErpcStatusException);
        assertEquals(ErpcStatus.PERMISSION_DENIED, ((ErpcStatusException) afterError.get()).status());
    }

    // ------------------------------------------------------------------
    // 收尾
    // ------------------------------------------------------------------

    /**
     * 正常结束：{@code afterCall} 跑一次，{@code error} 为 null。
     */
    @Test
    public void afterCallRunsOnceWithNullErrorOnSuccess() throws Exception {
        start(new ErpcServerInterceptor() {
            @Override
            public void afterCall(ErpcRequest request, ErpcResponse response, Throwable error) {
                afterCalls.incrementAndGet();
                afterError.set(error);
            }
        });

        call(METHOD_ECHO);
        awaitAfterCalls(1);

        assertEquals(1, afterCalls.get(), "afterCall 必须只跑一次");
        assertNull(afterError.get(), "正常结束的 afterCall 不该带错误");
        assertEquals(1, handlerCalls.get());
    }

    /**
     * 业务抛异常 ⇒ {@code afterCall} 拿到错误。
     */
    @Test
    public void afterCallGetsErrorWhenHandlerThrows() throws Exception {
        start(new ErpcServerInterceptor() {
            @Override
            public void afterCall(ErpcRequest request, ErpcResponse response, Throwable error) {
                afterCalls.incrementAndGet();
                afterError.set(error);
            }
        });

        call(METHOD_BOOM);
        awaitAfterCalls(1);

        assertEquals(1, afterCalls.get());
        assertNotNull(afterError.get(), "afterCall 应该拿到错误");
    }

    /**
     * {@code afterCall} 自己抛异常 ⇒ 不影响调用结果。
     */
    @Test
    public void afterCallFailureDoesNotBreakTheCall() throws Exception {
        start(new ErpcServerInterceptor() {
            @Override
            public void afterCall(ErpcRequest request, ErpcResponse response, Throwable error) {
                throw new RuntimeException("收尾钩子炸了");
            }
        });

        call(METHOD_ECHO);

        assertEquals(1, handlerCalls.get(), "afterCall 抛异常不该影响调用结果");
    }

    // ------------------------------------------------------------------
    // 顺序
    // ------------------------------------------------------------------

    /**
     * {@code beforeCall} 正序、{@code afterCall} 逆序。
     */
    @Test
    public void hooksRunInFilterOrder() throws Exception {
        start(named("a"), named("b"), named("c"));

        call(METHOD_ECHO);
        awaitOrder(6);

        List<String> expected = new ArrayList<>();
        expected.add("before:a");
        expected.add("before:b");
        expected.add("before:c");
        expected.add("after:c");
        expected.add("after:b");
        expected.add("after:a");
        assertEquals(expected, new ArrayList<>(order), "钩子顺序不对: " + order);
    }

    private ErpcServerInterceptor named(String name) {
        return new ErpcServerInterceptor() {
            @Override
            public void beforeCall(ErpcRequest request) {
                order.add("before:" + name);
            }

            @Override
            public void afterCall(ErpcRequest request, ErpcResponse response, Throwable error) {
                order.add("after:" + name);
            }
        };
    }

    // ------------------------------------------------------------------
    // 连接级上下文
    // ------------------------------------------------------------------

    /**
     * ★ <b>权限校验只做一次</b>：同一连接上第二次请求时，连接级上下文里已经有
     * 「已鉴权」标记 ⇒ 校验不再重做。
     *
     * <p>★ <b>用同一条连接发两帧</b>（不是两次 {@code call()} —— 那个每次都新建连接）。</p>
     */
    @Test
    public void authCheckHappensOncePerConnection() throws Exception {
        AtomicInteger verifications = new AtomicInteger();
        List<RpcChannelContext> seen = new CopyOnWriteArrayList<>();
        start(new ErpcServerInterceptor() {
            @Override
            public void beforeCall(ErpcRequest request, RpcChannelContext channel) {
                seen.add(channel);
                if (!channel.isAuthenticated()) {
                    verifications.incrementAndGet();
                    channel.markAuthenticated();
                }
            }
        });

        ErpcCodec pb = serializer("protobuf");
        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            byte[] payload = bytesOf(pb, new ErpcTestDto("x", 1));
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_ECHO, 0, 1, 0L, payload);
            raw.readFrame();
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_ECHO, 0, 2, 0L, payload);
            raw.readFrame();
        }

        assertEquals(2, seen.size(), "两次请求都应该经过拦截器");
        assertSame(seen.get(0), seen.get(1), "同一连接上应该是同一个连接级上下文");
        assertEquals(1, verifications.get(), "校验应该在同一条连接上只做一次");
    }

    /**
     * ★ <b>标记不跨连接泄漏</b>：另一条连接是另一个上下文 ⇒ 校验要重做。
     *
     * <p>这是「连接不断就不重复校验」的对偶面：新连接上的第一个请求必须重新校验。</p>
     */
    @Test
    public void channelContextIsNotSharedAcrossConnections() throws Exception {
        AtomicInteger verifications = new AtomicInteger();
        List<RpcChannelContext> seen = new CopyOnWriteArrayList<>();
        start(new ErpcServerInterceptor() {
            @Override
            public void beforeCall(ErpcRequest request, RpcChannelContext channel) {
                seen.add(channel);
                if (!channel.isAuthenticated()) {
                    verifications.incrementAndGet();
                    channel.markAuthenticated();
                }
            }
        });

        ErpcCodec pb = serializer("protobuf");
        byte[] payload = bytesOf(pb, new ErpcTestDto("x", 1));
        try (ErpcRawClient first = new ErpcRawClient(server.port())) {
            first.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_ECHO, 0, 1, 0L, payload);
            first.readFrame();
        }
        try (ErpcRawClient second = new ErpcRawClient(server.port())) {
            second.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_ECHO, 0, 1, 0L, payload);
            second.readFrame();
        }

        assertEquals(2, seen.size());
        assertNotSame(seen.get(0), seen.get(1), "两条连接不该共享同一个连接级上下文");
        assertEquals(2, verifications.get(), "换了一条连接就该重新校验");
    }

    /**
     * ★ <b>只实现老签名的拦截器照旧工作</b> —— 新钩子（带 channel）的默认实现
     * 会转调老钩子，老实现零改动。
     */
    @Test
    public void legacyInterceptorWithoutChannelOverloadStillRuns() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        start(new ErpcServerInterceptor() {
            @Override
            public void beforeCall(ErpcRequest request) {
                calls.incrementAndGet();
            }
        });

        call(METHOD_ECHO);

        assertEquals(1, calls.get(), "老签名的拦截器应该照旧被调用");
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private void start(ErpcServerInterceptor... interceptors) throws IOException {
        server = ErpcTestServer.start(methods(), s -> {
            for (ErpcServerInterceptor interceptor : interceptors) {
                s.addInterceptor(interceptor);
            }
        });
    }

    private Map<Integer, ErpcMethod> methods() {
        Map<Integer, ErpcMethod> methods = new HashMap<>();
        methods.put(METHOD_ECHO, new ErpcMethod(METHOD_ECHO, "test.InterceptorService", "echo",
            (req, resp) -> {
                handlerCalls.incrementAndGet();
                ErpcTestDto in = decode(req);
                resp.setPayload(bytesOf(req.getSerializer(),
                    new ErpcTestDto(in.getName(), in.getValue() * 2)));
            }, ErpcTestDto.class, ErpcTestDto.class));
        methods.put(METHOD_BOOM, new ErpcMethod(METHOD_BOOM, "test.InterceptorService", "boom",
            (req, resp) -> {
                handlerCalls.incrementAndGet();
                throw new IllegalStateException("业务炸了");
            }, ErpcTestDto.class, ErpcTestDto.class));
        return methods;
    }

    /** 用裸 socket 发一帧并把响应读掉（错误帧也读掉，测试不断言它的类型）。 */
    private void call(int methodId) throws IOException {
        ErpcCodec pb = serializer("protobuf");
        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            byte[] payload = bytesOf(pb, new ErpcTestDto("x", 1));
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                methodId, 0, 1, 0L, payload);
            raw.readFrame();
        }
    }

    /**
     * 等服务端的<b>收尾钩子</b>跑完。
     *
     * <p>★ 服务端的顺序是「<b>先写响应帧、再跑 afterCall</b>」——
     * 钩子的耗时<b>不算进响应延迟</b>（慢的审计钩子不该拖住调用方）。
     * 代价是：客户端拿到响应时钩子<b>可能还没跑</b> ⇒ 断言之前必须等一下，
     * 否则就是竞态（实测同一个用例 5 次里 4 次失败）。</p>
     */
    private void awaitAfterCalls(int expected) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (afterCalls.get() < expected && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 等钩子的执行序列<b>记满</b>（每个拦截器 before + after 各一条）。
     */
    private void awaitOrder(int expected) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (order.size() < expected && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static ErpcCodec serializer(String name) {
        ErpcCodec codec = ErpcCodecRegistry.instance().byName(name);
        assertNotNull(codec, "注册表里找不到序列化器: " + name);
        return codec;
    }

    private static byte[] bytesOf(ErpcCodec codec, Object obj) {
        ErpcWriter writer = codec.encode(obj);
        return writer == null ? new byte[0] : Arrays.copyOf(writer.buffer(), writer.size());
    }

    private static ErpcTestDto decode(ErpcRequest req) {
        byte[] payload = req.getPayload();
        if (payload == null || payload.length == 0) {
            return new ErpcTestDto();
        }
        return req.getSerializer().decode(payload, 0, payload.length, ErpcTestDto.class);
    }
}
