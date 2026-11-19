package io.edap.erpc.client;

import io.edap.erpc.ErpcRequest;
import io.edap.erpc.ErpcResponse;
import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.codec.ErpcCodecRegistry;
import io.edap.rpc.RpcChannelContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 客户端的<b>连接级上下文</b>行为测试。
 *
 * <p>验三件事：</p>
 * <ol>
 *   <li>同一条连接上的多次调用拿到<b>同一个</b>连接级上下文；</li>
 *   <li>连接被掐掉、池重建连接后 ⇒ <b>新</b>上下文（标记不沿用）；</li>
 *   <li>★ {@code beforeCall} 阶段拿不到连接级上下文（那时还没选连接），
 *       {@code afterCall} 阶段能拿到。</li>
 * </ol>
 */
public class ErpcClientChannelContextTest {

    /** 测试用的 methodId —— 与服务端各写一遍，只要一致就行。 */
    private static final int METHOD_ECHO = 1001;

    private static ErpcCodec serializer(String name) {
        ErpcCodec ser = ErpcCodecRegistry.instance().byName(name);
        assertNotNull(ser, "序列化器 " + name + " 没注册");
        return ser;
    }

    private static ErpcClient clientFor(ErpcCodec ser, ErpcClientOptions options, int port) {
        return new ErpcClient(Arrays.asList(new ErpcNode("127.0.0.1", port)), ser, options);
    }

    /**
     * ★ 同一条连接上的两次调用 ⇒ 同一个连接级上下文。
     *
     * <p>池大小设 1：池默认是 4 条连接 round-robin ⇒ 两次调用会落在<b>两条</b>连接上，
     * 那样就测不出「同一条连接」了。</p>
     */
    @Test
    public void sameConnectionSharesTheChannelContext() throws IOException {
        List<RpcChannelContext> seen = new CopyOnWriteArrayList<>();
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO);
             ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults().poolSize(1),
                 server.port())) {

            client.addInterceptor(new ErpcClientInterceptor() {
                @Override
                public void afterCall(ErpcRequest request, ErpcResponse response, Throwable error) {
                    seen.add(request.getChannelContext());
                }
            });

            client.invoke(METHOD_ECHO, new ClientDemoDto("a", 1), ClientDemoDto.class);
            client.invoke(METHOD_ECHO, new ClientDemoDto("b", 2), ClientDemoDto.class);

            assertEquals(2, seen.size(), "两次调用都应该跑过 afterCall");
            assertNotNull(seen.get(0), "afterCall 阶段应当拿得到连接级上下文");
            assertSame(seen.get(0), seen.get(1), "同一条连接上应该是同一个上下文实例");
        }
    }

    /**
     * ★ <b>连接重建 ⇒ 新上下文</b>：连接被掐掉后池会重建一条 ⇒ 上下文是新的
     * （旧连接上的标记不会沿用）。
     */
    @Test
    public void rebuiltConnectionGetsAFreshChannelContext() throws Exception {
        List<RpcChannelContext> seen = new CopyOnWriteArrayList<>();
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO);
             ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults().poolSize(1),
                 server.port())) {

            client.addInterceptor(new ErpcClientInterceptor() {
                @Override
                public void afterCall(ErpcRequest request, ErpcResponse response, Throwable error) {
                    seen.add(request.getChannelContext());
                }
            });

            client.invoke(METHOD_ECHO, new ClientDemoDto("a", 1), ClientDemoDto.class);
            RpcChannelContext before = seen.get(0);
            assertNotNull(before);

            // 掐掉连接 ⇒ 池重建
            server.dropConnections();
            // ★ 断链到「池发现连接已死」之间有个窗口：这段时间里池仍会把那条
            //   死连接派出来 ⇒ 拿到的还是旧上下文。所以要一直重试，直到池真的重建了连接。
            List<RuntimeException> failures = new ArrayList<>();
            RpcChannelContext rebuilt = null;
            for (int attempt = 0; attempt < 30 && rebuilt == null; attempt++) {
                try {
                    client.invoke(METHOD_ECHO, new ClientDemoDto("b", 2), ClientDemoDto.class, 1_000);
                } catch (RuntimeException e) {
                    // 断链的窗口期内会失败 —— 但必须是快速失败，不是挂死
                    failures.add(e);
                    Thread.sleep(20);
                }
                for (RpcChannelContext c : seen) {
                    if (c != before) {
                        rebuilt = c;
                        break;
                    }
                }
            }

            assertNotNull(rebuilt, "池重建连接后应当拿到新的上下文实例，最后一次失败: " + failures);
        }
    }

    /**
     * ★ <b>{@code beforeCall} 阶段拿不到连接级上下文</b> —— 钩子跑在「选节点 /
     * 选连接」之前，那时还不知道走哪条连接。
     */
    @Test
    public void beforeCallDoesNotSeeTheChannelContext() throws IOException {
        List<RpcChannelContext> seenInBefore = new CopyOnWriteArrayList<>();
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO);
             ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults().poolSize(1),
                 server.port())) {

            client.addInterceptor(new ErpcClientInterceptor() {
                @Override
                public void beforeCall(ErpcRequest request) {
                    seenInBefore.add(request.getChannelContext());
                }
            });

            client.invoke(METHOD_ECHO, new ClientDemoDto("a", 1), ClientDemoDto.class);

            assertEquals(1, seenInBefore.size());
            assertNull(seenInBefore.get(0), "选连接之前还没有连接级上下文");
        }
    }
}
