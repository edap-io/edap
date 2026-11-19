/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.erpc.server;

import io.edap.erpc.ErpcHandler;
import io.edap.erpc.ErpcMethod;
import io.edap.erpc.ErpcStatus;
import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.codec.ErpcCodecIds;
import io.edap.erpc.codec.ErpcCodecRegistry;
import io.edap.erpc.codec.ErpcWriter;
import io.edap.erpc.frame.ErpcFrameType;
import io.edap.rpc.RpcContext;
import io.edap.rpc.RpcProtocol;
import io.edap.rpc.RpcTimeout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * eRPC 的 RPC 上下文端到端测试 —— 真起服务端、真连 socket。
 *
 * <p>★ <b>三条不变式</b>由这个测试守住：</p>
 * <ol>
 *   <li><b>线上只传相对时长</b>：帧头 {@code flags} 的 bit1~bit7 是 7-bit 超时代码，
 *       服务端把它换算成<b>自己的</b>绝对 nanoTime 时刻 ⇒ 两端的 deadline 不受时钟偏移影响；</li>
 *   <li><b>老客户端不受影响</b>：{@code flags = 0} ⇒ 服务端看不到 deadline，
 *       行为与改造前完全一致；</li>
 *   <li><b>上下文不泄漏</b>：一次调用结束后，worker 线程上不能残留绑定。</li>
 * </ol>
 */
public class ErpcContextEndToEndTest {

    private static final int METHOD_ECHO = 3001;
    private static final int METHOD_BLOCK = 3002;

    /** handler 看到的上下文。 */
    private final AtomicReference<RpcContext> seen = new AtomicReference<>();
    /** handler 看到的「当前线程绑定」是否就是请求自己的上下文。 */
    private final AtomicReference<Boolean> boundMatches = new AtomicReference<>();

    private final AtomicInteger echoHits = new AtomicInteger();

    private ErpcTestServer server;

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    @BeforeEach
    public void setUp() throws IOException {
        server = ErpcTestServer.start(methods());
    }

    @AfterEach
    public void tearDown() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    private Map<Integer, ErpcMethod> methods() {
        Map<Integer, ErpcMethod> methods = new HashMap<>();
        methods.put(METHOD_ECHO, new ErpcMethod(METHOD_ECHO, "test.EchoService", "echo",
            echoHandler(), ErpcTestDto.class, ErpcTestDto.class));
        methods.put(METHOD_BLOCK, new ErpcMethod(METHOD_BLOCK, "test.EchoService", "block",
            blockHandler(), ErpcTestDto.class, ErpcTestDto.class));
        return methods;
    }

    /**
     * 普通回声 —— 把上下文记下来。
     */
    private ErpcHandler echoHandler() {
        return (req, resp) -> {
            echoHits.incrementAndGet();
            seen.set(req.getContext());
            boundMatches.set(req.getContext() == RpcContext.current());
            resp.setPayload(bytesOf(req.getSerializer(), new ErpcTestDto("ok", 1)));
        };
    }

    /**
     * 阻塞一会儿 —— 让 deadline 在业务执行途中到期。
     */
    private ErpcHandler blockHandler() {
        return (req, resp) -> {
            seen.set(req.getContext());
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
            resp.setPayload(bytesOf(req.getSerializer(), new ErpcTestDto("late", 1)));
        };
    }

    private static byte[] bytesOf(ErpcCodec codec, Object obj) {
        ErpcWriter writer = codec.encode(obj);
        return writer == null ? new byte[0] : Arrays.copyOf(writer.buffer(), writer.size());
    }

    private static ErpcCodec serializer(String name) {
        ErpcCodec s = ErpcCodecRegistry.instance().byName(name);
        assertNotNull(s, "注册表里找不到序列化器: " + name);
        return s;
    }

    // ------------------------------------------------------------------
    // deadline 从客户端传到服务端
    // ------------------------------------------------------------------

