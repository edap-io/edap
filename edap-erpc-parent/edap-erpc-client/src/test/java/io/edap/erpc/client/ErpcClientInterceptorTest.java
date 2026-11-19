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

package io.edap.erpc.client;

import io.edap.erpc.ErpcException;
import io.edap.erpc.ErpcRequest;
import io.edap.erpc.ErpcResponse;
import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.codec.ErpcCodecRegistry;
import io.edap.rpc.RpcContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * eRPC 客户端拦截器的行为测试。
 *
 * <p>覆盖：请求可见、改的 traceId 真的上线、拒绝（请求根本没发出去）、
 * 收尾钩子只跑一次、顺序。</p>
 */
public class ErpcClientInterceptorTest {

    /** 测试用的 methodId —— 与 MiniErpcServer 一致即可。 */
    private static final int METHOD_ECHO = 1001;

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
        seenMethodId.set(null);
        ctxMethod.set(null);
        afterCalls.set(0);
        afterError.set(null);
        order.clear();
    }

    // ------------------------------------------------------------------
    // 请求侧
    // ------------------------------------------------------------------

    /**
     * {@code beforeCall} 能拿到本次请求，也能拿到上下文。
     */
    @Test
    public void beforeCallSeesTheRequestAndContext() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO);
             ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults(), server.port())) {
            client.addInterceptor(new ErpcClientInterceptor() {
                @Override
                public void beforeCall(ErpcRequest request) {
                    seenMethodId.set(request.getMethodId());
                    ctxMethod.set(RpcContext.current().method());
                }
            });

            client.invoke(METHOD_ECHO, new ClientDemoDto("edap", 21), ClientDemoDto.class);

            assertEquals(METHOD_ECHO, seenMethodId.get(), "拦截器看不到 methodId");
            assertEquals("erpc:" + METHOD_ECHO, ctxMethod.get(), "拦截器里拿不到本次调用的上下文");
        }
    }

    /**
     * ★ {@code beforeCall} 改的 traceId <b>真的上了线</b>（服务端看到的是改过的值）。
     */
    @Test
    public void beforeCallChangeOfTraceIdReachesTheServer() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO);
             ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults(), server.port())) {
            client.addInterceptor(new ErpcClientInterceptor() {
                @Override
                public void beforeCall(ErpcRequest request) {
                    request.setTraceId(0x5EEDL);
                }
            });

            client.invoke(METHOD_ECHO, new ClientDemoDto("edap", 21), ClientDemoDto.class);

            assertEquals(0x5EEDL, server.lastRequestTraceId(), "拦截器改的 traceId 没上线");
        }
    }

    /**
     * ★ {@code beforeCall} 抛异常 ⇒ 调用失败，且<b>请求根本没发出去</b>。
     */
    @Test
    public void rejectionFailsCallWithoutSendingRequest() throws Exception {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO);
             ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults(), server.port())) {
            client.addInterceptor(new ErpcClientInterceptor() {
                @Override
                public void beforeCall(ErpcRequest request) {
                    throw new IllegalStateException("没鉴权，不许发");
                }
            });

            ErpcException ex = assertThrows(ErpcException.class,
                () -> client.invoke(METHOD_ECHO, new ClientDemoDto("denied", 1), ClientDemoDto.class));
            assertTrue(ex.getMessage().contains("没鉴权"), ex.getMessage());

            // 服务端没收到任何请求
            Thread.sleep(200);
            assertEquals(0, server.totalRequests(), "被拒绝的调用不该把请求发到服务端");
        }
    }

    /**
     * ★ 被拒绝时 <b>afterCall 不跑</b> —— 压根没发起调用，没有「调用结束」这回事。
     */
    @Test
    public void rejectionSkipsAfterCall() throws Exception {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO);
             ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults(), server.port())) {
            client.addInterceptor(new ErpcClientInterceptor() {
                @Override
                public void beforeCall(ErpcRequest request) {
                    throw new IllegalStateException("没鉴权，不许发");
                }

                @Override
                public void afterCall(ErpcRequest request, ErpcResponse response, Throwable error) {
                    afterCalls.incrementAndGet();
                }
            });

            assertThrows(ErpcException.class,
                () -> client.invoke(METHOD_ECHO, new ClientDemoDto("denied", 1), ClientDemoDto.class));

            assertEquals(0, afterCalls.get(), "没发起调用就不该有 afterCall");
        }
    }

    // ------------------------------------------------------------------
    // 收尾
    // ------------------------------------------------------------------

    /**
     * 正常结束：{@code afterCall} 跑一次，{@code error} 为 null。
     */
    @Test
    public void afterCallRunsOnceWithNullErrorOnSuccess() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO);
             ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults(), server.port())) {
            client.addInterceptor(new ErpcClientInterceptor() {
                @Override
                public void afterCall(ErpcRequest request, ErpcResponse response, Throwable error) {
                    afterCalls.incrementAndGet();
                    afterError.set(error);
                }
            });

            ClientDemoDto resp = client.invoke(METHOD_ECHO, new ClientDemoDto("ok", 1), ClientDemoDto.class);

            assertEquals("ok", resp.getName());
            assertEquals(1, afterCalls.get(), "afterCall 必须只跑一次");
            assertNull(afterError.get(), "正常结束的 afterCall 不该带错误");
        }
    }

    /**
     * 服务端回错误帧 ⇒ {@code afterCall} 拿到错误。
     */
    @Test
    public void afterCallGetsErrorOnFailedCall() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ERROR);
             ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults(), server.port())) {
            client.addInterceptor(new ErpcClientInterceptor() {
                @Override
                public void afterCall(ErpcRequest request, ErpcResponse response, Throwable error) {
                    afterCalls.incrementAndGet();
                    afterError.set(error);
                }
            });

            assertThrows(ErpcException.class,
                () -> client.invoke(METHOD_ECHO, new ClientDemoDto("boom", 1), ClientDemoDto.class));

            assertEquals(1, afterCalls.get());
            assertNotNull(afterError.get(), "afterCall 应该拿到错误");
        }
    }

    /**
     * {@code afterCall} 自己抛异常 ⇒ 不影响调用结果。
     */
    @Test
    public void afterCallFailureDoesNotBreakTheCall() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO);
             ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults(), server.port())) {
            client.addInterceptor(new ErpcClientInterceptor() {
                @Override
                public void afterCall(ErpcRequest request, ErpcResponse response, Throwable error) {
                    throw new RuntimeException("收尾钩子炸了");
                }
            });

            ClientDemoDto resp = client.invoke(METHOD_ECHO, new ClientDemoDto("hook", 1), ClientDemoDto.class);

            assertEquals("hook", resp.getName(), "afterCall 抛异常不该影响调用结果");
        }
    }

    /**
     * ★ {@code afterCall} 里能拿到<b>真正的请求号</b>。
     *
     * <p>请求号是<b>传输层</b>在写帧那一刻分配的（连接内唯一、递增）⇒
     * 调用方在发请求时还不知道它 ⇒ 它被回填到请求/应答上。</p>
     */
    @Test
    public void afterCallSeesTheTransportAssignedRequestId() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO);
             ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults(), server.port())) {
            AtomicReference<Integer> reqId = new AtomicReference<>();
            AtomicReference<Integer> respId = new AtomicReference<>();
            client.addInterceptor(new ErpcClientInterceptor() {
                @Override
                public void afterCall(ErpcRequest request, ErpcResponse response, Throwable error) {
                    reqId.set(request.getRequestId());
                    respId.set(response.getRequestId());
                }
            });

            client.invoke(METHOD_ECHO, new ClientDemoDto("id", 1), ClientDemoDto.class);

            assertNotNull(reqId.get(), "afterCall 拿不到请求号");
            assertTrue(reqId.get() > 0, "请求号应当是传输层分配的正数: " + reqId.get());
            assertEquals(reqId.get(), respId.get(), "应答上的请求号应当与请求一致");
        }
    }

    // ------------------------------------------------------------------
    // 顺序
    // ------------------------------------------------------------------

    /**
     * {@code beforeCall} 正序、{@code afterCall} 逆序。
     */
    @Test
    public void hooksRunInFilterOrder() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO);
             ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults(), server.port())) {
            client.addInterceptor(named("a"));
            client.addInterceptor(named("b"));
            client.addInterceptor(named("c"));

            client.invoke(METHOD_ECHO, new ClientDemoDto("order", 1), ClientDemoDto.class);

            List<String> expected = new ArrayList<>();
            expected.add("before:a");
            expected.add("before:b");
            expected.add("before:c");
            expected.add("after:c");
            expected.add("after:b");
            expected.add("after:a");
            assertEquals(expected, new ArrayList<>(order), "钩子顺序不对: " + order);
        }
    }

    private ErpcClientInterceptor named(String name) {
        return new ErpcClientInterceptor() {
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
    // 夹具
    // ------------------------------------------------------------------

    private static ErpcCodec serializer(String name) {
        ErpcCodec codec = ErpcCodecRegistry.instance().byName(name);
        assertNotNull(codec, "序列化器 " + name + " 没注册");
        return codec;
    }

    private static ErpcClient clientFor(ErpcCodec codec, ErpcClientOptions options, int port) {
        return new ErpcClient(Arrays.asList(new ErpcNode("127.0.0.1", port)), codec, options);
    }
}
