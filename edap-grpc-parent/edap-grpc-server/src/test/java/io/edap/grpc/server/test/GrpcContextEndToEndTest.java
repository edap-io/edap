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

package io.edap.grpc.server.test;

import io.edap.grpc.GrpcCodec;
import io.edap.grpc.GrpcException;
import io.edap.grpc.GrpcHandler;
import io.edap.grpc.GrpcMethod;
import io.edap.grpc.GrpcStatus;
import io.edap.http.http2.hpack.HpackHeader;
import io.edap.protobuf.annotation.ProtoGrpc;
import io.edap.rpc.RpcContext;
import io.edap.rpc.RpcProtocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * gRPC 服务端的 RPC 上下文端到端测试 —— 真起服务端、真连 socket。
 *
 * <p>★ <b>四条不变式</b>由这个测试守住：</p>
 * <ol>
 *   <li><b>线上只传相对时长</b>：客户端发标准 {@code grpc-timeout} 头 ⇒
 *       服务端换算成<b>自己的</b>绝对 nanoTime 时刻 ⇒ 两端的 deadline 不受时钟偏移影响；</li>
 *   <li><b>老客户端不受影响</b>：不带 {@code grpc-timeout} 头 ⇒ 服务端看不到 deadline，
 *       行为与改造前完全一致；</li>
 *   <li><b>过期 ⇒ 业务根本不跑</b>：请求到达时 deadline 已过 ⇒ 直接回
 *       {@code grpc-status=4}，业务一次都不被调用；</li>
 *   <li><b>上下文不泄漏</b>：一次调用结束后，线程上不能残留绑定；
 *       业务自起的线程<b>不继承</b>绑定（要显式 {@code wrap}）。</li>
 * </ol>
 */
public class GrpcContextEndToEndTest extends GrpcTestBase {

    private static final String PATH_ECHO = "/test.Svc/Echo";
    private static final String PATH_SLOW = "/test.Svc/Slow";

    /** handler 看到的上下文。 */
    private final AtomicReference<RpcContext> seen = new AtomicReference<>();
    /** handler 里的 {@code current()} 与 {@code call.context()} 是不是同一个对象。 */
    private final AtomicBoolean boundMatches = new AtomicBoolean();
    /** handler 看到的剩余毫秒。 */
    private final AtomicReference<Long> remaining = new AtomicReference<>();
    /** 业务被调用的次数。 */
    private final AtomicLong hits = new AtomicLong();
    /** 业务自起的线程上有没有残留绑定（不该有）。 */
    private final AtomicBoolean childThreadBound = new AtomicBoolean();
    /** 业务自起的线程显式 wrap 之后能不能拿到上下文（该能）。 */
    private final AtomicBoolean childThreadWrapped = new AtomicBoolean();
    /** 慢 handler 要睡多久（毫秒）。 */
    private final AtomicLong sleepMillis = new AtomicLong(1000);

    @BeforeEach
    void start() throws Exception {
        Map<String, GrpcMethod> methods = new HashMap<>();
        methods.put(PATH_ECHO, new GrpcMethod(PATH_ECHO, echoHandler(),
                ProtoGrpc.MethodType.UNARY, TestMessages.Req.class, TestMessages.Resp.class));
        methods.put(PATH_SLOW, new GrpcMethod(PATH_SLOW, slowHandler(),
                ProtoGrpc.MethodType.UNARY, TestMessages.Req.class, TestMessages.Resp.class));
        startServer(methods);
    }

    /**
     * 普通回声 —— 把上下文记下来。
     */
    private GrpcHandler echoHandler() {
        return call -> {
            hits.incrementAndGet();
            RpcContext ctx = RpcContext.current();
            seen.set(ctx);
            boundMatches.set(ctx == call.context());
            remaining.set(ctx.remainingMillis());
            probeChildThread(ctx);

            TestMessages.Resp resp = new TestMessages.Resp();
            resp.name = "ok";
            resp.value = 1;
            call.sendOne(resp);
            call.close(GrpcStatus.OK, null);
        };
    }

