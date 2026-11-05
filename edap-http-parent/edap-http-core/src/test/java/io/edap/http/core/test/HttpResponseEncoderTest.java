package io.edap.http.core.test;

import io.edap.buffer.FastBuf;
import io.edap.http.HttpResponse;
import io.edap.http.HttpVersion;
import io.edap.http.ResponseStatusCode;
import io.edap.http.header.ContentTypeHeader;
import io.edap.http.header.HeaderConnection;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 响应侧策略化（{@code ResponseEncoder} + {@code HttpResponse}）的行为约束。
 *
 * <p>核心约束：<b>HTTP/1.1 的输出字节不能变</b>。这里的期望值是照着改造前
 * {@code HttpResponse} 内联写法的字节逐个抄下来的。</p>
 */
public class HttpResponseEncoderTest {

    /** Header 是抽象类，测试需要一个具体实现 */
    static class TestHeader extends io.edap.http.header.Header {
        TestHeader(String name, String value) {
            super(name, value);
        }
    }

    private static FastBuf buf() {
        return new FastBuf(4096);
    }

    private static String text(FastBuf buf) {
        byte[] bs = new byte[buf.remain()];
        buf.get(bs);
        return new String(bs, StandardCharsets.ISO_8859_1);
    }

    private static HttpResponse response(FastBuf buf) {
        HttpResponse response = new HttpResponse();
        response.setBuf(buf);
        response.setVersion(HttpVersion.HTTP_1_1);
        return response;
    }

    /**
     * write(byte[])：状态行 → Content-Type → Content-Length → Date → Server → 空行 → body。
     */
    @Test
    public void testWriteBytes() throws Exception {
        FastBuf buf = buf();
        HttpResponse response = response(buf);
        response.contentType(ContentTypeHeader.from("text/plain; charset=UTF-8"));
        response.write("hello edap".getBytes(StandardCharsets.UTF_8));

        String actual = text(buf);
        assertTrue(actual.startsWith("HTTP/1.1 200 OK\r\n"), actual);
        assertTrue(actual.contains("Content-Type: text/plain; charset=UTF-8\r\n"), actual);
        assertTrue(actual.contains("Content-Length: 10\r\n"), actual);
        assertTrue(actual.contains("Date: "), actual);
        assertTrue(actual.contains("Server: ews\r\n"), actual);
        assertTrue(actual.endsWith("\r\n\r\nhello edap"), actual);
        // 头之间不能有多余的空行
        assertEquals(-1, actual.indexOf("\r\n\r\n\r\n"), actual);
    }

    /**
     * write(int, byte[])：状态码由响应对象持有。
     */
    @Test
    public void testWriteWithStatusCode() throws Exception {
        FastBuf buf = buf();
        HttpResponse response = response(buf);
        response.contentType(ContentTypeHeader.HTML);
        response.write(404, "<h1>404</h1>".getBytes(StandardCharsets.UTF_8));

        String actual = text(buf);
        assertTrue(actual.startsWith("HTTP/1.1 404 Not Found\r\n"), actual);
        assertTrue(actual.endsWith("\r\n\r\n<h1>404</h1>"), actual);
    }

    /**
     * setSimpleResponse：状态行 → varargs 头 → map 头 → Date → Server → 空行。
     *
     * <p><b>P5-5 的行为变更</b>：改造前本方法不写 Content-Length ⇒ HTTP/1.1 客户端
     * 只能 read-until-close，而 edap 默认 keep-alive 不关连接 ⇒ 客户端死等。
     * 现在补 {@code Content-Length: 0}（无 body）。</p>
     */
    @Test
    public void testSetSimpleResponse() throws Exception {
        FastBuf buf = buf();
        HttpResponse response = response(buf);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "text/plain");
        headers.put("x-amz-error-code", "NoSuchKey");
        response.setSimpleResponse(403, headers);

