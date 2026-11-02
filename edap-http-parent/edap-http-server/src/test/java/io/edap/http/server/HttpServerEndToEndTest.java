package io.edap.http.server;

import io.edap.Edap;
import io.edap.http.HttpHandler;
import io.edap.http.header.ContentTypeHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * edap-http-parent 的第一个端到端测试：真起服务 + 裸 socket 发 HTTP/1.1 请求。
 *
 * <p>此前 edap-http-parent 的测试全是纯单测 / 基准，没有一个真起过服务，
 * 响应字节长什么样没有任何断言支撑。这里把 HTTP/1.1 的响应字节落成 golden 文件
 * （Date 头归一化），后续响应侧策略化改造必须保证字节级不变。</p>
 */
public class HttpServerEndToEndTest {

    private static final String BODY = "hello edap";

    private int  port;
    private Edap edap;

    @BeforeEach
    void startServer() throws Exception {
        port = freePort();

        HttpHandler hello = (req, resp) -> {
            resp.contentType(ContentTypeHeader.from("text/plain; charset=UTF-8"));
            resp.write(BODY);
            req.getHttpNioSession().writeToChannel(resp.getBuf());
        };
        HttpHandler echo = (req, resp) -> {
            resp.contentType(ContentTypeHeader.from("text/plain; charset=UTF-8"));
            resp.write(req.getPath() + "|" + req.getMethod());
            req.getHttpNioSession().writeToChannel(resp.getBuf());
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

    @Test
    public void testSimpleGet() throws Exception {
        byte[] resp = request("GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n");
        String text = new String(resp, StandardCharsets.ISO_8859_1);

        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"), "状态行不对: " + firstLine(text));
        assertTrue(text.contains("Content-Length: " + BODY.length() + "\r\n"), "缺少 Content-Length: " + text);
        assertTrue(text.endsWith("\r\n\r\n" + BODY), "响应体不对: " + text);
    }

    /**
     * 响应字节 golden：Date 头是秒级动态值，归一化后再比对。
     * golden 文件不存在时自动落盘（首次运行），之后每次都比对。
     */
    @Test
    public void testResponseBytesGolden() throws Exception {
        byte[] resp = request("GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n");
        String actual = normalize(new String(resp, StandardCharsets.ISO_8859_1));

        Path golden = Paths.get("src/test/resources/golden/http1_get_hello.golden");
        if (!Files.exists(golden)) {
            Files.createDirectories(golden.getParent());
            Files.write(golden, actual.getBytes(StandardCharsets.ISO_8859_1));
            return;
        }
        String expected = new String(Files.readAllBytes(golden), StandardCharsets.ISO_8859_1);
        assertEquals(expected, actual, "HTTP/1.1 响应字节与 golden 不一致");
    }

    @Test
    public void testPathAndMethodVisibleToHandler() throws Exception {
        byte[] resp = request("GET /echo HTTP/1.1\r\nHost: localhost\r\n\r\n");
        String text = new String(resp, StandardCharsets.ISO_8859_1);
        assertTrue(text.endsWith("\r\n\r\n/echo|GET"), "handler 拿到的 path/method 不对: " + text);
    }

    @Test
    public void testNotFound() throws Exception {
        byte[] resp = request("GET /not-exist HTTP/1.1\r\nHost: localhost\r\n\r\n");
        String text = new String(resp, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("HTTP/1.1 404"), "未注册路径应返回 404: " + firstLine(text));
    }

    // ------------------------------------------------------------------ 辅助

    /** Date 头是秒级动态值，比对前归一化 */
    private static String normalize(String response) {
        return response.replaceAll("(?im)^Date: .*$", "Date: <DATE>");
    }

    private static String firstLine(String text) {
        int idx = text.indexOf("\r\n");
        return idx < 0 ? text : text.substring(0, idx);
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    /** 等端口真正可连接（容器起 acceptor 是异步的） */
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

    /** 发裸 HTTP 请求，按 Content-Length 读满响应（keep-alive 下服务端不会主动关连接） */
    private byte[] request(String raw) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            out.write(raw.getBytes(StandardCharsets.US_ASCII));
            out.flush();

            ByteArrayOutputStream all = new ByteArrayOutputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int matched = 0;
            while (matched < 4) {
                int b = socket.getInputStream().read();
                if (b < 0) {
                    throw new IOException("响应头未结束连接就关闭了");
                }
                all.write(b);
                head.write(b);
                if ((matched == 0 || matched == 2) && b == '\r') {
                    matched++;
                } else if ((matched == 1 || matched == 3) && b == '\n') {
                    matched++;
                } else {
                    matched = (b == '\r') ? 1 : 0;
                }
            }
            String header = head.toString(StandardCharsets.ISO_8859_1);
            int contentLength = 0;
            for (String line : header.split("\r\n")) {
                if (line.toLowerCase(Locale.ENGLISH).startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                }
            }
            byte[] body = new byte[contentLength];
            int read = 0;
            InputStream in = socket.getInputStream();
            while (read < contentLength) {
                int n = in.read(body, read, contentLength - read);
                if (n < 0) {
                    throw new IOException("响应体未读满连接就关闭了");
                }
                read += n;
            }
            all.write(body);
            return all.toByteArray();
        }
    }
}
