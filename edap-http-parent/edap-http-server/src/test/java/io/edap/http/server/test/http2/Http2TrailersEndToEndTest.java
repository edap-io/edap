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
 * HTTP/2 trailers 的端到端测试。
 *
 * <p><b>trailers 是 gRPC 的命脉</b>：gRPC 的响应以 trailers 结束，
 * {@code grpc-status} / {@code grpc-message} 都在里面。本测试验证
 * 「响应头 → body → trailers(HEADERS + END_STREAM)」这条链路，
 * 以及「没有 trailers 时响应形状不变」。</p>
 */
public class Http2TrailersEndToEndTest {

    private int  port;
    private Edap edap;

    @BeforeEach
    void startServer() throws Exception {
        port = freePort();

        HttpHandler withTrailer = (req, resp) -> {
            resp.contentType(ContentTypeHeader.from("text/plain; charset=UTF-8"));
            resp.begin();
            byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
            resp.chunk(body, 0, body.length);
            resp.putTrailer("x-trailer", "v1");
            resp.putTrailer("grpc-status", "0");
            resp.end();
        };

        HttpHandler plain = (req, resp) -> {
            resp.contentType(ContentTypeHeader.from("text/plain; charset=UTF-8"));
            resp.write("hello".getBytes(StandardCharsets.UTF_8));
        };

        HttpServer server = new HttpServerBuilder()
                .get("/trailer", withTrailer)
                .get("/plain", plain)
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
     * 响应头 → body → trailers(END_STREAM)。
     */
    @Test
    public void testTrailersEndStream() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.start();
            client.request(1, "GET", "/trailer", "localhost");

            List<HpackHeader> head = null;
            byte[] body = new byte[0];
            List<HpackHeader> trailers = null;
            boolean endStream = false;

            for (int i = 0; i < 32; i++) {
                Http2TestClient.Frame frame = client.readFrame();
                if (frame.streamId != 1) {
                    continue;
                }
                if (frame.type == Http2FrameType.HEADERS) {
                    List<HpackHeader> decoded = client.decodeHeaders(frame.payload);
                    if (head == null) {
                        head = decoded;
                    } else {
                        // 第二个 HEADERS 就是 trailers
                        trailers = decoded;
                    }
                    if (frame.hasFlag(Http2Flags.END_STREAM)) {
                        endStream = true;
                        break;
                    }
                } else if (frame.type == Http2FrameType.DATA) {
                    body = concat(body, frame.payload);
                    if (frame.hasFlag(Http2Flags.END_STREAM)) {
                        endStream = true;
                        break;
                    }
                }
            }

            assertNotNull(head, "没有收到响应头");
            assertEquals("200", value(head, ":status"), "状态码不对，headers=" + head);
            assertEquals("hello", new String(body, StandardCharsets.UTF_8), "响应体不对");
            assertNotNull(trailers, "没有收到 trailers");
            assertEquals("v1", value(trailers, "x-trailer"), "trailer 不对: " + trailers);
            assertEquals("0", value(trailers, "grpc-status"), "trailer 不对: " + trailers);
            assertTrue(endStream, "响应必须以 END_STREAM 结束");
        }
    }

    /**
     * 没有 trailers 时，响应仍然是「HEADERS + DATA(END_STREAM)」——既有形状不变。
     */
    @Test
    public void testNoTrailersKeepsOldShape() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.start();
            client.request(3, "GET", "/plain", "localhost");

            int headFrames = 0;
            int dataFrames = 0;
            boolean endStream = false;
            for (int i = 0; i < 32; i++) {
                Http2TestClient.Frame frame = client.readFrame();
                if (frame.streamId != 3) {
                    continue;
                }
                if (frame.type == Http2FrameType.HEADERS) {
                    headFrames++;
                    if (frame.hasFlag(Http2Flags.END_STREAM)) {
                        endStream = true;
                        break;
                    }
                } else if (frame.type == Http2FrameType.DATA) {
                    dataFrames++;
                    if (frame.hasFlag(Http2Flags.END_STREAM)) {
                        endStream = true;
                        break;
                    }
                }
            }
            assertTrue(endStream, "响应必须以 END_STREAM 结束");
            assertEquals(1, headFrames, "普通响应只应该有一个 HEADERS 帧");
            // 既有形状：DATA(内容) + DATA(空, END_STREAM)
            assertEquals(2, dataFrames, "普通响应应该是「内容 DATA + 空 DATA(END_STREAM)」两个 DATA 帧");
        }
    }

    // ------------------------------------------------------------------ 辅助

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static String value(List<HpackHeader> headers, String name) {
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
