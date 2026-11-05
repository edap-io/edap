package io.edap.http.core.test.http2;

import io.edap.http.http2.Http2ErrorCode;
import io.edap.http.http2.Http2Exception;
import io.edap.http.http2.hpack.HpackDecoder;
import io.edap.http.http2.hpack.HpackDynamicTable;
import io.edap.http.http2.hpack.HpackHeader;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HPACK 解码器对拍 RFC 7541 官方向量。
 *
 * <p>向量全部取自 RFC 7541 Appendix C（十六进制 dump 由脚本从 RFC 原文解析，
 * 不是手抄）。</p>
 */
public class HpackDecoderTest {

    // ------------------------------------------------------------------ RFC 向量

    /** C.3.1 第一个请求（不带 Huffman） */
    private static final String C_3_1 = "828684410f7777772e6578616d706c652e636f6d";
    /** C.3.2 第二个请求（不带 Huffman） */
    private static final String C_3_2 = "828684be58086e6f2d6361636865";
    /** C.3.3 第三个请求（不带 Huffman） */
    private static final String C_3_3 = "828785bf400a637573746f6d2d6b65790c637573746f6d2d76616c7565";
    /** C.4.1 第一个请求（带 Huffman） */
    private static final String C_4_1 = "828684418cf1e3c2e5f23a6ba0ab90f4ff";
    /** C.4.2 第二个请求（带 Huffman） */
    private static final String C_4_2 = "828684be5886a8eb10649cbf";
    /** C.4.3 第三个请求（带 Huffman） */
    private static final String C_4_3 = "828785bf408825a849e95ba97d7f8925a849e95bb8e8b4bf";
    /** C.5.1 第一个响应（不带 Huffman） */
    private static final String C_5_1 = "4803333032580770726976617465611d4d6f6e2c203231204f63742032303133"
            + "2032303a31333a323120474d546e1768747470733a2f2f7777772e6578616d706c652e636f6d";
    /** C.5.2 第二个响应（不带 Huffman） */
    private static final String C_5_2 = "4803333037c1c0bf";
    /** C.5.3 第三个响应（不带 Huffman） */
    private static final String C_5_3 = "88c1611d4d6f6e2c203231204f637420323031332032303a31333a323220474d54"
            + "c05a04677a69707738666f6f3d4153444a4b48514b425a584f5157454f50495541585157454f49553b206d61782d6167653d333630303b2076657273696f6e3d31";
    /** C.6.1 第一个响应（带 Huffman） */
    private static final String C_6_1 = "488264025885aec3771a4b6196d07abe941054d444a8200595040b8166e082a62d1bf"
            + "f6e919d29ad171863c78f0b97c8e9ae82ae43d3";
    /** C.6.2 第二个响应（带 Huffman） */
    private static final String C_6_2 = "4883640effc1c0bf";
    /** C.6.3 第三个响应（带 Huffman） */
    private static final String C_6_3 = "88c16196d07abe941054d444a8200595040b8166e084a62d1bffc05a839bd9ab77ad94e"
            + "7821dd7f2e6c7b335dfdfcd5b3960d5af27087f3672c1ab270fb5291f9587316065c003ed4ee5b1063d5007";

    private static final String DATE_21 = "Mon, 21 Oct 2013 20:13:21 GMT";
    private static final String DATE_22 = "Mon, 21 Oct 2013 20:13:22 GMT";
    private static final String LOCATION = "https://www.example.com";

    // ------------------------------------------------------------------ 工具

    private static byte[] hex(String s) {
        int n = s.length() / 2;
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return b;
    }

    private static void assertHeader(HpackHeader h, String name, String value) {
        assertEquals(name, h.getName());
        assertEquals(value, h.getValue());
    }

    // ------------------------------------------------------------------ 请求向量

    @Test
    public void testC31FirstRequest() {
        List<HpackHeader> h = new HpackDecoder().decode(hex(C_3_1));
        assertEquals(4, h.size());
        assertHeader(h.get(0), ":method", "GET");
        assertHeader(h.get(1), ":scheme", "http");
        assertHeader(h.get(2), ":path", "/");
        assertHeader(h.get(3), ":authority", "www.example.com");
    }

