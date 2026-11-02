/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.store;

import io.edap.s3.error.S3ErrorCode;
import io.edap.s3.error.S3Exception;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.CRC32;

/**
 * ListObjectsV2 的 continuation token —— 编码「已经报到哪儿了」的<b>边界</b>，
 * 而不是「最后一个输出的 key」。
 *
 * <p><b>为什么必须区分两种边界</b>(桶里有 {@code a/1, a/2, b, c}，delimiter=/)：
 * <ul>
 *   <li>边界 = (KEY, "a/1") ⇒ 续查所有 {@code K > "a/1"} ⇒ {@code a/2} 会被折成
 *       公共前缀 {@code a/} ⇒ <b>第 2 页重复输出 {@code a/}</b> ❌</li>
 *   <li>边界 = (COMMON_PREFIX, "a/") ⇒ 续查所有 {@code K > "a/"} <b>且</b>
 *       {@code !K.startsWith("a/")} ⇒ 跳过整个 group ⇒ 无重复无遗漏 ✓</li>
 * </ul>
 * 但也不能统一排除 {@code startsWith}:key {@code a} 和 {@code a/1} 共存时
 * (KEY, "a") 边界必须保留 {@code a/1} ⇒ 才会输出公共前缀 {@code a/}。
 * ⇒ kind 字段不可省。
 *
 * <p>编码（外层 Base64 URL-safe，可直接放 query string）：
 * <pre>
 *   [0]      version = 1
 *   [1]      kind    (0 = KEY, 1 = COMMON_PREFIX)
 *   [2..3]   boundary 长度 (BE uint16, UTF-8 字节数)
 *   [4..]    boundary (UTF-8)
 *   [..]     requestFingerprint (4 字节 BE) = CRC32(prefix + "\0" + delimiter)
 *   [..]     payloadCrc (4 字节 BE) = CRC32(前面所有字节)
 * </pre>
 *
 * <p>token 与 (prefix, delimiter) 绑定:拿 A 的 token 去请求 B ⇒ InvalidArgument，
 * 而不是给出一份语义错乱的结果。
 */
public final class ListToken {

    private static final byte VERSION = 1;
    private static final int HEADER_LEN = 4;      // version + kind + boundaryLen(BE uint16)

    /** 边界类型。 */
    public enum Kind {
        /** 最后一个输出的是对象 key ⇒ 续查所有 K > boundary。 */
        KEY,
        /** 最后一个输出的是公共前缀 ⇒ 续查所有 K > boundary 且 !K.startsWith(boundary)。 */
        COMMON_PREFIX
    }

    private ListToken() {}

    public static String encode(Kind kind, String boundary, String prefix, String delimiter) {
        byte[] boundaryBytes = boundary.getBytes(StandardCharsets.UTF_8);
        if (boundaryBytes.length > 0xFFFF) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "Continuation token boundary too long");
        }
        byte[] prefixBytes = (prefix == null ? "" : prefix).getBytes(StandardCharsets.UTF_8);
        byte[] delimiterBytes = (delimiter == null ? "" : delimiter).getBytes(StandardCharsets.UTF_8);

        byte[] payload = new byte[HEADER_LEN + boundaryBytes.length + 8];
        payload[0] = VERSION;
        payload[1] = (byte) (kind == Kind.COMMON_PREFIX ? 1 : 0);
        payload[2] = (byte) ((boundaryBytes.length >>> 8) & 0xFF);
        payload[3] = (byte) (boundaryBytes.length & 0xFF);
        System.arraycopy(boundaryBytes, 0, payload, HEADER_LEN, boundaryBytes.length);

        int off = HEADER_LEN + boundaryBytes.length;
        CRC32 fp = new CRC32();
        fp.update(prefixBytes);
        fp.update((byte) 0);
        fp.update(delimiterBytes);
        int fingerprint = (int) fp.getValue();
        payload[off] = (byte) ((fingerprint >>> 24) & 0xFF);
        payload[off + 1] = (byte) ((fingerprint >>> 16) & 0xFF);
        payload[off + 2] = (byte) ((fingerprint >>> 8) & 0xFF);
        payload[off + 3] = (byte) (fingerprint & 0xFF);

        CRC32 crc = new CRC32();
        crc.update(payload, 0, off + 4);
        int c = (int) crc.getValue();
        payload[off + 4] = (byte) ((c >>> 24) & 0xFF);
        payload[off + 5] = (byte) ((c >>> 16) & 0xFF);
        payload[off + 6] = (byte) ((c >>> 8) & 0xFF);
        payload[off + 7] = (byte) (c & 0xFF);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
    }

    /**
     * 解码 token —— 非法或与当前 (prefix, delimiter) 不匹配时抛
     * {@link S3Exception} (INVALID_ARGUMENT)。
     */
    public static Decoded decode(String token, String prefix, String delimiter) {
        if (token == null || token.isEmpty()) {
            throw invalid("Continuation token is empty");
        }
        byte[] payload;
        try {
            payload = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException e) {
            throw invalid("Continuation token is not valid base64");
        }
        if (payload.length < HEADER_LEN + 8) {
            throw invalid("Continuation token is truncated");
        }
        if (payload[0] != VERSION) {
            throw invalid("Unsupported continuation token version: " + (payload[0] & 0xFF));
        }
        // payload CRC 校验(最后 4 字节覆盖前面全部)
        CRC32 crc = new CRC32();
        crc.update(payload, 0, payload.length - 4);
        int expected = ((payload[payload.length - 4] & 0xFF) << 24)
                | ((payload[payload.length - 3] & 0xFF) << 16)
                | ((payload[payload.length - 2] & 0xFF) << 8)
                | (payload[payload.length - 1] & 0xFF);
        if ((int) crc.getValue() != expected) {
            throw invalid("Continuation token checksum mismatch");
        }

        int kindByte = payload[1] & 0xFF;
        if (kindByte > 1) {
            throw invalid("Continuation token kind is invalid");
        }
        Kind kind = kindByte == 1 ? Kind.COMMON_PREFIX : Kind.KEY;
        int boundaryLen = ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
        if (payload.length != HEADER_LEN + boundaryLen + 8) {
            throw invalid("Continuation token length mismatch");
        }
        String boundary = new String(payload, HEADER_LEN, boundaryLen, StandardCharsets.UTF_8);

        int off = HEADER_LEN + boundaryLen;
        int fingerprint = ((payload[off] & 0xFF) << 24)
                | ((payload[off + 1] & 0xFF) << 16)
                | ((payload[off + 2] & 0xFF) << 8)
                | (payload[off + 3] & 0xFF);
        CRC32 fp = new CRC32();
        fp.update((prefix == null ? "" : prefix).getBytes(StandardCharsets.UTF_8));
        fp.update((byte) 0);
        fp.update((delimiter == null ? "" : delimiter).getBytes(StandardCharsets.UTF_8));
        if ((int) fp.getValue() != fingerprint) {
            throw invalid("Continuation token does not match the request prefix/delimiter");
        }
        return new Decoded(kind, boundary);
    }

    private static S3Exception invalid(String msg) {
        return new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                "Invalid continuation token: " + msg);
    }

    /** 解码结果。 */
    public static final class Decoded {
        private final Kind kind;
        private final String boundary;

        Decoded(Kind kind, String boundary) {
            this.kind = kind;
            this.boundary = boundary;
        }

        public Kind kind() {
            return kind;
        }

        public String boundary() {
            return boundary;
        }
    }
}
