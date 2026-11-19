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

import io.edap.erpc.ErpcException;
import io.edap.erpc.ErpcHandler;
import io.edap.erpc.ErpcMethod;
import io.edap.erpc.ErpcStatus;
import io.edap.erpc.client.ErpcClient;
import io.edap.erpc.client.ErpcClientOptions;
import io.edap.erpc.client.ErpcNode;
import io.edap.erpc.client.ErpcNodePool;
import io.edap.erpc.client.ErpcNodeRegistry;
import io.edap.erpc.client.StaticErpcNodeProvider;
import io.edap.erpc.client.loadbalance.RoundRobinLoadBalancer;
import io.edap.erpc.codec.ErpcCodecIds;
import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.codec.ErpcCodecRegistry;
import io.edap.erpc.codec.ErpcWriter;
import io.edap.erpc.frame.ErpcFrameType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * eRPC 端到端测试 —— 真起服务端、真连 socket。
 *
 * <p>覆盖计划里的必测项：序列化可插拔、线上 codec 随帧变化、多路复用、
 * 连接池、节点级均衡、超时清理、错误路径、注册中心驱动的节点变更。</p>
 */
public class ErpcEndToEndTest {

    private static final int METHOD_ECHO = 1001;
    private static final int METHOD_SLOW = 1002;
    private static final int METHOD_BOOM = 1003;
    private static final int METHOD_RESULT = 1004;

    /** 服务端收到的请求数（按 methodId 分开计）。 */
    private final Map<Integer, AtomicInteger> hitCounters = new HashMap<>();

    private ErpcTestServer server;
    private ErpcClient    client;

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    @BeforeEach
    public void setUp() throws IOException {
        server = ErpcTestServer.start(methods());
    }

    @AfterEach
    public void tearDown() {
        if (client != null) {
            client.close();
            client = null;
        }
        if (server != null) {
            server.close();
            server = null;
        }
    }

    private Map<Integer, ErpcMethod> methods() {
        Map<Integer, ErpcMethod> methods = new HashMap<>();
        methods.put(METHOD_ECHO, new ErpcMethod(METHOD_ECHO, "test.EchoService", "echo",
            echoHandler(), ErpcTestDto.class, ErpcTestDto.class));
        methods.put(METHOD_SLOW, new ErpcMethod(METHOD_SLOW, "test.EchoService", "slow",
            slowHandler(), ErpcTestDto.class, ErpcTestDto.class));
        methods.put(METHOD_BOOM, new ErpcMethod(METHOD_BOOM, "test.EchoService", "boom",
            boomHandler(), ErpcTestDto.class, ErpcTestDto.class));
        methods.put(METHOD_RESULT, new ErpcMethod(METHOD_RESULT, "test.EchoService", "result",
            resultHandler(), ErpcTestDto.class, ErpcTestDto.class));
        return methods;
    }

    /** 回声：value × 2，name 原样回。 */
    private ErpcHandler echoHandler() {
        return (req, resp) -> {
            countHit(METHOD_ECHO);
            ErpcTestDto in = decode(req);
            resp.setPayload(bytesOf(req.getSerializer(), new ErpcTestDto(in.getName(), in.getValue() * 2)));
        };
    }

    /** 慢方法：睡 2 秒 —— 用来测客户端超时。 */
    private ErpcHandler slowHandler() {
        return (req, resp) -> {
            countHit(METHOD_SLOW);
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
            resp.setPayload(bytesOf(req.getSerializer(), new ErpcTestDto("slow", 1)));
        };
    }

    /** 抛异常的方法 —— 用来测 ERROR 帧。 */
    private ErpcHandler boomHandler() {
        return (req, resp) -> {
            countHit(METHOD_BOOM);
            throw new IllegalStateException("boom");
        };
    }

    /**
     * ★ 「交对象」的新路径 —— 容器生成的 handler 就是这条路
     * （handler 只交 DTO 对象，编码推迟到出站写帧那一刻 ⇒ 载荷只拷一次）。
     */
    private ErpcHandler resultHandler() {
        return (req, resp) -> {
            countHit(METHOD_RESULT);
            ErpcTestDto in = decode(req);
            resp.setResult(new ErpcTestDto(in.getName(), in.getValue() * 2));
        };
    }

