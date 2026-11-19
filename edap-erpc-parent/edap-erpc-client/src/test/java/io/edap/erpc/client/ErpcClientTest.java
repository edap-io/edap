package io.edap.erpc.client;

import io.edap.erpc.ErpcException;
import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.codec.ErpcCodecRegistry;
import io.edap.erpc.client.loadbalance.RoundRobinLoadBalancer;
import io.edap.erpc.client.loadbalance.WeightedLoadBalancer;
import io.edap.rpc.RpcContext;
import io.edap.rpc.RpcProtocol;
import io.edap.rpc.RpcTimeout;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ErpcClient} 的行为测试 —— 真起服务端、真连 socket。
 *
 * <p>★ <b>服务端是自己写的迷你实现</b>（{@link MiniErpcServer}），不是
 * edap-erpc-server —— 后者的测试作用域依赖本模块，反过来依赖会形成 reactor 环；
 * 而且对着<b>独立的协议实现</b>测更能抓出「两边对同一个字节理解不一致」的问题。</p>
 *
 * <p>覆盖：</p>
 * <ol>
 *   <li>一元往返（protobuf / json 两种 codec）；</li>
 *   <li>单连接多路复用（32 并发，requestId 不错配）；</li>
 *   <li>连接池把调用摊到多条连接；</li>
 *   <li>连接被掐断后自动重建（后续请求不挂死）；</li>
 *   <li>节点列表跟着注册中心变（增/删节点实时生效）；</li>
 *   <li>超时后<b>在途表不残留</b>；</li>
 *   <li>ERROR 帧 / 服务端断链 ⇒ 调用方拿到异常而不是永久等待；</li>
 *   <li>节点级均衡（轮询 / 加权）。</li>
 * </ol>
 */
public class ErpcClientTest {

    /** 测试用的 methodId —— 客户端与服务端各写一遍，只要一致就行。 */
    private static final int METHOD_ECHO = 1001;

    private static ErpcCodec serializer(String name) {
        ErpcCodec ser = ErpcCodecRegistry.instance().byName(name);
        assertNotNull(ser, "序列化器 " + name + " 没注册");
        return ser;
    }

    private static ErpcClient clientFor(ErpcCodec ser, ErpcClientOptions options, int port) {
        return new ErpcClient(Arrays.asList(new ErpcNode("127.0.0.1", port)), ser, options);
    }