    /**
     * 阻塞一会儿 —— 让 deadline 在业务执行途中到期。
     */
    private GrpcHandler slowHandler() {
        return call -> {
            hits.incrementAndGet();
            seen.set(RpcContext.current());
            try {
                Thread.sleep(sleepMillis.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                call.sendOne(new TestMessages.Resp());
                call.close(GrpcStatus.OK, null);
            } catch (GrpcException e) {
                // deadline 已经把调用掐断了 ⇒ 业务发不出去，这是预期
            }
        };
    }

    /**
     * 业务自起一个线程 —— 验证绑定<b>不</b>自动继承，但可以显式传播。
     *
     * <p>★ <b>{@code wrap} 必须在有绑定的线程上调用</b>：上下文是在<b>包装时</b>捕获的，
     * 在<b>执行时</b>绑定 —— 在子线程里包装等于捕获了一个空上下文。</p>
     */
    private void probeChildThread(RpcContext ctx) {
        // 在父线程（业务线程，有绑定）里包装
        Runnable wrapped = RpcContext.wrap(
                () -> childThreadWrapped.set(RpcContext.current() == ctx));
        Thread t = new Thread(() -> {
            childThreadBound.set(RpcContext.currentOrNull() != null);
            wrapped.run();
        });
        t.start();
        try {
            t.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sendReq(GrpcTestClient client, int streamId) throws Exception {
        TestMessages.Req req = new TestMessages.Req();
        req.name = "edap";
        req.value = 1;
        client.sendMessage(streamId, GrpcCodec.encode(TestMessages.Req.class, req), true);
    }

    private static List<HpackHeader> timeoutHeader(String value) {
        return List.of(new HpackHeader("grpc-timeout", value));
    }

    // ------------------------------------------------------------------
    // deadline 从客户端传到服务端
    // ------------------------------------------------------------------

    /**
     * ★ 客户端给的超时 ⇒ 服务端 handler 能看到自己的 deadline。
     */
    @Test
    public void clientDeadlineReachesTheHandler() throws Exception {
        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.start();
            // ★ 头值写死成 gRPC 规范的形式（2 秒）—— 端到端测试要验的是线上的字节，
            //   不是本仓库自己的编码器。
            client.sendHeaders(1, PATH_ECHO, "localhost", timeoutHeader("2S"));
            sendReq(client, 1);

            GrpcTestClient.Response response = client.readResponse(1);
            assertEquals("0", response.trailerValue("grpc-status"),
                    "trailers 里没有 grpc-status: " + response.trailers);
        }

        RpcContext ctx = seen.get();
        assertNotNull(ctx, "handler 应当拿到上下文");
        assertTrue(ctx.deadlineNanos() > 0, "服务端应当算出自己的 deadline");
        long left = remaining.get();
        // 上界是 2000 而不是略大一点：gRPC 的秒单位是精确的（2S = 恰好 2e9 ns），
        // 服务端拿到的只会比 2 秒「不短」——时间只会往前走。
        assertTrue(left > 0 && left <= 2000,
                "剩余时间应落在 (0, 2000] 区间，实际 " + left);
        assertEquals(RpcProtocol.GRPC, ctx.protocol());
        assertEquals(PATH_ECHO, ctx.method());
    }

    /**
     * ★ traceId / requestId 原样传过去。
     */
    @Test
    public void identityFieldsArePropagated() throws Exception {
        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.start();
            client.sendHeaders(1, PATH_ECHO, "localhost",
                    List.of(new HpackHeader("x-edap-trace-id", "888888")));
            sendReq(client, 1);
            client.readResponse(1);
        }
        RpcContext ctx = seen.get();
        assertNotNull(ctx);
        assertEquals(888888L, ctx.traceId());
        // HTTP/2 客户端发起的流号是奇数且递增 ⇒ 第一条流是 1
        assertEquals(1L, ctx.requestId());
        assertTrue(boundMatches.get(),
                "handler 里的 current() 必须是本次调用的上下文");
    }

    /**
     * ★ 老客户端（不带 {@code grpc-timeout}）⇒ 服务端看不到 deadline，行为与改造前一致。
     */
    @Test
    public void requestWithoutTimeoutHasNoDeadline() throws Exception {
        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.start();
            client.sendHeaders(1, PATH_ECHO, "localhost");
            sendReq(client, 1);
            GrpcTestClient.Response response = client.readResponse(1);
            assertEquals("0", response.trailerValue("grpc-status"),
                    "不带超时的请求必须照常被处理");
        }
        RpcContext ctx = seen.get();
        assertNotNull(ctx);
        assertEquals(0L, ctx.deadlineNanos(), "没有超时头 ⇒ 没有 deadline");
        assertEquals(Long.MAX_VALUE, ctx.remainingMillis());
        assertFalse(ctx.isExpired());
        assertEquals(1L, hits.get(), "业务必须被调用");
    }