    @Test
    public void testC32SecondRequest() {
        HpackDecoder d = new HpackDecoder();
        d.decode(hex(C_3_1));
        List<HpackHeader> h = d.decode(hex(C_3_2));
        assertEquals(5, h.size());
        assertHeader(h.get(3), ":authority", "www.example.com");
        assertHeader(h.get(4), "cache-control", "no-cache");

        // 动态表：RFC C.3.2 之后是 [1] cache-control: no-cache (53), [2] :authority: www.example.com (57)，总 110
        HpackDynamicTable t = d.getDynamicTable();
        assertEquals(2, t.size());
        assertEquals(110, t.getSize());
        assertEquals("cache-control", t.get(1).getName());
        assertEquals(":authority", t.get(2).getName());
    }

    @Test
    public void testC33ThirdRequest() {
        // C.3.x 是同一条连接上的连续三个请求，动态表必须延续
        HpackDecoder d = new HpackDecoder();
        d.decode(hex(C_3_1));
        d.decode(hex(C_3_2));
        List<HpackHeader> h = d.decode(hex(C_3_3));
        assertEquals(5, h.size());
        assertHeader(h.get(0), ":method", "GET");
        assertHeader(h.get(1), ":scheme", "https");
        assertHeader(h.get(2), ":path", "/index.html");
        assertHeader(h.get(3), ":authority", "www.example.com");
        assertHeader(h.get(4), "custom-key", "custom-value");
    }

    @Test
    public void testC41FirstRequestWithHuffman() {
        List<HpackHeader> h = new HpackDecoder().decode(hex(C_4_1));
        assertEquals(4, h.size());
        assertHeader(h.get(0), ":method", "GET");
        assertHeader(h.get(1), ":scheme", "http");
        assertHeader(h.get(2), ":path", "/");
        assertHeader(h.get(3), ":authority", "www.example.com");
    }

    @Test
    public void testC42SecondRequestWithHuffman() {
        HpackDecoder d = new HpackDecoder();
        d.decode(hex(C_4_1));
        List<HpackHeader> h = d.decode(hex(C_4_2));
        assertEquals(5, h.size());
        assertHeader(h.get(3), ":authority", "www.example.com");
        assertHeader(h.get(4), "cache-control", "no-cache");
        assertEquals(2, d.getDynamicTable().size());
        assertEquals(110, d.getDynamicTable().getSize());
    }

    @Test
    public void testC43ThirdRequestWithHuffman() {
        // C.4.x 同样是同一条连接上的连续三个请求
        HpackDecoder d = new HpackDecoder();
        d.decode(hex(C_4_1));
        d.decode(hex(C_4_2));
        List<HpackHeader> h = d.decode(hex(C_4_3));
        assertEquals(5, h.size());
        assertHeader(h.get(1), ":scheme", "https");
        assertHeader(h.get(2), ":path", "/index.html");
        assertHeader(h.get(4), "custom-key", "custom-value");
    }

    /**
     * C.3.x 与 C.4.x 是同一组头字段的两种编码，解出来的结果必须完全一致。
     */
    @Test
    public void testHuffmanAndPlainProduceSameHeaders() {
        List<HpackHeader> plain = new HpackDecoder().decode(hex(C_3_1));
        List<HpackHeader> huff = new HpackDecoder().decode(hex(C_4_1));
        assertEquals(plain.size(), huff.size());
        for (int i = 0; i < plain.size(); i++) {
            assertEquals(plain.get(i).getName(), huff.get(i).getName());
            assertEquals(plain.get(i).getValue(), huff.get(i).getValue());
        }
    }

    // ------------------------------------------------------------------ 响应向量（表大小 256）

    private static HpackDecoder responseDecoder() {
        HpackDecoder d = new HpackDecoder();
        // RFC C.5 / C.6 的示例把 SETTINGS_HEADER_TABLE_SIZE 设成 256
        d.setMaxTableSize(256);
        return d;
    }

    @Test
    public void testC51FirstResponse() {
        HpackDecoder d = responseDecoder();
        List<HpackHeader> h = d.decode(hex(C_5_1));
        assertEquals(4, h.size());
        assertHeader(h.get(0), ":status", "302");
        assertHeader(h.get(1), "cache-control", "private");
        assertHeader(h.get(2), "date", DATE_21);
        assertHeader(h.get(3), "location", LOCATION);

        HpackDynamicTable t = d.getDynamicTable();
        assertEquals(4, t.size());
        assertEquals(222, t.getSize());
        assertEquals("location", t.get(1).getName());
        assertEquals(":status", t.get(4).getName());
    }