    private void countHit(int methodId) {
        hitCounters.computeIfAbsent(methodId, k -> new AtomicInteger()).incrementAndGet();
    }

    /**
     * 编码并把「借来的」载荷拷出来 —— 借来的缓冲下一次编码就会被覆盖。
     */
    private static byte[] bytesOf(ErpcCodec codec, Object obj) {
        ErpcWriter writer = codec.encode(obj);
        return writer == null ? new byte[0] : Arrays.copyOf(writer.buffer(), writer.size());
    }

    private static ErpcTestDto decode(io.edap.erpc.ErpcRequest req) {
        byte[] payload = req.getPayload();
        if (payload == null || payload.length == 0) {
            return new ErpcTestDto();
        }
        return req.getSerializer().decode(payload, 0, payload.length, ErpcTestDto.class);
    }

    private ErpcCodec serializer(String name) {
        ErpcCodec s = ErpcCodecRegistry.instance().byName(name);
        assertNotNull(s, "注册表里找不到序列化器: " + name);
        return s;
    }

    private ErpcClient newClient(ErpcCodec serializer, ErpcClientOptions options) {
        return new ErpcClient(Arrays.asList(new ErpcNode("127.0.0.1", server.port())),
            serializer, options);
    }

    // ------------------------------------------------------------------
    // 序列化可插拔
    // ------------------------------------------------------------------

    @Test
    public void protobufRoundTrip() {
        client = newClient(serializer("protobuf"), ErpcClientOptions.defaults());
        ErpcTestDto resp = client.invoke(METHOD_ECHO, new ErpcTestDto("hello", 21), ErpcTestDto.class);
        assertEquals("hello", resp.getName());
        assertEquals(42, resp.getValue());
    }

    @Test
    public void jsonRoundTrip() {
        client = newClient(serializer("json"), ErpcClientOptions.defaults());
        ErpcTestDto resp = client.invoke(METHOD_ECHO, new ErpcTestDto("hello", 21), ErpcTestDto.class);
        assertEquals("hello", resp.getName());
        assertEquals(42, resp.getValue());
    }

    /**
     * ★ 「交对象」的服务端路径（容器生成的 handler 走的就是这条）。
     */
    @Test
    public void resultObjectPathRoundTripsWithProtobuf() {
        client = newClient(serializer("protobuf"), ErpcClientOptions.defaults());
        ErpcTestDto resp = client.invoke(METHOD_RESULT, new ErpcTestDto("hello", 21), ErpcTestDto.class);
        assertEquals("hello", resp.getName());
        assertEquals(42, resp.getValue());
    }

    /**
     * ★ 「交对象」的服务端路径 + 非内联序列化器（json 没有实现帧头内联）。
     */
    @Test
    public void resultObjectPathRoundTripsWithJson() {
        client = newClient(serializer("json"), ErpcClientOptions.defaults());
        ErpcTestDto resp = client.invoke(METHOD_RESULT, new ErpcTestDto("hello", 21), ErpcTestDto.class);
        assertEquals("hello", resp.getName());
        assertEquals(42, resp.getValue());
    }

    /**
     * ★ 同一条连接上 codec 随帧变化 —— 线上按帧协商，不是按连接协商。
     */
    @Test
    public void codecCanChangePerFrameOnTheSameConnection() throws IOException {
        ErpcCodec protobuf = serializer("protobuf");
        ErpcCodec json = serializer("json");

        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            // 第 1 帧：protobuf
            byte[] pbPayload = bytesOf(protobuf, new ErpcTestDto("pb", 1));
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_ECHO, 0, 1, 100L, pbPayload);
            ErpcRawClient.RawFrame r1 = raw.readFrame();
            assertEquals(ErpcFrameType.RESPONSE.getValue(), r1.header.getType());
            assertEquals(ErpcCodecIds.PROTOBUF, r1.header.getCodec());
            assertEquals(1, r1.header.getRequestId());
            ErpcTestDto d1 = protobuf.decode(r1.payload, 0, r1.payload.length, ErpcTestDto.class);
            assertEquals("pb", d1.getName());
            assertEquals(2, d1.getValue());

