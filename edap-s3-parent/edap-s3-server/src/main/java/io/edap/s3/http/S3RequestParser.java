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

import io.edap.http.HttpRequest;
import io.edap.http.HeaderValue;
import io.edap.http.HttpBody;
import io.edap.http.ValueHttpRequest;
import io.edap.http.model.QueryInfo;
import io.edap.s3.error.S3ErrorCode;
import io.edap.s3.error.S3Exception;
import io.edap.s3.model.PutStream;
import io.edap.s3.model.S3Request;
import io.edap.s3.op.S3Operation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把 {@link HttpRequest} 解析成 {@link S3Request}。
 *
 * <p>解析规则(按 AWS S3 path-style):
 * <pre>
 *   GET /                                → LIST_BUCKETS
 *   PUT /{bucket}                        → CREATE_BUCKET
 *   DELETE /{bucket}                     → DELETE_BUCKET
 *   HEAD /{bucket}                       → HEAD_BUCKET
 *   GET /{bucket}?list-type=2            → LIST_OBJECTS_V2
 *   GET /{bucket}?uploads                → LIST_MULTIPART_UPLOADS
 *   GET /{bucket}/{key}?uploadId=ID      → LIST_PARTS
 *   PUT /{bucket}/{key...}               → PUT_OBJECT
 *   GET /{bucket}/{key...}               → GET_OBJECT
 *   HEAD /{bucket}/{key...}              → HEAD_OBJECT
 *   DELETE /{bucket}/{key...}            → DELETE_OBJECT
 *   POST /{bucket}/{key...}?uploads      → INIT_MULTIPART
 *   PUT /{bucket}/{key...}?partNumber=N&uploadId=ID  → UPLOAD_PART
 *   POST /{bucket}/{key...}?uploadId=ID  → COMPLETE_MULTIPART
 *   DELETE /{bucket}/{key...}?uploadId=ID → ABORT_MULTIPART
 * </pre>
 *
 * <p>query 参数从 {@link ValueHttpRequest#getParameters()}(已由 HTTP 层
 * decode)或 {@link QueryInfo} 拿 —— 优先用 parameters(更准),fallback 用
 * raw query string。header key 统一 lowercase(SigV4 协议要求)。
 *
 * <p>multipart op 推断优先级:query 参数 + HTTP method 先判定,
 * 段数仅在没有 multipart query 时才决定 bucket / object 级别。
 *
 * <p>key 可含 "/"(S3 对象 key 允许嵌套路径),所以不能用简单 split:
 * 第一个 "/" 后剩下的全部当成 key。
 */
public final class S3RequestParser {

    private static final String USER_META_PREFIX = "x-amz-meta-";

    public S3Request parse(HttpRequest req) throws S3Exception {
        String method = req.getMethod();
        String path = req.getPath();
        if (path == null) path = "";

        Map<String, String> query = parseQueryParams(req);
        Map<String, String> headers = parseHeaders(req);
        S3Operation op = inferOperation(method, path, query);

        String bucket = null;
        String key = null;
        if (op != S3Operation.LIST_BUCKETS) {
            bucket = extractBucket(path);
            // bucket-only op(不需要 key)
            if (op != S3Operation.CREATE_BUCKET
                    && op != S3Operation.DELETE_BUCKET
                    && op != S3Operation.HEAD_BUCKET
                    && op != S3Operation.LIST_OBJECTS_V2
                    && op != S3Operation.LIST_MULTIPART_UPLOADS) {
                key = extractKey(path);
            }
        }

        PutStream putBody = null;
        if (op == S3Operation.PUT_OBJECT || op == S3Operation.UPLOAD_PART) {
            putBody = buildPutBody(req, headers);
        }

        byte[] xmlBody = null;
        if (op == S3Operation.COMPLETE_MULTIPART) {
            xmlBody = buildXmlBody(req);
        }

        return new S3Request(op, bucket, key, query, headers, putBody, xmlBody, path);
    }

    // ===================== operation 推断 =====================

