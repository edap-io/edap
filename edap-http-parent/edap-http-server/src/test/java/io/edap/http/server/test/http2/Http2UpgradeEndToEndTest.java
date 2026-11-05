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
import io.edap.http.http2.Http2ErrorCode;
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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * h2c 升级的端到端测试（RFC 7540 §3.2）。
 *
 * <p><b>★ 核心语义</b>：升级请求<b>本身就是</b> stream 1 的请求 ——</p>
 *
 * <blockquote>
 *   The HTTP/1.1 request that is sent prior to upgrade is assigned a stream
 *   identifier of 1 ... Stream 1 is implicitly "half-closed" from the client
 *   toward the server ... After commencing the HTTP/2 connection, stream 1 is
 *   used for the response.
 * </blockquote>
 *
 * <p>所以客户端<b>不会</b>把升级请求再发一遍（curl / JDK HttpClient 都不会），
 * 服务端必须自己用 stream 1 给出这个请求的响应。</p>
 *
 * <p>覆盖：</p>
 * <ol>
 *   <li>升级请求 → 101 → 服务端在 stream 1 上直接给出响应；</li>
 *   <li>带 HTTP2-Settings 头的升级（客户端提前通告 SETTINGS）；</li>
 *   <li>升级请求的请求头要能传到业务 handler；</li>
 *   <li>客户端违规重发 stream 1 的 HEADERS → RST_STREAM(PROTOCOL_ERROR)；</li>
 *   <li>不是升级请求的 HTTP/1.1 请求仍然走 HTTP/1.1（回归）；</li>
 *   <li>JDK HttpClient（默认 HTTP_2）明文请求。</li>
 * </ol>
 */
public class Http2UpgradeEndToEndTest {

    private static final String BODY = "hello edap h2c";

    private int port;
    private Edap edap;