            // 第 2 帧：同一条连接，换成 json
            byte[] jsonPayload = bytesOf(json, new ErpcTestDto("js", 3));
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.JSON,
                METHOD_ECHO, 0, 2, 101L, jsonPayload);
            ErpcRawClient.RawFrame r2 = raw.readFrame();
            assertEquals(ErpcFrameType.RESPONSE.getValue(), r2.header.getType());
            assertEquals(ErpcCodecIds.JSON, r2.header.getCodec());
            assertEquals(2, r2.header.getRequestId());
            ErpcTestDto d2 = json.decode(r2.payload, 0, r2.payload.length, ErpcTestDto.class);
            assertEquals("js", d2.getName());
            assertEquals(6, d2.getValue());
        }
    }

    /**
     * ★ 未知 codec ⇒ 回 ERROR 帧，<b>连接不断</b>。
     */
    @Test
    public void unknownCodecGetsErrorFrameAndConnectionSurvives() throws IOException {
        ErpcCodec protobuf = serializer("protobuf");
        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            raw.send(ErpcFrameType.REQUEST.getValue(), 99, METHOD_ECHO, 0, 7, 0L, new byte[]{1, 2, 3});
            ErpcRawClient.RawFrame err = raw.readFrame();
            assertEquals(ErpcFrameType.ERROR.getValue(), err.header.getType());
            assertEquals(7, err.header.getRequestId());
            // 错误体用服务端自己的默认 codec（protobuf）⇒ 客户端一定解得动
            assertEquals(ErpcCodecIds.PROTOBUF, err.header.getCodec());
            io.edap.erpc.RpcException body = protobuf.decode(err.payload, 0, err.payload.length,
                io.edap.erpc.RpcException.class);
            assertEquals(ErpcStatus.UNKNOWN_CODEC.code(), body.getStatus());

            // 连接还活着：再发一帧正常的
            byte[] payload = bytesOf(protobuf, new ErpcTestDto("alive", 1));
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_ECHO, 0, 8, 0L, payload);
            ErpcRawClient.RawFrame ok = raw.readFrame();
            assertEquals(ErpcFrameType.RESPONSE.getValue(), ok.header.getType());
            assertEquals(8, ok.header.getRequestId());
        }
    }

    // ------------------------------------------------------------------
    // 错误路径
    // ------------------------------------------------------------------

    @Test
    public void unknownMethodIdGetsErrorFrame() {
        client = newClient(serializer("protobuf"), ErpcClientOptions.defaults());
        ErpcException e = assertThrows(ErpcException.class,
            () -> client.invoke(999_999, new ErpcTestDto("x", 1), ErpcTestDto.class));
        assertTrue(e.getMessage().contains(ErpcStatus.UNKNOWN_METHOD.name()),
            "错误信息里应说明方法不存在: " + e.getMessage());
    }

    @Test
    public void handlerExceptionBecomesErrorFrameWithClassAndMessage() {
        client = newClient(serializer("protobuf"), ErpcClientOptions.defaults());
        ErpcException e = assertThrows(ErpcException.class,
            () -> client.invoke(METHOD_BOOM, new ErpcTestDto("x", 1), ErpcTestDto.class));
        assertTrue(e.getMessage().contains("IllegalStateException"),
            "错误信息里应带异常类名: " + e.getMessage());
        assertTrue(e.getMessage().contains("boom"),
            "错误信息里应带异常描述: " + e.getMessage());
    }

    @Test
    public void pingGetsPong() throws IOException {
        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            raw.send(ErpcFrameType.PING.getValue(), ErpcCodecIds.PROTOBUF, 0, 0, 55, 0L, null);
            ErpcRawClient.RawFrame pong = raw.readFrame();
            assertEquals(ErpcFrameType.PONG.getValue(), pong.header.getType());
            assertEquals(55, pong.header.getRequestId());
        }
    }

    /**
     * ★ 帧头 magic 不符 ⇒ 服务端关连接（不是 eRPC 流量）。
     */
    @Test
    public void nonErpcTrafficGetsConnectionClosed() throws IOException {
        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            byte[] garbage = new byte[64];
            Arrays.fill(garbage, (byte) 'X');
            raw.sendRaw(garbage);
            // 服务端会关掉连接 ⇒ 再读时读到 EOF
            boolean closed = false;
            try {
                raw.readFrame();
            } catch (IOException e) {
                closed = true;
            }
            assertTrue(closed, "服务端应当关闭非 eRPC 流量的连接");
        }
    }

    // ------------------------------------------------------------------
    // 多路复用 / 连接池 / 均衡
    // ------------------------------------------------------------------

    /**
     * ★ 单连接上并发 32 个请求 —— requestId 不错配、响应不错序归位。
     */
    @Test
    public void concurrentCallsAreMultiplexedOnOneConnection() throws Exception {
        client = newClient(serializer("protobuf"), ErpcClientOptions.defaults().poolSize(1));
        int n = 32;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ErpcTestDto>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return client.invoke(METHOD_ECHO, new ErpcTestDto("c" + idx, idx), ErpcTestDto.class, 10_000);
                }));
            }
            start.countDown();
            for (int i = 0; i < n; i++) {
                ErpcTestDto resp = futures.get(i).get(20, TimeUnit.SECONDS);
                assertEquals("c" + i, resp.getName(), "第 " + i + " 个请求的响应串位了");
                assertEquals(i * 2, resp.getValue());
            }
        } finally {
            pool.shutdownNow();
        }
        // 32 个请求全走同一条连接
        assertEquals(1, client.pools().get(0).channelCount());
    }

    /**
     * ★ 连接池：4 条连接都真的被用到（round-robin 可观测）。
     */
    @Test
    public void connectionPoolSpreadsCallsAcrossChannels() {
        client = newClient(serializer("protobuf"), ErpcClientOptions.defaults().poolSize(4));
        for (int i = 0; i < 8; i++) {
            client.invoke(METHOD_ECHO, new ErpcTestDto("p" + i, i), ErpcTestDto.class);
        }
        assertEquals(4, client.pools().get(0).channelCount());
    }

    /**
     * ★ 连接断了要能自动重建，后续调用不失败。
     */
    @Test
    public void brokenChannelIsRebuilt() {
        client = newClient(serializer("protobuf"), ErpcClientOptions.defaults().poolSize(1));
        client.invoke(METHOD_ECHO, new ErpcTestDto("a", 1), ErpcTestDto.class);

        ErpcNodePool pool = client.pools().get(0);
        pool.next().close();                    // 把唯一那条连接掐了

        ErpcTestDto resp = client.invoke(METHOD_ECHO, new ErpcTestDto("b", 2), ErpcTestDto.class);
        assertEquals("b", resp.getName());
        assertEquals(4, resp.getValue());
    }

    /**
     * ★ 节点级均衡：2 个节点 ⇒ 请求分到两边。
     */
    @Test
    public void twoNodesGetBalancedTraffic() throws IOException {
        AtomicInteger hits1 = new AtomicInteger();
        AtomicInteger hits2 = new AtomicInteger();
        try (ErpcTestServer s1 = ErpcTestServer.start(countingMethods(hits1));
             ErpcTestServer s2 = ErpcTestServer.start(countingMethods(hits2))) {
            StaticErpcNodeProvider provider = new StaticErpcNodeProvider(
                new ErpcNode("127.0.0.1", s1.port()),
                new ErpcNode("127.0.0.1", s2.port()));
            ErpcClientOptions options = ErpcClientOptions.defaults();
            ErpcCodec ser = serializer("protobuf");
            ErpcNodeRegistry registry = new ErpcNodeRegistry(provider, ser, options);
            RoundRobinLoadBalancer lb = new RoundRobinLoadBalancer(() -> registry.pools());
            ErpcClient c = new ErpcClient(registry, ser, lb, options);
            try {
                for (int i = 0; i < 10; i++) {
                    c.invoke(METHOD_ECHO, new ErpcTestDto("x", i), ErpcTestDto.class);
                }
                assertEquals(5, hits1.get(), "节点 1 应收到 5 个请求");
                assertEquals(5, hits2.get(), "节点 2 应收到 5 个请求");
            } finally {
                c.close();
            }
        }
    }

    /**
     * ★ 节点列表来自注册中心，随其变更实时变化（不需要重建客户端）。
     */
    @Test
    public void nodeListFollowsTheRegistry() throws IOException {
        StaticErpcNodeProvider provider = new StaticErpcNodeProvider();
        client = new ErpcClient(provider, serializer("protobuf"), ErpcClientOptions.defaults());
        assertEquals(0, client.nodeCount(), "注册中心还没推节点时，客户端没有节点");

        ErpcNode node = new ErpcNode("127.0.0.1", server.port());
        provider.addNode(node);
        assertEquals(1, client.nodeCount());

        ErpcTestDto resp = client.invoke(METHOD_ECHO, new ErpcTestDto("r", 1), ErpcTestDto.class);
        assertEquals(2, resp.getValue());

        // 注册中心摘掉节点 ⇒ 客户端的节点数跟着减
        provider.removeNode(node);
        assertEquals(0, client.nodeCount());
    }

    // ------------------------------------------------------------------
    // 超时 / 断连
    // ------------------------------------------------------------------

    /**
     * ★ 超时后在途表里不能有残留。
     */
    @Test
    public void timeoutLeavesNoInFlightCall() {
        client = newClient(serializer("protobuf"), ErpcClientOptions.defaults().poolSize(1));
        assertThrows(ErpcException.class,
            () -> client.invoke(METHOD_SLOW, new ErpcTestDto("s", 1), ErpcTestDto.class, 300));
        assertEquals(0, client.pools().get(0).inFlightCount(), "超时后在途表不能有残留");
    }

    /**
     * ★ 服务端关连接 ⇒ 客户端的挂起调用异常完结（不能永久挂住）。
     */
    @Test
    public void serverShutdownFailsPendingCalls() throws Exception {
        client = newClient(serializer("protobuf"), ErpcClientOptions.defaults().poolSize(1));
        client.invoke(METHOD_ECHO, new ErpcTestDto("a", 1), ErpcTestDto.class);

        server.close();
        server = null;
        Thread.sleep(300);                      // 让读线程看到 EOF

        assertThrows(ErpcException.class,
            () -> client.invoke(METHOD_ECHO, new ErpcTestDto("b", 2), ErpcTestDto.class, 2_000));
    }

    private Map<Integer, ErpcMethod> countingMethods(AtomicInteger counter) {
        Map<Integer, ErpcMethod> methods = new HashMap<>();
        methods.put(METHOD_ECHO, new ErpcMethod(METHOD_ECHO, "test.EchoService", "echo",
            (req, resp) -> {
                counter.incrementAndGet();
                resp.setPayload(bytesOf(req.getSerializer(), new ErpcTestDto("x", 1)));
            }, ErpcTestDto.class, ErpcTestDto.class));
        return methods;
    }

    /**
     * 载荷里塞满 magic 字节也不该被误切 —— 服务端照样回得了。
     *
     * <p>用 json 编码：载荷里真的会逐字节出现 {@code ERPC}（protobuf 编码后
     * 是二进制，看不出 magic 字节）。</p>
     */
    @Test
    public void payloadWithMagicBytesIsNotMisframed() throws IOException {
        ErpcCodec json = serializer("json");
        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            byte[] payload = bytesOf(json, new ErpcTestDto("ERPCERPC", 1));
            assertTrue(new String(payload, StandardCharsets.UTF_8).contains("ERPC"),
                "载荷里应当真的含 magic 字节");
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.JSON,
                METHOD_ECHO, 0, 3, 0L, payload);
            ErpcRawClient.RawFrame resp = raw.readFrame();
            assertEquals(ErpcFrameType.RESPONSE.getValue(), resp.header.getType());
            assertEquals(3, resp.header.getRequestId());
            ErpcTestDto back = json.decode(resp.payload, 0, resp.payload.length, ErpcTestDto.class);
            assertEquals("ERPCERPC", back.getName());
            assertEquals(2, back.getValue());
        }
    }
}
