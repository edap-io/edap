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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP/2 控制帧与 CONTINUATION 的端到端测试（RFC 9113 §4~§7）。
 *
 * <p>覆盖：</p>
 * <ol>
 *   <li>PING → PING ACK（载荷原样回显）；</li>
 *   <li>SETTINGS → SETTINGS ACK；</li>
 *   <li>PRIORITY 与未知帧类型 → 收即忽略（RFC 9113 §4.1 / §6.3）；</li>
 *   <li>header 名大写 → <b>流级</b> RST_STREAM(PROTOCOL_ERROR)，连接继续可用；</li>
 *   <li>PUSH_PROMISE → <b>连接级</b> GOAWAY(PROTOCOL_ERROR)（服务端不启用 push）；</li>
 *   <li>CONTINUATION 分段 header block → 服务端拼接后再解析。</li>
 * </ol>
 */
public class Http2ControlFrameEndToEndTest {

    private static final String BODY = "hello edap";

    private int port;
    private Edap edap;

    @BeforeEach
    void startServer() throws Exception {
        port = freePort();

        HttpHandler hello = (req, resp) -> {
            resp.contentType(ContentTypeHeader.from("text/plain; charset=UTF-8"));
            resp.write(BODY);
        };

        HttpServer server = new HttpServerBuilder()
                .get("/hello", hello)
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
     * PING 必须原样回一个带 ACK 的 PING（RFC 9113 §6.7）。
     */
    @Test
    public void testPingIsAckedWithSamePayload() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.start();
            byte[] payload = "12345678".getBytes(StandardCharsets.US_ASCII);
            client.sendPing(payload);

            Http2TestClient.Frame ack = readUntil(client, f ->
                    f.type == Http2FrameType.PING && f.hasFlag(Http2Flags.ACK));
            assertNotNull(ack, "没有收到 PING ACK");
            assertEquals(0, ack.streamId, "PING 的 streamId 必须是 0");
            assertArrayEquals(payload, ack.payload, "PING ACK 的载荷必须原样回显");
        }
    }

    /**
     * 收到 SETTINGS 必须回 ACK（RFC 9113 §6.5.3）。
     */
    @Test
    public void testSettingsIsAcked() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.start();
            client.sendSettings(65535);

            Http2TestClient.Frame ack = readUntil(client, f ->
                    f.type == Http2FrameType.SETTINGS && f.hasFlag(Http2Flags.ACK));
            assertNotNull(ack, "没有收到 SETTINGS ACK");
            assertEquals(0, ack.streamId, "SETTINGS 的 streamId 必须是 0");
            assertEquals(0, ack.payload.length, "SETTINGS ACK 不能带载荷");
        }
    }

    /**
     * PRIORITY 与未知帧类型都必须被忽略（RFC 9113 §6.3 / §4.1）—— 连接不能受影响。
     */
    @Test
    public void testPriorityAndUnknownFrameAreIgnored() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.start();
            // PRIORITY：streamId=1，依赖流 0，权重 15
            client.writeFrame(Http2FrameType.PRIORITY, 0, 1, new byte[]{0, 0, 0, 0, 15});
            // 未知帧类型 0x63（RFC 9113 保留给扩展）
            client.writeFrame(0x63, 0, 1, new byte[]{1, 2, 3, 4});

            // 连接必须还能正常服务
            client.request(1, "GET", "/hello", "localhost");
            Http2TestClient.Frame headers = readUntil(client,
                    f -> f.type == Http2FrameType.HEADERS && f.streamId == 1);
            assertNotNull(headers, "PRIORITY / 未知帧之后连接就不工作了");
            assertTrue(headers.hasFlag(Http2Flags.END_HEADERS), "响应 HEADERS 必须带 END_HEADERS");
        }
    }

    /**
     * header 名必须全小写（RFC 9113 §8.2.1）—— 违规是<b>流级</b>错误：
     * 只 RST 这一条流，连接继续可用。
     */
    @Test
    public void testUppercaseHeaderNameIsStreamError() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.start();

            List<HpackHeader> headers = new ArrayList<>(5);
            headers.add(new HpackHeader(":method", "GET"));
            headers.add(new HpackHeader(":path", "/hello"));
            headers.add(new HpackHeader(":scheme", "http"));
            headers.add(new HpackHeader(":authority", "localhost"));
            headers.add(new HpackHeader("X-Bad-Case", "1"));
            client.sendRawHeaders(1, client.encodeHeaders(headers),
                    Http2Flags.END_HEADERS | Http2Flags.END_STREAM);

            Http2TestClient.Frame rst = readUntil(client,
                    f -> f.type == Http2FrameType.RST_STREAM && f.streamId == 1);
            assertNotNull(rst, "大写 header 名应当触发 RST_STREAM，实际没有");
            assertEquals(Http2ErrorCode.PROTOCOL_ERROR, errorCode(rst.payload),
                    "错误码应当是 PROTOCOL_ERROR");

            // ★ 连接必须继续可用（流级错误，不是连接级错误）
            client.request(3, "GET", "/hello", "localhost");
            Http2TestClient.Frame respHeaders = readUntil(client,
                    f -> f.type == Http2FrameType.HEADERS && f.streamId == 3);
            assertNotNull(respHeaders, "流级错误之后连接就不工作了");
            assertTrue(respHeaders.hasFlag(Http2Flags.END_HEADERS));
        }
    }

    /**
     * 服务端不启用 push —— 收到 PUSH_PROMISE 是连接级错误（RFC 9113 §8.4）。
     */
    @Test
    public void testPushPromiseIsConnectionError() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.start();
            client.writeFrame(Http2FrameType.PUSH_PROMISE, 0, 1, new byte[]{0, 0, 0, 0});

            Http2TestClient.Frame goAway = readUntil(client, f -> f.type == Http2FrameType.GOAWAY);
            assertNotNull(goAway, "PUSH_PROMISE 应当导致 GOAWAY");
            assertEquals(0, goAway.streamId, "GOAWAY 的 streamId 必须是 0");
            assertEquals(Http2ErrorCode.PROTOCOL_ERROR, goAwayErrorCode(goAway.payload),
                    "GOAWAY 的错误码应当是 PROTOCOL_ERROR");
        }
    }

    /**
     * header block 被 CONTINUATION 分段 —— 服务端必须拼接后再解 HPACK。
     */
    @Test
    public void testContinuationAssemblesHeaderBlock() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.start();

            byte[] block = client.encodePseudoHeaders("GET", "/hello", "localhost");
            assertTrue(block.length > 2, "header block 太短，切不出两段");
            int half = block.length / 2;

            // HEADERS 不带 END_HEADERS（header block 没收完），但带 END_STREAM
            client.sendRawHeaders(1, Arrays.copyOfRange(block, 0, half), Http2Flags.END_STREAM);
            client.sendContinuation(1, Arrays.copyOfRange(block, half, block.length), true);

            Http2TestClient.Frame headers = readUntil(client,
                    f -> f.type == Http2FrameType.HEADERS && f.streamId == 1);
            assertNotNull(headers, "CONTINUATION 拼起来的 header block 没有被处理");

            List<HpackHeader> decoded = client.decodeHeaders(headers.payload);
            String status = null;
            for (HpackHeader h : decoded) {
                if (":status".equals(h.getName())) {
                    status = h.getValue();
                }
            }
            assertEquals("200", status, "CONTINUATION 场景下状态码不对，headers=" + decoded);
        }
    }

    /**
     * 客户端发的 RST_STREAM 只关掉那一条流，连接继续可用。
     */
    @Test
    public void testClientRstStreamKeepsConnectionAlive() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.start();
            client.sendHeadersOnly(1, "GET", "/hello", "localhost");
            client.sendRstStream(1, Http2ErrorCode.CANCEL);

            client.request(3, "GET", "/hello", "localhost");
            Http2TestClient.Frame headers = readUntil(client,
                    f -> f.type == Http2FrameType.HEADERS && f.streamId == 3);
            assertNotNull(headers, "RST_STREAM 之后连接就不工作了");
        }
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 读到第一帧满足条件的帧（跳过其它帧）。
     */
    private static Http2TestClient.Frame readUntil(Http2TestClient client, Predicate<Http2TestClient.Frame> cond)
            throws IOException {
        for (int i = 0; i < 64; i++) {
            Http2TestClient.Frame frame = client.readFrameOrNull(2000);
            if (frame == null) {
                return null;
            }
            if (cond.test(frame)) {
                return frame;
            }
        }
        return null;
    }

    /**
     * 从 4 字节载荷里取错误码（RST_STREAM）。
     */
    private static int errorCode(byte[] payload) {
        if (payload.length < 4) {
            return -1;
        }
        return ((payload[0] & 0xff) << 24) | ((payload[1] & 0xff) << 16)
                | ((payload[2] & 0xff) << 8) | (payload[3] & 0xff);
    }

    /**
     * GOAWAY 的载荷布局（RFC 9113 §6.8）：last-stream-id(4) + error-code(4) + debug data。
     */
    private static int goAwayErrorCode(byte[] payload) {
        if (payload.length < 8) {
            return -1;
        }
        return ((payload[4] & 0xff) << 24) | ((payload[5] & 0xff) << 16)
                | ((payload[6] & 0xff) << 8) | (payload[7] & 0xff);
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
