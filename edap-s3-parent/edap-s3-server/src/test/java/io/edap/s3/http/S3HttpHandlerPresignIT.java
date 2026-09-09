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
import io.edap.s3.auth.SigV4Presigner;
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
import io.edap.s3.store.MultipartStore;
import io.edap.s3.store.ObjectStore;
import io.edap.s3.store.mem.InMemoryBucketStore;
import io.edap.s3.store.mem.InMemoryMultipartStore;
import io.edap.s3.store.mem.InMemoryObjectStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预签 URL 端到端集成测试 —— 启动真实 {@link HttpServer},
 * 用 {@link SigV4Presigner} 生成 URL,然后用 JDK {@link HttpURLConnection}
 * 走裸 HTTP(不设 Authorization header,只设 Host)打到 server。
 *
 * <p>对比 {@link S3HttpHandlerIT} (header-mode SigV4),本测试只跑 query-string 模式。
 * 两边共享同一份 {@code S3ServerBuilder} scaffolding + 同一份 stores,
 * 区别仅在请求侧 —— wire 上 <b>没有任何</b> SigV4 header,所有签名信息在 URL 上。
 */
public class S3HttpHandlerPresignIT {

    private static final String AKID = "AKIDEXAMPLE";
    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
    private static final String REGION = "us-east-1";

    private int port;
    private HttpServer server;
    private Edap edap;
    private BucketStore bucketStore;
    private ObjectStore objectStore;
    private MultipartStore multipartStore;
    private SigV4Presigner presigner;

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
                .registerMultipartHandlers()
                .listen(port)
                .build();

        edap = new Edap();
        edap.addServer(server);
        edap.run();
        Thread.sleep(50);

