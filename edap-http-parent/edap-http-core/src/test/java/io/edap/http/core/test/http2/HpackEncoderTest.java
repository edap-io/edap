package io.edap.http.core.test.http2;

import io.edap.http.http2.hpack.HpackDecoder;
import io.edap.http.http2.hpack.HpackEncoder;
import io.edap.http.http2.hpack.HpackHeader;
import io.edap.http.http2.hpack.HpackStaticTable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HPACK 编码器：与解码器 round-trip，并验证 v1 的编码策略。
 */
public class HpackEncoderTest {

    private static final HpackEncoder ENCODER = new HpackEncoder();

    private static List<HpackHeader> headers(String... pairs) {
        List<HpackHeader> list = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            list.add(new HpackHeader(pairs[i], pairs[i + 1]));
        }
        return list;
    }

    private static void assertRoundTrip(List<HpackHeader> input) {
        byte[] encoded = ENCODER.encode(input);
        List<HpackHeader> decoded = new HpackDecoder().decode(encoded);
        assertEquals(input.size(), decoded.size(), "解出来的头字段数量不对");
        for (int i = 0; i < input.size(); i++) {
            assertEquals(input.get(i).getName(), decoded.get(i).getName());
            assertEquals(input.get(i).getValue(), decoded.get(i).getValue());
        }
    }

    @Test
    public void testRoundTripSimpleHeaders() {
        assertRoundTrip(headers(":method", "GET", ":scheme", "http", ":path", "/"));
    }

    @Test
    public void testRoundTripWithBodyHeaders() {
        assertRoundTrip(headers(
                ":status", "200",
                "content-type", "text/plain; charset=UTF-8",
                "content-length", "10",
                "server", "ews",
                "date", "Mon, 21 Oct 2013 20:13:21 GMT"));
    }

    @Test
    public void testRoundTripWithUnknownHeaderName() {
        assertRoundTrip(headers("x-custom-header", "some-value"));
    }

    @Test
    public void testRoundTripWithLongValue() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append('x');
        }
        assertRoundTrip(headers("x-long", sb.toString()));
    }

    @Test
    public void testRoundTripWithEmptyValue() {
        assertRoundTrip(headers("x-empty", ""));
    }

    @Test
    public void testRoundTripWithManyHeaders() {
        List<HpackHeader> list = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            list.add(new HpackHeader("x-header-" + i, "value-" + i));
        }
        assertRoundTrip(list);
    }

    /**
     * name + value 与静态表完全一致 ⇒ 用 Indexed Header Field（0x80 | index）。
     */
    @Test
    public void testExactStaticMatchUsesIndexedRepresentation() {
        byte[] encoded = ENCODER.encode(headers(":status", "200"));
        assertArrayEquals(new byte[]{(byte) 0x88}, encoded, ":status 200 应当用静态表索引 8");
    }

    @Test
    public void testNonExactMatchUsesLiteralWithoutIndexing() {
        // :status 201 不在静态表里 ⇒ Literal without Indexing，名字用静态表索引 8
        byte[] encoded = ENCODER.encode(headers(":status", "201"));
        assertEquals((byte) 0x08, encoded[0], "Literal without Indexing + 名字索引 8");
        assertEquals(3, encoded[1], "值长度 3");
        assertEquals("201", new String(encoded, 2, 3, java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    /**
     * 编码器从不使用「带增量索引的字面量」，所以对端的动态表始终是空的。
     */
    @Test
    public void testEncoderNeverPollutesDynamicTable() {
        HpackDecoder decoder = new HpackDecoder();
        decoder.decode(ENCODER.encode(headers(
                ":method", "GET",
                "x-custom", "value",
                "cache-control", "no-cache")));
        assertEquals(0, decoder.getDynamicTable().size(), "编码器不能污染对端动态表");
    }

    /**
     * 名字不在静态表里 ⇒ 名字本身是字面量。
     */
    @Test
    public void testLiteralNameWhenNotInStaticTable() {
        byte[] encoded = ENCODER.encode(headers("x-not-in-static", "v"));
        assertEquals((byte) 0x00, encoded[0], "4-bit 索引字段为 0 ⇒ 名字是字面量");
        assertEquals(15, encoded[1], "名字长度 15");
        assertEquals("x-not-in-static", new String(encoded, 2, 15,
                java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    @Test
    public void testStaticTableLookup() {
        assertEquals(1, HpackStaticTable.findName(":authority"));
        assertEquals(8, HpackStaticTable.find(":status", "200"));
        assertEquals(0, HpackStaticTable.find(":status", "201"));
        assertEquals(61, HpackStaticTable.findName("www-authenticate"));
        assertEquals(0, HpackStaticTable.findName("not-a-real-header"));
        assertEquals(61, HpackStaticTable.SIZE);
    }

    @Test
    public void testStaticTableValues() {
        assertEquals(":method", HpackStaticTable.getName(2));
        assertEquals("GET", HpackStaticTable.getValue(2));
        assertEquals("POST", HpackStaticTable.getValue(3));
        assertEquals("/", HpackStaticTable.getValue(4));
        assertEquals("/index.html", HpackStaticTable.getValue(5));
        assertEquals("http", HpackStaticTable.getValue(6));
        assertEquals("https", HpackStaticTable.getValue(7));
        assertEquals("gzip, deflate", HpackStaticTable.getValue(16));
    }

    /**
     * 编码器产出必须能被解码器读回，且解码结果与输入顺序一致。
     */
    @Test
    public void testEncodedBlockIsSelfConsistent() {
        List<HpackHeader> input = headers(":method", "POST", ":path", "/a/b", "content-length", "0");
        byte[] encoded = ENCODER.encode(input);
        List<HpackHeader> decoded = new HpackDecoder().decode(encoded);
        assertEquals(Arrays.asList(":method", ":path", "content-length"),
                Arrays.asList(decoded.get(0).getName(), decoded.get(1).getName(), decoded.get(2).getName()));
        assertTrue(decoded.get(2).getValue().equals("0"));
    }
}
