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
import io.edap.s3.op.handler.PutObjectHandler;
import io.edap.s3.store.BucketStore;
import io.edap.s3.store.ObjectStore;
import io.edap.s3.store.mem.InMemoryBucketStore;
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
 * 端到端集成测试 —— 启动一个真实 {@link HttpServer} 监听随机端口,
 * 用 JDK 自带 {@link HttpURLConnection} 发 SigV4 签名请求,验证完整
 * 请求 → handler → 响应 链路。
 *
 * <p>不需要额外的 HTTP 客户端依赖(JDK 11+ {@code java.net.http.HttpClient}
 * 也可,但 {@link HttpURLConnection} 更简单)。
 *
 * <p>选随机端口:开 server 时传 {@code listen(0)} 不行(edap 可能要求固定端口),
 * 用一个挑选高位的策略(19000 + 当前毫秒数后 4 位)避免冲突。
 */
public class S3HttpHandlerIT {

    private static final String AKID = "AKIDEXAMPLE";
    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
    private static final String REGION = "us-east-1";

    private int port;
    private HttpServer server;
    private Edap edap;
    private BucketStore bucketStore;
    private ObjectStore objectStore;

    @BeforeEach
    void setUp() throws Exception {
        // 用 ServerSocket(0) 拿 OS 分配的自由端口(避免并发跑测撞端口)
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        bucketStore = new InMemoryBucketStore();
        objectStore = new InMemoryObjectStore(bucketStore);

        Properties p = new Properties();
        p.setProperty("s3.accessKey." + AKID + ".secret", SECRET);
        p.setProperty("s3.accessKey." + AKID + ".region", REGION);
        AccessKeyResolver resolver = ConfigAccessKeyResolver.fromProperties(p);

        server = new S3ServerBuilder()
                .bucketStore(bucketStore)
                .objectStore(objectStore)
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
                .listen(port)
                .build();

        edap = new Edap();
        edap.addServer(server);
        edap.run();
        // 等待 server ready
        Thread.sleep(50);
    }

    @AfterEach
    void tearDown() {
        if (edap != null) edap.stop();
    }

    @Test
    void roundtripPutGetDeleteHead() throws Exception {
        String bucket = "test-bucket";
        String key = "hello.txt";
        String body = "Hello S3!";

        // 1. CreateBucket
        Response r1 = signedRequest("PUT", "/" + bucket, null, null, null);
        assertEquals(200, r1.status, "create bucket status");
        // 2. PutObject
        Response r2 = signedRequest("PUT", "/" + bucket + "/" + key, null,
                body.getBytes(StandardCharsets.UTF_8), null);
        assertEquals(200, r2.status, "put object status");
        String etag = r2.headers.get("ETag");
        assertNotNull(etag);
        // 3. GetObject
        Response r3 = signedRequest("GET", "/" + bucket + "/" + key, null, null, null);
        assertEquals(200, r3.status);
        assertEquals(body, r3.bodyText);
        // 4. HeadObject
        Response r4 = signedRequest("HEAD", "/" + bucket + "/" + key, null, null, null);
        assertEquals(200, r4.status);
        // 5. DeleteObject
        Response r5 = signedRequest("DELETE", "/" + bucket + "/" + key, null, null, null);
        assertEquals(204, r5.status);
        // 6. GetObject after delete → 404
        Response r6 = signedRequest("GET", "/" + bucket + "/" + key, null, null, null);
        assertEquals(404, r6.status);
    }

    @Test
    void listBucketsReturnsCreated() throws Exception {
        String bucket1 = "alpha-bucket";
        String bucket2 = "beta-bucket";
        signedRequest("PUT", "/" + bucket1, null, null, null);
        signedRequest("PUT", "/" + bucket2, null, null, null);

        Response r = signedRequest("GET", "/", null, null, null);
        assertEquals(200, r.status);
        assertTrue(r.bodyText.contains(bucket1));
        assertTrue(r.bodyText.contains(bucket2));
    }

    @Test
    void deleteNonEmptyBucketReturns409() throws Exception {
        String bucket = "nonempty-bucket";
        signedRequest("PUT", "/" + bucket, null, null, null);
        signedRequest("PUT", "/" + bucket + "/k",
                null, "v".getBytes(StandardCharsets.UTF_8), null);

        Response r = signedRequest("DELETE", "/" + bucket, null, null, null);
        assertEquals(409, r.status);
        assertTrue(r.bodyText.contains("BucketNotEmpty"));
    }

    @Test
    void noAuthorizationReturns403() throws Exception {
        URL url = URI.create("http://localhost:" + port + "/").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        int code = conn.getResponseCode();
        assertEquals(403, code);
    }

    // ===================== SigV4 request signing =====================

    private static final DateTimeFormatter AMZDATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private Response signedRequest(String method,
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

        // canonical request
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

        // string to sign
        String scope = dateStamp + "/" + REGION + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n"
                + amzDate + "\n"
                + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        // signing key
        byte[] kDate = hmac(("AWS4" + SECRET).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmac(kDate, REGION);
        byte[] kService = hmac(kRegion, "s3");
        byte[] kSigning = hmac(kService, "aws4_request");
        String signature = hex(hmac(kSigning, stringToSign));

        String authorization = "AWS4-HMAC-SHA256 Credential=" + AKID + "/" + scope
                + ", SignedHeaders=" + signedHeadersList
                + ", Signature=" + signature;

        URL url = URI.create("http://" + host + path).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Authorization", authorization);
        conn.setRequestProperty("x-amz-date", amzDate);
        conn.setRequestProperty("x-amz-content-sha256", payloadHash);
        conn.setRequestProperty("Host", host);
        // 不论 body 是否为 null 都明确告诉服务器 Content-Length,
        // 并实际写入空字节 + 关闭输出流,触发 HttpURLConnection 把
        // 0 字节 body 真正写到 wire —— 否则服务器会一直等 body 数据
        //
        // GET / HEAD 不走 setDoOutput —— 否则 HttpURLConnection 会把
        // GET 当 POST 发(SigV4 验签会因为 method 字段不一致 reject)
        boolean writeBody = !"GET".equals(method) && !"HEAD".equals(method);
        byte[] payload = body == null ? new byte[0] : body;
        if (writeBody) {
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(payload.length);
        }
        conn.setRequestProperty("Content-Length", String.valueOf(payload.length));
        if (writeBody) {
            try (java.io.OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
        }
        int code;
        InputStream is;
        try {
            code = conn.getResponseCode();
            is = conn.getInputStream();
        } catch (IOException e) {
            // 4xx / 5xx → 用 errorStream
            code = conn.getResponseCode();
            is = conn.getErrorStream();
        }
        byte[] respBody = (is == null) ? new byte[0] : is.readAllBytes();
        Map<String, String> respHeaders = new HashMap<>();
        conn.getHeaderFields().forEach((k, v) -> {
            if (k != null && !v.isEmpty()) respHeaders.put(k, v.get(0));
        });
        return new Response(code, respHeaders,
                new String(respBody, StandardCharsets.UTF_8));
    }

    static class Response {
        final int status;
        final Map<String, String> headers;
        final String bodyText;
        Response(int status, Map<String, String> headers, String bodyText) {
            this.status = status;
            this.headers = headers;
            this.bodyText = bodyText;
        }
    }

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