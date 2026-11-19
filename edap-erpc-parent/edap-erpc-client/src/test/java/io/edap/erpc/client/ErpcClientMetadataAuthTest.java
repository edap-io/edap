package io.edap.erpc.client;

import io.edap.erpc.ErpcException;
import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.codec.ErpcCodecRegistry;
import io.edap.erpc.frame.ErpcFrameType;
import io.edap.rpc.RpcMetadata;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端侧的<b>请求级元数据</b>与<b>连接级鉴权握手</b>端到端测试。
 *
 * <p>★ 服务端是<b>独立实现</b>的迷你服务端（{@link MiniErpcServer}）—— 客户端
 * 对着「按线格式写的最小实现」测，能抓出「两边对同一个字节的理解不一致」。</p>
 *
 * <p>两条独立的不变式：</p>
 * <ol>
 *   <li><b>元数据</b>：带上 ⇒ 帧头 type 字节的 bit7 置位、服务端拿得到 txId；
 *       <b>不带 ⇒ 线上帧字节级不变</b>（type 仍然是 {@code REQUEST = 1}）。</li>
 *   <li><b>鉴权</b>：建连后发一次 AUTH 帧 ⇒ 凭据对 ⇒ 调用放行；
 *       凭据不对 ⇒ <b>建连即失败</b>，坏连接不留在池里。</li>
 * </ol>
 */
public class ErpcClientMetadataAuthTest {

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

    // ------------------------------------------------------------------
    // 请求级元数据
    // ------------------------------------------------------------------

