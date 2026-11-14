/*
 * Copyright 2023 The edap Project
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

package io.edap.http.server.test.http2;

import io.edap.Edap;
import io.edap.http.HttpHandler;
import io.edap.http.HttpResponse;
import io.edap.http.header.ContentTypeHeader;
import io.edap.http.http2.Http2Flags;
import io.edap.http.http2.Http2FrameType;
import io.edap.http.server.HttpServer;
import io.edap.http.server.HttpServerBuilder;
import io.edap.http.server.http2.Http2ProtocolHandler;
import io.edap.http.server.http2.Http2ProtocolRouter;
import io.edap.http.server.http2.Http2Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP/2 协议路由器（{@link Http2ProtocolRouter}）的端到端测试。
 *
 * <p>验证三件事：</p>
 * <ol>
 *   <li><b>提前派发</b>：header 收齐就派发，<b>不等 body</b> —— 服务端流式的前提；</li>
 *   <li><b>增量 body</b>：body 逐块转发给协议处理器，不在流上累积；</li>
 *   <li><b>不误伤</b>：路由器返回 null 的路径仍然走默认的 HTTP 处理路径。</li>
 * </ol>
 */
public class Http2ProtocolRouterTest {

    private int  port;
    private Edap edap;

    /** 协议处理器收到的 body 分片 */
    private final List<byte[]> bodyChunks = new CopyOnWriteArrayList<>();

    /** 普通 HTTP handler 被调用的次数（协议路径不该调到它） */
    private final AtomicInteger plainHandlerCalls = new AtomicInteger();

    /** onHeaders 被调用时，body 还没到 —— 证明「提前派发」 */
    private final AtomicInteger bodyChunksAtHeaders = new AtomicInteger(-1);

    /** onEnd 被调用 */
    private final CountDownLatch ended = new CountDownLatch(1);

    @BeforeEach
    void startServer() throws Exception {
        port = freePort();

        HttpHandler plain = (req, resp) -> {
            plainHandlerCalls.incrementAndGet();
            resp.contentType(ContentTypeHeader.from("text/plain; charset=UTF-8"));
            resp.write("plain".getBytes(StandardCharsets.UTF_8));
        };

        HttpServer server = new HttpServerBuilder()
                .get("/plain", plain)
                .listen(port)
                .build();

        server.setProtocolRouter(new Http2ProtocolRouter() {
            @Override
            public Http2ProtocolHandler select(io.edap.http.ValueHttpRequest request) {
                if (!"/stream".equals(request.getPath())) {
                    return null;
                }
                return new Http2ProtocolHandler() {
                    @Override
                    public boolean takesStreamingBody() {
                        return true;
                    }

                    @Override
                    public void onHeaders(Http2Stream stream) throws IOException {
                        bodyChunksAtHeaders.set(bodyChunks.size());
                        HttpResponse resp = stream.getResponse();
                        resp.contentType(ContentTypeHeader.from("application/grpc"));
                        resp.begin();
                        byte[] first = "first".getBytes(StandardCharsets.UTF_8);
                        resp.chunk(first, 0, first.length);
                    }

                    @Override
                    public void onBody(Http2Stream stream, byte[] data, int offset, int length) throws IOException {
                        byte[] copy = new byte[length];
                        System.arraycopy(data, offset, copy, 0, length);
                        bodyChunks.add(copy);
                    }

                    @Override
                    public void onEnd(Http2Stream stream) throws IOException {
                        HttpResponse resp = stream.getResponse();
                        byte[] last = "last".getBytes(StandardCharsets.UTF_8);
                        resp.chunk(last, 0, last.length);
                        resp.putTrailer("grpc-status", "0");
                        resp.end();
                        ended.countDown();
                    }
                };
            }
        });

        edap = new Edap();
        edap.addServer(server);
        edap.run();
        awaitListening(port);
    }

    @AfterEach
    void stopServer() {
        if (edap != null) {
            edap.stop();
        }
    }

    /**
     * 只发 HEADERS（不发 DATA、也不发 END_STREAM）—— 服务端必须<b>立刻</b>回响应。
     *
     * <p>这就是 gRPC 服务端流式的关键：请求还没收完，响应就能开始。</p>
     */
    @Test
    public void testDispatchOnHeadersOnly() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.start();
            client.sendHeadersOnly(1, "POST", "/stream", "localhost");

