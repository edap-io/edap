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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;

/**
 * 索引 log 的编解码 —— 格式见 {@link IndexLog} 的类注释。
 *
 * <p>一条记录的物理形态:
 * <pre>
 *   <payload> TAB <crc32hex8>
 * </pre>
 * {@code payload} = {@code <op> TAB <field> (TAB <field>)*},CRC32 覆盖 payload 的
 * UTF-8 字节(不含 crc 字段自身)。字段里的 TAB / LF / CR 等控制字符、{@code %}
 * 自身、{@code 0x7F} 全部 percent-encoding ⇒ payload 里不可能出现裸 TAB
 * ⇒ 一条记录严格一行。
 */
public final class IndexCodec {

    /** 索引文件首行。 */
    public static final String MAGIC = "#EDAPIDX1";

    private static final char[] HEX_UPPER = "0123456789ABCDEF".toCharArray();

    private IndexCodec() {}

    // ================= 字段转义 =================

    /**
     * percent-encoding(作用在 UTF-8 字节上):
     * {@code b < 0x20}、{@code b == '%'}、{@code b == 0x7F} 转成
     * {@code %XX}(大写 hex);其余字节原样。
     */
    public static String encodeField(String s) {
        if (s == null || s.isEmpty()) return "";
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length);
        for (byte b : raw) {
            int v = b & 0xFF;
            if (v < 0x20 || v == 0x25 || v == 0x7F) {
                out.write('%');
                out.write(HEX_UPPER[v >>> 4]);
                out.write(HEX_UPPER[v & 0x0F]);
            } else {
                out.write(v);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * {@link #encodeField} 的逆 —— 非法转义(截断的 {@code %XX} / 非 hex)抛
     * {@link IOException}。
     */
    public static String decodeField(String s) throws IOException {
        if (s == null || s.isEmpty()) return "";
        byte[] raw = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length);
        for (int i = 0; i < raw.length; ) {
            if (raw[i] == '%') {
                if (i + 2 >= raw.length) {
                    throw new IOException("truncated percent escape in index field");
                }
                int hi = hexVal(raw[i + 1]);
                int lo = hexVal(raw[i + 2]);
                if (hi < 0 || lo < 0) {
                    throw new IOException("invalid percent escape in index field");
                }
                out.write((hi << 4) | lo);
                i += 3;
            } else {
                out.write(raw[i]);
                i++;
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static int hexVal(byte b) {
        int v = b & 0xFF;
        if (v >= '0' && v <= '9') return v - '0';
        if (v >= 'A' && v <= 'F') return v - 'A' + 10;
        if (v >= 'a' && v <= 'f') return v - 'a' + 10;
        return -1;
    }

    // ================= 记录编解码 =================

    /** 编码成一行(不含行尾 LF)。 */
    public static String encodeLine(IndexRecord rec) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(rec.op().code());
        sb.append('\t').append(encodeField(rec.key()));
        if (rec.op() == IndexRecord.Op.PUT) {
            sb.append('\t').append(encodeField(rec.etag()));
            sb.append('\t').append(rec.size());
            sb.append('\t').append(encodeField(rec.contentType()));
            sb.append('\t').append(rec.lastModifiedMillis());
            // userMetadata 按 key 排序 ⇒ 同一份元数据永远编出同一行(log 可复现)
            Map<String, String> um = new TreeMap<>(rec.userMetadata());
            sb.append('\t').append(um.size());
            for (Map.Entry<String, String> e : um.entrySet()) {
                sb.append('\t').append(encodeField(e.getKey()));
                sb.append('\t').append(encodeField(e.getValue()));
            }
        }
        String payload = sb.toString();
        return payload + '\t' + crcHex(payload);
    }

    /**
     * 解码一行(不含行尾 LF)。格式非法 / CRC 不匹配 ⇒ 抛 {@link IOException}
     * (调用方据此判定「这条及其后全部不可信」)。
     */
    public static IndexRecord decodeLine(String line) throws IOException {
        int tab = line.lastIndexOf('\t');
        if (tab < 0) {
            throw new IOException("index line has no checksum field");
        }
        String payload = line.substring(0, tab);
        String crcHex = line.substring(tab + 1);
        if (crcHex.length() != 8) {
            throw new IOException("index line checksum must be 8 hex chars");
        }
        CRC32 crc = new CRC32();
        crc.update(payload.getBytes(StandardCharsets.UTF_8));
        long expected;
        try {
            expected = Long.parseLong(crcHex, 16);
        } catch (NumberFormatException e) {
            throw new IOException("index line checksum is not hex: " + crcHex);
        }
        if (crc.getValue() != expected) {
            throw new IOException("index line checksum mismatch");
        }

        String[] f = payload.split("\t", -1);
        if (f.length < 2) {
            throw new IOException("index line has too few fields");
        }
        IndexRecord.Op op = IndexRecord.Op.fromCode(f[0].charAt(0));
        if (op == null) {
            throw new IOException("unknown index op: " + f[0]);
        }
        String key = decodeField(f[1]);
        if (op == IndexRecord.Op.DELETE) {
            if (f.length != 2) {
                throw new IOException("DELETE record must have exactly 2 fields");
            }
            return IndexRecord.delete(key);
        }
        if (f.length < 7) {
            throw new IOException("PUT record has too few fields");
        }
        String etag = decodeField(f[2]);
        long size = parseLong(f[3], "size");
        String contentType = decodeField(f[4]);
        if (contentType.isEmpty()) contentType = null;
        long lm = parseLong(f[5], "lastModified");
        int umCount;
        try {
            umCount = Integer.parseInt(f[6]);
        } catch (NumberFormatException e) {
            throw new IOException("index record userMetadata count is not a number: " + f[6]);
        }
        if (umCount < 0 || f.length != 7 + 2 * umCount) {
            throw new IOException("index record userMetadata count mismatch");
        }
        Map<String, String> um = new TreeMap<>();
        for (int i = 0; i < umCount; i++) {
            um.put(decodeField(f[7 + 2 * i]), decodeField(f[8 + 2 * i]));
        }
        return IndexRecord.put(key, etag.isEmpty() ? null : etag, size, contentType, lm, um);
    }

    private static long parseLong(String s, String what) throws IOException {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new IOException("index record " + what + " is not a number: " + s);
        }
    }

    private static String crcHex(String payload) {
        CRC32 crc = new CRC32();
        crc.update(payload.getBytes(StandardCharsets.UTF_8));
        long v = crc.getValue();
        StringBuilder sb = new StringBuilder(8);
        for (int shift = 28; shift >= 0; shift -= 4) {
            sb.append(HEX_UPPER[(int) ((v >>> shift) & 0xF)]);
        }
        return sb.toString();
    }
}