    @BeforeEach
    void startServer() throws Exception {
        port = freePort();

        HttpHandler hello = (req, resp) -> {
            resp.contentType(ContentTypeHeader.from("text/plain; charset=UTF-8"));
            resp.write(BODY);
        };

        // 回显请求头 —— 验证升级请求的头确实搬到了 HTTP/2 流上
        HttpHandler echo = (req, resp) -> {
            resp.contentType(ContentTypeHeader.from("text/plain; charset=UTF-8"));
            io.edap.http.HeaderValue ua = req.getHeaderValue("User-Agent");
            resp.write(ua == null ? "<none>" : ua.getValue());
        };

        HttpServer server = new HttpServerBuilder()
                .get("/hello", hello)
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
     * 升级请求 → 101 → 服务端在 stream 1 上直接给出响应（客户端不再发 HEADERS）。
     */
    @Test
    public void testUpgradeRequestIsAnsweredOnStream1() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.sendH2cUpgrade("/hello", "localhost", null);

            String head = client.readHttpResponseHead();
            assertTrue(head.startsWith("HTTP/1.1 101 Switching Protocols"), "101 响应不对: " + head);
            assertTrue(head.contains("Connection: Upgrade"), "缺少 Connection: Upgrade: " + head);
            assertTrue(head.contains("Upgrade: h2c"), "缺少 Upgrade: h2c: " + head);

            // 101 之后进入 HTTP/2：连接前言 + SETTINGS
            client.start();

            Map<Integer, String> bodies = new HashMap<>();
            Map<Integer, Integer> statuses = new HashMap<>();
            boolean sawServerSettings = false;
            boolean sawSettingsAck = false;
            // 注意：SETTINGS ACK 在响应之后才到（ACK 是对客户端 SETTINGS 帧的回应，
            // 而响应是升级成功后立刻发出的）—— 所以三个条件都满足才算读完
            for (int i = 0; i < 64
                    && !(sawServerSettings && sawSettingsAck && !bodies.isEmpty()); i++) {
                Http2TestClient.Frame frame = client.readFrame();
                if (frame.type == Http2FrameType.SETTINGS) {
                    if (frame.hasFlag(Http2Flags.ACK)) {
                        sawSettingsAck = true;
                    } else {
                        sawServerSettings = true;
                    }
                } else if (frame.type == Http2FrameType.HEADERS) {
                    for (HpackHeader h : client.decodeHeaders(frame.payload)) {
                        if (":status".equals(h.getName())) {
                            statuses.put(frame.streamId, Integer.parseInt(h.getValue()));
                        }
                    }
                } else if (frame.type == Http2FrameType.DATA) {
                    String prev = bodies.get(frame.streamId);
                    bodies.put(frame.streamId, (prev == null ? "" : prev)
                            + new String(frame.payload, StandardCharsets.UTF_8));
                }
            }
            assertTrue(sawServerSettings, "服务端没有发 SETTINGS");
            assertTrue(sawSettingsAck, "服务端没有确认客户端的 SETTINGS");
            assertEquals(200, statuses.get(1), "升级请求的状态码不对");
            assertEquals(BODY, bodies.get(1), "升级请求的响应体不对");
        }
    }

    /**
     * 带 HTTP2-Settings 头的升级 —— 客户端提前通告自己的 SETTINGS。
     */
    @Test
    public void testUpgradeWithHttp2SettingsHeader() throws Exception {
        // SETTINGS: INITIAL_WINDOW_SIZE = 1024
        byte[] settings = new byte[6];
        settings[0] = 0x00;
        settings[1] = 0x04;
        settings[2] = 0x00;
        settings[3] = 0x00;
        settings[4] = 0x04;
        settings[5] = 0x00;
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(settings);

        try (Http2TestClient client = new Http2TestClient(port)) {
            client.sendH2cUpgrade("/hello", "localhost", b64);

            String head = client.readHttpResponseHead();
            assertTrue(head.startsWith("HTTP/1.1 101 Switching Protocols"), "101 响应不对: " + head);

            client.start();

            String body = null;
            for (int i = 0; i < 64 && body == null; i++) {
                Http2TestClient.Frame frame = client.readFrame();
                if (frame.type == Http2FrameType.DATA) {
                    String prev = body;
                    body = (prev == null ? "" : prev) + new String(frame.payload, StandardCharsets.UTF_8);
                }
            }
            assertNotNull(body, "没有收到响应体");
            assertEquals(BODY, body);
        }
    }

    /**
     * 升级请求的请求头要能传到业务 handler（HTTP/1.1 的头搬到了 HTTP/2 流上）。
     */
    @Test
    public void testUpgradeRequestHeadersReachHandler() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.writeRaw("GET /echo HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "User-Agent: edap-h2c-test/1.0\r\n"
                    + "Connection: Upgrade, HTTP2-Settings\r\n"
                    + "Upgrade: h2c\r\n"
                    + "\r\n");
            String head = client.readHttpResponseHead();
            assertTrue(head.startsWith("HTTP/1.1 101 Switching Protocols"), "101 响应不对: " + head);

            client.start();

            String body = null;
            for (int i = 0; i < 64 && body == null; i++) {
                Http2TestClient.Frame frame = client.readFrame();
                if (frame.type == Http2FrameType.DATA) {
                    String prev = body;
                    body = (prev == null ? "" : prev) + new String(frame.payload, StandardCharsets.UTF_8);
                }
            }
            assertNotNull(body, "没有收到响应体");
            assertEquals("edap-h2c-test/1.0", body, "升级请求的请求头没有传到 handler");
        }
    }

    /**
     * 客户端违规重发 stream 1 的 HEADERS ⇒ RST_STREAM(PROTOCOL_ERROR)。
     *
     * <p>RFC 7540 §3.2：stream 1 已经是升级请求，客户端不该再对它发 HEADERS。</p>
     */
    @Test
    public void testResentHeadersOnStream1IsRejected() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.sendH2cUpgrade("/hello", "localhost", null);
            String head = client.readHttpResponseHead();
            assertTrue(head.startsWith("HTTP/1.1 101 Switching Protocols"), "101 响应不对: " + head);

            client.start();
            // 违规：再发一遍 stream 1 的请求
            client.request(1, "GET", "/hello", "localhost");

            Integer rstError = null;
            for (int i = 0; i < 64 && rstError == null; i++) {
                Http2TestClient.Frame frame = client.readFrame();
                if (frame.type == Http2FrameType.RST_STREAM && frame.streamId == 1) {
                    rstError = ((frame.payload[0] & 0xff) << 24)
                            | ((frame.payload[1] & 0xff) << 16)
                            | ((frame.payload[2] & 0xff) << 8)
                            | (frame.payload[3] & 0xff);
                }
            }
            assertNotNull(rstError, "重发 stream 1 的 HEADERS 没有收到 RST_STREAM");
            assertEquals(Http2ErrorCode.PROTOCOL_ERROR, rstError.intValue(), "RST_STREAM 的错误码不对");
        }
    }

    /**
     * 回归：不是升级请求的 HTTP/1.1 请求仍然正常返回 HTTP/1.1 响应。
     */
    @Test
    public void testNormalHttp11RequestIsNotUpgraded() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.sendHttp11Request("/hello", "localhost");
            String head = client.readHttpResponseHead();
            assertTrue(head.startsWith("HTTP/1.1 200"), "普通 HTTP/1.1 请求的响应不对: " + head);
            assertTrue(!head.contains("101"), "普通请求不能被升级: " + head);
        }
    }

    /**
     * 回归：只带 {@code Upgrade: h2c} 但 {@code Connection} 里没有 {@code HTTP2-Settings}
     * 的请求不算升级请求（RFC 7540 §3.2 的两个必要条件都要满足）。
     */
    @Test
    public void testUpgradeHeaderWithoutConnectionTokenIsNotUpgraded() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.writeRaw("GET /hello HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Upgrade: h2c\r\n"
                    + "\r\n");
            String head = client.readHttpResponseHead();
            assertTrue(head.startsWith("HTTP/1.1 200"), "不应升级，应返回普通响应: " + head);
        }
    }

    /**
     * ★ P3 里程碑：JDK 的 {@link java.net.http.HttpClient} 默认用 HTTP_2，
     * 明文连接时它会先发 h2c 升级请求 —— 升级成功后请求必须正常返回。
     *
     * <p>（升级之前 JDK HttpClient 会一直死等：它发完 Upgrade 请求等 101，
     * 服务端不回 101 它就一直挂着，连 request timeout 都不触发。）</p>
     */
    @Test
    public void testJdkHttpClientH2cUpgrade() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + "/hello"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "JDK HttpClient 的请求状态码不对");
        assertEquals(BODY, response.body(), "JDK HttpClient 的响应体不对");
        assertEquals(HttpClient.Version.HTTP_2, response.version(),
                "应当协商成 HTTP/2，实际是 " + response.version());
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