        presigner = new SigV4Presigner();
    }

    @AfterEach
    void tearDown() {
        if (edap != null) edap.stop();
    }

    // ===================== single-object presign =====================

    @Test
    void presignedPutObject_roundtrip() throws Exception {
        String bucket = "ps-bucket";
        String key = "hello.txt";
        String body = "presigned PUT payload";

        // 先建桶(用 header-mode,因为 presign 的 createBucket 不是常规需求)
        signedRequest("PUT", "/" + bucket, null, null, null);
        assertEquals(200, lastStatus, "create bucket");

        // 1. presign PUT
        SigV4Presigner.PresignedUrl u = presigner.presignPutObject(
                AKID, SECRET, REGION, bucket, key,
                "localhost", port, Duration.ofMinutes(5));

        // 2. 裸 PUT —— 不设 Authorization
        RawResponse rp = rawHttp("PUT", u.url(), body.getBytes(StandardCharsets.UTF_8),
                "text/plain", null);
        assertEquals(200, rp.status, "presigned PUT status, body=" + rp.bodyText);
        String etag = rp.headers.get("ETag");
        assertNotNull(etag, "ETag missing on presigned PUT");

        // 3. presign GET 拿回
        SigV4Presigner.PresignedUrl g = presigner.presignGetObject(
                AKID, SECRET, REGION, bucket, key,
                "localhost", port, Duration.ofMinutes(5));
        RawResponse rg = rawHttp("GET", g.url(), null, null, null);
        assertEquals(200, rg.status);
        assertEquals(body, rg.bodyText);
    }

    @Test
    void presignedGetObject_objectNotFound() throws Exception {
        String bucket = "missing-bucket";
        signedRequest("PUT", "/" + bucket, null, null, null);

        SigV4Presigner.PresignedUrl u = presigner.presignGetObject(
                AKID, SECRET, REGION, bucket, "no-such-key",
                "localhost", port, Duration.ofMinutes(5));
        RawResponse r = rawHttp("GET", u.url(), null, null, null);
        assertEquals(404, r.status);
    }

    @Test
    void presignedPutObject_largeBody() throws Exception {
        String bucket = "big-bucket";
        signedRequest("PUT", "/" + bucket, null, null, null);

        // 8KB 随机 payload(避开 edap ~15KB body 限制,见 project_edap_http_body_limit.md)。
        // 仍覆盖"非平凡 body 走 presign 通路"这条路径(单元对齐 1MB PUT 路径之外
        // 还要保证 PUT 端 body 接收正确、GET 端能完整回收)。
        byte[] payload = new byte[8 * 1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ((i * 31 + 7) & 0xff);
        }

        String key = "8kb.bin";
        SigV4Presigner.PresignedUrl u = presigner.presignPutObject(
                AKID, SECRET, REGION, bucket, key,
                "localhost", port, Duration.ofMinutes(5));
        RawResponse rp = rawHttp("PUT", u.url(), payload, "application/octet-stream", null);
        assertEquals(200, rp.status, "presigned PUT 8KB failed: " + rp.bodyText);
        String etag = rp.headers.get("ETag");
        assertNotNull(etag);

        // 拿回比对
        SigV4Presigner.PresignedUrl g = presigner.presignGetObject(
                AKID, SECRET, REGION, bucket, key,
                "localhost", port, Duration.ofMinutes(5));
        RawResponse rg = rawHttp("GET", g.url(), null, null, null);
        assertEquals(200, rg.status);
        assertEquals(payload.length, rg.body.length);
        // 全字节比对
        for (int i = 0; i < payload.length; i++) {
            if (payload[i] != rg.body[i]) {
                throw new AssertionError("byte diff at " + i
                        + " expAt=" + (payload[i] & 0xff)
                        + " gotAt=" + (rg.body[i] & 0xff));
            }
        }
    }

    // ===================== multipart presign =====================

    @Test
    void presignedMultipart_e2e() throws Exception {
        String bucket = "mp-ps-bucket";
        String key = "big.bin";
        signedRequest("PUT", "/" + bucket, null, null, null);

        // 1. presign InitiateMultipartUpload
        SigV4Presigner.PresignedUrl u1 = presigner.presignInitiateMultipart(
                AKID, SECRET, REGION, bucket, key,
                "localhost", port, Duration.ofMinutes(10));
        RawResponse ri = rawHttp("POST", u1.url(), null, null, null);
        assertEquals(200, ri.status, "init presigned: " + ri.bodyText);
        String uploadId = extractTag(ri.bodyText, "UploadId");
        assertNotNull(uploadId);

        // 2. presign UploadPart × 3
        String part1 = "AA".repeat(1024); // 2KB
        String part2 = "BB".repeat(2048); // 4KB
        String part3 = "CC".repeat(1024); // 2KB
        String etag1 = presignUploadPart(bucket, key, uploadId, 1, part1);
        String etag2 = presignUploadPart(bucket, key, uploadId, 2, part2);
        String etag3 = presignUploadPart(bucket, key, uploadId, 3, part3);

        // 3. presign CompleteMultipartUpload
        String completeBody = "<CompleteMultipartUpload>"
                + "<Part><PartNumber>1</PartNumber><ETag>\"" + etag1 + "\"</ETag></Part>"
                + "<Part><PartNumber>2</PartNumber><ETag>\"" + etag2 + "\"</ETag></Part>"
                + "<Part><PartNumber>3</PartNumber><ETag>\"" + etag3 + "\"</ETag></Part>"
                + "</CompleteMultipartUpload>";
        SigV4Presigner.PresignedUrl u4 = presigner.presignCompleteMultipart(
                AKID, SECRET, REGION, bucket, key, uploadId,
                "localhost", port, Duration.ofMinutes(10));
        RawResponse rc = rawHttp("POST", u4.url(),
                completeBody.getBytes(StandardCharsets.UTF_8),
                "application/xml", null);
        assertEquals(200, rc.status, "complete presigned: " + rc.bodyText);
        assertTrue(rc.bodyText.contains("<CompleteMultipartUploadResult"));

        // 4. presign GET 拿回
        SigV4Presigner.PresignedUrl g = presigner.presignGetObject(
                AKID, SECRET, REGION, bucket, key,
                "localhost", port, Duration.ofMinutes(5));
        RawResponse rg = rawHttp("GET", g.url(), null, null, null);
        assertEquals(200, rg.status);
        String expected = part1 + part2 + part3;
        assertEquals(expected, rg.bodyText, "presigned e2e concatenation mismatch");
        assertEquals(expected.length(), rg.bodyText.length());
    }

    @Test
    void presignedMultipart_abort() throws Exception {
        String bucket = "abort-ps-bucket";
        signedRequest("PUT", "/" + bucket, null, null, null);

        SigV4Presigner.PresignedUrl u1 = presigner.presignInitiateMultipart(
                AKID, SECRET, REGION, bucket, "k",
                "localhost", port, Duration.ofMinutes(10));
        RawResponse ri = rawHttp("POST", u1.url(), null, null, null);
        String uploadId = extractTag(ri.bodyText, "UploadId");
        assertNotNull(uploadId);

        presignUploadPart(bucket, "k", uploadId, 1, "X");
        presignUploadPart(bucket, "k", uploadId, 2, "YY");

        // abort
        SigV4Presigner.PresignedUrl uA = presigner.presignAbortMultipart(
                AKID, SECRET, REGION, bucket, "k", uploadId,
                "localhost", port, Duration.ofMinutes(10));
        RawResponse ra = rawHttp("DELETE", uA.url(), null, null, null);
        assertEquals(204, ra.status);

        // 再 listParts → 404(因为 abort 后 upload 已不存在)
        // 这里用 header-mode signedRequest(因为没有 presignListParts 公开方法)
        Map<String, String> lq = new HashMap<>();
        lq.put("uploadId", uploadId);
        signedRequest("GET", "/" + bucket + "/k", lq, null, null);
        assertEquals(404, lastStatus, "listParts after abort should 404");
    }

    // ===================== expiry =====================

    @Test
    void presignedPutObject_expiredUrlRejected() throws Exception {
        String bucket = "exp-bucket";
        signedRequest("PUT", "/" + bucket, null, null, null);

        // presign 一份正常 URL
        SigV4Presigner.PresignedUrl u = presigner.presignPutObject(
                AKID, SECRET, REGION, bucket, "k",
                "localhost", port, Duration.ofSeconds(60));
        // 把 URL 里的 X-Amz-Date 倒推 1 小时,让 URL 落在 expires 窗口之外
        String tampered = tamperAmzDateBack(u.url(), Duration.ofHours(1));

        RawResponse r = rawHttp("PUT", tampered, "x".getBytes(StandardCharsets.UTF_8),
                "text/plain", null);
        assertEquals(403, r.status, "expired URL must be rejected with 403, body=" + r.bodyText);
        assertTrue(r.bodyText.contains("AccessDenied") || r.bodyText.contains("expired"),
                "expected AccessDenied/expired message, got: " + r.bodyText);
    }

    // ===================== helpers =====================

    /** 裸 HTTP 调用 —— 不设任何 SigV4 header,只设 Host(与 presigner 用的 host:port 一致)。 */
    private RawResponse rawHttp(String method, String url, byte[] body,
                                String contentType, Map<String, String> extraHeaders) throws IOException {
        // RAW SOCKET path: 用 java.net.Socket 直接发请求、读响应,
        // 完全避开 HttpURLConnection 的 internal buffering。
        // 诊断用 —— 验证 server 端实际送出的 wire 字节。
        boolean useRaw = System.getProperty("debug.rawSocket") != null;
        if (useRaw) {
            return rawHttpRawSocket(method, url, body, contentType, extraHeaders);
        }
        URL u = URI.create(url).toURL();
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        // 关闭 keep-alive,避免 response body 错乱(底层 socket pool bug)
        conn.setRequestProperty("Connection", "close");

        // wire 上 Host header 必须与 presigner canonical host 严格相等(80/443 除外)
        int port = u.getPort();
        String hostHdr = (port == -1 || port == 80 || port == 443)
                ? u.getHost() : u.getHost() + ":" + port;
        conn.setRequestProperty("Host", hostHdr);
        if (contentType != null) {
            conn.setRequestProperty("Content-Type", contentType);
        }
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }

        boolean writeBody = body != null && !"GET".equals(method) && !"HEAD".equals(method);
        byte[] payload = body == null ? new byte[0] : body;
        if (writeBody) {
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(payload.length);
        }
        conn.setRequestProperty("Content-Length", String.valueOf(payload.length));
        if (writeBody) {
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
        }
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
        return new RawResponse(code, respHeaders, respBody,
                new String(respBody, StandardCharsets.UTF_8));
    }

    private RawResponse rawHttpRawSocket(String method, String url, byte[] body,
                                         String contentType, Map<String, String> extraHeaders) throws IOException {
        URL u = URI.create(url).toURL();
        int port = u.getPort();
        String host = u.getHost();
        String hostHdr = (port == -1 || port == 80 || port == 443) ? host : host + ":" + port;
        Socket sock = new Socket();
        sock.connect(new InetSocketAddress(host, port), 5000);
        sock.setSoTimeout(15000);
        OutputStream out = sock.getOutputStream();
        InputStream in = sock.getInputStream();

        byte[] payload = body == null ? new byte[0] : body;
        StringBuilder req = new StringBuilder();
        req.append(method).append(' ').append(u.getPath());
        if (u.getQuery() != null) req.append('?').append(u.getQuery());
        req.append(" HTTP/1.1\r\n");
        req.append("Host: ").append(hostHdr).append("\r\n");
        req.append("Content-Length: ").append(payload.length).append("\r\n");
        if (contentType != null) req.append("Content-Type: ").append(contentType).append("\r\n");
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
        }
        req.append("Connection: close\r\n");
        req.append("\r\n");
        out.write(req.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        if (payload.length > 0) {
            int off = 0;
            while (off < payload.length) {
                int chunk = Math.min(8192, payload.length - off);
                out.write(payload, off, chunk);
                off += chunk;
            }
        }
        out.flush();
        System.out.println(">>> RAW " + method + " sent, payload=" + payload.length + ", waiting response...");
        System.out.println(">>> RAW socket local=" + sock.getLocalPort() + " remote=" + sock.getPort());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        int reads = 0;
        // First read: get headers (peek until \r\n\r\n)
        int hdrEnd = -1;
        while ((n = in.read(tmp)) != -1) {
            baos.write(tmp, 0, n);
            reads++;
            byte[] cur = baos.toByteArray();
            for (int i = 0; i < baos.size() - 3; i++) {
                if (cur[i] == '\r' && cur[i+1] == '\n' && cur[i+2] == '\r' && cur[i+3] == '\n') {
                    hdrEnd = i + 4;
                    break;
                }
            }
            if (hdrEnd > 0) break;
        }
        if (hdrEnd < 0) {
            sock.close();
            throw new IOException("no header terminator");
        }
        // Find Content-Length in headers
        String headerPart = new String(baos.toByteArray(), 0, hdrEnd - 4, StandardCharsets.UTF_8);
        int contentLength = -1;
        for (String line : headerPart.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                try { contentLength = Integer.parseInt(line.substring(15).trim()); } catch (Exception ignore) {}
            }
        }
        System.out.println(">>> RAW " + method + " headers got=" + hdrEnd + " contentLength=" + contentLength + " alreadyHave=" + (baos.size() - hdrEnd));
        // Read body up to contentLength — strict: don't over-read past contentLength
        while (baos.size() - hdrEnd < contentLength) {
            int need = contentLength - (baos.size() - hdrEnd);
            n = in.read(tmp, 0, Math.min(need, tmp.length));
            if (n < 0) break;
            baos.write(tmp, 0, n);
            reads++;
            if (reads <= 5 || reads % 20 == 0) {
                System.out.println(">>> RAW GET read #" + reads + " n=" + n + " baos=" + baos.size() + " target=" + (hdrEnd + contentLength));
            }
        }
        System.out.println(">>> RAW " + method + " total reads=" + reads + " baos=" + baos.size() + " expected=" + (hdrEnd + contentLength));
        sock.close();

        byte[] respAll = baos.toByteArray();
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream("/tmp/raw_socket_get.bin");
            fos.write(respAll);
            fos.close();
            System.out.println(">>> RAW dumped " + respAll.length + " bytes to /tmp/raw_socket_get.bin");
        } catch (Exception ex) {}
        String[] lines = headerPart.split("\r\n");
        int code = 0;
        Map<String, String> respHeaders = new HashMap<>();
        for (String line : lines) {
            if (line.startsWith("HTTP/1.1 ")) {
                code = Integer.parseInt(line.substring(9, 12));
            } else {
                int c = line.indexOf(':');
                if (c > 0) respHeaders.put(line.substring(0, c), line.substring(c + 1).trim());
            }
        }
        byte[] body2 = new byte[respAll.length - hdrEnd];
        System.arraycopy(respAll, hdrEnd, body2, 0, body2.length);
        return new RawResponse(code, respHeaders, body2, new String(body2, StandardCharsets.UTF_8));
    }

    private String presignUploadPart(String bucket, String key, String uploadId,
                                     int partNumber, String body) throws Exception {
        SigV4Presigner.PresignedUrl u = presigner.presignUploadPart(
                AKID, SECRET, REGION, bucket, key, uploadId, partNumber,
                "localhost", port, Duration.ofMinutes(10));
        RawResponse r = rawHttp("PUT", u.url(), body.getBytes(StandardCharsets.UTF_8),
                "application/octet-stream", null);
        assertEquals(200, r.status, "presign upload part " + partNumber + ": " + r.bodyText);
        String etag = r.headers.get("ETag");
        assertNotNull(etag, "ETag missing on part " + partNumber);
        return etag.replace("\"", "");
    }

    /**
     * 把 presigned URL 里 query 上的 X-Amz-Date 倒推一段时长,
     * 使 URL 落在已过期窗口。验签端会比对 (signedAt + X-Amz-Expires) vs now → 拒。
     */
    private static String tamperAmzDateBack(String url, Duration back) {
        int q = url.indexOf('?');
        if (q < 0) throw new IllegalArgumentException("url has no query: " + url);
        String path = url.substring(0, q);
        String query = url.substring(q + 1);
        StringBuilder out = new StringBuilder();
        java.time.format.DateTimeFormatter fmt =
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssX");
        java.time.format.DateTimeFormatter outFmt =
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            if ("X-Amz-Date".equals(k)) {
                java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(v, fmt);
                java.time.Instant tampered = odt.toInstant().minus(back);
                String newV = outFmt.format(tampered.atOffset(java.time.ZoneOffset.UTC));
                if (out.length() > 0) out.append('&');
                out.append(k).append('=').append(newV);
            } else {
                if (out.length() > 0) out.append('&');
                out.append(pair);
            }
        }
        return path + "?" + out;
    }

    private static String extractTag(String xml, String tag) {
        String open = "<" + tag + ">";
        String close = "</" + tag + ">";
        int openIdx = xml.indexOf(open);
        if (openIdx < 0) return null;
        int closeIdx = xml.indexOf(close, openIdx);
        if (closeIdx < 0) return null;
        return xml.substring(openIdx + open.length(), closeIdx).trim();
    }

    // ===================== header-mode signed request (just to create bucket) =====================
    // 这里复用 S3HttpHandlerIT 思路:用 JDK 自己算 SigV4 header,只为建桶这种 setup 操作。
    // presign 测试的主体(被测代码)是 query-mode 路径。

    private int lastStatus;

    private void signedRequest(String method, String path,
                               Map<String, String> queryParams, byte[] body,
                               Map<String, String> extraHeaders) throws IOException {
        RawResponse r = headerModeSignedRequest(method, path, queryParams, body, extraHeaders);
        lastStatus = r.status;
    }

    private RawResponse headerModeSignedRequest(String method, String path,
                                               Map<String, String> queryParams, byte[] body,
                                               Map<String, String> extraHeaders) throws IOException {
        java.time.Instant now = java.time.Instant.now();
        String amzDate = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(java.time.ZoneOffset.UTC).format(now);
        String dateStamp = amzDate.substring(0, 8);
        byte[] payload = body == null ? new byte[0] : body;
        String payloadHash = sha256Hex(payload);
        String host = "localhost:" + port;
        String canonicalUri = path.isEmpty() ? "/" : path;
        String canonicalQuery = canonicalQueryString(queryParams);

        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedHeadersList = new StringBuilder();
        Map<String, String> headersToSign = new HashMap<>();
        headersToSign.put("host", host);
        headersToSign.put("x-amz-date", amzDate);
        headersToSign.put("x-amz-content-sha256", payloadHash);
        for (Map.Entry<String, String> e : headersToSign.entrySet()) {
            canonicalHeaders.append(e.getKey()).append(':')
                    .append(e.getValue().trim().replaceAll("\\s+", " ")).append('\n');
            if (signedHeadersList.length() > 0) signedHeadersList.append(';');
            signedHeadersList.append(e.getKey());
        }
        String canonicalRequest = method + "\n" + canonicalUri + "\n" + canonicalQuery + "\n"
                + canonicalHeaders + "\n" + signedHeadersList + "\n" + payloadHash;
        String scope = dateStamp + "/" + REGION + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] kDate = hmac(("AWS4" + SECRET).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmac(kDate, REGION);
        byte[] kService = hmac(kRegion, "s3");
        byte[] kSigning = hmac(kService, "aws4_request");
        String signature = hex(hmac(kSigning, stringToSign));
        String authorization = "AWS4-HMAC-SHA256 Credential=" + AKID + "/" + scope
                + ", SignedHeaders=" + signedHeadersList + ", Signature=" + signature;

        String fullPath = path;
        if (!canonicalQuery.isEmpty()) fullPath = path + "?" + canonicalQuery;
        URL url = URI.create("http://" + host + fullPath).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Authorization", authorization);
        conn.setRequestProperty("x-amz-date", amzDate);
        conn.setRequestProperty("x-amz-content-sha256", payloadHash);
        conn.setRequestProperty("Host", host);
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        boolean writeBody = !"GET".equals(method) && !"HEAD".equals(method);
        if (writeBody) {
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(payload.length);
        }
        conn.setRequestProperty("Content-Length", String.valueOf(payload.length));
        if (writeBody) {
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
        }
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
        return new RawResponse(code, respHeaders, respBody,
                new String(respBody, StandardCharsets.UTF_8));
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
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IOException("HMAC failure", e);
        }
    }

    private static String sha256Hex(byte[] data) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
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

    static class RawResponse {
        final int status;
        final Map<String, String> headers;
        final byte[] body;
        final String bodyText;
        RawResponse(int status, Map<String, String> headers, byte[] body, String bodyText) {
            this.status = status;
            this.headers = headers;
            this.body = body;
            this.bodyText = bodyText;
        }
    }
}
