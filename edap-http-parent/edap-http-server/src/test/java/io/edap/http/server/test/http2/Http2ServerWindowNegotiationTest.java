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
import io.edap.http.http2.Http2FrameType;
import io.edap.http.server.HttpServer;
import io.edap.http.server.HttpServerBuilder;
import io.edap.http.server.http2.Http2FlowController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 服务端建连时的<b>流控窗口协商</b>测试（RFC 9113 §6.9.2）。
 *
 * <p><b>回归背景</b>：服务端曾经只通告默认的 65535 窗口，也从不主动扩大连接级窗口。
 * 后果是「一条连接上能同时在途的字节只有 64KB」：</p>
 * <ul>
 *   <li>64KB 的负载 → 一条连接最多 1 个请求在途 → 并发 1 和并发 32 吞吐一样；</li>
 *   <li>grpc-java 两端都会把连接级窗口撑到 1MB，并用 SETTINGS 通告 1MB 的流级窗口。</li>
 * </ul>
 *
 * <p>本测试断言服务端建连后必须发出：</p>
 * <ol>
 *   <li>{@code SETTINGS(INITIAL_WINDOW_SIZE=1MB)} —— 流级接收窗口；</li>
 *   <li>{@code WINDOW_UPDATE(streamId=0)} —— 把连接级接收窗口撑到 {@link Http2FlowController#CONN_WINDOW}
 *       （连接级窗口<b>不受</b> SETTINGS_INITIAL_WINDOW_SIZE 影响，只能这样撑大）。</li>
 * </ol>
 */
public class Http2ServerWindowNegotiationTest {

    /** 服务端应该通告的流级接收窗口 */
    private static final int EXPECTED_STREAM_WINDOW = 1024 * 1024;

    /** 服务端应该把连接级接收窗口撑到的值（与生产常量同源，避免两处各写一份） */
    private static final int EXPECTED_CONN_WINDOW = Http2FlowController.CONN_WINDOW;

    /** SETTINGS_INITIAL_WINDOW_SIZE 的设置项编号 */
    private static final int SETTINGS_INITIAL_WINDOW_SIZE = 0x4;

    private int port;
    private Edap edap;

    @BeforeEach
    void startServer() throws Exception {
        port = freePort();

        HttpHandler hello = (req, resp) -> {
            resp.contentType(ContentTypeHeader.from("text/plain; charset=UTF-8"));
            resp.write("hello edap");
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

    @Test
    public void testServerAdvertisesBigWindowsOnConnect() throws Exception {
        try (Http2TestClient client = new Http2TestClient(port)) {
            client.start();

            // 建连后服务端的第一帧必须是 SETTINGS（RFC 9113 §3.4）
            Http2TestClient.Frame settings = client.readFrame();
            assertNotNull(settings, "服务端必须先发 SETTINGS");
            assertEquals(Http2FrameType.SETTINGS, settings.type,
                    "服务端建连后的第一帧必须是 SETTINGS");
            assertEquals(EXPECTED_STREAM_WINDOW,
                    readSetting(settings.payload, SETTINGS_INITIAL_WINDOW_SIZE),
                    "服务端必须通告 INITIAL_WINDOW_SIZE=1MB —— 65535 太小，"
                            + "一条 64KB 的请求塞不进一个窗口，在途请求数被压在 1 个");

            // 紧接着必须有一帧连接级的 WINDOW_UPDATE，把连接级窗口撑到 1MB
            Http2TestClient.Frame windowUpdate = client.readFrame();
            assertNotNull(windowUpdate, "服务端必须发连接级的 WINDOW_UPDATE");
            assertEquals(Http2FrameType.WINDOW_UPDATE, windowUpdate.type,
                    "SETTINGS 之后应该是 WINDOW_UPDATE");
            assertEquals(0, windowUpdate.streamId,
                    "连接级 WINDOW_UPDATE 的 streamId 必须是 0");
            assertEquals(EXPECTED_CONN_WINDOW - 65535, readInt(windowUpdate.payload, 0),
                    "服务端必须把连接级接收窗口撑到 1MB —— "
                            + "连接级窗口不受 SETTINGS_INITIAL_WINDOW_SIZE 影响（RFC 9113 §6.9.2），"
                            + "只能靠接收方发 WINDOW_UPDATE 撑大");
        }
    }

    // ------------------------------------------------------------------ 辅助

    private static int readSetting(byte[] payload, int id) {
        for (int i = 0; i + 6 <= payload.length; i += 6) {
            if (((payload[i] & 0xFF) << 8 | (payload[i + 1] & 0xFF)) == id) {
                return readInt(payload, i + 2);
            }
        }
        return -1;
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
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