    @Test
    public void testC52SecondResponseEvictsOldest() {
        HpackDecoder d = responseDecoder();
        d.decode(hex(C_5_1));
        List<HpackHeader> h = d.decode(hex(C_5_2));
        assertEquals(4, h.size());
        assertHeader(h.get(0), ":status", "307");
        assertHeader(h.get(1), "cache-control", "private");
        assertHeader(h.get(2), "date", DATE_21);
        assertHeader(h.get(3), "location", LOCATION);

        // :status 302 被驱逐，:status 307 成为最新
        HpackDynamicTable t = d.getDynamicTable();
        assertEquals(4, t.size());
        assertEquals(222, t.getSize());
        assertEquals(":status", t.get(1).getName());
        assertEquals("307", t.get(1).getValue());
        assertEquals("location", t.get(2).getName());
        assertEquals("date", t.get(3).getName());
        assertEquals("cache-control", t.get(4).getName());
    }

    @Test
    public void testC53ThirdResponseEvictsSeveral() {
        HpackDecoder d = responseDecoder();
        d.decode(hex(C_5_1));
        d.decode(hex(C_5_2));
        List<HpackHeader> h = d.decode(hex(C_5_3));
        assertEquals(6, h.size());
        assertHeader(h.get(0), ":status", "200");
        assertHeader(h.get(1), "cache-control", "private");
        assertHeader(h.get(2), "date", DATE_22);
        assertHeader(h.get(3), "location", LOCATION);
        assertHeader(h.get(4), "content-encoding", "gzip");
        assertHeader(h.get(5), "set-cookie",
                "foo=ASDJKHQKBZXOQWEOPIUAXQWEOIU; max-age=3600; version=1");
    }

    @Test
    public void testC61FirstResponseWithHuffman() {
        HpackDecoder d = responseDecoder();
        List<HpackHeader> h = d.decode(hex(C_6_1));
        assertEquals(4, h.size());
        assertHeader(h.get(0), ":status", "302");
        assertHeader(h.get(1), "cache-control", "private");
        assertHeader(h.get(2), "date", DATE_21);
        assertHeader(h.get(3), "location", LOCATION);
        assertEquals(222, d.getDynamicTable().getSize());
    }

    @Test
    public void testC62SecondResponseWithHuffman() {
        HpackDecoder d = responseDecoder();
        d.decode(hex(C_6_1));
        List<HpackHeader> h = d.decode(hex(C_6_2));
        assertEquals(4, h.size());
        assertHeader(h.get(0), ":status", "307");
        assertHeader(h.get(1), "cache-control", "private");
        assertHeader(h.get(2), "date", DATE_21);
        assertHeader(h.get(3), "location", LOCATION);
        assertEquals(":status", d.getDynamicTable().get(1).getName());
        assertEquals("307", d.getDynamicTable().get(1).getValue());
    }

    @Test
    public void testC63ThirdResponseWithHuffman() {
        HpackDecoder d = responseDecoder();
        d.decode(hex(C_6_1));
        d.decode(hex(C_6_2));
        List<HpackHeader> h = d.decode(hex(C_6_3));
        assertEquals(6, h.size());
        assertHeader(h.get(0), ":status", "200");
        assertHeader(h.get(4), "content-encoding", "gzip");
        assertHeader(h.get(5), "set-cookie",
                "foo=ASDJKHQKBZXOQWEOPIUAXQWEOIU; max-age=3600; version=1");
    }

    /**
     * C.5.x 与 C.6.x 是同一组响应头字段的两种编码，结果必须完全一致。
     */
    @Test
    public void testResponseHuffmanAndPlainProduceSameHeaders() {
        HpackDecoder d1 = responseDecoder();
        HpackDecoder d2 = responseDecoder();
        List<HpackHeader> plain = d1.decode(hex(C_5_1));
        List<HpackHeader> huff = d2.decode(hex(C_6_1));
        assertEquals(plain.size(), huff.size());
        for (int i = 0; i < plain.size(); i++) {
            assertEquals(plain.get(i).getName(), huff.get(i).getName());
            assertEquals(plain.get(i).getValue(), huff.get(i).getValue());
        }
    }

