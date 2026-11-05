package io.edap.http.server.test.http2;

import io.edap.Decoder;
import io.edap.buffer.FastBuf;
import io.edap.http.HttpNioSession;
import io.edap.http.HttpRequest;
import io.edap.http.MethodInfo;
import io.edap.http.ValueHttpRequest;
import io.edap.http.server.Http1Connection;
import io.edap.http.server.HttpServerNioSession;
import io.edap.nio.ParseResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP/1.1 连接策略：嗅探暂存字节的回放 + 跨 read 未消费字节的保留。
 */
public class Http1ConnectionTest {

    private static final String REQ = "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n";

    /**
     * 流式解码器 stub：模仿真实解码器「解析不完整时不推进读位置」的行为
     * （{@code MethodDecoder} / {@code PathDecoder} 找不到分隔符时都直接返回 null）。
     *
     * <p>只关心「字节有没有被吞掉」，所以这里直接把缓冲里的文本当请求行看待。</p>
     */
    static class StreamDecoder implements Decoder<HttpRequest, HttpNioSession> {
        String lastRequest;
        int requests;

        @Override
        public ParseResult<HttpRequest> decode(FastBuf bufIn, HttpNioSession session) {
            ParseResult<HttpRequest> result = new ParseResult<>();
            int remain = bufIn.remain();
            if (remain == 0) {
                return result;
            }
            long rpos = bufIn.rpos();
            byte[] tmp = new byte[remain];
            bufIn.get(rpos, tmp, 0, remain);
            String text = new String(tmp, StandardCharsets.ISO_8859_1);
            if (text.contains("\r\n\r\n")) {
                // 完整了 —— 消费掉
                bufIn.rpos(rpos + remain);
                lastRequest = text;
                requests++;
                ValueHttpRequest req = new ValueHttpRequest();
                req.setPath("/hello");
                // 真实解码器一定会设置 method —— stub 也要模仿它，
                // 否则 h2c 升级判定（getMethodInfo）拿不到方法。
                MethodInfo methodInfo = new MethodInfo();
                methodInfo.setMethod("GET");
                methodInfo.setMethodIndex(0);
                req.methodInfo = methodInfo;
                result.setMessage(req);
                result.setFinished(true);
            }
            // 不完整 —— 不推进读位置（与真实解码器一致）
            return result;
        }

        @Override
        public void reset() {
        }
    }

    static class RecordingSession extends HttpServerNioSession {
        final List<String> handled = new ArrayList<>();

        @Override
        public void handle(HttpRequest request) throws IOException {
            handled.add(request.getPath());
        }
    }

    private static FastBuf buf(String s) {
        FastBuf b = new FastBuf(Math.max(64, s.length()));
        b.write(s.getBytes(StandardCharsets.ISO_8859_1));
        return b;
    }

    private static RecordingSession newSession(StreamDecoder decoder) {
        RecordingSession session = new RecordingSession();
        session.setDecoder(decoder);
        return session;
    }

    /**
     * 快路径：没有暂存字节，行为与改造前一致。
     */
    @Test
    public void testFastPath() throws IOException {
        StreamDecoder decoder = new StreamDecoder();
        RecordingSession session = newSession(decoder);
        Http1Connection conn = new Http1Connection(session, new byte[0]);

        assertTrue(conn.decode(buf(REQ)));
        assertEquals(1, decoder.requests);
        assertEquals(REQ, decoder.lastRequest);
        assertEquals(1, session.handled.size());
        assertEquals(0, conn.getPendingLen());
    }

    /**
     * 嗅探暂存的 1 个字节必须回放给解码器 —— 否则 "G" 被吞掉，请求解析不出来。
     */
    @Test
    public void testCarryIsReplayed() throws IOException {
        StreamDecoder decoder = new StreamDecoder();
        RecordingSession session = newSession(decoder);
        Http1Connection conn = new Http1Connection(session, "G".getBytes(StandardCharsets.ISO_8859_1));

        assertTrue(conn.decode(buf(REQ.substring(1))));
        assertEquals(1, decoder.requests);
        assertEquals(REQ, decoder.lastRequest, "暂存的 G 必须被回放");
        assertEquals(0, conn.getPendingLen());
    }

    /**
     * 嗅探暂存的 2 个字节（"PO" 开头的 HTTP/1.1 请求）同样要回放。
     */
    @Test
    public void testTwoCarryBytesReplayed() throws IOException {
        StreamDecoder decoder = new StreamDecoder();
        RecordingSession session = newSession(decoder);
        Http1Connection conn = new Http1Connection(session, "GE".getBytes(StandardCharsets.ISO_8859_1));

        assertTrue(conn.decode(buf(REQ.substring(2))));
        assertEquals(REQ, decoder.lastRequest);
        assertEquals(1, decoder.requests);
    }

    /**
     * 请求跨 read 拆分：未消费完的字节必须留到下一次 read，不能丢
     * （慢路径：暂存字节非空时进入）。
     */
    @Test
    public void testPartialRequestKeepsRemainder() throws IOException {
        StreamDecoder decoder = new StreamDecoder();
        RecordingSession session = newSession(decoder);
        Http1Connection conn = new Http1Connection(session, "G".getBytes(StandardCharsets.ISO_8859_1));

        assertFalse(conn.decode(buf("ET /hel")), "请求不完整，不应该报完成");
        assertEquals(0, decoder.requests);
        assertTrue(conn.getPendingLen() > 0, "未消费的字节必须保留");

        assertTrue(conn.decode(buf("lo HTTP/1.1\r\nHost: localhost\r\n\r\n")));
        assertEquals(1, decoder.requests);
        assertEquals(REQ, decoder.lastRequest);
        assertEquals(0, conn.getPendingLen());
    }

    /**
     * 暂存字节 + 跨 read 拆分叠加：两条路径都要正确。
     */
    @Test
    public void testCarryPlusPartial() throws IOException {
        StreamDecoder decoder = new StreamDecoder();
        RecordingSession session = newSession(decoder);
        Http1Connection conn = new Http1Connection(session, "G".getBytes(StandardCharsets.ISO_8859_1));

        assertFalse(conn.decode(buf("ET /hel")));
        assertTrue(conn.decode(buf("lo HTTP/1.1\r\nHost: localhost\r\n\r\n")));
        assertEquals(REQ, decoder.lastRequest);
        assertEquals(1, decoder.requests);
    }

    /**
     * 快路径保持改造前的行为：请求跨 read 拆分时未消费的字节仍然丢弃
     * （HTTP/1.1 的这个老问题不在本次改动范围内，留作独立任务）。
     */
    @Test
    public void testFastPathDoesNotKeepRemainder() throws IOException {
        StreamDecoder decoder = new StreamDecoder();
        RecordingSession session = newSession(decoder);
        Http1Connection conn = new Http1Connection(session, new byte[0]);

        assertFalse(conn.decode(buf("GET /hel")));
        assertEquals(0, conn.getPendingLen(), "快路径行为与改造前一致：不保留未消费字节");
    }
}