    /**
     * ★ 带元数据的调用 ⇒ 服务端拿得到 txId，<b>且业务 DTO 不受影响</b>
     * （服务端把 value 翻倍说明它把 DTO 解对了）。
     */
    @Test
    public void metadataReachesTheServerAndDtoStaysIntact() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO);
             ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults(), server.port())) {

            RpcMetadata meta = RpcMetadata.create();
            meta.put("txid", "0f9a-42");
            meta.put("tenant", "acme");

            ClientDemoDto resp = client.invoke(METHOD_ECHO, new ClientDemoDto("edap", 21),
                ClientDemoDto.class, 5_000, meta);
            assertEquals("edap", resp.getName());
            assertEquals(42, resp.getValue(), "服务端把 value 翻倍了 ⇒ 业务 DTO 解得对");

            RpcMetadata seen = server.lastMetadata();
            assertNotNull(seen, "服务端应当拿到元数据");
            assertEquals("0f9a-42", seen.get("txid"));
            assertEquals("acme", seen.get("tenant"));
        }
    }

    /**
     * ★ <b>不带元数据 ⇒ 线上帧字节级不变</b>：type 仍然是 {@code REQUEST = 1}，
     * bit7 不能被置位。
     */
    @Test
    public void plainCallKeepsTheOriginalTypeByte() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO);
             ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults(), server.port())) {

            client.invoke(METHOD_ECHO, new ClientDemoDto("plain", 1), ClientDemoDto.class);

            assertEquals(ErpcFrameType.REQUEST.getValue(), server.lastRequestType(),
                "不带元数据时线上帧的 type 字节不能变");
            assertNull(server.lastMetadata(), "不带元数据时服务端不该拿到元数据");
        }
    }

    /**
     * ★ 带元数据 ⇒ 帧头 type 字节的 <b>bit7 置位</b>（帧种类仍是 REQUEST）。
     */
    @Test
    public void metadataCallSetsTheContextBit() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO);
             ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults(), server.port())) {

            RpcMetadata meta = RpcMetadata.create();
            meta.put("txid", "t-1");
            client.invoke(METHOD_ECHO, new ClientDemoDto("m", 1), ClientDemoDto.class, 5_000, meta);

            assertEquals(ErpcFrameType.type(ErpcFrameType.REQUEST.getValue(), true),
                server.lastRequestType(), "带元数据 ⇒ type 字节的 bit7 置位");
        }
    }

    /**
     * ★ <b>空元数据视同没有</b>：容器建了但一个条目都没有 ⇒ 不该在线上多带一段。
     */
    @Test
    public void emptyMetadataIsTreatedAsNoMetadata() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO);
             ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults(), server.port())) {

            client.invoke(METHOD_ECHO, new ClientDemoDto("e", 1), ClientDemoDto.class,
                5_000, RpcMetadata.create());

            assertEquals(ErpcFrameType.REQUEST.getValue(), server.lastRequestType(),
                "空元数据不该在线上带上下文段");
        }
    }

    /**
     * ★ <b>客户端拦截器可以往上下文里写元数据</b> ⇒ 这就是「分布式事务
     * txId 自动传播」的那条路径。
     */
    @Test
    public void interceptorCanInjectMetadata() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO);
             ErpcClient client = clientFor(serializer("protobuf"), ErpcClientOptions.defaults(), server.port())) {

            client.addInterceptor(new ErpcClientInterceptor() {
                @Override
                public void beforeCall(io.edap.erpc.ErpcRequest request) {
                    request.getContext().metadata().put("txid", "from-interceptor");
                }
            });

            client.invoke(METHOD_ECHO, new ClientDemoDto("i", 1), ClientDemoDto.class);

            RpcMetadata seen = server.lastMetadata();
            assertNotNull(seen, "拦截器写进上下文的元数据应当上线");
            assertEquals("from-interceptor", seen.get("txid"));
        }
    }

    // ------------------------------------------------------------------
    // 连接级鉴权握手
    // ------------------------------------------------------------------

    /**
     * ★ 凭据对 ⇒ 建连后握手成功 ⇒ 调用放行。
     */
    @Test
    public void authHandshakeSucceedsWithTheRightCredential() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"),
                 MiniErpcServer.Mode.ECHO, "secret");
             ErpcClient client = clientFor(serializer("protobuf"),
                 ErpcClientOptions.defaults().credential("secret"), server.port())) {

            ClientDemoDto resp = client.invoke(METHOD_ECHO, new ClientDemoDto("a", 3), ClientDemoDto.class);
            assertEquals(6, resp.getValue());

            assertEquals("secret", server.lastCredential(), "服务端应当收到握手的凭据");
            assertEquals(1, server.authCount(), "应当握过一次手");
        }
    }

    /**
     * ★ 凭据不对 ⇒ <b>建连即失败</b>，调用方拿到异常而不是挂死。
     */
    @Test
    public void wrongCredentialFailsTheCall() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"),
                 MiniErpcServer.Mode.ECHO, "secret");
             ErpcClient client = clientFor(serializer("protobuf"),
                 ErpcClientOptions.defaults().credential("wrong"), server.port())) {

            ErpcException e = assertThrows(ErpcException.class,
                () -> client.invoke(METHOD_ECHO, new ClientDemoDto("a", 1), ClientDemoDto.class));
            assertTrue(e.getMessage().contains("握手"), e.getMessage());
        }
    }

    /**
     * ★ 握手失败的连接<b>不能留在池里</b>：槽位保持空 ⇒ 不会把没鉴权成功的连接派出去。
     */
    @Test
    public void failedHandshakeLeavesNoBrokenChannelInThePool() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"),
                 MiniErpcServer.Mode.ECHO, "secret");
             ErpcClient client = clientFor(serializer("protobuf"),
                 ErpcClientOptions.defaults().poolSize(2).credential("wrong"), server.port())) {

            assertThrows(ErpcException.class,
                () -> client.invoke(METHOD_ECHO, new ClientDemoDto("a", 1), ClientDemoDto.class));
            assertEquals(0, client.pools().get(0).channelCount(),
                "握手失败的连接不能留在池里");
        }
    }

    /**
     * ★ <b>握手是连接级的</b>：一条连接上多次调用只握一次手。
     */
    @Test
    public void handshakeHappensOncePerConnection() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"),
                 MiniErpcServer.Mode.ECHO, "secret");
             ErpcClient client = clientFor(serializer("protobuf"),
                 ErpcClientOptions.defaults().poolSize(1).credential("secret"), server.port())) {

            for (int i = 0; i < 5; i++) {
                client.invoke(METHOD_ECHO, new ClientDemoDto("a", i), ClientDemoDto.class);
            }
            assertEquals(1, server.connectionCount(), "池大小为 1 时只应该建一条连接");
            assertEquals(1, server.authCount(), "连接级握手只做一次（连接不断就不重复校验）");
            assertEquals(5, server.totalRequests());
        }
    }

    /**
     * ★ 配了凭据但服务端<b>不校验</b>（{@code expectedCredential == null}）⇒ 握手照样放行。
     */
    @Test
    public void handshakePassesWhenTheServerDoesNotCheck() throws IOException {
        try (MiniErpcServer server = MiniErpcServer.start(serializer("protobuf"), MiniErpcServer.Mode.ECHO);
             ErpcClient client = clientFor(serializer("protobuf"),
                 ErpcClientOptions.defaults().credential("whatever"), server.port())) {

            ClientDemoDto resp = client.invoke(METHOD_ECHO, new ClientDemoDto("a", 2), ClientDemoDto.class);
            assertEquals(4, resp.getValue());
            assertEquals("whatever", server.lastCredential());
        }
    }
}