    /**
     * ★ 客户端给的超时 ⇒ 服务端 handler 能看到自己的 deadline。
     */
    @Test
    public void clientDeadlineReachesTheHandler() throws IOException {
        ErpcCodec pb = serializer("protobuf");
        int code = RpcTimeout.encodeErpcTimeoutCode(2_000_000_000L);   // 2 秒
        int flags = RpcTimeout.toErpcFlags(0, code);

        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            byte[] payload = bytesOf(pb, new ErpcTestDto("x", 1));
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_ECHO, 0, 1, 77L, flags, payload);
            ErpcRawClient.RawFrame resp = raw.readFrame();
            assertEquals(ErpcFrameType.RESPONSE.getValue(), resp.header.getType());
        }

        RpcContext ctx = seen.get();
        assertNotNull(ctx, "handler 应当拿到上下文");
        assertTrue(ctx.deadlineNanos() > 0, "服务端应当算出自己的 deadline");
        long remaining = ctx.remainingMillis();
        // ★ 上界是 2500 而不是 2000：7-bit 刻度按 1/4 八度向上取整（最大超调 25%），
        //   2 秒会被刻到 2048 毫秒 ⇒ 服务端的 deadline 只会比客户端的「不短」。
        assertTrue(remaining > 0 && remaining <= 2_500,
            "剩余时间应落在 (0, 2500] 区间，实际 " + remaining);
        assertEquals(RpcProtocol.ERPC, ctx.protocol());
        assertEquals("erpc:" + METHOD_ECHO, ctx.method());
    }

    /**
     * ★ traceId / requestId 原样传过去。
     */
    @Test
    public void identityFieldsArePropagated() throws IOException {
        ErpcCodec pb = serializer("protobuf");
        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            byte[] payload = bytesOf(pb, new ErpcTestDto("x", 1));
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_ECHO, 0, 4242, 999_888L, payload);
            raw.readFrame();
        }
        RpcContext ctx = seen.get();
        assertNotNull(ctx);
        assertEquals(999_888L, ctx.traceId());
        assertEquals(4242L, ctx.requestId());
    }

    /**
     * ★ 老客户端（flags = 0）⇒ 服务端看不到 deadline，行为与改造前一致。
     */
    @Test
    public void legacyClientWithoutFlagsHasNoDeadline() throws IOException {
        ErpcCodec pb = serializer("protobuf");
        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            byte[] payload = bytesOf(pb, new ErpcTestDto("x", 1));
            // 不带 flags —— 老客户端就是这么发的
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_ECHO, 0, 5, 6L, payload);
            ErpcRawClient.RawFrame resp = raw.readFrame();
            assertEquals(ErpcFrameType.RESPONSE.getValue(), resp.header.getType(),
                "老客户端的请求必须照常被处理");
        }
        RpcContext ctx = seen.get();
        assertNotNull(ctx);
        assertEquals(0L, ctx.deadlineNanos(), "没有超时码 ⇒ 没有 deadline");
        assertEquals(Long.MAX_VALUE, ctx.remainingMillis());
        assertTrue(!ctx.isExpired());
    }

    // ------------------------------------------------------------------
    // deadline 到期
    // ------------------------------------------------------------------

    /**
     * ★ 业务执行途中 deadline 到期 ⇒ 回 DEADLINE_EXCEEDED 错误帧。
     */
    @Test
    public void deadlineFiresWhileTheHandlerIsRunning() throws IOException {
        ErpcCodec pb = serializer("protobuf");
        int code = RpcTimeout.encodeErpcTimeoutCode(50_000_000L);      // 50 毫秒
        int flags = RpcTimeout.toErpcFlags(0, code);

        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            byte[] payload = bytesOf(pb, new ErpcTestDto("x", 1));
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_BLOCK, 0, 11, 0L, flags, payload);

            ErpcRawClient.RawFrame err = raw.readFrame();
            assertEquals(ErpcFrameType.ERROR.getValue(), err.header.getType(),
                "deadline 到了就该回错误帧，而不是等业务跑完");
            io.edap.erpc.RpcException body = pb.decode(err.payload, 0, err.payload.length,
                io.edap.erpc.RpcException.class);
            assertEquals(ErpcStatus.DEADLINE_EXCEEDED.code(), body.getStatus());
        }
    }

    /**
     * ★ 请求到达时 deadline 已经过了 ⇒ <b>业务根本不该跑</b>，直接回 DEADLINE_EXCEEDED。
     *
     * <p>★ <b>怎么保证「到达时就过期了」</b>：业务线程池只有 1 个线程，
     * 且第一个请求一直占着它 ⇒ 第二个请求在队列里躺到 deadline 过期。</p>
     */
    @Test
    public void expiredRequestSkipsTheBusiness() throws Exception {
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Map<Integer, ErpcMethod> methods = new HashMap<>();
        methods.put(METHOD_BLOCK, new ErpcMethod(METHOD_BLOCK, "test.EchoService", "block",
            (req, resp) -> {
                blocked.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                resp.setPayload(bytesOf(req.getSerializer(), new ErpcTestDto("x", 1)));
            }, ErpcTestDto.class, ErpcTestDto.class));
        methods.put(METHOD_ECHO, new ErpcMethod(METHOD_ECHO, "test.EchoService", "echo",
            (req, resp) -> {
                echoHits.incrementAndGet();
                resp.setPayload(bytesOf(req.getSerializer(), new ErpcTestDto("x", 1)));
            }, ErpcTestDto.class, ErpcTestDto.class));

        server.close();
        server = ErpcTestServer.start(methods, s -> s.setWorkerThreads(1));

        ErpcCodec pb = serializer("protobuf");
        int code = RpcTimeout.encodeErpcTimeoutCode(100_000_000L);      // 100 毫秒
        int flags = RpcTimeout.toErpcFlags(0, code);

        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            byte[] payload = bytesOf(pb, new ErpcTestDto("x", 1));
            // 第 1 个请求：占住唯一那个 worker
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_BLOCK, 0, 1, 0L, 0, payload);
            assertTrue(blocked.await(5, TimeUnit.SECONDS), "第一个请求应当占住 worker");

            // 第 2 个请求：排队 ⇒ 等它被处理时 deadline 早就过了
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_ECHO, 0, 2, 0L, flags, payload);

            // ★ 必须等 deadline 真的过去（客户端 100ms ⇒ 服务端刻到 112ms）：
            //   否则 worker 一空出来就把第 2 个请求捡走 —— 那时它还没过期，
            //   业务会照常跑，这个用例就测不到「过期 ⇒ 不进业务」了。
            ErpcTestServer.sleep(300);

            // 第 1 个请求完成 ⇒ worker 空出来 ⇒ 第 2 个请求被处理（此时已过期）
            release.countDown();
            ErpcRawClient.RawFrame resp = raw.readFrame();
            assertEquals(ErpcFrameType.RESPONSE.getValue(), resp.header.getType(),
                "第一个请求应当正常返回");
            ErpcRawClient.RawFrame err = raw.readFrame();
            assertEquals(ErpcFrameType.ERROR.getValue(), err.header.getType());
            io.edap.erpc.RpcException body = pb.decode(err.payload, 0, err.payload.length,
                io.edap.erpc.RpcException.class);
            assertEquals(ErpcStatus.DEADLINE_EXCEEDED.code(), body.getStatus());
            assertEquals(0, echoHits.get(), "过期的请求不该进业务");
        }
    }

    // ------------------------------------------------------------------
    // 线程绑定
    // ------------------------------------------------------------------

    /**
     * ★ handler 里 {@code RpcContext.current()} 就是本次请求的上下文。
     */
    @Test
    public void handlerRunsWithTheRequestContextBound() throws IOException {
        ErpcCodec pb = serializer("protobuf");
        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            byte[] payload = bytesOf(pb, new ErpcTestDto("x", 1));
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_ECHO, 0, 1, 0L, payload);
            raw.readFrame();
        }
        assertEquals(Boolean.TRUE, boundMatches.get(),
            "handler 里的 current() 必须是本次请求的上下文");
        // 调用方线程上不该有残留绑定（框架只在 handler 执行期间绑定）
        assertNull(RpcContext.currentOrNull(), "调用方线程上不该残留绑定");
    }

    /**
     * ★ 上下文不跨请求泄漏：第二个请求看到的必须是第二个请求的上下文。
     */
    @Test
    public void contextDoesNotLeakAcrossRequests() throws IOException {
        ErpcCodec pb = serializer("protobuf");
        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            byte[] payload = bytesOf(pb, new ErpcTestDto("x", 1));
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_ECHO, 0, 101, 0L, payload);
            raw.readFrame();
            RpcContext first = seen.get();
            assertNotNull(first);

            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_ECHO, 0, 202, 0L, payload);
            raw.readFrame();
            RpcContext second = seen.get();

            assertNotNull(second);
            assertTrue(first != second, "第二次请求不能拿到上一次的上下文");
            assertEquals(202L, second.requestId());
            assertEquals(101L, first.requestId());
        }
    }

    /**
     * ★ 同一个 worker 线程上连着跑两次调用 ⇒ 第二次绑的必须是第二个请求的上下文。
     *
     * <p>★ <b>为什么只验到「调用期间」</b>：调用结束后的绑定状态没法从外部观测
     * —— 框架的 {@code Scope.close()} 负责还原，而还原语义（绑前没有 ⇒ 之后也没有）
     * 由 {@code RpcContextTest} 的 attach/detach 用例守住。</p>
     */
    @Test
    public void reusedWorkerSeesEachRequestOwnContext() throws IOException {
        Map<Integer, ErpcMethod> methods = new HashMap<>();
        AtomicReference<RpcContext> boundInSecondCall = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        methods.put(METHOD_ECHO, new ErpcMethod(METHOD_ECHO, "test.EchoService", "echo",
            (req, resp) -> {
                if (calls.incrementAndGet() == 2) {
                    boundInSecondCall.set(RpcContext.currentOrNull());
                }
                resp.setPayload(bytesOf(req.getSerializer(), new ErpcTestDto("x", 1)));
            }, ErpcTestDto.class, ErpcTestDto.class));

        server.close();
        server = ErpcTestServer.start(methods, s -> s.setWorkerThreads(1));

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
        RpcContext bound = boundInSecondCall.get();
        assertNotNull(bound, "第二次调用时线程上应当绑着本次调用的上下文");
        assertEquals(2L, bound.requestId(), "第二次调用绑的必须是第二个请求的上下文");
    }

    /**
     * ★ 没有绑定的线程上 {@code currentOrNull()} 是 null（不残留）。
     */
    @Test
    public void unboundThreadSeesNoContext() {
        assertNull(RpcContext.currentOrNull());
    }
}
