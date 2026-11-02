/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.store.disk;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IndexCodec} —— 往返 / 特殊字符 / CRC 篡改 / 格式错误。
 */
public class IndexCodecTest {

    @Test
    void putRecordRoundTrips() throws Exception {
        Map<String, String> um = new LinkedHashMap<>();
        um.put("source", "test");
        um.put("empty", "");
        IndexRecord rec = IndexRecord.put("dir/a.txt", "5d41402abc4b2a76b9719d911017c592",
                11, "application/octet-stream", 1780000001000L, um);

        IndexRecord back = IndexCodec.decodeLine(IndexCodec.encodeLine(rec));
        assertEquals(IndexRecord.Op.PUT, back.op());
        assertEquals("dir/a.txt", back.key());
        assertEquals("5d41402abc4b2a76b9719d911017c592", back.etag());
        assertEquals(11, back.size());
        assertEquals("application/octet-stream", back.contentType());
        assertEquals(1780000001000L, back.lastModifiedMillis());
        assertEquals(um, back.userMetadata());
    }

    @Test
    void deleteRecordRoundTrips() throws Exception {
        IndexRecord rec = IndexRecord.delete("old/removed.txt");
        String line = IndexCodec.encodeLine(rec);
        IndexRecord back = IndexCodec.decodeLine(line);
        assertEquals(IndexRecord.Op.DELETE, back.op());
        assertEquals("old/removed.txt", back.key());
    }

    @Test
    void encodedLineContainsNoRawTabOrNewline() {
        IndexRecord rec = IndexRecord.put("a\tb\nc", "e", 1, "t", 0L, null);
        String line = IndexCodec.encodeLine(rec);
        // key 里的 TAB / LF 被转义 ⇒ 行内不可能出现裸 TAB(除了字段分隔符)或换行
        assertTrue(line.contains("%09"));
        assertTrue(line.contains("%0A"));
        assertTrue(line.indexOf('\n') < 0);
        assertTrue(line.indexOf('\r') < 0);
    }

    @Test
    void specialCharactersRoundTrip() throws Exception {
        String weird = "tab\there\nnewline\rreturn%percent\u0000nul\u007Fdel中文😀";
        IndexRecord rec = IndexRecord.put(weird, "e%t\tag", 42, "c\nt", 7L,
                Map.of("k\ney", "v\na%l"));
        IndexRecord back = IndexCodec.decodeLine(IndexCodec.encodeLine(rec));
        assertEquals(weird, back.key());
        assertEquals("e%t\tag", back.etag());
        assertEquals("c\nt", back.contentType());
        assertEquals(Map.of("k\ney", "v\na%l"), back.userMetadata());
    }

    @Test
    void surrogatePairSurvivesEncoding() throws Exception {
        String emoji = "😀";
        IndexRecord rec = IndexRecord.put(emoji, "e", 1, "t", 0L, null);
        IndexRecord back = IndexCodec.decodeLine(IndexCodec.encodeLine(rec));
        assertEquals(emoji, back.key());
        assertEquals(2, emoji.length(), "前提:emoji 在 Java 里是两个 char");
    }

    @Test
    void emptyUserMetadataAndNullContentType() throws Exception {
        IndexRecord rec = IndexRecord.put("k", null, 0, null, 0L, null);
        IndexRecord back = IndexCodec.decodeLine(IndexCodec.encodeLine(rec));
        assertNull(back.etag());
        assertNull(back.contentType());
        assertTrue(back.userMetadata().isEmpty());
    }

    @Test
    void tamperedChecksumIsRejected() {
        IndexRecord rec = IndexRecord.put("k", "etag", 1, "t", 0L, null);
        String line = IndexCodec.encodeLine(rec);
        // 改掉 payload 里的一个字符,crc 不再匹配
        char[] chars = line.toCharArray();
        int idx = line.indexOf("etag");
        chars[idx] = 'x';
        String tampered = new String(chars);
        IOException ex = assertThrows(IOException.class,
                () -> IndexCodec.decodeLine(tampered));
        assertTrue(ex.getMessage().contains("checksum"));
    }

    @Test
    void malformedLineIsRejected() {
        assertThrows(IOException.class, () -> IndexCodec.decodeLine("no-checksum"));
        assertThrows(IOException.class, () -> IndexCodec.decodeLine("P\tk\tzzz"));
        assertThrows(IOException.class, () -> IndexCodec.decodeLine("X\tk"));
    }

    @Test
    void unknownOpIsRejected() {
        // 合法 CRC 但 op 未知
        String payload = "X\tk";
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String line = payload + "\t" + String.format("%08x", crc.getValue());
        IOException ex = assertThrows(IOException.class, () -> IndexCodec.decodeLine(line));
        assertTrue(ex.getMessage().contains("op"));
    }

    @Test
    void fieldEscapeIsReversible() throws Exception {
        assertEquals("", IndexCodec.decodeField(IndexCodec.encodeField("")));
        assertEquals("plain", IndexCodec.decodeField(IndexCodec.encodeField("plain")));
        assertEquals("%25", IndexCodec.encodeField("%"));
        assertEquals("\t", IndexCodec.decodeField(IndexCodec.encodeField("\t")));
        assertEquals("\n", IndexCodec.decodeField(IndexCodec.encodeField("\n")));
        assertEquals("\u007F", IndexCodec.decodeField(IndexCodec.encodeField("\u007F")));
        assertEquals("\u0000", IndexCodec.decodeField(IndexCodec.encodeField("\u0000")));
    }

    @Test
    void invalidPercentEscapeIsRejected() {
        assertThrows(IOException.class, () -> IndexCodec.decodeField("abc%"));
        assertThrows(IOException.class, () -> IndexCodec.decodeField("abc%ZZ"));
    }

    @Test
    void metaProjectionRoundTrips() throws Exception {
        io.edap.s3.model.ObjectMeta meta = new io.edap.s3.model.ObjectMeta(
                "k", 12, "etag", "text/plain",
                java.time.Instant.ofEpochMilli(1780000000000L),
                Map.of("a", "b"));
        IndexRecord rec = IndexRecord.putOf(meta);
        io.edap.s3.model.ObjectMeta back = rec.toMeta();
        assertNotNull(back);
        assertEquals("k", back.key());
        assertEquals(12, back.size());
        assertEquals("etag", back.etag());
        assertEquals("text/plain", back.contentType());
        assertEquals(java.time.Instant.ofEpochMilli(1780000000000L), back.lastModified());
        assertEquals(Map.of("a", "b"), back.userMetadata());
    }
}
