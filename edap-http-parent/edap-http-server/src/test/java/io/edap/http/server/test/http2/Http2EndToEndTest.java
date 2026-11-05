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
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP/2 端到端测试：真起服务 + 裸 socket 走 h2c prior knowledge。
 *
 * <p>覆盖 P1 的核心闭环：连接前言 → SETTINGS 交换 → 伪头映射 → 路由 → 响应帧
 * （HEADERS + DATA + END_STREAM）。</p>
 */
public class Http2EndToEndTest {

    private static final String BODY = "hello edap";

    private int  port;
    private Edap edap;

    @BeforeEach
    void startServer() throws Exception {
        port = freePort();

        HttpHandler hello = (req, resp) -> {
            resp.contentType(ContentTypeHeader.from("text/plain; charset=UTF-8"));
            resp.write(BODY);
        };
        HttpHandler echo = (req, resp) -> {
            resp.contentType(ContentTypeHeader.from("text/plain; charset=UTF-8"));
            resp.write(req.getPath() + "|" + req.getMethod() + "|" + req.getParameter("name"));
        };
        HttpHandler echoBody = (req, resp) -> {
            resp.contentType(ContentTypeHeader.from("text/plain; charset=UTF-8"));
            byte[] body = readAll(req);
            resp.write(body.length + ":" + new String(body, StandardCharsets.UTF_8));
        };

        HttpServer server = new HttpServerBuilder()
                .get("/hello", hello)
                .get("/echo", echo)
                .post("/echo-body", echoBody)
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
     * 服务端必须先发自己的 SETTINGS 帧（RFC 9113 §3.4）。
     */
    @Test
    public void testServerSendsSettingsFirst() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.start();
            Http2TestClient.Frame frame = client.readFrame();
            assertEquals(Http2FrameType.SETTINGS, frame.type, "第一帧必须是 SETTINGS，实际是 " + frame);
            assertEquals(0, frame.streamId, "SETTINGS 的 streamId 必须是 0");
            assertTrue(!frame.hasFlag(Http2Flags.ACK), "服务端的 SETTINGS 不能带 ACK");
        }
    }

    /**
     * 完整的 GET：伪头映射 → 路由 → HEADERS(:status 200) + DATA + END_STREAM。
     */
    @Test
    public void testSimpleGet() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.start();
            client.request(1, "GET", "/hello", "localhost");

            Response resp = readResponse(client, 1);
            assertEquals(200, resp.status, "状态码不对，headers=" + resp.headers);
            assertEquals(BODY, resp.bodyText(), "响应体不对");
            assertTrue(resp.endStream, "响应必须以 END_STREAM 结束");
            assertNotNull(resp.header("content-type"), "缺少 content-type 头");
            assertTrue(resp.header("content-type").contains("text/plain"),
                    "content-type 不对: " + resp.header("content-type"));
            assertNotNull(resp.header("date"), "缺少 date 头");
        }
    }

    /**
     * 未注册的路径 → 404。
     */
    @Test
    public void testNotFound() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.start();
            client.request(1, "GET", "/not-exist", "localhost");

            Response resp = readResponse(client, 1);
            assertEquals(404, resp.status, "未注册路径应返回 404");
        }
    }

    /**
     * {@code :path} 的 query 部分 → {@code request.getParameter()}。
     */
    @Test
    public void testQueryParameter() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.start();
            client.request(1, "GET", "/echo?name=edap", "localhost");

            Response resp = readResponse(client, 1);
            assertEquals(200, resp.status);
            assertEquals("/echo|GET|edap", resp.bodyText(), "handler 看到的 path/method/参数不对");
        }
    }

    /**
     * POST 带 body → body 累积到流级缓冲后交给 handler。
     */
    @Test
    public void testPostBody() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.start();
            client.requestWithBody(3, "POST", "/echo-body", "localhost",
                    "abcdef".getBytes(StandardCharsets.UTF_8));

            Response resp = readResponse(client, 3);
            assertEquals(200, resp.status);
            assertEquals("6:abcdef", resp.bodyText(), "handler 看到的 body 不对");
        }
    }

    /**
     * 客户端流标识符必须是奇数（RFC 9113 §5.1.1）——服务端要拒绝偶数流标识符。
     */
    @Test
    public void testEvenStreamIdRejected() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.start();
            client.request(2, "GET", "/hello", "localhost");

            Http2TestClient.Frame frame = null;
            for (int i = 0; i < 10; i++) {
                frame = client.readFrame();
                if (frame.type == Http2FrameType.GOAWAY) {
                    break;
                }
            }
            assertNotNull(frame, "没有收到任何帧");
            assertEquals(Http2FrameType.GOAWAY, frame.type,
                    "偶数流标识符应当导致 GOAWAY，实际收到 " + frame);
        }
    }

    // ------------------------------------------------------------------ 辅助

    private static byte[] readAll(io.edap.http.HttpRequest req) {
        io.edap.http.HttpBody body = req.getBody();
        if (body == null) {
            return new byte[0];
        }
        return body.toByteArray();
    }

    /**
     * 读到指定流的响应结束（END_STREAM）。
     */
    private static Response readResponse(Http2TestClient client, int streamId) throws IOException {
        Response resp = new Response();
        for (int i = 0; i < 32; i++) {
            Http2TestClient.Frame frame = client.readFrame();
            if (frame.streamId != streamId) {
                // SETTINGS / SETTINGS ACK / PING 等连接级帧
                continue;
            }
            if (frame.type == Http2FrameType.HEADERS) {
                resp.headers = client.decodeHeaders(frame.payload);
                for (HpackHeader h : resp.headers) {
                    if (":status".equals(h.getName())) {
                        resp.status = Integer.parseInt(h.getValue());
                    }
                }
                if (frame.hasFlag(Http2Flags.END_STREAM)) {
                    resp.endStream = true;
                    return resp;
                }
            } else if (frame.type == Http2FrameType.DATA) {
                resp.appendBody(frame.payload);
                if (frame.hasFlag(Http2Flags.END_STREAM)) {
                    resp.endStream = true;
                    return resp;
                }
            }
        }
        throw new IOException("响应没有以 END_STREAM 结束");
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

    /**
     * 一次响应。
     */
    private static class Response {
        int status;
        List<HpackHeader> headers;
        byte[] body = new byte[0];
        boolean endStream;

        void appendBody(byte[] data) {
            byte[] nb = new byte[body.length + data.length];
            System.arraycopy(body, 0, nb, 0, body.length);
            System.arraycopy(data, 0, nb, body.length, data.length);
            body = nb;
        }

        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }

        String header(String name) {
            if (headers == null) {
                return null;
            }
            for (HpackHeader h : headers) {
                if (name.equals(h.getName())) {
                    return h.getValue();
                }
            }
            return null;
        }
    }
}
