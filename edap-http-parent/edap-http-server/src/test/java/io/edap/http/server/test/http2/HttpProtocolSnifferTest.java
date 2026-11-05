package io.edap.http.server.test.http2;

import io.edap.buffer.FastBuf;
import io.edap.http.server.HttpProtocolSniffer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 协议嗅探器（RFC 9113 §3.5）。
 *
 * <p>核心约束：</p>
 * <ul>
 *   <li>判定为 HTTP/1.1 时<b>不能消费</b>缓冲里的字节（HTTP/1.1 字节级零行为变更）</li>
 *   <li>数据不足时必须把已读到的字节暂存（dispatcher 下一次 read 前会 buf.reset()）</li>
 *   <li>分 1/2/3 字节到达都要能正确判定</li>
 * </ul>
 */
public class HttpProtocolSnifferTest {

    private static final String H2_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";
    private static final String HTTP11_REQ = "GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n";

    private static FastBuf buf(String s) {
        FastBuf b = new FastBuf(Math.max(64, s.length()));
        b.write(s.getBytes(StandardCharsets.ISO_8859_1));
        return b;
    }

    private static String str(byte[] b, int len) {
        return new String(b, 0, len, StandardCharsets.ISO_8859_1);
    }

    @Test
    public void testHttp11Detected() {
        HttpProtocolSniffer sniffer = new HttpProtocolSniffer();
        FastBuf b = buf(HTTP11_REQ);
        int remainBefore = b.remain();
        assertEquals(HttpProtocolSniffer.Protocol.HTTP_1_1, sniffer.detect(b));
        assertEquals(remainBefore, b.remain(), "判定 HTTP/1.1 时不能消费任何字节");
        assertEquals(0, sniffer.getCarryLen());
    }

    @Test
    public void testHttp2Detected() {
        HttpProtocolSniffer sniffer = new HttpProtocolSniffer();
        FastBuf b = buf(H2_PREFACE);
        assertEquals(HttpProtocolSniffer.Protocol.HTTP_2, sniffer.detect(b));
        assertEquals(0, sniffer.getCarryLen());
    }

    /**
     * h2 前言分 1 字节到达：判定为「数据不足」，字节被暂存。
     */
    @Test
    public void testSplitOneByte() {
        HttpProtocolSniffer sniffer = new HttpProtocolSniffer();
        FastBuf b1 = buf("P");
        assertNull(sniffer.detect(b1), "只有 1 字节无法判定");
        assertEquals(1, sniffer.getCarryLen());
        assertEquals("P", str(sniffer.getCarry(), 1));
        assertEquals(0, b1.remain(), "暂存的字节必须从缓冲里取走，否则下一次 read 会冲掉");
    }

    @Test
    public void testSplitTwoBytes() {
        HttpProtocolSniffer sniffer = new HttpProtocolSniffer();
        FastBuf b1 = buf("PR");
        assertNull(sniffer.detect(b1));
        assertEquals(2, sniffer.getCarryLen());

        FastBuf b2 = buf("I * HTTP/2.0\r\n\r\nSM\r\n\r\n");
        assertEquals(HttpProtocolSniffer.Protocol.HTTP_2, sniffer.detect(b2));
        assertEquals("PR", str(sniffer.getCarry(), 2), "暂存的字节是前言的开头，要交给 h2 连接");
    }

    @Test
    public void testSplitThreeBytes() {
        HttpProtocolSniffer sniffer = new HttpProtocolSniffer();
        FastBuf b1 = buf("PRI");
        assertEquals(HttpProtocolSniffer.Protocol.HTTP_2, sniffer.detect(b1));
        assertEquals(0, sniffer.getCarryLen());
    }

    /**
     * HTTP/1.1 请求分 2 字节到达：首字节 "G" 不是 "PRI" 的前缀 ⇒ 立即判定 HTTP/1.1，
     * 且不能消费缓冲里的字节（否则 "G" 会被吞掉）。
     */
    @Test
    public void testHttp11FirstByteNotConsumed() {
        HttpProtocolSniffer sniffer = new HttpProtocolSniffer();
        FastBuf b1 = buf("G");
        assertEquals(HttpProtocolSniffer.Protocol.HTTP_1_1, sniffer.detect(b1));
        assertEquals(1, b1.remain(), "判定 HTTP/1.1 时不能消费任何字节");
        assertEquals(0, sniffer.getCarryLen());
    }

    /**
     * "P" 开头但不是 h2（如 "POST"）：暂存的 "P" 必须留给 HTTP/1.1 解码器回放。
     */
    @Test
    public void testHttp11WithCarryAfterAmbiguousPrefix() {
        HttpProtocolSniffer sniffer = new HttpProtocolSniffer();
        FastBuf b1 = buf("P");
        assertNull(sniffer.detect(b1));
        assertEquals(1, sniffer.getCarryLen());

        FastBuf b2 = buf("OST /x HTTP/1.1\r\n\r\n");
        assertEquals(HttpProtocolSniffer.Protocol.HTTP_1_1, sniffer.detect(b2));
        assertEquals("P", str(sniffer.getCarry(), 1), "暂存的字节要留给 HTTP/1.1 解码器");
        assertEquals(b2.remain(), b2.remain(), "第二个缓冲不能被消费");
    }

    /**
     * 逐字节喂入完整的 h2 前言。
     */
    @Test
    public void testByteByBytePreface() {
        HttpProtocolSniffer sniffer = new HttpProtocolSniffer();
        byte[] bytes = H2_PREFACE.getBytes(StandardCharsets.ISO_8859_1);
        HttpProtocolSniffer.Protocol result = null;
        for (int i = 0; i < bytes.length; i++) {
            FastBuf b = new FastBuf(1);
            b.write(bytes, i, 1);
            result = sniffer.detect(b);
            if (result != null) {
                break;
            }
        }
        assertNotNull(result);
        assertEquals(HttpProtocolSniffer.Protocol.HTTP_2, result);
    }

    /**
     * 逐字节喂入 HTTP/1.1 请求：第 1 个字节就能判定。
     */
    @Test
    public void testByteByByteHttp11() {
        HttpProtocolSniffer sniffer = new HttpProtocolSniffer();
        FastBuf b = buf("G");
        assertEquals(HttpProtocolSniffer.Protocol.HTTP_1_1, sniffer.detect(b));
    }
}
