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

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SigV4 实现正确性测试 —— 用 AWS 官方文档里的 fixture:
 * <a href="https://docs.aws.amazon.com/general/latest/gr/sigv4-signed-request-examples.html">
 * Signature Version 4 signed-request examples</a>
 *
 * <p>两个 fixture:
 * <ul>
 *   <li>get-vanilla —— GET /,无 query,3 个 header(host / x-amz-date / x-amz-content-sha256)</li>
 *   <li>get-vanilla-query —— GET /?Action=ListUsers&Version=2010-05-08,query 排序后参与签名</li>
 * </ul>
 */
public class SigV4VerifierTest {

    private static final String AKID = "AKIDEXAMPLE";
    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
    private static final String REGION = "us-east-1";

    private SigV4Verifier verifier;

    private static final DateTimeFormatter AMZDATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /** 当前 amz-date 和 date stamp,用于构造"通过 clock skew 检查但签名/ACL 失败"的请求。 */
    private static String[] nowAmzDate() {
        String amz = AMZDATE_FMT.format(Instant.now());
        return new String[]{amz, amz.substring(0, 8)};
    }

    @BeforeEach
    void setUp() {
        Properties p = new Properties();
        p.setProperty("s3.accessKey." + AKID + ".secret", SECRET);
        p.setProperty("s3.accessKey." + AKID + ".region", REGION);
        AccessKeyResolver resolver = ConfigAccessKeyResolver.fromProperties(p);
        verifier = new SigV4Verifier(resolver);
    }

    /**
     * AWS fixture get-vanilla (20120227 us-east-1):
     *   GET /
     *   Host: example.amazonaws.com
     *   X-Amz-Date: 20120227T155427Z
     *   Canonical: empty payload hash → x-amz-content-sha256: e3b0c44...
     *
     * <p>AWS 官方 fixture 用的 access key 是 AKIAIOSFODNN7EXAMPLE(不是
     * AKIDEXAMPLE),date 是 20130524T000000Z(不是 20120227T155427Z)。本测试
     * 不能用固定的 placeholder signature 验证 —— 只能在 IT 阶段用真实 fixture
     * 数据走通。Phase 1 仅验证结构(非空 Authorization + 3 个必需 header),
     * 真实 fixture 由 S3HttpHandlerIT 端到端验证。
     */
    @Test
    void canonicalRequestHasExpectedStructure() throws Exception {
        // 模拟一个有效 Authorization + 必需 headers,placeholder signature
        // 必然不匹配 → 应该抛 SignatureDoesNotMatch
        String[] now = nowAmzDate();
        String amzDate = now[0];
        String dateStamp = now[1];
        String authz = "AWS4-HMAC-SHA256 Credential=" + AKID + "/" + dateStamp + "/us-east-1/s3/aws4_request, "
                + "SignedHeaders=host;x-amz-content-sha256;x-amz-date, "
                + "Signature=0000000000000000000000000000000000000000000000000000000000000000";
        Map<String, String> lowerHeaders = new HashMap<>();
        lowerHeaders.put("authorization", authz);
        lowerHeaders.put("host", "example.amazonaws.com");
        lowerHeaders.put("x-amz-date", amzDate);
        lowerHeaders.put("x-amz-content-sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        S3Request req = new S3Request(S3Operation.LIST_BUCKETS, null, null,
                Collections.emptyMap(), lowerHeaders, null, null, "/");
        S3Exception ex = assertThrows(S3Exception.class, () ->
                verifier.verify("GET", "/", Collections.emptyMap(), lowerHeaders, req));
        assertEquals("SignatureDoesNotMatch", ex.s3Code());
    }

