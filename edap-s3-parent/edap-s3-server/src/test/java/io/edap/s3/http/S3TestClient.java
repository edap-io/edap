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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 集成测试用的最小 S3 客户端 —— 支持 SigV4 签名请求和匿名请求。
 *
 * <p>只实现测试需要的部分:path-style URL、SigV4(v4,unsigned-payload 走
 * {@code x-amz-content-sha256})、query 参数按 key 排序后进 canonical query。
 */
final class S3TestClient {

    private static final DateTimeFormatter AMZDATE_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final int port;
    private final String akid;
    private final String secret;
    private final String region;

    S3TestClient(int port, String akid, String secret, String region) {
        this.port = port;
        this.akid = akid;
        this.secret = secret;
        this.region = region;
    }

    // ===================== 请求 =====================

    HttpResp signed(String method,
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

        String scope = dateStamp + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n"
                + amzDate + "\n"
                + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

        byte[] kDate = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, "s3");
        byte[] kSigning = hmac(kService, "aws4_request");
        String signature = hex(hmac(kSigning, stringToSign));

        String authorization = "AWS4-HMAC-SHA256 Credential=" + akid + "/" + scope
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
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        return send(conn, method, body);
    }

    HttpResp anonymous(String method, String path) throws IOException {
        return anonymous(method, path, null, null);
    }

    HttpResp anonymous(String method, String path, byte[] body) throws IOException {
        return anonymous(method, path, body, null);
    }

    HttpResp anonymous(String method,
                       String path,
                       byte[] body,
                       Map<String, String> extraHeaders) throws IOException {
        URL url = URI.create("http://localhost:" + port + path).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        if (extraHeaders != null) {
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        return send(conn, method, body);
    }

    private HttpResp send(HttpURLConnection conn, String method, byte[] body) throws IOException {
        boolean writeBody = !"GET".equals(method) && !"HEAD".equals(method);
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
        return new HttpResp(code, respHeaders, new String(respBody, StandardCharsets.UTF_8));
    }

    // ===================== 工具 =====================

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

    // ===================== 响应 =====================

    static final class HttpResp {
        final int status;
        final Map<String, String> headers;
        final String body;

        HttpResp(int status, Map<String, String> headers, String body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }
    }
}
