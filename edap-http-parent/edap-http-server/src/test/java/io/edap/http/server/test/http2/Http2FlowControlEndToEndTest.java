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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP/2 流控端到端测试（RFC 9113 §6.9）。
 *
 * <p>覆盖三个方向：</p>
 * <ol>
 *   <li><b>发送方向</b>：响应体超过窗口 ⇒ 服务端只能发一个窗口的量，
 *       必须等客户端 WINDOW_UPDATE 才能继续；</li>
 *   <li><b>接收方向</b>：请求体超过窗口 ⇒ 服务端必须主动发 WINDOW_UPDATE 归还额度；</li>
 *   <li><b>窗口初值</b>：对端改 SETTINGS_INITIAL_WINDOW_SIZE ⇒ 新流的发送窗口跟着变。</li>
 * </ol>
 */
public class Http2FlowControlEndToEndTest {

    /** RFC 9113 §6.9.2 的默认窗口 */
    private static final int WINDOW = 65535;

    /** 大于一个窗口，才能真正触发流控 */
    private static final int BIG_BODY_SIZE = 200_000;

    /** 单个 DATA 帧的最大载荷（不能超过对端的 MAX_FRAME_SIZE） */
    private static final int CHUNK = 16384;

    private int port;
    private Edap edap;
    private byte[] bigBody;

    @BeforeEach
    void startServer() throws Exception {
        port = freePort();
        bigBody = new byte[BIG_BODY_SIZE];
        for (int i = 0; i < bigBody.length; i++) {
            bigBody[i] = (byte) ('a' + (i % 26));
        }

        HttpHandler big = (req, resp) -> {
            resp.contentType(ContentTypeHeader.from("application/octet-stream"));
            resp.write(bigBody);
        };
        HttpHandler bodyLength = (req, resp) -> {
            resp.contentType(ContentTypeHeader.from("text/plain; charset=UTF-8"));
            io.edap.http.HttpBody body = req.getBody();
            resp.write(String.valueOf(body == null ? 0 : body.toByteArray().length));
        };

        HttpServer server = new HttpServerBuilder()
                .get("/big", big)
                .post("/body-length", bodyLength)
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
     * 大响应体被流控卡住：服务端只能发一个窗口（65535 字节），
     * 客户端不补 WINDOW_UPDATE 就永远收不到剩下的数据。
     */
    @Test
    public void testLargeResponseBlockedUntilWindowUpdate() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.start();
            client.request(1, "GET", "/big", "localhost");

            // 阶段一：读满一个窗口的量
            Collector c = new Collector();
            int guard = 0;
            while (c.bodyLen < WINDOW) {
                Http2TestClient.Frame frame = client.readFrame();
                c.accept(client, frame);
                if (++guard > 200) {
                    throw new IOException("服务端发得比一个窗口还多，流控没生效");
                }
            }
            assertEquals(WINDOW, c.bodyLen, "服务端只能发一个窗口的量");
            assertFalse(c.endStream, "响应不该在窗口耗尽时结束");
            assertEquals(200, c.status);

            // 阶段二：确认服务端确实被流控卡住（不再发任何东西）
            assertNull(client.readFrameOrNull(300),
                    "窗口耗尽后服务端不应该再发数据，实际又发了一帧");

            // 阶段三：归还额度，服务端继续发
            int rounds = 0;
            while (!c.endStream) {
                Http2TestClient.Frame frame = client.readFrameOrNull(300);
                if (frame == null) {
                    // 又被卡住了 —— 再次归还额度
                    rounds++;
                    if (rounds > 10) {
                        throw new IOException("反复归还额度仍然收不到完整响应");
                    }
                    client.sendWindowUpdate(1, WINDOW);
                    client.sendWindowUpdate(0, WINDOW);
                    continue;
                }
                c.accept(client, frame);
            }

            assertEquals(BIG_BODY_SIZE, c.bodyLen, "响应体长度不对");
            assertArrayEquals(bigBody, c.body, "响应体内容不对");
            assertTrue(rounds >= 2, "200KB 的响应至少要归还 2 次额度，实际 " + rounds);
        }
    }

    /**
     * 大请求体：服务端必须边收边发 WINDOW_UPDATE 归还接收额度，
     * 否则客户端发过一个窗口就会把服务端顶死。
     */
    @Test
    public void testLargeRequestBodyIsGrantedByServer() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.start();
            client.sendHeadersOnly(1, "POST", "/body-length", "localhost");

            int sent = 0;
            while (sent < BIG_BODY_SIZE) {
                int n = Math.min(CHUNK, BIG_BODY_SIZE - sent);
                byte[] chunk = new byte[n];
                System.arraycopy(bigBody, sent, chunk, 0, n);
                client.sendData(1, chunk, sent + n == BIG_BODY_SIZE);
                sent += n;
            }

            boolean sawWindowUpdate = false;
            Collector c = new Collector();
            for (int i = 0; i < 200; i++) {
                Http2TestClient.Frame frame = client.readFrame();
                if (frame.type == Http2FrameType.WINDOW_UPDATE) {
                    sawWindowUpdate = true;
                    continue;
                }
                c.accept(client, frame);
                if (c.endStream) {
                    break;
                }
            }

            assertTrue(sawWindowUpdate, "服务端必须发 WINDOW_UPDATE 归还接收额度");
            assertEquals(200, c.status, "大请求体也应正常响应");
            assertEquals(String.valueOf(BIG_BODY_SIZE), c.bodyText(),
                    "handler 看到的 body 长度不对");
        }
    }

    /**
     * 对端把 SETTINGS_INITIAL_WINDOW_SIZE 改成 16384 ⇒ 新流的发送窗口就是 16384，
     * 响应体会更早被卡住（RFC 9113 §6.9.2）。
     */
    @Test
    public void testInitialWindowSizeChangeLimitsResponse() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.start();
            client.sendSettings(16384);
            client.request(1, "GET", "/big", "localhost");

            Collector c = new Collector();
            int guard = 0;
            while (c.bodyLen < 16384) {
                Http2TestClient.Frame frame = client.readFrame();
                c.accept(client, frame);
                if (++guard > 200) {
                    throw new IOException("没收到预期窗口量的数据");
                }
            }
            assertEquals(16384, c.bodyLen, "发送窗口应变成对端通告的 INITIAL_WINDOW_SIZE");
            assertFalse(c.endStream);
            assertNull(client.readFrameOrNull(300),
                    "流级窗口耗尽后服务端不应该再发数据");
        }
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 一次响应（顺带收集帧）。
     */
    private static final class Collector {
        int status;
        byte[] body = new byte[0];
        int bodyLen;
        boolean endStream;
        List<HpackHeader> headers;

        void accept(Http2TestClient client, Http2TestClient.Frame frame) {
            if (frame == null || frame.streamId != 1) {
                return;
            }
            if (frame.type == Http2FrameType.HEADERS) {
                headers = client.decodeHeaders(frame.payload);
                for (HpackHeader h : headers) {
                    if (":status".equals(h.getName())) {
                        status = Integer.parseInt(h.getValue());
                    }
                }
                if (frame.hasFlag(Http2Flags.END_STREAM)) {
                    endStream = true;
                }
            } else if (frame.type == Http2FrameType.DATA) {
                byte[] nb = new byte[body.length + frame.payload.length];
                System.arraycopy(body, 0, nb, 0, body.length);
                System.arraycopy(frame.payload, 0, nb, body.length, frame.payload.length);
                body = nb;
                bodyLen = body.length;
                if (frame.hasFlag(Http2Flags.END_STREAM)) {
                    endStream = true;
                }
            }
        }

        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
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