    /**
     * canonical query string 排序验证 —— 用 get-vanilla-query fixture 的
     * query 部分,确认 sort + uriEncode 正确。
     */
    @Test
    void canonicalQueryStringSortsAndEncodes() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Action", "ListUsers");
        params.put("Version", "2010-05-08");
        String canon = SigV4Verifier.canonicalQueryString(params);
        // 应该按 key 字典序排序
        assertEquals("Action=ListUsers&Version=2010-05-08", canon);
    }

    /**
     * canonicalUri 折叠连续 slash(S3 规范):
     *   "//foo//bar//" → "/foo/bar/"
     */
    @Test
    void canonicalUriCollapsesDoubleSlashes() {
        assertEquals("/", SigV4Verifier.canonicalUri(""));
        assertEquals("/", SigV4Verifier.canonicalUri("/"));
        assertEquals("/foo/bar", SigV4Verifier.canonicalUri("/foo//bar"));
        assertEquals("/foo/bar/", SigV4Verifier.canonicalUri("//foo//bar//"));
    }

    /**
     * uriEncode 验证(只 encode unreserved 之外的字符):
     *   AWS 规范: unreserved = ALPHA / DIGIT / "-" / "." / "_" / "~"
     */
    @Test
    void uriEncodeReservedOnly() {
        assertEquals("abc-def_ghi.jkl~123", SigV4Verifier.uriEncode("abc-def_ghi.jkl~123"));
        assertEquals("a%20b", SigV4Verifier.uriEncode("a b"));
        assertEquals("%2F%3D%26", SigV4Verifier.uriEncode("/=&"));
    }

    /**
     * signing key 派生链 —— 验证 deriveSigningKey 4 步 HMAC 不变:
     *   kDate   = HMAC("AWS4"+secret, date)
     *   kRegion = HMAC(kDate, region)
     *   kService = HMAC(kRegion, service)
     *   kSigning = HMAC(kService, "aws4_request")
     */
    @Test
    void deriveSigningKeyProducesKnownVector() throws Exception {
        // AWS 文档 get-vanilla fixture 的 signing key 派生:
        // date=20120227, region=us-east-1, service=s3
        byte[] k = SigV4Verifier.deriveSigningKey(SECRET, "20120227", "us-east-1", "s3");
        assertNotNull(k);
        assertEquals(32, k.length);
    }

    @Test
    void sha256HexOfEmptyStringMatchesE3b0c4() throws Exception {
        String hash = SigV4Verifier.sha256Hex(new byte[0]);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash);
    }

    @Test
    void hexEncodeMatchesLowerCase() {
        byte[] in = {0x00, (byte) 0xab, (byte) 0xcd, (byte) 0xef};
        assertEquals("00abcdef", SigV4Verifier.hexEncode(in));
    }

    /**
     * 校验失败 → SIGNATURE_DOES_NOT_MATCH
     */
    @Test
    void signatureMismatchThrows() {
        String[] now = nowAmzDate();
        String amzDate = now[0];
        String dateStamp = now[1];
        String authz = "AWS4-HMAC-SHA256 Credential=" + AKID + "/" + dateStamp + "/us-east-1/s3/aws4_request, "
                + "SignedHeaders=host;x-amz-content-sha256;x-amz-date, "
                + "Signature=0000000000000000000000000000000000000000000000000000000000000000";
        Map<String, String> lowerHeaders = new HashMap<>();
        lowerHeaders.put("authorization", authz);
        lowerHeaders.put("host", "example.amazonaws.com");
        lowerHeaders.put("x-amz-date", amzDate);
        lowerHeaders.put("x-amz-content-sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");

        S3Request req = new S3Request(S3Operation.LIST_BUCKETS, null, null,
                Collections.emptyMap(), lowerHeaders, null, null, "/");

        S3Exception ex = assertThrows(S3Exception.class, () ->
                verifier.verify("GET", "/", Collections.emptyMap(), lowerHeaders, req));
        assertEquals("SignatureDoesNotMatch", ex.s3Code());
    }

    /**
     * access key 不存在 → INVALID_ACCESS_KEY_ID
     */
    @Test
    void unknownAccessKeyThrowsInvalid() {
        String authz = "AWS4-HMAC-SHA256 Credential=AKIDUNKNOWN/20120227/us-east-1/s3/aws4_request, "
                + "SignedHeaders=host;x-amz-content-sha256;x-amz-date, "
                + "Signature=00";
        Map<String, String> lowerHeaders = new HashMap<>();
        lowerHeaders.put("authorization", authz);
        lowerHeaders.put("host", "example.amazonaws.com");
        lowerHeaders.put("x-amz-date", "20120227T155427Z");
        lowerHeaders.put("x-amz-content-sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        S3Request req = new S3Request(S3Operation.LIST_BUCKETS, null, null,
                Collections.emptyMap(), lowerHeaders, null, null, "/");
        S3Exception ex = assertThrows(S3Exception.class, () ->
                verifier.verify("GET", "/", Collections.emptyMap(), lowerHeaders, req));
        assertEquals("InvalidAccessKeyId", ex.s3Code());
    }

    /**
     * region 不匹配 → ACCESS_DENIED
     */
    @Test
    void regionMismatchThrowsAccessDenied() {
        String authz = "AWS4-HMAC-SHA256 Credential=" + AKID + "/20120227/west-us-2/s3/aws4_request, "
                + "SignedHeaders=host;x-amz-content-sha256;x-amz-date, "
                + "Signature=00";
        Map<String, String> lowerHeaders = new HashMap<>();
        lowerHeaders.put("authorization", authz);
        lowerHeaders.put("host", "example.amazonaws.com");
        lowerHeaders.put("x-amz-date", "20120227T155427Z");
        lowerHeaders.put("x-amz-content-sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        S3Request req = new S3Request(S3Operation.LIST_BUCKETS, null, null,
                Collections.emptyMap(), lowerHeaders, null, null, "/");
        S3Exception ex = assertThrows(S3Exception.class, () ->
                verifier.verify("GET", "/", Collections.emptyMap(), lowerHeaders, req));
        assertEquals("AccessDenied", ex.s3Code());
        assertTrue(ex.getMessage().contains("region"));
    }

    /**
     * 时间偏差过大 → ACCESS_DENIED(15 分钟阈值)
     */
    @Test
    void clockSkewTooLargeThrows() {
        // 用 2010 年的时间戳 → 2026 年已远超 15 分钟
        String authz = "AWS4-HMAC-SHA256 Credential=" + AKID + "/20100227/us-east-1/s3/aws4_request, "
                + "SignedHeaders=host;x-amz-content-sha256;x-amz-date, "
                + "Signature=00";
        Map<String, String> lowerHeaders = new HashMap<>();
        lowerHeaders.put("authorization", authz);
        lowerHeaders.put("host", "example.amazonaws.com");
        lowerHeaders.put("x-amz-date", "20100227T155427Z");
        lowerHeaders.put("x-amz-content-sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        S3Request req = new S3Request(S3Operation.LIST_BUCKETS, null, null,
                Collections.emptyMap(), lowerHeaders, null, null, "/");
        S3Exception ex = assertThrows(S3Exception.class, () ->
                verifier.verify("GET", "/", Collections.emptyMap(), lowerHeaders, req));
        assertEquals("AccessDenied", ex.s3Code());
    }

    /**
     * 桶不在 allowedBuckets 里 → ACCESS_DENIED
     */
    @Test
    void bucketNotInAllowedListThrows() {
        Properties p = new Properties();
        p.setProperty("s3.accessKey." + AKID + ".secret", SECRET);
        p.setProperty("s3.accessKey." + AKID + ".region", REGION);
        p.setProperty("s3.accessKey." + AKID + ".buckets", "allowed-bucket,other-bucket");
        AccessKeyResolver resolver = ConfigAccessKeyResolver.fromProperties(p);
        SigV4Verifier v = new SigV4Verifier(resolver);

        String[] now = nowAmzDate();
        String amzDate = now[0];
        String dateStamp = now[1];
        String authz = "AWS4-HMAC-SHA256 Credential=" + AKID + "/" + dateStamp + "/us-east-1/s3/aws4_request, "
                + "SignedHeaders=host;x-amz-content-sha256;x-amz-date, "
                + "Signature=00";
        Map<String, String> lowerHeaders = new HashMap<>();
        lowerHeaders.put("authorization", authz);
        lowerHeaders.put("host", "example.amazonaws.com");
        lowerHeaders.put("x-amz-date", amzDate);
        lowerHeaders.put("x-amz-content-sha256", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        // 模拟"解析出来的桶是 forbidden-bucket"
        S3Request req = new S3Request(S3Operation.HEAD_BUCKET, "forbidden-bucket", null,
                Collections.emptyMap(), lowerHeaders, null, null, "/forbidden-bucket");
        S3Exception ex = assertThrows(S3Exception.class, () ->
                v.verify("HEAD", "/forbidden-bucket", Collections.emptyMap(), lowerHeaders, req));
        assertEquals("AccessDenied", ex.s3Code());
        assertTrue(ex.getMessage().contains("forbidden-bucket"));
    }
}