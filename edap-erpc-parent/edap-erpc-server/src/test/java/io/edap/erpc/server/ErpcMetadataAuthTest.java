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
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.edap.erpc.server;

import io.edap.erpc.ErpcAuthResult;
import io.edap.erpc.ErpcAuthenticator;
import io.edap.erpc.ErpcMethod;
import io.edap.erpc.ErpcServerInterceptor;
import io.edap.erpc.ErpcStatus;
import io.edap.erpc.RpcException;
import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.codec.ErpcCodecIds;
import io.edap.erpc.codec.ErpcCodecRegistry;
import io.edap.erpc.codec.ErpcMetadataCodec;
import io.edap.erpc.codec.ErpcWriter;
import io.edap.erpc.frame.ErpcFrameType;
import io.edap.erpc.ErpcRequest;
import io.edap.rpc.RpcChannelContext;
import io.edap.rpc.RpcMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * eRPC 的<b>请求级元数据</b>与<b>连接级鉴权握手</b>端到端测试。
 *
 * <p>★ <b>两条独立的不变式</b>：</p>
 * <ol>
 *   <li><b>元数据</b>：帧头 type 字节的 bit7 置位 ⇒ 内容区 = {@code [上下文段][业务 DTO]}；
 *       服务端把上下文段剥下来放进 {@code RpcContext.metadata()}，
 *       <b>业务 DTO 的字节不受影响</b>；不带元数据时行为与改造前完全一致。</li>
 *   <li><b>鉴权</b>：AUTH 帧校验通过 ⇒ 标记记在那条连接上 ⇒
 *       <b>同一连接的后续请求免校验</b>；新连接 = 新上下文 ⇒ 标记不跨连接泄漏。</li>
 * </ol>
 */
public class ErpcMetadataAuthTest {

    private static final int METHOD_ECHO = 4101;

    /** handler 看到的元数据。 */
    private final AtomicReference<RpcMetadata> seenMetadata = new AtomicReference<>();
    /** handler 看到的业务 DTO。 */
    private final AtomicReference<ErpcTestDto> seenDto = new AtomicReference<>();
    /** 拦截器看到的连接级上下文。 */
    private final AtomicReference<RpcChannelContext> seenChannel = new AtomicReference<>();
    /** 鉴权器被调用的次数。 */
    private final AtomicInteger authCalls = new AtomicInteger();
    /** 鉴权器收到的凭据。 */
    private final AtomicReference<String> seenCredential = new AtomicReference<>();

