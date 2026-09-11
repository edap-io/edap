/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.http;

import io.edap.Edap;
import io.edap.http.server.HttpServer;
import io.edap.s3.auth.AccessKeyResolver;
import io.edap.s3.auth.ConfigAccessKeyResolver;
import io.edap.s3.op.handler.CreateBucketHandler;
import io.edap.s3.op.handler.DeleteBucketHandler;
import io.edap.s3.op.handler.DeleteObjectHandler;
import io.edap.s3.op.handler.GetObjectHandler;
import io.edap.s3.op.handler.HeadBucketHandler;
import io.edap.s3.op.handler.HeadObjectHandler;
import io.edap.s3.op.handler.ListBucketsHandler;
import io.edap.s3.op.handler.ListObjectsV2Handler;
import io.edap.s3.op.handler.PutBucketAclHandler;
import io.edap.s3.op.handler.PutObjectHandler;
import io.edap.s3.store.BucketStore;
import io.edap.s3.store.MultipartStore;
import io.edap.s3.store.ObjectStore;
import io.edap.s3.store.mem.InMemoryBucketStore;
import io.edap.s3.store.mem.InMemoryMultipartStore;
import io.edap.s3.store.mem.InMemoryObjectStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桶 ACL 集成测试 —— 验证 canned ACL 的 wire-level 行为:
 * <ul>
 *   <li>PUBLIC_READ:匿名 GET / HEAD / LIST 放行;PUT 仍要签名</li>
 *   <li>PUBLIC_READ_WRITE:匿名 GET + PUT 都放行</li>
 *   <li>PRIVATE:所有非签请求都拒</li>
 *   <li>PUT_BUCKET_ACL 自身永远要求签名(任何 ACL 下)</li>
 * </ul>
 */
public class S3BucketAclIT {

    private static final String AKID = "AKIDEXAMPLE";
    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
    private static final String REGION = "us-east-1";

    private int port;
    private HttpServer server;
    private Edap edap;
    private BucketStore bucketStore;
    private ObjectStore objectStore;
    private MultipartStore multipartStore;

    @BeforeEach
    void setUp() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        bucketStore = new InMemoryBucketStore();
        objectStore = new InMemoryObjectStore(bucketStore);
        multipartStore = new InMemoryMultipartStore(bucketStore, objectStore);

        Properties p = new Properties();
        p.setProperty("s3.accessKey." + AKID + ".secret", SECRET);
        p.setProperty("s3.accessKey." + AKID + ".region", REGION);
        AccessKeyResolver resolver = ConfigAccessKeyResolver.fromProperties(p);

        // 不显式注 authVerifier → build() 自动用 BucketPolicyAwareVerifier 包装
        server = new S3ServerBuilder()
                .bucketStore(bucketStore)
                .objectStore(objectStore)
                .multipartStore(multipartStore)
                .accessKeyResolver(resolver)
                .register(new ListBucketsHandler(bucketStore))
                .register(new CreateBucketHandler(bucketStore))
                .register(new DeleteBucketHandler(bucketStore, objectStore))
                .register(new HeadBucketHandler(bucketStore))
                .register(new ListObjectsV2Handler(bucketStore, objectStore))
                .register(new PutObjectHandler(bucketStore, objectStore))
                .register(new GetObjectHandler(bucketStore, objectStore))
                .register(new HeadObjectHandler(bucketStore, objectStore))
                .register(new DeleteObjectHandler(bucketStore, objectStore))
                .registerBucketAclHandler()
                .listen(port)
                .build();