    // ------------------------------------------------------------------ 其它表示形式

    /** RFC 7541 C.2.2：Literal Header Field without Indexing */
    @Test
    public void testLiteralWithoutIndexing() {
        // 040c 2f73616d706c652f70617468 -> :path: /sample/path
        List<HpackHeader> h = new HpackDecoder().decode(hex("040c2f73616d706c652f70617468"));
        assertEquals(1, h.size());
        assertHeader(h.get(0), ":path", "/sample/path");
    }

    /** RFC 7541 C.2.3：Literal Header Field Never Indexed */
    @Test
    public void testLiteralNeverIndexed() {
        // 10 08 "password" 06 "secret"
        List<HpackHeader> h = new HpackDecoder().decode(hex("100870617373776f726406736563726574"));
        assertEquals(1, h.size());
        assertHeader(h.get(0), "password", "secret");
    }

    /** RFC 7541 C.2.1：Literal Header Field with Indexing */
    @Test
    public void testLiteralWithIncrementalIndexing() {
        // 40 0a "custom-key" 0c "custom-value"
        HpackDecoder d = new HpackDecoder();
        List<HpackHeader> h = d.decode(hex("400a637573746f6d2d6b65790c637573746f6d2d76616c7565"));
        assertEquals(1, h.size());
        assertHeader(h.get(0), "custom-key", "custom-value");
        assertEquals(1, d.getDynamicTable().size());
        assertEquals("custom-key", d.getDynamicTable().get(1).getName());
    }

    /** RFC 7541 C.2.4：Indexed Header Field */
    @Test
    public void testIndexedHeaderField() {
        List<HpackHeader> h = new HpackDecoder().decode(hex("82"));
        assertEquals(1, h.size());
        assertHeader(h.get(0), ":method", "GET");
    }

    /**
     * Dynamic Table Size Update（RFC 7541 §6.3）：把表上限设为 0 等价于清空动态表。
     */
    @Test
    public void testDynamicTableSizeUpdate() {
        HpackDecoder d = new HpackDecoder();
        d.decode(hex("400a637573746f6d2d6b65790c637573746f6d2d76616c7565"));
        assertEquals(1, d.getDynamicTable().size());

        // 0x20 = 001 00000 -> Dynamic Table Size Update，值为 0
        d.decode(hex("20"));
        assertEquals(0, d.getDynamicTable().size());
        assertEquals(0, d.getDynamicTable().getMaxSize());
    }

    @Test
    public void testDynamicTableSizeUpdateAboveLimitRejected() {
        HpackDecoder d = new HpackDecoder();
        d.setMaxTableSize(256);
        // 0x3f 0xe1 0x1f => size update 到 4096，超过本端通告的 256
        Http2Exception e = assertThrows(Http2Exception.class, () -> d.decode(hex("3fe11f")));
        assertEquals(Http2ErrorCode.COMPRESSION_ERROR, e.getErrorCode());
    }

    // ------------------------------------------------------------------ 错误路径

    @Test
    public void testIndexZeroRejected() {
        Http2Exception e = assertThrows(Http2Exception.class, () -> new HpackDecoder().decode(hex("80")));
        assertEquals(Http2ErrorCode.COMPRESSION_ERROR, e.getErrorCode());
    }

    @Test
    public void testTruncatedBlockRejected() {
        assertThrows(Http2Exception.class, () -> new HpackDecoder().decode(hex("828684410f7777")));
    }

    @Test
    public void testIndexOutOfRangeRejected() {
        // 索引 62 在空动态表上无效
        assertThrows(Http2Exception.class, () -> new HpackDecoder().decode(hex("be")));
    }

    @Test
    public void testHuffmanEosRejected() {
        // 一串全 1 的字节必然命中 EOS
        Http2Exception e = assertThrows(Http2Exception.class,
                () -> new HpackDecoder().decode(hex("0004ffffffff")));
        assertEquals(Http2ErrorCode.COMPRESSION_ERROR, e.getErrorCode());
    }

    @Test
    public void testEmptyBlockProducesEmptyList() {
        List<HpackHeader> h = new HpackDecoder().decode(new byte[0]);
        assertNotNull(h);
        assertTrue(h.isEmpty());
    }
}
