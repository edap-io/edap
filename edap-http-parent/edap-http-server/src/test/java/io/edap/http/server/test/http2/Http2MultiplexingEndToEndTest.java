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
import io.edap.http.header.ContentTypeHeader;
import io.edap.http.http2.Http2Flags;
import io.edap.http.http2.Http2FrameType;
import io.edap.http.http2.hpack.HpackHeader;
import io.edap.http.server.HttpServer;
import io.edap.http.server.HttpServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP/2 多路复用端到端测试（RFC 9113 §5）。
 *
 * <p>核心验证：<b>一条连接上同时存在多条流</b>，且服务端不会因为某条流没收完
 * 就阻塞其它流。</p>
 */
public class Http2MultiplexingEndToEndTest {

    /** 客户端发起的流必须是奇数（RFC 9113 §5.1.1） */
    private static final int STREAMS = 20;

    private int port;
    private Edap edap;

    @BeforeEach
    void startServer() throws Exception {
        port = freePort();

        HttpHandler echo = (req, resp) -> {
            resp.contentType(ContentTypeHeader.from("text/plain; charset=UTF-8"));
            resp.write(req.getPath() + "|" + req.getParameter("n"));
        };

        HttpServer server = new HttpServerBuilder()
                .get("/echo", echo)
                .listen(port)
                .build();

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
     * 一条连接上跑 20 条流，每条流的响应都必须正确。
     */
    @Test
    public void testTwentyConcurrentStreamsOnOneConnection() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.start();

            for (int i = 0; i < STREAMS; i++) {
                client.request(streamId(i), "GET", "/echo?n=" + i, "localhost");
            }

            Map<Integer, String> bodies = new HashMap<>();
            Map<Integer, Integer> statuses = new HashMap<>();
            Map<Integer, Boolean> ended = new HashMap<>();
            for (int i = 0; i < 256 && ended.size() < STREAMS; i++) {
                Http2TestClient.Frame frame = client.readFrame();
                if (frame.type == Http2FrameType.HEADERS) {
                    for (HpackHeader h : client.decodeHeaders(frame.payload)) {
                        if (":status".equals(h.getName())) {
                            statuses.put(frame.streamId, Integer.parseInt(h.getValue()));
                        }
                    }
                } else if (frame.type == Http2FrameType.DATA) {
                    // 响应以「带 END_STREAM 的空 DATA 帧」收尾，所以要累加而不是覆盖
                    String prev = bodies.get(frame.streamId);
                    bodies.put(frame.streamId, (prev == null ? "" : prev)
                            + new String(frame.payload, StandardCharsets.UTF_8));
                    if (frame.hasFlag(Http2Flags.END_STREAM)) {
                        ended.put(frame.streamId, Boolean.TRUE);
                    }
                }
            }

            assertEquals(STREAMS, ended.size(), "没有收齐 " + STREAMS + " 条流的响应，实际 "
                    + ended.size());
            for (int i = 0; i < STREAMS; i++) {
                int sid = streamId(i);
                assertEquals(200, statuses.get(sid), "流 " + sid + " 的状态码不对");
                assertEquals("/echo|" + i, bodies.get(sid), "流 " + sid + " 的响应体不对");
            }
        }
    }

    /**
     * ★ 多路复用的关键性质：<b>一条流没收完，不阻塞其它流</b>。
     *
     * <p>流 1 只发 HEADERS（请求体还没发完），服务端必须立刻去服务流 3。</p>
     */
    @Test
    public void testOpenStreamDoesNotBlockOthers() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.start();

            // 流 1：只发请求头，body 永远不发 —— 服务端应当一直等它的 body
            client.sendHeadersOnly(1, "GET", "/echo?n=pending", "localhost");

            // 流 3：正常请求，服务端必须立刻响应
            client.request(3, "GET", "/echo?n=ok", "localhost");

            Http2TestClient.Frame headers = null;
            for (int i = 0; i < 32; i++) {
                Http2TestClient.Frame frame = client.readFrame();
                if (frame.type == Http2FrameType.HEADERS && frame.streamId == 3) {
                    headers = frame;
                    break;
                }
            }
            assertNotNull(headers, "流 1 没收完，服务端就不服务流 3 了（没有真正多路复用）");
            assertTrue(headers.hasFlag(Http2Flags.END_HEADERS));

            // 流 1 依然可以继续 —— 服务端没把它关掉
            client.sendData(1, new byte[0], true);
        }
    }

    /**
     * 多条流同时存在时，每条流必须拿到<b>自己的</b>响应（响应头不能串流）。
     */
    @Test
    public void testResponsesDoNotCrossStreams() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.start();

            // 交错发送：先 1 再 3 再 5 ...，响应回来时按 streamId 归位
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < STREAMS; i++) {
                int sid = streamId(i);
                ids.add(sid);
                client.request(sid, "GET", "/echo?n=" + i, "localhost");
            }

            Map<Integer, String> bodies = new HashMap<>();
            Map<Integer, Boolean> ended = new HashMap<>();
            for (int i = 0; i < 256 && ended.size() < STREAMS; i++) {
                Http2TestClient.Frame frame = client.readFrame();
                if (frame.type == Http2FrameType.DATA) {
                    String prev = bodies.get(frame.streamId);
                    bodies.put(frame.streamId, (prev == null ? "" : prev)
                            + new String(frame.payload, StandardCharsets.UTF_8));
                    if (frame.hasFlag(Http2Flags.END_STREAM)) {
                        ended.put(frame.streamId, Boolean.TRUE);
                    }
                }
            }
            assertEquals(STREAMS, ended.size(), "没有收齐 " + STREAMS + " 条流的响应");
            for (int i = 0; i < STREAMS; i++) {
                assertEquals("/echo|" + i, bodies.get(streamId(i)),
                        "流 " + streamId(i) + " 拿到了别的流的响应体");
            }
        }
    }

    private static int streamId(int index) {
        return index * 2 + 1;
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
