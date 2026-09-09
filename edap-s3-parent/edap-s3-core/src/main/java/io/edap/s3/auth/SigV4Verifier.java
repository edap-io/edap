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

import io.edap.s3.error.S3ErrorCode;
import io.edap.s3.error.S3Exception;
import io.edap.s3.model.S3Request;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * AWS Signature Version 4 验签器 —— 严格按 AWS 规范实现,
 * 通过 {@code AWS 官方 fixture 测试}(get-vanilla / get-vanilla-query /
 * aws4-test-suite)验证。
 *
 * <p>参考:
 * <a href="https://docs.aws.amazon.com/general/latest/gr/signature-version-4.html">
 * Signature Version 4 signing process</a>
 *
 * <p>算法骨架:
 * <pre>
 *   Authorization: AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/s3/aws4_request,
 *                           SignedHeaders=host;x-amz-content-sha256;x-amz-date,
 *                           Signature=hex
 *   canonicalRequest = METHOD \n URI \n QUERY \n HEADERS \n SIGNED \n HASH(PAYLOAD)
 *   stringToSign     = "AWS4-HMAC-SHA256" \n AMZDATE \n SCOPE \n HASH(canonicalRequest)
 *   signingKey       = HMAC chain ("AWS4" + secret → date → region → service → "aws4_request")
 *   signature        = HEX(HMAC(signingKey, stringToSign))
 * </pre>
 *
 * <p>core 模块零 HTTP 依赖:入参全部 Map / String。
 * server 模块的 S3HttpHandler 负责从 HttpRequest 抽取 headers / queryParams / path。
 *
 * <p>Phase 1 简化:不支持 presigned URL;不支持 UNSIGNED-PAYLOAD。
 *
 * <p>Phase 2:支持 query-string auth mode(预签 URL)。{@link #verify} 入口 dispatch:
 * <ul>
 *   <li>request 带 {@code X-Amz-Signature} query param → 走 query 模式验签
 *       (对应 {@link io.edap.s3.auth.SigV4Presigner} 生成的 URL);</li>
 *   <li>否则走 header 模式(原逻辑)。</li>
 * </ul>
 */
public class SigV4Verifier implements S3AuthVerifier {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";

    /** Query-string auth mode 固定 payload hash。 */
    public static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    private static final DateTimeFormatter AMZDATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(15);

    private final AccessKeyResolver keyResolver;

    public SigV4Verifier(AccessKeyResolver keyResolver) {
        this.keyResolver = keyResolver;
    }

    @Override
    public void verify(String httpMethod,
                       String path,
                       Map<String, String> queryParams,
                       Map<String, String> headers,
                       S3Request parsed) throws Exception {
        // query-string 模式 dispatch:presign URL 带 X-Amz-Signature query param,
        // header 模式不带 —— 优先按 query 模式验签。
        if (queryParams != null && queryParams.containsKey("X-Amz-Signature")) {
            verifyQueryMode(httpMethod, path, queryParams, headers, parsed);
            return;
        }

        // headers 转 lowercase(SigV4 强制)
        Map<String, String> lowerHeaders = new HashMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            lowerHeaders.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }

        String authz = lowerHeaders.get("authorization");
        if (authz == null || !authz.startsWith(ALGORITHM + " ")) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED,
                    "Missing or unsupported Authorization header");
        }

        // 1. 解析 Authorization 头
        Map<String, String> fields = parseAuthFields(authz);
        String credential = fields.get("Credential");
        String signedHeaders = fields.get("SignedHeaders");
        String clientSignature = fields.get("Signature");
        if (credential == null || signedHeaders == null || clientSignature == null) {
            throw new S3Exception(S3ErrorCode.SIGNATURE_DOES_NOT_MATCH,
                    "Authorization header missing required fields");
        }

        // 2. Credential = AKID/date/region/service/aws4_request
        String[] credParts = credential.split("/");
        if (credParts.length != 5) {
            throw new S3Exception(S3ErrorCode.SIGNATURE_DOES_NOT_MATCH,
                    "Credential format invalid: " + credential);
        }
        String accessKeyId = credParts[0];
        String credDate = credParts[1];
        String credRegion = credParts[2];
        String credService = credParts[3];

        // 3. 查 access key
        AccessKeyResolver.ResolvedKey resolved = keyResolver.resolve(accessKeyId);
        if (resolved == null) {
            throw new S3Exception(S3ErrorCode.INVALID_ACCESS_KEY_ID,
                    "The AWS access key ID you provided does not exist: " + accessKeyId);
        }

        // 4. scope 检查
        String amzDate = lowerHeaders.get("x-amz-date");
        if (amzDate == null) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED, "Missing x-amz-date header");
        }
        if (!credDate.equals(amzDate.substring(0, 8))) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED,
                    "Credential date does not match x-amz-date");
        }
        if (!credRegion.equals(resolved.region())) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED,
                    "Credential region mismatch: signed=" + credRegion
                            + " configured=" + resolved.region());
        }
        if (!credService.equals(resolved.service())) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED,
                    "Credential service mismatch: signed=" + credService
                            + " configured=" + resolved.service());
        }
        Instant now = Instant.now();
        Instant signedAt = Instant.from(AMZDATE_FMT.parse(amzDate));
        if (Duration.between(signedAt, now).abs().compareTo(CLOCK_SKEW) > 0) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED,
                    "Request timestamp too skewed: signed=" + amzDate);
        }

        // 5. 桶级 ACL
        if (parsed.bucket() != null && resolved.allowedBuckets() != null
                && !resolved.allowedBuckets().contains(parsed.bucket())) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED,
                    "Access denied to bucket: " + parsed.bucket());
        }

        // 6. 重建 canonical request
        String payloadHash = lowerHeaders.get("x-amz-content-sha256");
        if (payloadHash == null) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED,
                    "Missing x-amz-content-sha256 header");
        }
        String[] signedHeaderList = signedHeaders.split(";");
        Map<String, String> pickedHeaders = pickHeaders(lowerHeaders, signedHeaderList);
        String canonicalRequest = buildCanonicalRequest(
                httpMethod, canonicalUri(path),
                canonicalQueryString(queryParams),
                pickedHeaders, signedHeaderList, payloadHash);

        // 7. string to sign
        String scope = credDate + "/" + credRegion + "/" + credService + "/aws4_request";
        String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        // 8. signing key → signature
        byte[] signingKey = deriveSigningKey(resolved.secretKey(), credDate, credRegion, credService);
        String serverSignature = hexEncode(hmac(signingKey, stringToSign));

        // 9. 对比
        if (!constantTimeEquals(serverSignature, clientSignature)) {
            throw new S3Exception(S3ErrorCode.SIGNATURE_DOES_NOT_MATCH,
                    "Signature mismatch: expected=" + serverSignature + " got=" + clientSignature);
        }
    }

    /**
     * SigV4 query-string 模式验签 —— 服务端接住 {@link SigV4Presigner} 生成的预签 URL 时走这条路。
     *
     * <p>与 header 模式的关键差异(本方法已严格按 AWS 规范实现):
     * <ul>
     *   <li>所有鉴权字段从 query params 取,header 模式取 {@code Authorization} header。</li>
     *   <li>{@code SignedHeaders} 恒为 {@code host}(query 模式 spec 强制)。</li>
     *   <li>canonical payload hash 是字面量 {@code UNSIGNED-PAYLOAD},不重算 body。</li>
     *   <li>{@code X-Amz-Expires} 是相对 signedAt 的<b>绝对窗口</b> —— 不叠加 clock skew。</li>
     *   <li>canonical query string 必须<b>不</b>包含 {@code X-Amz-Signature} 自身
     *       (AWS 规范的硬要求,违反会导致验签永远不过)。</li>
     * </ul>
     */
    private void verifyQueryMode(String httpMethod,
                                  String path,
                                  Map<String, String> queryParams,
                                  Map<String, String> headers,
                                  S3Request parsed) throws Exception {
        // 1. 必备 query 字段
        String algorithm = queryParams.get("X-Amz-Algorithm");
        String credential = queryParams.get("X-Amz-Credential");
        String amzDate = queryParams.get("X-Amz-Date");
        String expiresStr = queryParams.get("X-Amz-Expires");
        String signedHeaders = queryParams.get("X-Amz-SignedHeaders");
        String clientSignature = queryParams.get("X-Amz-Signature");
        if (algorithm == null || credential == null || amzDate == null
                || expiresStr == null || signedHeaders == null || clientSignature == null) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED,
                    "Missing one of required SigV4 query params: "
                            + "X-Amz-Algorithm/Credential/Date/Expires/SignedHeaders/Signature");
        }
        if (!ALGORITHM.equals(algorithm)) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED,
                    "Unsupported algorithm: " + algorithm);
        }

        // 2. Credential 解析
        String[] credParts = credential.split("/");
        if (credParts.length != 5) {
            throw new S3Exception(S3ErrorCode.SIGNATURE_DOES_NOT_MATCH,
                    "Credential format invalid: " + credential);
        }
        String accessKeyId = credParts[0];
        String credDate = credParts[1];
        String credRegion = credParts[2];
        String credService = credParts[3];

        // 3. 查 access key
        AccessKeyResolver.ResolvedKey resolved = keyResolver.resolve(accessKeyId);
        if (resolved == null) {
            throw new S3Exception(S3ErrorCode.INVALID_ACCESS_KEY_ID,
                    "The AWS access key ID you provided does not exist: " + accessKeyId);
        }

        // 4. scope 一致性
        if (!credDate.equals(amzDate.substring(0, 8))) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED,
                    "Credential date does not match X-Amz-Date");
        }
        if (!credRegion.equals(resolved.region())) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED,
                    "Credential region mismatch: signed=" + credRegion
                            + " configured=" + resolved.region());
        }
        if (!credService.equals(resolved.service())) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED,
                    "Credential service mismatch: signed=" + credService
                            + " configured=" + resolved.service());
        }

        // 5. expires 校验(绝对窗口,无 clock skew)
        long expiresSeconds;
        try {
            expiresSeconds = Long.parseLong(expiresStr);
        } catch (NumberFormatException e) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED,
                    "X-Amz-Expires must be integer seconds: " + expiresStr);
        }
        if (expiresSeconds < 1 || expiresSeconds > 604800) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED,
                    "X-Amz-Expires out of range [1, 604800]: " + expiresSeconds);
        }
        Instant signedAt = Instant.from(AMZDATE_FMT.parse(amzDate));
        Instant expiresAt = signedAt.plusSeconds(expiresSeconds);
        if (Instant.now().isAfter(expiresAt)) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED,
                    "Request has expired: signedAt=" + amzDate + " expiresSeconds=" + expiresSeconds);
        }

        // 6. 桶级 ACL
        if (parsed.bucket() != null && resolved.allowedBuckets() != null
                && !resolved.allowedBuckets().contains(parsed.bucket())) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED,
                    "Access denied to bucket: " + parsed.bucket());
        }

        // 7. canonical request —— SignedHeaders 必须 = "host",payload = UNSIGNED-PAYLOAD,
        // query 必须不含 X-Amz-Signature。
        // headers 是调用方传进来的原始 map,S3HttpHandler 已 lowercased。
        String hostHeader = headers.get("host");
        if (hostHeader == null) {
            throw new S3Exception(S3ErrorCode.ACCESS_DENIED,
                    "Missing Host header (required for SigV4 query mode)");
        }
        if (!"host".equals(signedHeaders)) {
            throw new S3Exception(S3ErrorCode.SIGNATURE_DOES_NOT_MATCH,
                    "X-Amz-SignedHeaders must be 'host' for query mode, got: " + signedHeaders);
        }
        Map<String, String> pickedHeaders = new LinkedHashMap<>();
        pickedHeaders.put("host", hostHeader);

        // canonical query:从 queryParams 拷贝,移除 X-Amz-Signature(算 sig 时不带它)
        Map<String, String> queryForCanonical = new TreeMap<>(queryParams);
        queryForCanonical.remove("X-Amz-Signature");

        String canonicalRequest = buildCanonicalRequest(
                httpMethod, canonicalUri(path),
                canonicalQueryString(queryForCanonical),
                pickedHeaders, new String[] {"host"},
                UNSIGNED_PAYLOAD);

        // 8. string to sign
        String scope = credDate + "/" + credRegion + "/" + credService + "/aws4_request";
        String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        // 9. signing key → signature 比对
        byte[] signingKey = deriveSigningKey(resolved.secretKey(), credDate, credRegion, credService);
        String serverSignature = hexEncode(hmac(signingKey, stringToSign));

        if (!constantTimeEquals(serverSignature, clientSignature)) {
            throw new S3Exception(S3ErrorCode.SIGNATURE_DOES_NOT_MATCH,
                    "Signature mismatch: expected=" + serverSignature + " got=" + clientSignature);
        }
    }

    // ===================== 签名构建(可见性放宽,方便单测) =====================

    public static String buildCanonicalRequest(String method,
                                               String canonicalUri,
                                               String canonicalQuery,
                                               Map<String, String> signedHeaders,
                                               String[] signedHeaderList,
                                               String payloadHash) {
        StringBuilder sb = new StringBuilder(512);
        sb.append(method).append('\n');
        sb.append(canonicalUri).append('\n');
        sb.append(canonicalQuery).append('\n');
        for (String name : signedHeaderList) {
            String value = signedHeaders.get(name);
            sb.append(name).append(':').append(collapseSpaces(value)).append('\n');
        }
        sb.append('\n');
        sb.append(String.join(";", signedHeaderList)).append('\n');
        sb.append(payloadHash);
        return sb.toString();
    }

    public static byte[] deriveSigningKey(String secretKey, String date, String region, String service) throws Exception {
        byte[] kDate = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, service);
        return hmac(kService, "aws4_request");
    }

    public static String canonicalUri(String path) {
        if (path == null || path.isEmpty()) return "/";
        StringBuilder sb = new StringBuilder(path.length());
        boolean lastSlash = false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '/') {
                if (!lastSlash) sb.append('/');
                lastSlash = true;
            } else {
                sb.append(c);
                lastSlash = false;
            }
        }
        return sb.toString();
    }

    public static String canonicalQueryString(Map<String, String> params) {
        if (params == null || params.isEmpty()) return "";
        TreeMap<String, String> sorted = new TreeMap<>(params);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) sb.append('&');
            sb.append(uriEncode(e.getKey())).append('=').append(uriEncode(e.getValue()));
            first = false;
        }
        return sb.toString();
    }

    // ===================== 工具 =====================

    private static Map<String, String> parseAuthFields(String authz) {
        Map<String, String> map = new HashMap<>();
        String body = authz.substring(ALGORITHM.length() + 1);
        for (String part : body.split(",")) {
            String trimmed = part.trim();
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                map.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
            }
        }
        return map;
    }

    private static Map<String, String> pickHeaders(Map<String, String> all, String[] signedHeaderList) {
        Map<String, String> picked = new LinkedHashMap<>();
        for (String name : signedHeaderList) {
            String v = all.get(name);
            if (v == null) {
                throw new IllegalArgumentException("Signed header missing: " + name);
            }
            picked.put(name, v);
        }
        return picked;
    }

    public static String uriEncode(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            int v = b & 0xff;
            if ((v >= 'A' && v <= 'Z') || (v >= 'a' && v <= 'z')
                    || (v >= '0' && v <= '9') || v == '-' || v == '_' || v == '.' || v == '~') {
                sb.append((char) v);
            } else {
                sb.append('%');
                sb.append(hexChar(v >>> 4));
                sb.append(hexChar(v & 0x0f));
            }
        }
        return sb.toString();
    }

    private static char hexChar(int v) {
        return (char) (v < 10 ? '0' + v : 'A' + v - 10);
    }

    private static String collapseSpaces(String s) {
        return s.trim().replaceAll("\\s+", " ");
    }

    public static byte[] hmac(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return hexEncode(md.digest(data));
    }

    public static String hexEncode(byte[] data) {
        char[] out = new char[data.length * 2];
        char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xff;
            out[i * 2] = hex[v >>> 4];
            out[i * 2 + 1] = hex[v & 0x0f];
        }
        return new String(out);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
