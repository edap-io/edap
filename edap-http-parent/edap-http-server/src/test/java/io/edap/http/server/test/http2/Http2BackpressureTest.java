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
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * HTTP/2 出站背压测试 —— <b>客户端不读</b>时服务端的出站路径。
 *
 * <p><b>★ 要测的是什么</b>：出站累积缓冲（{@code Http2FrameWriter}）满了以后，
 * 服务端会尝试把已累积的字节写出去。socket 写不动时（对端不读 ⇒ 内核发送/接收缓冲都满）
 * 这一步是<b>半写</b>：一部分字节进了 socket，剩下的挂到 NIO 层的写队列上等 {@code OP_WRITE}。
 *
 * <p>半写之后，累积缓冲<b>已经交给了 NIO 层</b>（本类手里没有缓冲了）——
 * 如果后续逻辑还按「手里有缓冲」去处理，就会 NPE / 丢字节。
 * 这个路径在「客户端一直读」的基准里<b>永远走不到</b>，
 * 必须用「客户端不读 + 大响应」才能逼出来。</p>
 */
public class Http2BackpressureTest {

    /**
     * 响应体大小 —— 必须<b>远大于</b>出站累积缓冲（{@code NioSession.WRITE_BUFFER_SIZE}，
     * 默认 256KB），否则累积缓冲永远填不满，走不到「缓冲满了 → 先写出去」那条路。
     */
    private static final int BODY_SIZE = 4 * 1024 * 1024;

    /** 流级 / 连接级窗口 —— 要能装下整个响应，否则会被流控卡住而不是被 socket 卡住。 */
    private static final int WINDOW = 16 * 1024 * 1024;

    private int port;
    private Edap edap;
    private byte[] bigBody;

    @BeforeEach
    void startServer() throws Exception {
        port = freePort();
        bigBody = new byte[BODY_SIZE];
        for (int i = 0; i < bigBody.length; i++) {
            bigBody[i] = (byte) ('a' + (i % 26));
        }

        HttpHandler big = (req, resp) -> {
            resp.contentType(ContentTypeHeader.from("application/octet-stream"));
            resp.write(bigBody);
        };

        HttpServer server = new HttpServerBuilder()
                .get("/big", big)
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
     * 客户端<b>不读</b> ⇒ 服务端的 socket 写不动 ⇒ 走「半写」路径。
     *
     * <p>断言：服务端不能因为出站缓冲处理不当而抛异常（那会变成 RST_STREAM，
     * 客户端看到的是「响应被中断」而不是完整数据）。</p>
     */
    @Test
    public void testLargeResponseWhenClientDoesNotRead() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.start();
            // 把流级与连接级窗口都撑大 —— 否则响应会被流控卡住，而不是被 socket 卡住
            client.sendSettings(WINDOW);
            client.sendWindowUpdate(0, WINDOW);
            client.request(1, "GET", "/big", "localhost");

            // ★ 故意不读：让服务端的 socket 发送缓冲 + 本端接收缓冲都填满
            TimeUnit.MILLISECONDS.sleep(1000);

            // 现在开始读，把响应全部收回来
            Collector c = new Collector();
            while (!c.endStream) {
                Http2TestClient.Frame frame = client.readFrameOrNull(3000);
                if (frame == null) {
                    break;
                }
                if (frame.type == Http2FrameType.RST_STREAM) {
                    fail("服务端发了 RST_STREAM —— 出站路径在半写时出异常了");
                }
                c.accept(client, frame);
            }

            assertEquals(200, c.status, "响应头不对");
            assertFalse(c.rstStream, "服务端不该发 RST_STREAM");
            assertEquals(BODY_SIZE, c.bodyLen, "响应体没收全（半写路径丢了字节）");
            assertArrayEquals(bigBody, c.body, "响应体内容不对");
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
        boolean rstStream;

        void accept(Http2TestClient client, Http2TestClient.Frame frame) {
            if (frame == null) {
                return;
            }
            if (frame.type == Http2FrameType.RST_STREAM) {
                rstStream = true;
                return;
            }
            if (frame.streamId != 1) {
                return;
            }
            if (frame.type == Http2FrameType.HEADERS) {
                List<HpackHeader> headers = client.decodeHeaders(frame.payload);
                for (HpackHeader h : headers) {
                    if (":status".equals(h.getName())) {
                        status = Integer.parseInt(h.getValue());
                    }
                }
                if (frame.hasFlag(Http2Flags.END_STREAM)) {
                    endStream = true;
                }
            } else if (frame.type == Http2FrameType.DATA) {
                byte[] nb = new byte[bodyLen + frame.payload.length];
                System.arraycopy(body, 0, nb, 0, bodyLen);
                System.arraycopy(frame.payload, 0, nb, bodyLen, frame.payload.length);
                body = nb;
                bodyLen = body.length;
                if (frame.hasFlag(Http2Flags.END_STREAM)) {
                    endStream = true;
                }
            }
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
