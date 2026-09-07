/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.xml;

import io.edap.s3.error.S3Exception;
import io.edap.s3.model.ObjectMeta;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * S3 XML 响应生成器 —— 用 {@link StringBuilder} 拼 XML,
 * 输出字节流响应体。
 *
 * <p>Phase 1 不引入 XML 库,直接拼 —— 字段值都做 XML escape,
 * 字段名固定,S3 响应模板稳定。
 *
 * <p>所有日期格式 {@code ISO 8601 GMT}:{@code yyyy-MM-dd'T'HH:mm:ss.SSS'Z'}。
 */
public final class XmlResponseBuilder {

    private static final DateTimeFormatter LAST_MODIFIED_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");

    private XmlResponseBuilder() {}

    // ===================== ListBuckets =====================

    public static byte[] listAllMyBucketsResult(String ownerId, String ownerDisplayName, List<String> buckets) {
        StringBuilder sb = new StringBuilder(256 + buckets.size() * 80);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<ListAllMyBucketsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">");
        sb.append("<Owner>");
        appendEscaped(sb, "<ID>", ownerId, "</ID>");
        appendEscaped(sb, "<DisplayName>", ownerDisplayName, "</DisplayName>");
        sb.append("</Owner>");
        sb.append("<Buckets>");
        for (String name : buckets) {
            appendEscaped(sb, "<Bucket><Name>", name, "</Name>");
            sb.append("<CreationDate>").append(formatInstant(Instant.now())).append("</CreationDate>");
            sb.append("</Bucket>");
        }
        sb.append("</Buckets>");
        sb.append("</ListAllMyBucketsResult>");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ===================== ListObjectsV2 =====================

    public static byte[] listBucketResultV2(String bucket,
                                            String prefix,
                                            int maxKeys,
                                            boolean isTruncated,
                                            String nextContinuationToken,
                                            List<ObjectMeta> contents,
                                            List<String> commonPrefixes) {
        StringBuilder sb = new StringBuilder(512 + contents.size() * 200);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">");
        appendEscaped(sb, "<Name>", bucket, "</Name>");
        if (prefix != null && !prefix.isEmpty()) {
            appendEscaped(sb, "<Prefix>", prefix, "</Prefix>");
        } else {
            sb.append("<Prefix></Prefix>");
        }
        sb.append("<KeyCount>").append(contents.size()).append("</KeyCount>");
        sb.append("<MaxKeys>").append(maxKeys).append("</MaxKeys>");
        sb.append("<IsTruncated>").append(isTruncated).append("</IsTruncated>");
        for (ObjectMeta m : contents) {
            sb.append("<Contents>");
            appendEscaped(sb, "<Key>", m.key(), "</Key>");
            sb.append("<LastModified>").append(formatInstant(m.lastModified())).append("</LastModified>");
            sb.append("<ETag>\"").append(escape(m.etag())).append("\"</ETag>");
            sb.append("<Size>").append(m.size()).append("</Size>");
            sb.append("<StorageClass>STANDARD</StorageClass>");
            sb.append("</Contents>");
        }
        if (commonPrefixes != null) {
            for (String cp : commonPrefixes) {
                appendEscaped(sb, "<CommonPrefixes><Prefix>", cp, "</Prefix></CommonPrefixes>");
            }
        }
        if (nextContinuationToken != null) {
            appendEscaped(sb, "<NextContinuationToken>", nextContinuationToken, "</NextContinuationToken>");
        }
        sb.append("</ListBucketResult>");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ===================== Error =====================

    public static byte[] errorBody(S3Exception ex) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<Error>");
        sb.append("<Code>").append(ex.s3Code()).append("</Code>");
        String msg = ex.getMessage();
        if (msg == null) msg = "";
        appendEscaped(sb, "<Message>", msg, "</Message>");
        if (ex.resource() != null) {
            appendEscaped(sb, "<Resource>", ex.resource(), "</Resource>");
        }
        sb.append("<RequestId>").append("0000000000000000").append("</RequestId>");
        sb.append("</Error>");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ===================== CommonPrefixes utilities =====================

    /**
     * Phase 1 简化版:从已排序的 object 列表里抽出 commonPrefixes。
     * 若 {@code delimiter} 为 null/"" 返回空列表。
     */
    public static java.util.List<String> computeCommonPrefixes(List<ObjectMeta> contents, String prefix, String delimiter) {
        if (delimiter == null || delimiter.isEmpty()) return java.util.Collections.emptyList();
        java.util.Set<String> set = new java.util.TreeSet<>();
        String filter = prefix == null ? "" : prefix;
        for (ObjectMeta m : contents) {
            if (!m.key().startsWith(filter)) continue;
            int idx = m.key().indexOf(delimiter, filter.length());
            if (idx >= 0) {
                set.add(m.key().substring(0, idx + delimiter.length()));
            }
        }
        return new java.util.ArrayList<>(set);
    }

    // ===================== XML escape + datetime =====================

    private static void appendEscaped(StringBuilder sb, String openTag, String value, String closeTag) {
        sb.append(openTag).append(escape(value)).append(closeTag);
    }

    static String escape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&':  out.append("&amp;"); break;
                case '<':  out.append("&lt;"); break;
                case '>':  out.append("&gt;"); break;
                case '"':  out.append("&quot;"); break;
                case '\'': out.append("&apos;"); break;
                case '\t':
                case '\n':
                case '\r':
                    // S3 错误响应里 message 字段保留空白;其他字段换行直接 strip
                    out.append(c);
                    break;
                default:
                    if (c < 0x20) {
                        out.append('?');
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    private static String formatInstant(Instant instant) {
        return LAST_MODIFIED_FMT.format(instant.atZone(java.time.ZoneOffset.UTC));
    }
}
