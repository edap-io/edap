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
import io.edap.s3.model.ListResult;
import io.edap.s3.model.ObjectMeta;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class XmlResponseBuilderTest {

    @Test
    void listAllMyBucketsResultContainsOwnerAndBuckets() {
        byte[] body = XmlResponseBuilder.listAllMyBucketsResult(
                "owner-1", "owner-name",
                Arrays.asList("alpha", "beta"));
        String xml = new String(body, StandardCharsets.UTF_8);
        assertTrue(xml.contains("<ListAllMyBucketsResult"));
        assertTrue(xml.contains("<ID>owner-1</ID>"));
        assertTrue(xml.contains("<DisplayName>owner-name</DisplayName>"));
        assertTrue(xml.contains("<Name>alpha</Name>"));
        assertTrue(xml.contains("<Name>beta</Name>"));
    }

    @Test
    void listBucketResultV2ContainsContentsAndCommonPrefixes() {
        Instant lm = Instant.parse("2026-09-06T10:30:00Z");
        ObjectMeta a = new ObjectMeta("a.txt", 100, "abc", "text/plain", lm, Collections.emptyMap());
        ObjectMeta b = new ObjectMeta("b.txt", 200, "def", "application/json", lm, Collections.emptyMap());
        ListResult result = new ListResult(Arrays.asList(a, b), Arrays.asList("docs/"), false, null);
        byte[] body = XmlResponseBuilder.listBucketResultV2(
                "mybucket", "", "", 1000, null, null, result);
        String xml = new String(body, StandardCharsets.UTF_8);
        assertTrue(xml.contains("<ListBucketResult"));
        assertTrue(xml.contains("<Name>mybucket</Name>"));
        assertTrue(xml.contains("<Key>a.txt</Key>"));
        assertTrue(xml.contains("<Key>b.txt</Key>"));
        assertTrue(xml.contains("<ETag>\"abc\"</ETag>"));
        assertTrue(xml.contains("<CommonPrefixes><Prefix>docs/</Prefix></CommonPrefixes>"));
        assertTrue(xml.contains("<MaxKeys>1000</MaxKeys>"));
        assertTrue(xml.contains("<IsTruncated>false</IsTruncated>"));
        // KeyCount = contents + commonPrefixes
        assertTrue(xml.contains("<KeyCount>3</KeyCount>"));
    }

    @Test
    void listBucketResultV2EchoesDelimiterAndTokens() {
        ListResult result = new ListResult(Collections.emptyList(), Collections.emptyList(),
                true, "NEXT-TOKEN");
        byte[] body = XmlResponseBuilder.listBucketResultV2(
                "mybucket", "p/", "/", 10, "CUR-TOKEN", "start-key", result);
        String xml = new String(body, StandardCharsets.UTF_8);
        assertTrue(xml.contains("<Prefix>p/</Prefix>"));
        assertTrue(xml.contains("<Delimiter>/</Delimiter>"));
        assertTrue(xml.contains("<ContinuationToken>CUR-TOKEN</ContinuationToken>"));
        assertTrue(xml.contains("<NextContinuationToken>NEXT-TOKEN</NextContinuationToken>"));
        assertTrue(xml.contains("<StartAfter>start-key</StartAfter>"));
        assertTrue(xml.contains("<IsTruncated>true</IsTruncated>"));
    }

    @Test
    void errorBodyIncludesCodeMessageAndRequestId() {
        S3Exception ex = new S3Exception(S3ErrorCode.NO_SUCH_KEY, "no key", "/b/k");
        byte[] body = XmlResponseBuilder.errorBody(ex);
        String xml = new String(body, StandardCharsets.UTF_8);
        assertTrue(xml.contains("<Code>NoSuchKey</Code>"));
        assertTrue(xml.contains("<Message>no key</Message>"));
        assertTrue(xml.contains("<Resource>/b/k</Resource>"));
        assertTrue(xml.contains("<RequestId>0000000000000000</RequestId>"));
    }

    @Test
    void escapeHandlesXmlSpecials() {
        assertEquals("&amp;", XmlResponseBuilder.escape("&"));
        assertEquals("&lt;", XmlResponseBuilder.escape("<"));
        assertEquals("&gt;", XmlResponseBuilder.escape(">"));
        assertEquals("&quot;", XmlResponseBuilder.escape("\""));
        assertEquals("&apos;", XmlResponseBuilder.escape("'"));
        assertEquals("a&amp;b&lt;c&gt;d", XmlResponseBuilder.escape("a&b<c>d"));
    }

    @Test
    void escapeStripsControlChars() {
        // 0x01 等不可打印 → '?'
        assertEquals("?", XmlResponseBuilder.escape("\u0001"));
    }

    @Test
    void escapeNullReturnsEmpty() {
        assertEquals("", XmlResponseBuilder.escape(null));
    }

    @Test
    void errorBodyNullMessageUsesEmpty() {
        S3Exception ex = new S3Exception(S3ErrorCode.INTERNAL_ERROR, null);
        byte[] body = XmlResponseBuilder.errorBody(ex);
        String xml = new String(body, StandardCharsets.UTF_8);
        assertTrue(xml.contains("<Message></Message>"));
    }

    @Test
    void listAllMyBucketsEmptyBucketList() {
        byte[] body = XmlResponseBuilder.listAllMyBucketsResult("o", "n", Collections.emptyList());
        String xml = new String(body, StandardCharsets.UTF_8);
        assertTrue(xml.contains("<Buckets></Buckets>"));
        assertFalse(xml.contains("<Bucket>"));
    }
}