    private S3Operation inferOperation(String method, String path, Map<String, String> query) {
        int segCount = countSegments(path);
        boolean hasUploads = query.containsKey("uploads");
        boolean hasUploadId = query.containsKey("uploadId");
        boolean hasPartNumber = query.containsKey("partNumber");

        switch (method) {
            case "POST":
                // multipart first
                if (hasUploads) {
                    if (segCount >= 1) return S3Operation.INIT_MULTIPART;
                    throw new S3Exception(S3ErrorCode.INVALID_REQUEST,
                            "POST ?uploads requires a key in path");
                }
                if (hasUploadId) return S3Operation.COMPLETE_MULTIPART;
                // POST 还没其他 op
                throw new S3Exception(S3ErrorCode.METHOD_NOT_ALLOWED,
                        "Unsupported HTTP method: " + method);
            case "GET":
                if (hasUploads && segCount == 1) return S3Operation.LIST_MULTIPART_UPLOADS;
                if (hasUploadId && segCount >= 1) return S3Operation.LIST_PARTS;
                if (segCount == 0) return S3Operation.LIST_BUCKETS;
                if (segCount == 1) return S3Operation.LIST_OBJECTS_V2;
                return S3Operation.GET_OBJECT;
            case "PUT":
                if (hasUploadId) {
                    if (!hasPartNumber) {
                        throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                                "UploadPart requires partNumber query parameter");
                    }
                    if (segCount >= 1) return S3Operation.UPLOAD_PART;
                }
                if (segCount == 1) return S3Operation.CREATE_BUCKET;
                return S3Operation.PUT_OBJECT;
            case "DELETE":
                if (hasUploadId && segCount >= 1) return S3Operation.ABORT_MULTIPART;
                if (segCount == 1) return S3Operation.DELETE_BUCKET;
                return S3Operation.DELETE_OBJECT;
            case "HEAD":
                if (segCount == 1) return S3Operation.HEAD_BUCKET;
                return S3Operation.HEAD_OBJECT;
            default:
                throw new S3Exception(S3ErrorCode.METHOD_NOT_ALLOWED,
                        "Unsupported HTTP method: " + method);
        }
    }

    private static int countSegments(String path) {
        if (path == null || path.isEmpty()) return 0;
        int count = 0;
        boolean inSeg = false;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                inSeg = false;
            } else if (!inSeg) {
                count++;
                inSeg = true;
            }
        }
        return count;
    }

    // ===================== path 拆分 =====================

    private static String extractBucket(String path) throws S3Exception {
        if (path == null || path.isEmpty() || path.equals("/")) return null;
        int slash = path.indexOf('/', 1);
        String raw = slash < 0 ? path.substring(1) : path.substring(1, slash);
        if (raw.isEmpty()) return null;
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    private static String extractKey(String path) throws S3Exception {
        if (path == null || path.isEmpty() || path.equals("/")) return null;
        int slash = path.indexOf('/', 1);
        if (slash < 0 || slash == path.length() - 1) return null;
        String raw = path.substring(slash + 1);
        if (raw.isEmpty()) return null;
        return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    }

    // ===================== query / headers =====================

    private static Map<String, String> parseQueryParams(HttpRequest req) {
        Map<String, String> map = new LinkedHashMap<>();
        if (req instanceof ValueHttpRequest) {
            ValueHttpRequest v = (ValueHttpRequest) req;
            Map<String, List<io.edap.http.ParameterValue>> params = v.getParameters();
            if (params != null && !params.isEmpty()) {
                for (Map.Entry<String, List<io.edap.http.ParameterValue>> e : params.entrySet()) {
                    if (!e.getValue().isEmpty()) {
                        map.put(e.getKey(), e.getValue().get(0).getValue());
                    }
                }
                return map;
            }
            QueryInfo qi = v.queryInfo;
            if (qi != null && qi.getQueryBytes() != null && qi.getQueryBytes().length > 0) {
                return parseRawQuery(qi.getQuery());
            }
        }
        // fallback: 逐个常见 query 名查
        String[] common = {"list-type", "prefix", "delimiter", "max-keys",
                "continuation-token", "encoding-type", "fetch-owner",
                "uploads", "uploadId", "partNumber"};
        for (String n : common) {
            String v = req.getParameter(n);
            if (v != null) map.put(n, v);
        }
        return map;
    }

    static Map<String, String> parseRawQuery(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return map;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            String v = eq < 0 ? "" : part.substring(eq + 1);
            map.put(URLDecoder.decode(k, StandardCharsets.UTF_8),
                    URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return map;
    }

    /**
     * 提取所有 header,key lowercase。优先通过反射读 ValueHttpRequest.headers,
     * 拿不到再按常见名字逐个查。
     */
    private static Map<String, String> parseHeaders(HttpRequest req) {
        Map<String, String> map = new HashMap<>();
        if (req instanceof ValueHttpRequest) {
            ValueHttpRequest v = (ValueHttpRequest) req;
            Map<String, HeaderValue> hdrs = readHeadersField(v);
            if (hdrs != null) {
                for (Map.Entry<String, HeaderValue> e : hdrs.entrySet()) {
                    if (e.getValue() != null) {
                        map.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getValue());
                    }
                }
                return map;
            }
        }
        // 兜底:常用 header 名列表(覆盖 SigV4 必需 + S3 常见)
        String[] names = {
                "authorization", "x-amz-date", "x-amz-content-sha256",
                "content-length", "content-md5", "content-type",
                "range", "if-match", "if-none-match", "host",
                "expect", "x-amz-meta-*"
        };
        for (String n : names) {
            if (n.endsWith("*")) continue;
            HeaderValue hv = req.getHeaderValue(canonical(n));
            if (hv == null) hv = req.getHeaderValue(n);
            if (hv != null) map.put(n, hv.getValue());
        }
        return map;
    }

    private static Map<String, HeaderValue> readHeadersField(ValueHttpRequest v) {
        try {
            Field f = ValueHttpRequest.class.getDeclaredField("headers");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, HeaderValue> hdrs = (Map<String, HeaderValue>) f.get(v);
            return hdrs;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String canonical(String lower) {
        // "x-amz-date" -> "X-Amz-Date"; "content-md5" -> "Content-MD5"
        StringBuilder sb = new StringBuilder(lower.length());
        boolean up = true;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c == '-') {
                up = true;
                sb.append(c);
            } else if (up) {
                sb.append(Character.toUpperCase(c));
                up = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ===================== PutBody =====================

    private static PutStream buildPutBody(HttpRequest req, Map<String, String> headers) throws S3Exception {
        HttpBody body = req.getBody();
        if (body == null || body.length() == 0) {
            return new PutStream(new ByteArrayInputStream(new byte[0]),
                    headers.getOrDefault("content-type", "application/octet-stream"),
                    extractUserMeta(headers), 0, headers.get("content-md5"));
        }
        long len = body.length();
        long contentLength = headers.containsKey("content-length")
                ? Long.parseLong(headers.get("content-length"))
                : len;
        // 直接拿 body 的流式视图，下游 DiskObjectStore 边读边落盘
        InputStream stream;
        try {
            stream = body.openStream();
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR, "failed to open body stream", null, e);
        }
        return new PutStream(stream,
                headers.getOrDefault("content-type", "application/octet-stream"),
                extractUserMeta(headers),
                contentLength,
                headers.get("content-md5"));
    }

    private static Map<String, String> extractUserMeta(Map<String, String> headers) {
        Map<String, String> meta = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().toLowerCase(Locale.ROOT).startsWith(USER_META_PREFIX)) {
                meta.put(e.getKey().substring(USER_META_PREFIX.length()), e.getValue());
            }
        }
        return meta;
    }

    /**
     * 提取 COMPLETE_MULTIPART 请求的 XML body —— 完整读到 byte[]。
     * body 通常很小(列出 part references,几十行 XML),直接缓冲即可。
     */
    private static byte[] buildXmlBody(HttpRequest req) {
        HttpBody body = req.getBody();
        if (body == null || body.length() == 0) {
            return new byte[0];
        }
        return body.toByteArray();
    }
}