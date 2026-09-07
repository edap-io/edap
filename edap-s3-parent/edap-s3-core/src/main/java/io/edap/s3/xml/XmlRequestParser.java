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

import io.edap.s3.error.S3ErrorCode;
import io.edap.s3.error.S3Exception;
import io.edap.s3.model.CompletedPart;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * S3 请求 XML body 解析 —— Phase 2 只支持 CompleteMultipartUpload。
 *
 * <p>不用 SAX/StAX 之类的 XML 库,直接 string scan:只识别 {@code <Part>...</Part>}
 * 块,提取 {@code <PartNumber>N</PartNumber>} 和 {@code <ETag>"..."</ETag>}。
 * 任何解析失败 → {@link S3Exception} {@code MALFORMED_XML}(400)。
 *
 * <p>输入 bytes 假定是 UTF-8 编码的 XML 文本;空白无关紧要。
 */
public final class XmlRequestParser {

    private XmlRequestParser() {}

    /**
     * 解析 {@code CompleteMultipartUpload} 请求 body。
     * 返回的 list 顺序:按 {@code <Part>} 在 XML 中出现的顺序(调用方需自己排序)。
     *
     * <p>ETag 两端的 ASCII 双引号({@code "})会被剥掉,只保留 hex 串。
     */
    public static List<CompletedPart> completeMultipartBody(byte[] xmlBytes) {
        if (xmlBytes == null || xmlBytes.length == 0) {
            return new ArrayList<>();
        }
        String xml = new String(xmlBytes, StandardCharsets.UTF_8);
        List<CompletedPart> result = new ArrayList<>();
        int idx = 0;
        while (true) {
            int partStart = xml.indexOf("<Part>", idx);
            if (partStart < 0) break;
            int partEnd = xml.indexOf("</Part>", partStart);
            if (partEnd < 0) {
                throw new S3Exception(S3ErrorCode.MALFORMED_XML,
                        "Missing </Part> close tag in CompleteMultipartUpload body");
            }
            String block = xml.substring(partStart + "<Part>".length(), partEnd);

            int partNumber = extractInt(block, "PartNumber");
            String etag = extractString(block, "ETag");

            result.add(new CompletedPart(partNumber, etag));
            idx = partEnd + "</Part>".length();
        }
        return result;
    }

    /**
     * 提取 {@code <TAG>VALUE</TAG>} 形式的 int。VALUE 必须是合法十进制整数。
     */
    private static int extractInt(String block, String tag) {
        String raw = extractString(block, tag);
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new S3Exception(S3ErrorCode.MALFORMED_XML,
                    "Invalid <" + tag + "> value: " + raw);
        }
    }

    /**
     * 提取 {@code <TAG>VALUE</TAG>} 形式的字符串。VALUE 周围 ASCII 双引号会被剥掉。
     */
    private static String extractString(String block, String tag) {
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int openIdx = block.indexOf(open);
        int closeIdx = block.indexOf(close);
        if (openIdx < 0 || closeIdx < 0 || closeIdx < openIdx) {
            throw new S3Exception(S3ErrorCode.MALFORMED_XML,
                    "Missing <" + tag + ">...</" + tag + "> in <Part> block");
        }
        String value = block.substring(openIdx + open.length(), closeIdx).trim();
        // S3 规范 ETag 在 XML 里带引号,剥掉
        if (value.length() >= 2 && value.charAt(0) == '"'
                && value.charAt(value.length() - 1) == '"') {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }
}
