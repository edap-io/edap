package io.edap.s3.http;

import io.edap.buffer.FastBuf;
import io.edap.http.HttpHandler;
import io.edap.http.HttpRequest;
import io.edap.http.HttpResponse;
import io.edap.http.server.HttpServer;
import io.edap.http.server.HttpServerBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest.*;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import org.junit.jupiter.api.Test;

public class RawHttpTest {
    @Test
    void rawSocketRequest() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }

        HttpHandler handler = new HttpHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse resp) throws IOException {
                System.out.println(">>> HANDLER CALLED for " + req.getMethod() + " " + req.getPath());
                resp.setSimpleResponse(200, java.util.Map.of("Content-Type", "text/plain"));
                FastBuf buf = resp.getBuf();
                if (buf != null) {
                    byte[] body = "hello".getBytes();
                    buf.write(body, 0, body.length);
                }
                req.getHttpNioSession().writeToChannel(resp.getBuf());
                System.out.println(">>> HANDLER DONE");
            }
        };

        HttpServer server = new HttpServerBuilder()
                .get("/*", handler)
                .listen(port)
                .build();

        io.edap.Edap edap = new io.edap.Edap();
        edap.addServer(server);
        edap.run();
        Thread.sleep(100);

        try (Socket sock = new Socket("127.0.0.1", port)) {
            sock.setSoTimeout(3000);
            OutputStream out = sock.getOutputStream();
            InputStream in = sock.getInputStream();
            String req = "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            System.out.println(">>> SENDING: " + req.replace("\r\n", "\\r\\n\n"));
            out.write(req.getBytes());
            out.flush();

            byte[] resp = new byte[4096];
            int total = 0;
            int n;
            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline && total < resp.length) {
                try {
                    n = in.read(resp, total, resp.length - total);
                    if (n < 0) {
                        System.out.println(">>> CLIENT READ EOF after " + total + " bytes");
                        break;
                    }
                    total += n;
                } catch (java.net.SocketTimeoutException e) {
                    System.out.println(">>> CLIENT READ TIMEOUT after " + total + " bytes");
                    break;
                } catch (java.net.SocketException e) {
                    System.out.println(">>> CLIENT READ EX: " + e + " after " + total + " bytes");
                    break;
                }
            }
            System.out.println(">>> TOTAL READ: " + total + " bytes");
            if (total > 0) {
                System.out.println(">>> RESPONSE:\n" + new String(resp, 0, total));
            }
        }
        edap.stop();
    }

    @Test
    void httpClientRequest() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }

        HttpHandler handler = new HttpHandler() {
            @Override
            public void handle(HttpRequest req, HttpResponse resp) throws IOException {
                System.out.println(">>> HANDLER CALLED for " + req.getMethod() + " " + req.getPath());
                byte[] body = "hello".getBytes();
                // 必须显式写 Content-Length:edap setSimpleResponse 不写 Content-Length,
                // HTTP/1.1 客户端默认 read-until-close 模式;edap keep-alive 不关连接,
                // 客户端会一直等 body 终止,3s request timeout 也不触发(实测)。
                resp.setSimpleResponse(200, java.util.Map.of(
                        "Content-Type", "text/plain",
                        "Content-Length", String.valueOf(body.length)));
                FastBuf buf = resp.getBuf();
                if (buf != null) {
                    buf.write(body, 0, body.length);
                }
                req.getHttpNioSession().writeToChannel(resp.getBuf());
                System.out.println(">>> HANDLER DONE");
            }
        };

        HttpServer server = new HttpServerBuilder()
                .get("/*", handler)
                .listen(port)
                .build();

        io.edap.Edap edap = new io.edap.Edap();
        edap.addServer(server);
        edap.run();
        Thread.sleep(100);

        // 强制 HTTP_1_1:默认 HTTP_2 会先发 Upgrade: h2c,edap 的 setSimpleResponse 只写
        // HTTP/1.1 200 OK 不回 101,JDK HttpClient 在 h2c preface 等死、request timeout 不触发,
        // 见 RawHttpTest.httpClientRequest 卡住的复现 / 根因。
        java.net.http.HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build();
        try {
            java.net.http.HttpResponse<String> resp = client.send(
                    java.net.http.HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + port + "/"))
                            .timeout(Duration.ofSeconds(3))
                            .GET().build(),
                    BodyHandlers.ofString());
            System.out.println(">>> HTTP CLIENT status=" + resp.statusCode() + " body=" + resp.body());
        } catch (Exception e) {
            System.out.println(">>> HTTP CLIENT EX: " + e);
        }
        edap.stop();
    }
}
