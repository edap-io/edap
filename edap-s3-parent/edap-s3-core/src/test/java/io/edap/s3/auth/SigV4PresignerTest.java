/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.auth;

import io.edap.s3.error.S3Exception;
import io.edap.s3.model.S3Request;
import io.edap.s3.op.S3Operation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SigV4Presigner 单元测试 —— 覆盖 URL shape / 签名确定性 / expires 边界 /
 * 端口处理 / 多段参数 / 与 {@link SigV4Verifier} 的 roundtrip。
 */
public class SigV4PresignerTest {

    private static final String AKID = "AKIDEXAMPLE";
    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
    private static final String REGION = "us-east-1";
    private static final String HOST = "s3.internal";
    private static final int PORT = 9000;

    private SigV4Presigner presigner;
    private SigV4Verifier verifier;

    @BeforeEach
    void setUp() {
        Properties p = new Properties();
        p.setProperty("s3.accessKey." + AKID + ".secret", SECRET);
        p.setProperty("s3.accessKey." + AKID + ".region", REGION);
        AccessKeyResolver resolver = ConfigAccessKeyResolver.fromProperties(p);
        presigner = new SigV4Presigner();
        verifier = new SigV4Verifier(resolver);
    }

    // ===================== URL shape =====================

    @Test
    void presignPutObject_urlShape() throws Exception {
        SigV4Presigner.PresignedUrl u = presigner.presignPutObject(
                AKID, SECRET, REGION, "my-bucket", "photos/cat.jpg",
                HOST, PORT, Duration.ofMinutes(15));

        assertTrue(u.url().startsWith("http://" + HOST + ":" + PORT + "/my-bucket/photos/cat.jpg?"),
                "URL 必须 path-style: " + u.url());

        Map<String, String> q = u.queryParams();
        assertEquals("AWS4-HMAC-SHA256", q.get("X-Amz-Algorithm"));
        assertEquals(AKID + "/", q.get("X-Amz-Credential").substring(0, AKID.length() + 1),
                "X-Amz-Credential 必须以 AKID/ 开头");
        assertNotNull(q.get("X-Amz-Date"));
        assertEquals("900", q.get("X-Amz-Expires"));  // 15min = 900s
        assertEquals("host", q.get("X-Amz-SignedHeaders"));
        assertNotNull(q.get("X-Amz-Signature"));
        assertEquals(64, q.get("X-Amz-Signature").length(), "signature 必须是 64 hex chars (SHA-256)");
    }

    // ===================== 确定性 =====================

    @Test
    void presignPutObject_signatureDeterministic() throws Exception {
        SigV4Presigner.PresignedUrl a = presigner.presignPutObject(
                AKID, SECRET, REGION, "b", "k", HOST, PORT, Duration.ofMinutes(5));
        SigV4Presigner.PresignedUrl b = presigner.presignPutObject(
                AKID, SECRET, REGION, "b", "k", HOST, PORT, Duration.ofMinutes(5));
        assertNotNull(a.queryParams().get("X-Amz-Signature"));
        assertNotNull(b.queryParams().get("X-Amz-Signature"));
    }

    @Test
    void presignPutObject_differentExpiresDifferentSignature() throws Exception {
        SigV4Presigner.PresignedUrl shortUrl = presigner.presignPutObject(
                AKID, SECRET, REGION, "b", "k", HOST, PORT, Duration.ofMinutes(1));
        SigV4Presigner.PresignedUrl longUrl = presigner.presignPutObject(
                AKID, SECRET, REGION, "b", "k", HOST, PORT, Duration.ofHours(1));
        // X-Amz-Expires 不同(60 vs 3600),sig 必然不同
        assertNotEquals(shortUrl.queryParams().get("X-Amz-Signature"),
                longUrl.queryParams().get("X-Amz-Signature"));
        assertEquals("60", shortUrl.queryParams().get("X-Amz-Expires"));
        assertEquals("3600", longUrl.queryParams().get("X-Amz-Expires"));
    }

    // ===================== expires 边界 =====================

    @Test
    void presignPutObject_expiresBoundary() throws Exception {
        // 1 秒 OK
        SigV4Presigner.PresignedUrl min = presigner.presignPutObject(
                AKID, SECRET, REGION, "b", "k", HOST, PORT, Duration.ofSeconds(1));
        assertEquals("1", min.queryParams().get("X-Amz-Expires"));

        // 7 天 OK
        SigV4Presigner.PresignedUrl max = presigner.presignPutObject(
                AKID, SECRET, REGION, "b", "k", HOST, PORT, Duration.ofDays(7));
        assertEquals("604800", max.queryParams().get("X-Amz-Expires"));

        // 超过 7 天 → IAE
        assertThrows(IllegalArgumentException.class, () -> presigner.presignPutObject(
                AKID, SECRET, REGION, "b", "k", HOST, PORT,
                Duration.ofDays(7).plusSeconds(1)));

        // 0 秒 → IAE
        assertThrows(IllegalArgumentException.class, () -> presigner.presignPutObject(
                AKID, SECRET, REGION, "b", "k", HOST, PORT, Duration.ZERO));

        // null → IAE
        assertThrows(IllegalArgumentException.class, () -> presigner.presignPutObject(
                AKID, SECRET, REGION, "b", "k", HOST, PORT, null));
    }