        String actual = text(buf);
        assertTrue(actual.startsWith("HTTP/1.1 403 Forbidden\r\n"), actual);
        assertTrue(actual.contains("Content-Type: text/plain\r\n"), actual);
        assertTrue(actual.contains("x-amz-error-code: NoSuchKey\r\n"), actual);
        assertTrue(actual.contains("Server: ews\r\n"), actual);
        assertTrue(actual.endsWith("\r\n\r\n"), actual);
        assertTrue(actual.contains("Content-Length: 0\r\n"), actual);
    }

    /**
     * 1xx（101 WebSocket 握手 / h2c 升级）<b>不能</b>带 Content-Length ——
     * 对端会按 0 字节读完响应头，然后把后续的协议数据（WS 帧 / h2 帧）当成
     * 响应体丢掉。
     */
    @Test
    public void testSetSimpleResponseNoContentLengthFor1xx() throws Exception {
        FastBuf buf = buf();
        HttpResponse response = response(buf);
        response.setSimpleResponse(101, new LinkedHashMap<>(), HeaderConnection.UPGRADE);

        String actual = text(buf);
        assertTrue(actual.startsWith("HTTP/1.1 101 Switching Protocols\r\n"), actual);
        assertFalse(actual.contains("Content-Length"), actual);
    }

    /**
     * 204 / 304 同样不能带 Content-Length（RFC 9110 §8.6）。
     */
    @Test
    public void testSetSimpleResponseNoContentLengthFor204And304() throws Exception {
        for (int code : new int[]{204, 304}) {
            FastBuf buf = buf();
            HttpResponse response = response(buf);
            response.setSimpleResponse(code, new LinkedHashMap<>());
            assertFalse(text(buf).contains("Content-Length"), "status=" + code);
        }
    }

    /**
     * 调用方自己给了 Content-Length（edap-s3 就是这种用法）⇒ 框架<b>不</b>再输出一个，
     * 否则会出现两个 Content-Length。响应字节与改造前完全一致。
     */
    @Test
    public void testSetSimpleResponseKeepsCallerContentLength() throws Exception {
        FastBuf buf = buf();
        HttpResponse response = response(buf);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/xml");
        headers.put("Content-Length", "128");
        response.setSimpleResponse(404, headers);

        String actual = text(buf);
        assertTrue(actual.contains("Content-Length: 128\r\n"), actual);
        assertEquals(1, countOccurrences(actual, "Content-Length:"), "Content-Length 只能有一个: " + actual);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /**
     * setSimpleResponse 的 varargs 头输出在 map 头之前（WebSocket 握手依赖这个顺序）。
     */
    @Test
    public void testSetSimpleResponseExtraHeadersFirst() throws Exception {
        FastBuf buf = buf();
        HttpResponse response = response(buf);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Sec-WebSocket-Accept", "abc");
        response.setSimpleResponse(101, headers, HeaderConnection.UPGRADE);

        String actual = text(buf);
        assertTrue(actual.startsWith("HTTP/1.1 101 Switching Protocols\r\n"), actual);
        assertTrue(actual.indexOf("Connection: Upgrade") < actual.indexOf("Sec-WebSocket-Accept"), actual);
    }

    /**
     * header(String, String)：改造前只 put 不输出，现在必须真的写出去。
     */
    @Test
    public void testHeaderStringIsWritten() throws Exception {
        FastBuf buf = buf();
        HttpResponse response = response(buf);
        response.contentType(ContentTypeHeader.PLAIN);
        response.header("X-Custom", "v1");
        response.write("ok".getBytes(StandardCharsets.UTF_8));

        String actual = text(buf);
        assertTrue(actual.contains("X-Custom: v1\r\n"), actual);
    }

    /**
     * header(Header)：改造前是空实现，现在必须真的写出去。
     */
    @Test
    public void testHeaderObjectIsWritten() throws Exception {
        FastBuf buf = buf();
        HttpResponse response = response(buf);
        response.contentType(ContentTypeHeader.PLAIN);
        response.header(new TestHeader("X-Trace", "t1"));
        response.write("ok".getBytes(StandardCharsets.UTF_8));

        String actual = text(buf);
        assertTrue(actual.contains("X-Trace: t1\r\n"), actual);
    }

    /**
     * 内置头不允许被用户覆盖（Server / Date / Content-Length 由框架输出）。
     */
    @Test
    public void testBuiltinHeaderCannotBeOverridden() throws Exception {
        FastBuf buf = buf();
        HttpResponse response = response(buf);
        response.contentType(ContentTypeHeader.PLAIN);
        response.header("Server", "evil");
        response.write("ok".getBytes(StandardCharsets.UTF_8));

        String actual = text(buf);
        assertTrue(actual.contains("Server: ews\r\n"), actual);
        assertFalse(actual.contains("evil"), actual);
    }

    /**
     * 响应对象是线程级单例 —— reset 必须清掉上一个请求的状态，否则会串到下一个响应。
     */
    @Test
    public void testResetClearsState() throws Exception {
        FastBuf buf = buf();
        HttpResponse response = response(buf);
        response.contentType(ContentTypeHeader.PLAIN);
        response.header("X-Custom", "v1");
        response.setStatusCode(500);
        response.write("ok".getBytes(StandardCharsets.UTF_8));

        response.reset();

        assertEquals(200, response.getStatusCode());
        assertEquals(0, response.getHeaders().size());
        assertEquals(0, response.getExtraHeaders() == null ? 0 : response.getExtraHeaders().size());

        FastBuf buf2 = buf();
        response.setBuf(buf2);
        response.write("ok2".getBytes(StandardCharsets.UTF_8));
        String actual = text(buf2);
        assertFalse(actual.contains("X-Custom"), actual);
        assertTrue(actual.startsWith("HTTP/1.1 200 OK\r\n"), actual);
        assertFalse(actual.contains("Content-Type"), "reset 后 contentType 也必须清掉: " + actual);
    }

    /**
     * HTTP/2 的 {@code :status} 伪头只有 3 字节状态码，没有 CRLF。
     */
    @Test
    public void testStatusCodeBytes() {
        assertArrayEquals("200".getBytes(StandardCharsets.ISO_8859_1), ResponseStatusCode.getCodeBytes(200));
        assertArrayEquals("404".getBytes(StandardCharsets.ISO_8859_1), ResponseStatusCode.getCodeBytes(404));
        assertArrayEquals("500".getBytes(StandardCharsets.ISO_8859_1), ResponseStatusCode.getCodeBytes(500));
    }

    /**
     * HTTP/2 的版本标识 —— 新增 {@code HTTP_2}，历史的 {@code HTTP_2_0} 保留但标 deprecated。
     */
    @Test
    public void testHttp2Version() {
        assertTrue(HttpVersion.HTTP_2.isHttp2());
        assertTrue(HttpVersion.HTTP_2_0.isHttp2());
        assertFalse(HttpVersion.HTTP_1_1.isHttp2());
        assertEquals(HttpVersion.HTTP_2, HttpVersion.fromVersion("HTTP/2"));
        assertEquals(HttpVersion.HTTP_2, HttpVersion.fromVersion(2));
        assertEquals(HttpVersion.HTTP_2_0, HttpVersion.fromVersion("HTTP/2.0"));
    }
}