        edap = new Edap();
        edap.addServer(server);
        edap.run();
        Thread.sleep(50);
    }

    @AfterEach
    void tearDown() {
        if (edap != null) edap.stop();
    }

    @Test
    void privateBucketRejectsAnonymousGet() throws Exception {
        String bucket = "private-bkt";
        signed("PUT", "/" + bucket, null, null, null);
        // 先签名 PUT 一个对象
        signed("PUT", "/" + bucket + "/img.png", null,
                "x".getBytes(StandardCharsets.UTF_8), null);

        // 匿名 GET → 403
        HttpResp r = anonymous("GET", "/" + bucket + "/img.png");
        assertEquals(403, r.status);
    }

    @Test
    void publicReadAllowsAnonymousGetAndHead() throws Exception {
        String bucket = "public-bkt";
        String key = "img.png";
        String body = "PNG-BYTES";

        signed("PUT", "/" + bucket, null, null, null);
        signed("PUT", "/" + bucket + "/" + key, null,
                body.getBytes(StandardCharsets.UTF_8), null);
        // 设 ACL = public-read(签名)
        HttpResp aclResp = signed("PUT", "/" + bucket + "?acl", null, null,
                Map.of("x-amz-acl", "public-read"));
        assertEquals(200, aclResp.status, "set public-read failed: " + aclResp.body);

        // 匿名 GET → 200 + body
        HttpResp r1 = anonymous("GET", "/" + bucket + "/" + key);
        assertEquals(200, r1.status, "anon GET should be 200: " + r1.body);
        assertEquals(body, r1.body);

        // 匿名 HEAD → 200
        HttpResp r2 = anonymous("HEAD", "/" + bucket + "/" + key);
        assertEquals(200, r2.status, "anon HEAD should be 200");

        // 匿名 PUT → 403(public-read 不允许匿名写)
        HttpResp r3 = anonymous("PUT", "/" + bucket + "/other.png",
                "no".getBytes(StandardCharsets.UTF_8));
        assertEquals(403, r3.status, "anon PUT should be 403");
    }

    @Test
    void publicReadWriteAllowsAnonymousPut() throws Exception {
        String bucket = "public-rw";
        signed("PUT", "/" + bucket, null, null, null);
        HttpResp aclResp = signed("PUT", "/" + bucket + "?acl", null, null,
                Map.of("x-amz-acl", "public-read-write"));
        assertEquals(200, aclResp.status);

        // 匿名 PUT → 200
        HttpResp r1 = anonymous("PUT", "/" + bucket + "/upload.png",
                "DATA".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, r1.status, "anon PUT should be 200");

        // 匿名 GET → 200
        HttpResp r2 = anonymous("GET", "/" + bucket + "/upload.png");
        assertEquals(200, r2.status);
        assertEquals("DATA", r2.body);
    }

    @Test
    void putBucketAclItselfRequiresSignature() throws Exception {
        String bucket = "acl-bkt";
        signed("PUT", "/" + bucket, null, null, null);

        // 匿名 PUT ?acl → 403(改 ACL 必须授权)
        HttpResp r = anonymous("PUT", "/" + bucket + "?acl",
                null, Map.of("x-amz-acl", "public-read"));
        assertEquals(403, r.status, "anon PUT?acl must be 403");
        // 桶 ACL 仍然是 PRIVATE → 匿名 GET 也得 403
        HttpResp r2 = anonymous("GET", "/" + bucket + "/x");
        assertEquals(403, r2.status);
    }

    @Test
    void anonymousReadOnNonPublicBucket403() throws Exception {
        String bucket = "default-bkt";
        signed("PUT", "/" + bucket, null, null, null);
        signed("PUT", "/" + bucket + "/k", null, "v".getBytes(StandardCharsets.UTF_8), null);

        // 没设 ACL → 默认 PRIVATE
        HttpResp r = anonymous("GET", "/" + bucket + "/k");
        assertEquals(403, r.status);
    }

    @Test
    void listBucketsNeverAllowsAnonymous() throws Exception {
        // 即使 PUBLIC_READ_WRITE 也不行(桶级管理类 op)
        String bucket = "lba-bkt";
        signed("PUT", "/" + bucket, null, null, null);
        signed("PUT", "/" + bucket + "?acl", null, null,
                Map.of("x-amz-acl", "public-read-write"));

        HttpResp r = anonymous("GET", "/");
        assertEquals(403, r.status, "LIST_BUCKETS should always require auth");
    }

    @Test
    void invalidAclHeaderReturns400() throws Exception {
        String bucket = "bad-acl-bkt";
        signed("PUT", "/" + bucket, null, null, null);
        HttpResp r = signed("PUT", "/" + bucket + "?acl", null, null,
                Map.of("x-amz-acl", "authenticated-read"));
        assertEquals(400, r.status, "unsupported ACL must be 400: " + r.body);
        assertTrue(r.body.contains("InvalidArgument") || r.body.contains("INVALID_ARGUMENT"));
    }

    // ===================== Helpers =====================

    private HttpResp signed(String method,
                            String path,
                            Map<String, String> queryParams,
                            byte[] body,
                            Map<String, String> extraHeaders) throws IOException {
        Instant now = Instant.now();
        String amzDate = AMZDATE_FMT.format(now);
        String dateStamp = amzDate.substring(0, 8);
        String payloadHash = sha256Hex(body == null ? new byte[0] : body);
        String host = "localhost:" + port;

        Map<String, String> headersToSign = new HashMap<>();
        headersToSign.put("host", host);
        headersToSign.put("x-amz-date", amzDate);
        headersToSign.put("x-amz-content-sha256", payloadHash);

        String canonicalUri = path.isEmpty() ? "/" : path;
        String canonicalQuery = canonicalQueryString(queryParams);
        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedHeadersList = new StringBuilder();
        for (Map.Entry<String, String> e : headersToSign.entrySet()) {
            canonicalHeaders.append(e.getKey()).append(':')
                    .append(e.getValue().trim().replaceAll("\\s+", " ")).append('\n');
            if (signedHeadersList.length() > 0) signedHeadersList.append(';');
            signedHeadersList.append(e.getKey());
        }
        String canonicalRequest = method + "\n"
                + canonicalUri + "\n"
                + canonicalQuery + "\n"
                + canonicalHeaders + "\n"
                + signedHeadersList + "\n"
                + payloadHash;

        String scope = dateStamp + "/" + REGION + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n"
                + amzDate + "\n"
                + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        byte[] kDate = hmac(("AWS4" + SECRET).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmac(kDate, REGION);
        byte[] kService = hmac(kRegion, "s3");
        byte[] kSigning = hmac(kService, "aws4_request");
        String signature = hex(hmac(kSigning, stringToSign));

        String authorization = "AWS4-HMAC-SHA256 Credential=" + AKID + "/" + scope
                + ", SignedHeaders=" + signedHeadersList
                + ", Signature=" + signature;

        String qs = canonicalQueryString(queryParams);
        String fullPath = qs.isEmpty() ? path : path + "?" + qs;
        URL url = URI.create("http://" + host + fullPath).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Authorization", authorization);
        conn.setRequestProperty("x-amz-date", amzDate);
        conn.setRequestProperty("x-amz-content-sha256", payloadHash);
        conn.setRequestProperty("Host", host);

        boolean writeBody = !"GET".equals(method) && !"HEAD".equals(method);
        byte[] payload = body == null ? new byte[0] : body;
        if (writeBody) {
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(payload.length);
        }
        conn.setRequestProperty("Content-Length", String.valueOf(payload.length));
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        if (writeBody) {
            try (java.io.OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
        }
        return readResponse(conn);
    }

    private HttpResp anonymous(String method, String path) throws IOException {
        return anonymous(method, path, null, null);
    }

    private HttpResp anonymous(String method, String path, byte[] body) throws IOException {
        return anonymous(method, path, body, null);
    }

    private HttpResp anonymous(String method,
                               String path,
                               byte[] body,
                               Map<String, String> extraHeaders) throws IOException {
        URL url = URI.create("http://localhost:" + port + path).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        boolean writeBody = !"GET".equals(method) && !"HEAD".equals(method);
        byte[] payload = body == null ? new byte[0] : body;
        if (writeBody) {
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(payload.length);
        }
        conn.setRequestProperty("Content-Length", String.valueOf(payload.length));
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        if (writeBody) {
            try (java.io.OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
        }
        return readResponse(conn);
    }

    private HttpResp readResponse(HttpURLConnection conn) throws IOException {
        int code;
        InputStream is;
        try {
            code = conn.getResponseCode();
            is = conn.getInputStream();
        } catch (IOException e) {
            code = conn.getResponseCode();
            is = conn.getErrorStream();
        }
        byte[] respBody = (is == null) ? new byte[0] : is.readAllBytes();
        Map<String, String> respHeaders = new HashMap<>();
        conn.getHeaderFields().forEach((k, v) -> {
            if (k != null && !v.isEmpty()) respHeaders.put(k, v.get(0));
        });
        return new HttpResp(code, respHeaders,
                new String(respBody, StandardCharsets.UTF_8));
    }

    static class HttpResp {
        final int status;
        final Map<String, String> headers;
        final String body;
        HttpResp(int status, Map<String, String> headers, String body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }
    }

    private static final DateTimeFormatter AMZDATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static String canonicalQueryString(Map<String, String> params) {
        if (params == null || params.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    if (sb.length() > 0) sb.append('&');
                    sb.append(uriEncode(e.getKey())).append('=').append(uriEncode(e.getValue()));
                });
        return sb.toString();
    }

    private static String uriEncode(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
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

    private static byte[] hmac(byte[] key, String data) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IOException("HMAC failure", e);
        }
    }

    private static String sha256Hex(byte[] data) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(data));
        } catch (Exception e) {
            throw new IOException("SHA-256 failure", e);
        }
    }

    private static String hex(byte[] data) {
        char[] r = new char[data.length * 2];
        char[] h = "0123456789abcdef".toCharArray();
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xff;
            r[i * 2] = h[v >>> 4];
            r[i * 2 + 1] = h[v & 0x0f];
        }
        return new String(r);
    }
}