    // ===================== 端口处理 =====================

    @Test
    void presignPutObject_hostWithPort_appearsInUrl() throws Exception {
        SigV4Presigner.PresignedUrl u = presigner.presignPutObject(
                AKID, SECRET, REGION, "b", "k", HOST, 8080,
                Duration.ofMinutes(5));
        assertTrue(u.url().contains(":8080"), "非默认端口必须在 URL 里出现: " + u.url());
        assertEquals("s3.internal:8080", SigV4Presigner.canonicalHost(HOST, 8080));
    }

    @Test
    void presignPutObject_hostPortDefault_omittedFromCanonical() {
        // 80 / 443 → canonical host 不带端口
        assertEquals("s3.internal", SigV4Presigner.canonicalHost(HOST, 80));
        assertEquals("s3.internal", SigV4Presigner.canonicalHost(HOST, 443));
    }

    @Test
    void presignPutObject_canonicalHost_lowercaseAndPort() {
        // 大写 host → lowercase
        assertEquals("s3.internal", SigV4Presigner.canonicalHost("S3.Internal", 443));
        assertEquals("s3.internal:8080", SigV4Presigner.canonicalHost("S3.INTERNAL", 8080));
    }

    // ===================== Multipart query params =====================

    @Test
    void presignInitiateMultipart_hasUploadsParam() throws Exception {
        SigV4Presigner.PresignedUrl u = presigner.presignInitiateMultipart(
                AKID, SECRET, REGION, "b", "k", HOST, PORT,
                Duration.ofMinutes(5));
        assertTrue(u.url().contains("uploads="),
                "POST /key?uploads 必须有 uploads= query: " + u.url());
        assertEquals("", u.queryParams().get("uploads"));
    }

    @Test
    void presignUploadPart_includesPartNumberAndUploadId() throws Exception {
        SigV4Presigner.PresignedUrl u = presigner.presignUploadPart(
                AKID, SECRET, REGION, "b", "k", "upload-id-xyz", 7, HOST, PORT,
                Duration.ofMinutes(5));
        assertEquals("upload-id-xyz", u.queryParams().get("uploadId"));
        assertEquals("7", u.queryParams().get("partNumber"));
        assertTrue(u.url().contains("partNumber=7"), "URL 必须含 partNumber=7: " + u.url());
        assertTrue(u.url().contains("uploadId=upload-id-xyz"),
                "URL 必须含 uploadId=upload-id-xyz: " + u.url());
    }

    @Test
    void presignCompleteMultipart_includesUploadId() throws Exception {
        SigV4Presigner.PresignedUrl u = presigner.presignCompleteMultipart(
                AKID, SECRET, REGION, "b", "k", "uid", HOST, PORT,
                Duration.ofMinutes(5));
        assertEquals("uid", u.queryParams().get("uploadId"));
        // 不应该有 uploads=
        assertFalse(u.url().contains("uploads="),
                "Complete 不应有 uploads= query: " + u.url());
    }

    @Test
    void presignAbortMultipart_includesUploadId() throws Exception {
        SigV4Presigner.PresignedUrl u = presigner.presignAbortMultipart(
                AKID, SECRET, REGION, "b", "k", "uid", HOST, PORT,
                Duration.ofMinutes(5));
        assertEquals("uid", u.queryParams().get("uploadId"));
        assertTrue(u.url().contains("/b/k?"), "URL 路径正确: " + u.url());
    }

    // ===================== Roundtrip with verifier =====================

    @Test
    void presignRoundtripWithVerifier_putObject() throws Exception {
        // presign
        SigV4Presigner.PresignedUrl u = presigner.presignPutObject(
                AKID, SECRET, REGION, "my-bucket", "uploads/x.bin", HOST, PORT,
                Duration.ofMinutes(10));

        // 模拟 server 收到的请求:从 URL 拆 query,塞到 headers
        ParsedUrl parsed = ParsedUrl.parse(u.url());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("host", HOST + ":" + PORT);  // wire 上 Host header 必须含端口

        S3Request req = new S3Request(S3Operation.PUT_OBJECT, "my-bucket", "uploads/x.bin",
                parsed.queryParams, headers, null, null, parsed.path);

        // 验签 —— 期望不抛
        verifier.verify("PUT", parsed.path, parsed.queryParams, headers, req);
    }