    private ErpcTestServer server;
    private ErpcCodec pb;

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    @BeforeEach
    public void setUp() {
        pb = ErpcCodecRegistry.instance().byName("protobuf");
        assertNotNull(pb, "注册表里找不到 protobuf 序列化器");
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
            (req, resp) -> {
                seenMetadata.set(req.getContext() == null ? null : req.getContext().metadata());
                seenDto.set(bytesOf(req.getSerializer(), req.getPayload(), ErpcTestDto.class));
                resp.setPayload(bytesOf(req.getSerializer(), new ErpcTestDto("ok", 1)));
            }, ErpcTestDto.class, ErpcTestDto.class));
        return methods;
    }

    /**
     * 起一个服务端（可先改配置）。
     */
    private ErpcTestServer startServer(java.util.function.Consumer<ErpcServer> configurator)
            throws IOException {
        server = ErpcTestServer.start(methods(), configurator);
        // 拦截器用来观察连接级上下文（鉴权标记 / 认证主体）
        server.server().addInterceptor(new ErpcServerInterceptor() {
            @Override
            public void beforeCall(ErpcRequest request, RpcChannelContext channel) {
                seenChannel.set(channel);
            }
        });
        return server;
    }

    // ------------------------------------------------------------------
    // 请求级元数据
    // ------------------------------------------------------------------

    /**
     * ★ 带上下文的请求帧 ⇒ 服务端把元数据放进 {@code RpcContext.metadata()}，
     * 且<b>业务 DTO 解出来是对的</b>（上下文段被剥掉了，没混进 DTO）。
     */
    @Test
    public void metadataSegmentIsStrippedAndDelivered() throws IOException {
        startServer(s -> { });

        RpcMetadata meta = RpcMetadata.create();
        meta.put("txid", "0f9a-42");
        byte[] segment = ErpcMetadataCodec.encode(meta);
        byte[] dto = bytesOf(pb, new ErpcTestDto("hello", 7));

        byte[] content = new byte[segment.length + dto.length];
        System.arraycopy(segment, 0, content, 0, segment.length);
        System.arraycopy(dto, 0, content, segment.length, dto.length);

        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            // type 字节的 bit7 置位 ⇒ 本帧带 RPC 上下文
            int type = ErpcFrameType.type(ErpcFrameType.REQUEST.getValue(), true);
            raw.send(type, ErpcCodecIds.PROTOBUF, METHOD_ECHO, 0, 1, 77L, content);
            ErpcRawClient.RawFrame resp = raw.readFrame();
            assertEquals(ErpcFrameType.RESPONSE.getValue(), resp.header.getType());
        }

        RpcMetadata got = seenMetadata.get();
        assertNotNull(got, "handler 应当拿到元数据");
        assertEquals("0f9a-42", got.get("txid"));

        ErpcTestDto dtoGot = seenDto.get();
        assertNotNull(dtoGot, "handler 应当拿到业务 DTO");
        assertEquals("hello", dtoGot.getName());
        assertEquals(7, dtoGot.getValue());
    }

    /**
     * ★ <b>普通请求帧的行为零变化</b>：没有上下文段 ⇒ 元数据是空的。
     */
    @Test
    public void plainRequestHasNoMetadata() throws IOException {
        startServer(s -> { });

        byte[] dto = bytesOf(pb, new ErpcTestDto("plain", 1));
        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_ECHO, 0, 2, 78L, dto);
            ErpcRawClient.RawFrame resp = raw.readFrame();
            assertEquals(ErpcFrameType.RESPONSE.getValue(), resp.header.getType());
        }

        RpcMetadata got = seenMetadata.get();
        assertNotNull(got, "上下文对象总是存在");
        assertTrue(got.isEmpty(), "普通请求帧不该带元数据");
        assertFalse(got.containsKey("txid"));
    }

    /**
     * ★ 上下文段的长度越界 ⇒ <b>协议错误</b>（关连接），不是静默截断。
     */
    @Test
    public void bogusContextLengthClosesTheConnection() throws IOException {
        startServer(s -> { });

        // 声明块体 1000 字节，但内容区只有 4 字节
        byte[] content = new byte[4];
        content[0] = 0;
        content[1] = 0;
        content[2] = 0x03;
        content[3] = (byte) 0xE8;      // 1000

        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            int type = ErpcFrameType.type(ErpcFrameType.REQUEST.getValue(), true);
            raw.send(type, ErpcCodecIds.PROTOBUF, METHOD_ECHO, 0, 3, 0L, content);
            // 服务端应当关连接 ⇒ 读帧会拿到 EOF
            assertThrows(EOFException.class, raw::readFrame);
        }
    }

    // ------------------------------------------------------------------
    // 连接级鉴权
    // ------------------------------------------------------------------

    /**
     * ★ AUTH 帧校验通过 ⇒ 回 RESPONSE + 那条连接被标记为已鉴权。
     *
     * <p>★ <b>握手与后续请求必须在同一条连接上</b>：连接级标记记在连接上，
     * 换一条连接就是另一个上下文（那正是 {@link #authDoesNotLeakAcrossConnections()} 测的）。</p>
     */
    @Test
    public void authFrameMarksTheConnection() throws IOException {
        startServer(s -> s.setAuthenticator(acceptingAuthenticator()));

        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            raw.send(ErpcFrameType.AUTH.getValue(), ErpcCodecIds.PROTOBUF,
                0, 0, 10, 0L, "secret".getBytes(StandardCharsets.UTF_8));
            ErpcRawClient.RawFrame resp = raw.readFrame();
            assertEquals(ErpcFrameType.RESPONSE.getValue(), resp.header.getType(),
                "握手成功应当回一个 RESPONSE 帧");
            assertEquals(10, resp.header.getRequestId(), "requestId 原样回填");
            assertEquals(0, resp.payload.length, "握手应答没有载荷");

            // 同一连接的后续请求 ⇒ 鉴权标记生效（拦截器能看到）
            byte[] dto = bytesOf(pb, new ErpcTestDto("x", 1));
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_ECHO, 0, 11, 0L, dto);
            raw.readFrame();
        }

        assertEquals(1, authCalls.get(), "鉴权器应当被调用一次");
        assertEquals("secret", seenCredential.get());

        RpcChannelContext channel = seenChannel.get();
        assertNotNull(channel);
        assertTrue(channel.isAuthenticated(), "握手成功后这条连接应当已鉴权");
        assertEquals("alice", channel.get(RpcChannelContext.PRINCIPAL));
    }

    /**
     * ★ <b>同一连接的后续请求免校验</b>：握手一次之后再发请求，鉴权器不被再调。
     */
    @Test
    public void subsequentRequestsSkipAuthentication() throws IOException {
        startServer(s -> s.setAuthenticator(acceptingAuthenticator()));

        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            raw.send(ErpcFrameType.AUTH.getValue(), ErpcCodecIds.PROTOBUF,
                0, 0, 20, 0L, "secret".getBytes(StandardCharsets.UTF_8));
            raw.readFrame();

            byte[] dto = bytesOf(pb, new ErpcTestDto("x", 1));
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_ECHO, 0, 21, 0L, dto);
            raw.readFrame();
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_ECHO, 0, 22, 0L, dto);
            raw.readFrame();
        }
        assertEquals(1, authCalls.get(),
            "鉴权是连接级的 —— 同一连接上后续请求不该再校验");
    }

    /**
     * ★ 握手失败 ⇒ 回 ERROR(UNAUTHENTICATED) + <b>关连接</b>。
     */
    @Test
    public void failedAuthReturnsErrorAndClosesTheConnection() throws IOException {
        startServer(s -> s.setAuthenticator(credential -> {
            authCalls.incrementAndGet();
            return ErpcAuthResult.fail("凭据不对");
        }));

        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            raw.send(ErpcFrameType.AUTH.getValue(), ErpcCodecIds.PROTOBUF,
                0, 0, 30, 0L, "wrong".getBytes(StandardCharsets.UTF_8));
            ErpcRawClient.RawFrame err = raw.readFrame();
            assertEquals(ErpcFrameType.ERROR.getValue(), err.header.getType());
            RpcException body = pb.decode(err.payload, 0, err.payload.length, RpcException.class);
            assertEquals(ErpcStatus.UNAUTHENTICATED.code(), body.getStatus());
            // 服务端随后关连接
            assertThrows(EOFException.class, raw::readFrame);
        }
        assertEquals(1, authCalls.get());
    }

    /**
     * ★ 服务端<b>没配</b>鉴权器 ⇒ 握手视为通过（不该把坚持握手的客户端拒掉）。
     */
    @Test
    public void authPassesWhenNoAuthenticatorIsConfigured() throws IOException {
        startServer(s -> { });

        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            raw.send(ErpcFrameType.AUTH.getValue(), ErpcCodecIds.PROTOBUF,
                0, 0, 40, 0L, "whatever".getBytes(StandardCharsets.UTF_8));
            ErpcRawClient.RawFrame resp = raw.readFrame();
            assertEquals(ErpcFrameType.RESPONSE.getValue(), resp.header.getType());
        }
        assertEquals(0, authCalls.get(), "没配鉴权器就不该调它");
    }

    /**
     * ★ 开了 {@code requireAuth} ⇒ 未鉴权的连接发请求 ⇒ 回 ERROR(UNAUTHENTICATED)。
     */
    @Test
    public void requireAuthRejectsUnauthenticatedRequests() throws IOException {
        startServer(s -> {
            s.setAuthenticator(acceptingAuthenticator());
            s.setRequireAuth(true);
        });

        byte[] dto = bytesOf(pb, new ErpcTestDto("x", 1));
        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            // 没握手就发请求
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_ECHO, 0, 50, 0L, dto);
            ErpcRawClient.RawFrame err = raw.readFrame();
            assertEquals(ErpcFrameType.ERROR.getValue(), err.header.getType());
            RpcException body = pb.decode(err.payload, 0, err.payload.length, RpcException.class);
            assertEquals(ErpcStatus.UNAUTHENTICATED.code(), body.getStatus());
        }
        assertEquals(0, authCalls.get(), "没握手就不该调鉴权器");
    }

    /**
     * ★ 开了 {@code requireAuth} ⇒ 握手之后同一连接上的请求正常放行。
     */
    @Test
    public void requireAuthLetsAuthenticatedRequestsThrough() throws IOException {
        startServer(s -> {
            s.setAuthenticator(acceptingAuthenticator());
            s.setRequireAuth(true);
        });

        byte[] dto = bytesOf(pb, new ErpcTestDto("x", 1));
        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            raw.send(ErpcFrameType.AUTH.getValue(), ErpcCodecIds.PROTOBUF,
                0, 0, 60, 0L, "secret".getBytes(StandardCharsets.UTF_8));
            raw.readFrame();
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_ECHO, 0, 61, 0L, dto);
            ErpcRawClient.RawFrame resp = raw.readFrame();
            assertEquals(ErpcFrameType.RESPONSE.getValue(), resp.header.getType());
        }
    }

    /**
     * ★ <b>新连接 = 新上下文</b>：第一条连接鉴权过，第二条连接仍然是未鉴权的。
     */
    @Test
    public void authDoesNotLeakAcrossConnections() throws IOException {
        startServer(s -> {
            s.setAuthenticator(acceptingAuthenticator());
            s.setRequireAuth(true);
        });

        // 第 1 条连接：握手 ⇒ 请求放行
        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            raw.send(ErpcFrameType.AUTH.getValue(), ErpcCodecIds.PROTOBUF,
                0, 0, 70, 0L, "secret".getBytes(StandardCharsets.UTF_8));
            raw.readFrame();
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_ECHO, 0, 71, 0L, bytesOf(pb, new ErpcTestDto("x", 1)));
            assertEquals(ErpcFrameType.RESPONSE.getValue(), raw.readFrame().header.getType());
        }

        // 第 2 条连接：没握手 ⇒ 被拒（标记不跨连接泄漏）
        try (ErpcRawClient raw = new ErpcRawClient(server.port())) {
            raw.send(ErpcFrameType.REQUEST.getValue(), ErpcCodecIds.PROTOBUF,
                METHOD_ECHO, 0, 72, 0L, bytesOf(pb, new ErpcTestDto("x", 1)));
            ErpcRawClient.RawFrame err = raw.readFrame();
            assertEquals(ErpcFrameType.ERROR.getValue(), err.header.getType());
            RpcException body = pb.decode(err.payload, 0, err.payload.length, RpcException.class);
            assertEquals(ErpcStatus.UNAUTHENTICATED.code(), body.getStatus());
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private ErpcAuthenticator acceptingAuthenticator() {
        return credential -> {
            authCalls.incrementAndGet();
            seenCredential.set(credential);
            return "secret".equals(credential)
                ? ErpcAuthResult.ok("alice")
                : ErpcAuthResult.fail("凭据不对");
        };
    }

    private static byte[] bytesOf(ErpcCodec codec, Object obj) {
        ErpcWriter writer = codec.encode(obj);
        return writer == null ? new byte[0] : Arrays.copyOf(writer.buffer(), writer.size());
    }

    private static <T> T bytesOf(ErpcCodec codec, byte[] payload, Class<T> type) {
        if (payload == null || payload.length == 0) {
            return null;
        }
        return codec.decode(payload, 0, payload.length, type);
    }
}