            Http2TestClient.Frame frame = readFrameFor(client, 1, 2000);
            assertNotNull(frame, "header 收齐后服务端必须立刻回响应");
            assertEquals(Http2FrameType.HEADERS, frame.type,
                    "第一个响应帧必须是 HEADERS，实际是 " + frame);
            assertEquals("application/grpc", headerValue(client, frame, "content-type"),
                    "content-type 不对");
            assertEquals(0, plainHandlerCalls.get(), "协议路径不该走普通 HTTP handler");
            assertEquals(0, bodyChunksAtHeaders.get(),
                    "onHeaders 时 body 还没到（提前派发），实际已有 " + bodyChunksAtHeaders.get() + " 片");
        }
    }

    /**
     * body 逐块转发 + onEnd 收尾（trailers）。
     */
    @Test
    public void testStreamingBodyAndEnd() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.start();
            client.sendHeadersOnly(1, "POST", "/stream", "localhost");
            client.sendData(1, "aaa".getBytes(StandardCharsets.UTF_8), false);
            client.sendData(1, "bbb".getBytes(StandardCharsets.UTF_8), false);
            client.sendData(1, "ccc".getBytes(StandardCharsets.UTF_8), true);

            assertTrue(ended.await(5, TimeUnit.SECONDS), "onEnd 没有被调用");

            assertEquals(3, bodyChunks.size(), "body 应该被拆成 3 片转发");
            assertEquals("aaa", text(bodyChunks.get(0)));
            assertEquals("bbb", text(bodyChunks.get(1)));
            assertEquals("ccc", text(bodyChunks.get(2)));

            // 响应：HEADERS + DATA(first) + DATA(last) + HEADERS(trailers, END_STREAM)
            StringBuilder body = new StringBuilder();
            String trailerStatus = null;
            boolean sawEndStream = false;
            for (int i = 0; i < 32; i++) {
                Http2TestClient.Frame frame = client.readFrame();
                if (frame.streamId != 1) {
                    continue;
                }
                if (frame.type == Http2FrameType.DATA) {
                    body.append(new String(frame.payload, StandardCharsets.UTF_8));
                } else if (frame.type == Http2FrameType.HEADERS) {
                    List<io.edap.http.http2.hpack.HpackHeader> hs = client.decodeHeaders(frame.payload);
                    String status = null;
                    for (io.edap.http.http2.hpack.HpackHeader h : hs) {
                        if ("grpc-status".equals(h.getName())) {
                            status = h.getValue();
                        }
                    }
                    if (status != null) {
                        trailerStatus = status;
                        sawEndStream = frame.hasFlag(Http2Flags.END_STREAM);
                        break;
                    }
                }
            }
            assertEquals("firstlast", body.toString(), "响应体不对");
            assertEquals("0", trailerStatus, "trailers 里没有 grpc-status");
            assertTrue(sawEndStream, "trailers 帧必须带 END_STREAM");
        }
    }

    /**
     * 路由器返回 null 的路径 → 走默认的 HTTP 处理路径（既有行为不变）。
     */
    @Test
    public void testUnselectedPathFallsBackToHttp() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.start();
            client.request(1, "GET", "/plain", "localhost");

            Http2TestClient.Frame frame = readFrameFor(client, 1, 2000);
            assertNotNull(frame, "没有收到响应");
            assertEquals(Http2FrameType.HEADERS, frame.type, "第一个响应帧必须是 HEADERS");
            assertEquals(1, plainHandlerCalls.get(), "普通 handler 应该被调用一次");
        }
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 读一个属于指定流的帧（跳过连接级帧），超时返回 null。
     */
    private static Http2TestClient.Frame readFrameFor(Http2TestClient client, int streamId, long timeoutMillis)
            throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Http2TestClient.Frame frame = client.readFrameOrNull(200);
            if (frame == null) {
                continue;
            }
            if (frame.streamId == streamId) {
                return frame;
            }
        }
        return null;
    }

    private static String headerValue(Http2TestClient client, Http2TestClient.Frame frame, String name) {
        List<io.edap.http.http2.hpack.HpackHeader> hs = client.decodeHeaders(frame.payload);
        for (io.edap.http.http2.hpack.HpackHeader h : hs) {
            if (name.equals(h.getName())) {
                return h.getValue();
            }
        }
        return null;
    }

    private static String text(byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static void awaitListening(int port) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException e) {
                TimeUnit.MILLISECONDS.sleep(20);
            }
        }
        throw new IllegalStateException("端口 " + port + " 未在 5s 内开始监听");
    }
}