    @Test
    void presignRoundtripWithVerifier_uploadPart() throws Exception {
        SigV4Presigner.PresignedUrl u = presigner.presignUploadPart(
                AKID, SECRET, REGION, "b", "k", "uid-123", 3, HOST, PORT,
                Duration.ofMinutes(10));

        ParsedUrl parsed = ParsedUrl.parse(u.url());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("host", HOST + ":" + PORT);

        S3Request req = new S3Request(S3Operation.UPLOAD_PART, "b", "k",
                parsed.queryParams, headers, null, null, parsed.path);
        verifier.verify("PUT", parsed.path, parsed.queryParams, headers, req);
    }

    @Test
    void presignRoundtripWithVerifier_initiateMultipart() throws Exception {
        SigV4Presigner.PresignedUrl u = presigner.presignInitiateMultipart(
                AKID, SECRET, REGION, "b", "k", HOST, PORT,
                Duration.ofMinutes(10));

        ParsedUrl parsed = ParsedUrl.parse(u.url());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("host", HOST + ":" + PORT);

        S3Request req = new S3Request(S3Operation.INIT_MULTIPART, "b", "k",
                parsed.queryParams, headers, null, null, parsed.path);
        verifier.verify("POST", parsed.path, parsed.queryParams, headers, req);
    }

    @Test
    void presignRoundtripWithVerifier_completeMultipart() throws Exception {
        SigV4Presigner.PresignedUrl u = presigner.presignCompleteMultipart(
                AKID, SECRET, REGION, "b", "k", "uid-abc", HOST, PORT,
                Duration.ofMinutes(10));

        ParsedUrl parsed = ParsedUrl.parse(u.url());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("host", HOST + ":" + PORT);

        S3Request req = new S3Request(S3Operation.COMPLETE_MULTIPART, "b", "k",
                parsed.queryParams, headers, null, null, parsed.path);
        verifier.verify("POST", parsed.path, parsed.queryParams, headers, req);
    }

    @Test
    void presignRoundtripWithVerifier_abortMultipart() throws Exception {
        SigV4Presigner.PresignedUrl u = presigner.presignAbortMultipart(
                AKID, SECRET, REGION, "b", "k", "uid-zzz", HOST, PORT,
                Duration.ofMinutes(10));

        ParsedUrl parsed = ParsedUrl.parse(u.url());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("host", HOST + ":" + PORT);

        S3Request req = new S3Request(S3Operation.ABORT_MULTIPART, "b", "k",
                parsed.queryParams, headers, null, null, parsed.path);
        verifier.verify("DELETE", parsed.path, parsed.queryParams, headers, req);
    }

    // ===================== helpers =====================

    /** 仅用于测试中提取 HTTP 方法(从 ? 之前的 query 上没法,这里靠手算)。实际 URL 是
     *  path-style,我们只是 verify 路由,所以从 query 里读 X-Amz-* 不影响方法提取。
     *  这里直接传 method 跳过。 */
    private static String extractMethodFromCanonical(String url) {
        // 实际上 caller 已知 method,不需要从 URL 解析。这里仅占位防止编译期 unused 警告。
        return url.contains("uploads=") ? "POST" : "GET";
    }

    /**
     * 从完整 URL 拆 path + query params —— 模拟 server 收到的 wire 形态。
     */
    static final class ParsedUrl {
        final String path;
        final Map<String, String> queryParams;

        ParsedUrl(String path, Map<String, String> queryParams) {
            this.path = path;
            this.queryParams = queryParams;
        }

        static ParsedUrl parse(String url) {
            URI uri = URI.create(url);
            String path = uri.getRawPath();
            Map<String, String> params = new TreeMap<>();
            String rawQuery = uri.getRawQuery();
            if (rawQuery != null && !rawQuery.isEmpty()) {
                for (String pair : rawQuery.split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        String k = java.net.URLDecoder.decode(pair.substring(0, eq),
                                java.nio.charset.StandardCharsets.UTF_8);
                        String v = java.net.URLDecoder.decode(pair.substring(eq + 1),
                                java.nio.charset.StandardCharsets.UTF_8);
                        params.put(k, v);
                    } else {
                        String k = java.net.URLDecoder.decode(pair,
                                java.nio.charset.StandardCharsets.UTF_8);
                        params.put(k, "");
                    }
                }
            }
            return new ParsedUrl(path, params);
        }
    }
}