    @Test
    public void protobufRoundTrip() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO);
             ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults(), server.port())) {
            ClientDemoDto resp = client.invoke(METHOD_ECHO, new ClientDemoDto("edap", 21), ClientDemoDto.class);
            assertEquals("edap", resp.getName());
            assertEquals(42, resp.getValue(), "服务端把 value 翻倍了");
        }
    }

    /**
     * ★ 换一种 codec 也得通 —— codec 是<b>按帧</b>协商的。
     */
    @Test
    public void jsonRoundTrip() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("json"), MiniErpcServer.Mode.ECHO);
             ErpcClient client = clientFor(serializer("json"), ErpcClientOptions.defaults(), server.port())) {
            ClientDemoDto resp = client.invoke(METHOD_ECHO, new ClientDemoDto("js", 3), ClientDemoDto.class);
            assertEquals("js", resp.getName());
            assertEquals(6, resp.getValue());
        }
    }

    /**
     * ★ 单连接上并发 32 个调用 —— 靠 requestId 配对，响应不能串。
     */
    @Test
    public void concurrentCallsAreMultiplexedOnOneConnection() throws Exception {
        int n = 32;
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO);
             ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults().poolSize(1), server.port())) {

            ExecutorService pool = Executors.newFixedThreadPool(8);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ClientDemoDto>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < n; i++) {
                    final int idx = i;
                    futures.add(pool.submit(() -> {
                        start.await();
                        return client.invoke(METHOD_ECHO, new ClientDemoDto("c" + idx, idx), ClientDemoDto.class);
                    }));
                }
                start.countDown();
                for (int i = 0; i < n; i++) {
                    ClientDemoDto resp = futures.get(i).get(20, TimeUnit.SECONDS);
                    assertEquals("c" + i, resp.getName(), "第 " + i + " 个响应串了");
                    assertEquals(i * 2, resp.getValue(), "第 " + i + " 个响应的载荷串了");
                }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(n, server.totalRequests());
            assertEquals(1, server.connectionCount(), "池大小为 1 时只应该建一条连接");
        }
    }

    /**
     * ★ 连接池：池大小 4 ⇒ 8 次调用把 4 条连接都用上。
     */
    @Test
    public void connectionPoolSpreadsCallsAcrossChannels() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO);
             ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults().poolSize(4), server.port())) {
            for (int i = 0; i < 8; i++) {
                client.invoke(METHOD_ECHO, new ClientDemoDto("p" + i, i), ClientDemoDto.class);
            }
            assertEquals(4, client.pools().get(0).channelCount(), "4 次 round-robin 应该建起 4 条连接");
        }
    }

    /**
     * ★ 服务端把连接掐了 ⇒ 客户端要能重建连接，后续调用不挂死。
     *
     * <p>「掐掉之后的第一通电话」可能正好落在客户端还没察觉断链的那个窗口里
     * ⇒ 它会快速失败（不是挂死）。池重建后，后续调用必须恢复。</p>
     */
    @Test
    public void brokenConnectionIsRebuiltAndCallsRecover() throws Exception {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO);
             ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults().poolSize(1), server.port())) {

            client.invoke(METHOD_ECHO, new ClientDemoDto("a", 1), ClientDemoDto.class);
            server.dropConnections();

            ClientDemoDto recovered = null;
            RuntimeException firstFailure = null;
            for (int attempt = 0; attempt < 30 && recovered == null; attempt++) {
                try {
                    recovered = client.invoke(METHOD_ECHO, new ClientDemoDto("b", 2), ClientDemoDto.class, 1_000);
                } catch (RuntimeException e) {
                    firstFailure = e;
                    // 断链的窗口期内会失败 —— 但必须是<b>快速</b>失败，不是干等
                    Thread.sleep(20);
                }
            }
            assertNotNull(recovered, "连接被掐掉后客户端应当重建连接并恢复调用，最后一次失败: " + firstFailure);
            assertEquals("b", recovered.getName());
            assertEquals(4, recovered.getValue());
        }
    }

    /**
     * ★ 节点列表来自注册中心，随其变更实时生效（不需要重建客户端）。
     */
    @Test
    public void nodeListFollowsTheRegistry() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO)) {
            StaticErpcNodeProvider provider = new StaticErpcNodeProvider();
            ErpcCodec ser = serializer("protobuf");
            ErpcClientOptions options = ErpcClientOptions.defaults();
            try (ErpcClient client = new ErpcClient(provider, ser, options)) {
                assertEquals(0, client.nodeCount(), "注册中心还没推节点时客户端没有节点");

                provider.addNode(new ErpcNode("127.0.0.1", server.port()));
                assertEquals(1, client.nodeCount(), "注册中心推来节点后客户端立刻就该知道");

                ClientDemoDto resp = client.invoke(METHOD_ECHO, new ClientDemoDto("r", 1), ClientDemoDto.class);
                assertEquals(2, resp.getValue());

                provider.removeNode(new ErpcNode("127.0.0.1", server.port()));
                assertEquals(0, client.nodeCount(), "注册中心摘掉节点后客户端要跟着摘");
            }
        }
    }

    /**
     * ★ 没有节点时快速失败（而不是空指针或挂死）。
     */
    @Test
    public void noNodeFailsFast() {
        StaticErpcNodeProvider provider = new StaticErpcNodeProvider();
        try (ErpcClient client = new ErpcClient(provider, serializer("protobuf"), ErpcClientOptions.defaults())) {
            ErpcException e = assertThrows(ErpcException.class,
                () -> client.invoke(METHOD_ECHO, new ClientDemoDto("x", 1), ClientDemoDto.class));
            assertTrue(e.getMessage().contains("没有可用的 eRPC 服务节点"), e.getMessage());
        }
    }

    /**
     * ★ 超时后<b>在途表里不能残留</b> —— 残留会让连接池的计数失真。
     */
    @Test
    public void timeoutLeavesNoInFlightCall() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.SILENT);
             ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults(), server.port())) {

            ErpcException e = assertThrows(ErpcException.class,
                () -> client.invoke(METHOD_ECHO, new ClientDemoDto("t", 1), ClientDemoDto.class, 200));
            assertTrue(e.getMessage().contains("超时"), e.getMessage());
            assertEquals(0, client.pools().get(0).inFlightCount(), "超时后连接的在途表必须有残留");
        }
    }

    /**
     * ★ 服务端回 ERROR 帧 ⇒ 调用方拿到带类名与 message 的异常。
     */
    @Test
    public void errorFrameBecomesException() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ERROR);
             ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults(), server.port())) {

            ErpcException e = assertThrows(ErpcException.class,
                () -> client.invoke(METHOD_ECHO, new ClientDemoDto("e", 1), ClientDemoDto.class));
            assertTrue(e.getMessage().contains("IllegalStateException"), e.getMessage());
            assertTrue(e.getMessage().contains("mini server boom"), e.getMessage());
        }
    }

    /**
     * ★ 服务端收到请求就断链 ⇒ 调用方拿到异常，而不是永久等待。
     */
    @Test
    public void serverClosingConnectionFailsTheCall() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.CLOSE_ON_REQUEST);
             ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults(), server.port())) {

            ErpcException e = assertThrows(ErpcException.class,
                () -> client.invoke(METHOD_ECHO, new ClientDemoDto("c", 1), ClientDemoDto.class, 5_000));
            assertTrue(e.getMessage().contains("连接"), e.getMessage());
        }
    }

    /**
     * ★ 节点级轮询均衡：两个节点各拿一半。
     */
    @Test
    public void roundRobinBalancerSplitsTraffic() throws IOException {
        try (MiniErpcServer s1 = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO);
             MiniErpcServer s2 = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO)) {

            StaticErpcNodeProvider provider = new StaticErpcNodeProvider(
                new ErpcNode("127.0.0.1", s1.port()),
                new ErpcNode("127.0.0.1", s2.port()));
            ErpcCodec ser = serializer("protobuf");
            ErpcClientOptions options = ErpcClientOptions.defaults();
            ErpcNodeRegistry registry = new ErpcNodeRegistry(provider, ser, options);
            try (ErpcClient client = new ErpcClient(registry, ser,
                new RoundRobinLoadBalancer(() -> registry.pools()), options)) {
                for (int i = 0; i < 10; i++) {
                    client.invoke(METHOD_ECHO, new ClientDemoDto("x", i), ClientDemoDto.class);
                }
            }
            assertEquals(5, s1.totalRequests(), "节点 1 应收到 5 个请求");
            assertEquals(5, s2.totalRequests(), "节点 2 应收到 5 个请求");
        }
    }

    /**
     * ★ 加权均衡：权重高的节点拿到的流量明显更多。
     */
    @Test
    public void weightedBalancerPrefersTheHeavierNode() throws IOException {
        try (MiniErpcServer s1 = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO);
             MiniErpcServer s2 = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO)) {

            StaticErpcNodeProvider provider = new StaticErpcNodeProvider(
                new ErpcNode("127.0.0.1", s1.port(), 1),
                new ErpcNode("127.0.0.1", s2.port(), 9));
            ErpcCodec ser = serializer("protobuf");
            ErpcClientOptions options = ErpcClientOptions.defaults();
            ErpcNodeRegistry registry = new ErpcNodeRegistry(provider, ser, options);
            try (ErpcClient client = new ErpcClient(registry, ser,
                new WeightedLoadBalancer(() -> registry.pools()), options)) {
                for (int i = 0; i < 200; i++) {
                    client.invoke(METHOD_ECHO, new ClientDemoDto("x", i), ClientDemoDto.class);
                }
            }
            int light = s1.totalRequests();
            int heavy = s2.totalRequests();
            assertEquals(200, light + heavy);
            assertTrue(heavy > light * 3,
                "权重 9:1 的节点应拿到绝大多数流量，实际 " + heavy + " : " + light);
        }
    }

    /**
     * ★ 节点被注册中心摘掉后，那个节点的池要关闭（在途调用随之失败）。
     */
    @Test
    public void removingANodeClosesItsPool() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO)) {
            StaticErpcNodeProvider provider = new StaticErpcNodeProvider(
                new ErpcNode("127.0.0.1", server.port()));
            ErpcCodec ser = serializer("protobuf");
            ErpcNodeRegistry registry = new ErpcNodeRegistry(provider, ser, ErpcClientOptions.defaults());
            ErpcNodePool pool = registry.pools().get(0);
            try (ErpcClient client = new ErpcClient(registry, ser, null, ErpcClientOptions.defaults())) {
                client.invoke(METHOD_ECHO, new ClientDemoDto("a", 1), ClientDemoDto.class);
                assertEquals(1, pool.channelCount());

                provider.removeNode(new ErpcNode("127.0.0.1", server.port()));
                assertEquals(0, registry.nodeCount(), "摘掉节点后节点表里不该再有它");
                assertEquals(1, pool.closedChannelCount(), "摘掉节点后那个池的连接要全部关掉");
            }
        }
    }

    /**
     * ★ 客户端关闭 ⇒ 池里的连接全关，后续调用快速失败（而不是挂住）。
     */
    @Test
    public void closingTheClientClosesThePools() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO)) {
            ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults(), server.port());
            client.invoke(METHOD_ECHO, new ClientDemoDto("a", 1), ClientDemoDto.class);
            ErpcNodePool pool = client.pools().get(0);
            client.close();
            assertEquals(pool.channelCount(), pool.closedChannelCount(), "关闭客户端要把池里的连接全关掉");
            assertThrows(ErpcException.class,
                () -> client.invoke(METHOD_ECHO, new ClientDemoDto("b", 2), ClientDemoDto.class));
        }
    }

    // ------------------------------------------------------------------
    // RPC 上下文：deadline 上线 + 本地取消
    // ------------------------------------------------------------------

    /**
     * ★ 请求帧的 {@code flags} 高位必须带超时代码 —— 服务端据此算自己的截止时刻。
     *
     * <p>★ <b>为什么不能只断言「有值」</b>：7-bit 刻度最粗 25% 超调，
     * 断言「解出来的时长落在请求值的 [1.0, 1.3] 倍」才是真的守住了
     * 「向上取整、不会提前掐断客户端仍认为有效的调用」这条不变式。</p>
     */
    @Test
    public void requestFrameCarriesTheTimeoutCode() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO);
             ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults(), server.port())) {
            client.invoke(METHOD_ECHO, new ClientDemoDto("t", 1), ClientDemoDto.class, 2_000);

            int flags = server.lastRequestFlags();
            int code = (flags >>> 1) & 0x7F;
            assertTrue(code > 0, "请求帧必须带超时代码，实际 flags=" + flags);

            long decoded = RpcTimeout.decodeErpcTimeoutNanos(code);
            // deadline 从 invoke() 开始走 ⇒ 落帧时已经过去几毫秒 ⇒ 允许一点起跑误差
            assertTrue(decoded >= 1_900_000_000L,
                "解出来的超时不该短于请求值（向上取整），实际 " + decoded + "ns");
            assertTrue(decoded <= 2_600_000_000L,
                "7-bit 刻度的最大超调是 25%，实际 " + decoded + "ns");
        }
    }

    /**
     * ★ {@code flags} 的 bit0 是「压缩预留」—— 任何路径都不许写它。
     */
    @Test
    public void compressionReservedBitStaysZero() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO);
             ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults(), server.port())) {
            client.invoke(METHOD_ECHO, new ClientDemoDto("t", 1), ClientDemoDto.class, 2_000);
            assertEquals(0, server.lastRequestFlags() & 0x01, "bit0（压缩预留）不能被写");
        }
    }

    /**
     * ★ 本地取消 ⇒ 调用方立刻拿到失败 + 取消信号被 trip。
     *
     * <p>★ <b>线上没有 CANCEL 帧</b>（eRPC v1 帧格式不变）⇒ 服务端不会立刻停手，
     * 本地效果是「调用方立刻拿到失败、上下文能观察到取消」。</p>
     */
    @Test
    public void localCancelFailsThePendingCall() throws Exception {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.SILENT)) {
            ErpcChannel channel = new ErpcChannel(new ErpcNode("127.0.0.1", server.port()),
                serializer("protobuf"), 3_000);
            try {
                RpcContext ctx = RpcContext.builder()
                    .protocol(RpcProtocol.ERPC)
                    .method("erpc:" + METHOD_ECHO)
                    .timeoutMillis(30_000)
                    .build();

                AtomicReference<Throwable> failure = new AtomicReference<>();
                Thread caller = new Thread(() -> {
                    try {
                        channel.call(METHOD_ECHO, 0, new ClientDemoDto("c", 1), 1L, 30_000, ctx);
                    } catch (Throwable e) {
                        failure.set(e);
                    }
                }, "erpc-cancel-caller");
                caller.setDaemon(true);
                caller.start();

                // 等请求真的落到服务端（请求号是传输层分配的，只能从上下文里读）
                long deadline = System.currentTimeMillis() + 5_000;
                while (server.totalRequests() == 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals(1, server.totalRequests(), "请求应当已经发出去");
                assertTrue(ctx.requestId() > 0, "上下文里的请求号应当已被填上");

                ErpcException cause = new ErpcException("调用方主动取消");
                assertTrue(channel.cancel((int) ctx.requestId(), cause), "这次调用确实还在途");

                caller.join(5_000);
                assertNotNull(failure.get(), "取消后调用方应当拿到失败");
                assertTrue(ctx.cancellation().isCancelled(), "取消信号应当被 trip");
                assertNotNull(ctx.cancellation().reason(), "取消原因不该为空");
                assertEquals(0, channel.inFlightCount(), "取消后在途表里不能残留");
            } finally {
                channel.close();
            }
        }
    }
}
