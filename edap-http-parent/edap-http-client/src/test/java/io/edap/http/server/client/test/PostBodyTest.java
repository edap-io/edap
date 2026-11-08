package io.edap.http.server.client.test;

import com.sun.net.httpserver.HttpServer;
import io.edap.http.server.client.HttpBody;
import io.edap.http.server.client.HttpClient;
import io.edap.http.server.client.HttpResp;
import io.edap.http.server.client.HttpReq;
import io.edap.http.server.client.method.Post;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

public class PostBodyTest {

    static HttpBody body(byte[] bytes) {
        return new HttpBody() {
            @Override
            public void writeTo(OutputStream out) {
                try {
                    out.write(bytes);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }

            @Override
            public byte[] bytes() {
                return bytes;
            }
        };
    }

    @Test
    public void testSetBodyThenGetBody() throws IOException {
        byte[] content = "hello edap".getBytes(StandardCharsets.UTF_8);
        Post post = new Post("http://127.0.0.1:1/x");
        assertSame(post, post.setBody(body(content)));
        assertNotNull(post.getBody());
        assertArrayEquals(content, post.getBody().bytes());

        HttpReq req = post;
        assertNotNull(req.getBody());
        assertArrayEquals(content, req.getBody().bytes());
    }

    @Test
    public void testPostBodyReachesServer() throws IOException {
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = exchange.getRequestBody()) {
                in.transferTo(out);
            }
            received.set(new String(out.toByteArray(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            HttpClient client = new HttpClient();
            Post post = new Post("http://127.0.0.1:" + server.getAddress().getPort() + "/echo")
                    .setBody(body("hello edap".getBytes(StandardCharsets.UTF_8)));
            post.setHeader("cache", "fdsafd");
            post.setHeader("host", "www.easyea.com");
            HttpResp resp = client.post(post);
            assertEquals(200, resp.code());
            assertEquals("hello edap", received.get());
        } finally {
            server.stop(0);
        }
    }
}