    // ------------------------------------------------------------------
    // deadline 到期
    // ------------------------------------------------------------------

    /**
     * ★ 请求到达时 deadline 已经过了 ⇒ <b>业务根本不该跑</b>，直接回 DEADLINE_EXCEEDED。
     */
    @Test
    public void expiredRequestSkipsTheBusiness() throws Exception {
        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.start();
            // 1 纳秒的超时 —— 请求到达时必然已经过期
            client.sendHeaders(1, PATH_ECHO, "localhost", timeoutHeader("1n"));
            Thread.sleep(20);
            sendReq(client, 1);

            GrpcTestClient.Response response = client.readResponse(1);
            assertEquals("4", response.trailerValue("grpc-status"),
                    "过期 ⇒ grpc-status=4 (DEADLINE_EXCEEDED)，实际 "
                            + response.trailerValue("grpc-status"));
            assertEquals(0L, hits.get(), "过期的请求不该进业务");
        }
    }

    /**
     * ★ 业务执行途中 deadline 到期 ⇒ 回 DEADLINE_EXCEEDED，而不是业务自己的 OK。
     */
    @Test
    public void deadlineFiresWhileTheHandlerIsRunning() throws Exception {
        sleepMillis.set(1000);
        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.start();
            client.sendHeaders(1, PATH_SLOW, "localhost", timeoutHeader("100m"));
            sendReq(client, 1);

            GrpcTestClient.Response response = client.readResponse(1);
            assertEquals("4", response.trailerValue("grpc-status"),
                    "deadline 到了就该回 DEADLINE_EXCEEDED，而不是等业务跑完");
        }
        assertEquals(1L, hits.get(), "业务跑过了（只是它回不回消息已经晚了）");
    }

    // ------------------------------------------------------------------
    // 线程绑定
    // ------------------------------------------------------------------

    /**
     * ★ 业务自起的线程<b>不继承</b>绑定，但显式 wrap 之后能拿到。
     */
    @Test
    public void businessThreadHasNoImplicitBinding() throws Exception {
        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.start();
            client.sendHeaders(1, PATH_ECHO, "localhost");
            sendReq(client, 1);
            client.readResponse(1);
        }
        assertFalse(childThreadBound.get(),
                "业务自起的线程不该继承父线程的绑定（不许用 InheritableThreadLocal）");
        assertTrue(childThreadWrapped.get(),
                "显式 wrap 之后子线程应当能拿到上下文");
    }

    /**
     * ★ 上下文不跨请求泄漏：第二个请求看到的必须是第二个请求的上下文。
     */
    @Test
    public void contextDoesNotLeakAcrossCalls() throws Exception {
        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.start();
            client.sendHeaders(1, PATH_ECHO, "localhost");
            sendReq(client, 1);
            client.readResponse(1);
            RpcContext first = seen.get();
            assertNotNull(first);

            // 同一条连接上的第二条流 ⇒ 流号 3
            client.sendHeaders(3, PATH_ECHO, "localhost");
            sendReq(client, 3);
            client.readResponse(3);
            RpcContext second = seen.get();

            assertNotNull(second);
            assertTrue(first != second, "第二次请求不能拿到上一次的上下文");
            assertEquals(1L, first.requestId());
            assertEquals(3L, second.requestId());
        }
        // 调用方线程上不该有残留绑定（框架只在 handler 执行期间绑定）
        assertNull(RpcContext.currentOrNull(), "调用方线程上不该残留绑定");
    }
}
