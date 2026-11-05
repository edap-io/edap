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

package io.edap.http.server.test.tls;

import io.edap.Edap;
import io.edap.http.HttpHandler;
import io.edap.http.header.ContentTypeHeader;
import io.edap.http.server.HttpServer;
import io.edap.http.server.HttpServerBuilder;
import io.edap.http.server.tls.TlsConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * TLS + ALPN 的端到端测试。
 *
 * <p>覆盖：</p>
 * <ol>
 *   <li>ALPN 协商成 {@code h2} ⇒ HTTP/2 over TLS 正常返回；</li>
 *   <li>ALPN 协商成 {@code http/1.1} ⇒ HTTP/1.1 over TLS 正常返回；</li>
 *   <li>明文端口不受影响（回归）；</li>
 *   <li>明文与 TLS 共享同一张路由表。</li>
 * </ol>
 *
 * <p>测试用的 keystore 是仓库里的自签证书（{@code src/test/resources/edap-test-keystore.p12}），
 * 由 {@code scripts/gen-dev-keystore.sh} 生成 —— 只用于测试，不是任何真实凭据。</p>
 */
public class TlsEndToEndTest {

    private static final String BODY = "hello edap tls";

    /**
     * 大响应体的长度 —— 必须<b>大于</b> socket 的发送缓冲（16KB），
     * 否则测不出「写队列 + OP_WRITE 续写」这条路径。
     */
    private static final int BIG_SIZE = 300_000;

    /** 测试 keystore 的口令（仓库里的自签证书，公开无风险） */
    private static final String KEYSTORE_PASSWORD = "edap-test";

    private int plainPort;
    private int tlsPort;
    private Edap edap;

    @BeforeEach
    void startServer() throws Exception {
        plainPort = freePort();
        tlsPort = freePort();

        HttpHandler hello = (req, resp) -> {
            resp.contentType(ContentTypeHeader.from("text/plain; charset=UTF-8"));
            resp.write(BODY);
        };

        byte[] big = new byte[BIG_SIZE];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) ('a' + (i % 26));
        }
        byte[] bigBody = big;
        HttpHandler bigHandler = (req, resp) -> {
            resp.contentType(ContentTypeHeader.from("application/octet-stream"));
            resp.write(bigBody);
        };

        HttpServerBuilder builder = new HttpServerBuilder()
                .get("/hello", hello)
                .get("/big", bigHandler)
                .listen(plainPort)
                .listenTls(tlsPort)
                .tls(TlsConfig.builder()
                        .keyStore(keyStorePath().toString(), KEYSTORE_PASSWORD)
                        .keyStoreType("PKCS12")
                        .build());

        edap = new Edap();
        List<HttpServer> servers = builder.buildAll();
        assertEquals(2, servers.size(), "应当构建出明文 + TLS 两个 server");
        servers.forEach(edap::addServer);
        edap.run();
        awaitListening(plainPort);
        awaitListening(tlsPort);
    }

    @AfterEach
    void stopServer() {
        if (edap != null) {
            edap.stop();
        }
    }

    /**
     * ALPN 协商成 h2 ⇒ HTTP/2 over TLS。
     */
    @Test
    public void testHttp2OverTls() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .sslContext(trustingContext())
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<String> response = send(client, tlsPort);
        assertEquals(200, response.statusCode(), "状态码不对");
        assertEquals(BODY, response.body(), "响应体不对");
        assertEquals(HttpClient.Version.HTTP_2, response.version(),
                "应当通过 ALPN 协商成 HTTP/2，实际是 " + response.version());
    }

    /**
     * ALPN 协商成 http/1.1 ⇒ HTTP/1.1 over TLS。
     */
    @Test
    public void testHttp11OverTls() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .sslContext(trustingContext())
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<String> response = send(client, tlsPort);
        assertEquals(200, response.statusCode(), "状态码不对");
        assertEquals(BODY, response.body(), "响应体不对");
        assertEquals(HttpClient.Version.HTTP_1_1, response.version(),
                "应当协商成 HTTP/1.1，实际是 " + response.version());
    }

    /**
     * 回归：明文端口不受 TLS 的影响。
     */
    @Test
    public void testPlainPortStillWorks() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpResponse<String> response = send(client, plainPort, "http");
        assertEquals(200, response.statusCode(), "状态码不对");
        assertEquals(BODY, response.body(), "响应体不对");
    }

    /**
     * 回归：TLS + HTTP/2 的大响应体。
     *
     * <p><b>★ 这条用例守的是「TLS 密文 backlog 卡死」那个 bug</b>：
     * 300KB 的响应必然写不满 socket（发送缓冲只有 16KB），剩下的字节挂在
     * TLS 层的密文队列上等 {@code OP_WRITE} 续写。当时有两个坑：</p>
     * <ol>
     *   <li>密文队列头被 NIO 层摘掉时没触发续写 ⇒ 大响应体永远发不出去；</li>
     *   <li>HTTP/2 帧写完就撤销 {@code OP_WRITE}，但那只是把字节交给了 TLS 层，
     *       密文还堵在队列里 ⇒ selector 再也不会醒。</li>
     * </ol>
     */
    @Test
    public void testLargeBodyOverHttp2Tls() throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .sslContext(trustingContext())
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://127.0.0.1:" + tlsPort + "/big"))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode(), "状态码不对");
        assertEquals(HttpClient.Version.HTTP_2, response.version(),
                "应当通过 ALPN 协商成 HTTP/2，实际是 " + response.version());
        assertEquals(BIG_SIZE, response.body().length,
                "大响应体被截断了 —— TLS 密文 backlog 没有排空");
    }

    // ------------------------------------------------------------------

    private HttpResponse<String> send(HttpClient client, int port) throws Exception {
        return send(client, port, "https");
    }

    private HttpResponse<String> send(HttpClient client, int port, String scheme) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(scheme + "://127.0.0.1:" + port + "/hello"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 构造一个信任测试证书的 SSLContext（服务端用的是仓库里的自签证书）。
     */
    private static SSLContext trustingContext() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = TlsEndToEndTest.class.getResourceAsStream("/edap-test-keystore.p12")) {
            assertNotNull(in, "测试 keystore 不在 classpath 上");
            keyStore.load(in, KEYSTORE_PASSWORD.toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    /**
     * 测试 keystore 的文件路径（服务端要按路径加载）。
     */
    private static Path keyStorePath() throws Exception {
        return Paths.get(TlsEndToEndTest.class.getResource("/edap-test-keystore.p12").toURI());
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
