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

import io.edap.http.HeaderValue;
import io.edap.http.HttpRequest;
import io.edap.http.HttpResponse;
import io.edap.http.HttpVersion;
import io.edap.http.MethodInfo;
import io.edap.s3.model.S3Request;
import io.edap.s3.op.S3Operation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class S3RequestParserTest {

    private final S3RequestParser parser = new S3RequestParser();

    /**
     * 构造一个 ValueHttpRequest 用来测 —— 用反射设内部 fields(parameters /
     * queryInfo / headers)。
     */
    private io.edap.http.ValueHttpRequest makeRequest(String method,
                                                      String path,
                                                      String query,
                                                      Map<String, String> headers) throws Exception {
        io.edap.http.ValueHttpRequest r = new io.edap.http.ValueHttpRequest();
        MethodInfo mi = new MethodInfo();
        mi.setMethod(method);
        r.methodInfo = mi;
        r.setPath(path);

        // parameters
        Map<String, List<io.edap.http.ParameterValue>> params = new LinkedHashMap<>();
        if (query != null && !query.isEmpty()) {
            for (String part : query.split("&")) {
                int eq = part.indexOf('=');
                String k = eq < 0 ? part : part.substring(0, eq);
                String v = eq < 0 ? "" : part.substring(eq + 1);
                io.edap.http.ParameterValue pv = new io.edap.http.ParameterValue(v);
                params.computeIfAbsent(k, x -> new java.util.ArrayList<>()).add(pv);
            }
        }
        r.setParameters(params);

        // queryInfo
        io.edap.http.model.QueryInfo qi = new io.edap.http.model.QueryInfo();
        qi.setQuery(query == null ? "" : query);
        r.queryInfo = qi;

        // headers
        Map<String, HeaderValue> hdrs = new HashMap<>();
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                HeaderValue hv = new HeaderValue();
                hv.setValue(e.getValue());
                hdrs.put(e.getKey(), hv);
            }
        }
        Field hdrField = io.edap.http.ValueHttpRequest.class.getDeclaredField("headers");
        hdrField.setAccessible(true);
        hdrField.set(r, hdrs);

        return r;
    }

    @Test
    void listBucketsRoot() throws Exception {
        HttpRequest req = makeRequest("GET", "/", null, null);
        S3Request s3 = parser.parse(req);
        assertEquals(S3Operation.LIST_BUCKETS, s3.operation());
        assertNull(s3.bucket());
    }

    @Test
    void createBucketFromPath() throws Exception {
        HttpRequest req = makeRequest("PUT", "/mybucket", null, null);
        S3Request s3 = parser.parse(req);
        assertEquals(S3Operation.CREATE_BUCKET, s3.operation());
        assertEquals("mybucket", s3.bucket());
        assertNull(s3.key());
    }

    @Test
    void deleteBucket() throws Exception {
        HttpRequest req = makeRequest("DELETE", "/mybucket", null, null);
        S3Request s3 = parser.parse(req);
        assertEquals(S3Operation.DELETE_BUCKET, s3.operation());
        assertEquals("mybucket", s3.bucket());
    }

    @Test
    void headBucket() throws Exception {
        HttpRequest req = makeRequest("HEAD", "/mybucket", null, null);
        S3Request s3 = parser.parse(req);
        assertEquals(S3Operation.HEAD_BUCKET, s3.operation());
    }

    @Test
    void listObjectsV2FromQuery() throws Exception {
        HttpRequest req = makeRequest("GET", "/mybucket", "list-type=2&prefix=foo/", null);
        S3Request s3 = parser.parse(req);
        assertEquals(S3Operation.LIST_OBJECTS_V2, s3.operation());
        assertEquals("mybucket", s3.bucket());
        assertEquals("2", s3.queryParam("list-type"));
        assertEquals("foo/", s3.queryParam("prefix"));
    }

    @Test
    void putObjectExtractsBucketAndKey() throws Exception {
        HttpRequest req = makeRequest("PUT", "/mybucket/path/to/key.txt", null, null);
        S3Request s3 = parser.parse(req);
        assertEquals(S3Operation.PUT_OBJECT, s3.operation());
        assertEquals("mybucket", s3.bucket());
        assertEquals("path/to/key.txt", s3.key());
    }

    @Test
    void getObjectWithNestedKey() throws Exception {
        HttpRequest req = makeRequest("GET", "/b/a/b/c.txt", null, null);
        S3Request s3 = parser.parse(req);
        assertEquals(S3Operation.GET_OBJECT, s3.operation());
        assertEquals("b", s3.bucket());
        assertEquals("a/b/c.txt", s3.key());
    }

    @Test
    void headObject() throws Exception {
        HttpRequest req = makeRequest("HEAD", "/b/k", null, null);
        S3Request s3 = parser.parse(req);
        assertEquals(S3Operation.HEAD_OBJECT, s3.operation());
    }

    @Test
    void deleteObject() throws Exception {
        HttpRequest req = makeRequest("DELETE", "/b/k", null, null);
        S3Request s3 = parser.parse(req);
        assertEquals(S3Operation.DELETE_OBJECT, s3.operation());
    }

    @Test
    void unsupportedMethodThrows() {
        HttpRequest req = stubRequest("PATCH", "/", null);
        try {
            parser.parse(req);
            org.junit.jupiter.api.Assertions.fail("should have thrown");
        } catch (Exception e) {
            assertEquals("MethodNotAllowed",
                    ((io.edap.s3.error.S3Exception) e).s3Code());
        }
    }

    @Test
    void headersLowercased() throws Exception {
        Map<String, String> hdrs = new HashMap<>();
        hdrs.put("Authorization", "AWS4-HMAC-SHA256 ...");
        hdrs.put("X-Amz-Date", "20260906T100000Z");
        HttpRequest req = makeRequest("GET", "/", null, hdrs);
        S3Request s3 = parser.parse(req);
        assertEquals("AWS4-HMAC-SHA256 ...", s3.header("authorization"));
        assertEquals("20260906T100000Z", s3.header("x-amz-date"));
    }

    @Test
    void rawHttpRequestPreserved() throws Exception {
        HttpRequest req = makeRequest("GET", "/foo/bar", null, null);
        S3Request s3 = parser.parse(req);
        assertEquals("/foo/bar", s3.rawHttpRequest());
    }

    @Test
    void parseRawQueryHandlesMissingEquals() {
        Map<String, String> q = S3RequestParser.parseRawQuery("foo&bar=baz&qux=");
        assertEquals("", q.get("foo"));
        assertEquals("baz", q.get("bar"));
        assertEquals("", q.get("qux"));
    }

    @Test
    void parseRawQueryHandlesNull() {
        Map<String, String> q = S3RequestParser.parseRawQuery(null);
        assertNotNull(q);
        assertEquals(0, q.size());
    }

    // ===================== Multipart 推断 =====================

    @Test
    void initMultipartFromPostUploads() throws Exception {
        HttpRequest req = makeRequest("POST", "/b/k", "uploads", null);
        S3Request s3 = parser.parse(req);
        assertEquals(S3Operation.INIT_MULTIPART, s3.operation());
        assertEquals("b", s3.bucket());
        assertEquals("k", s3.key());
        assertNotNull(s3.queryParam("uploads"));
    }

    @Test
    void completeMultipartFromPostUploadId() throws Exception {
        HttpRequest req = makeRequest("POST", "/b/k", "uploadId=ABC-123", null);
        S3Request s3 = parser.parse(req);
        assertEquals(S3Operation.COMPLETE_MULTIPART, s3.operation());
        assertEquals("ABC-123", s3.queryParam("uploadId"));
        assertEquals("b", s3.bucket());
        assertEquals("k", s3.key());
    }

    @Test
    void uploadPartFromPut() throws Exception {
        HttpRequest req = makeRequest("PUT", "/b/k",
                "partNumber=3&uploadId=ABC-123", null);
        S3Request s3 = parser.parse(req);
        assertEquals(S3Operation.UPLOAD_PART, s3.operation());
        assertEquals("3", s3.queryParam("partNumber"));
        assertEquals("ABC-123", s3.queryParam("uploadId"));
    }

    @Test
    void uploadPartRequiresPartNumber() throws Exception {
        // PUT + uploadId 但没 partNumber → INVALID_ARGUMENT
        HttpRequest req = makeRequest("PUT", "/b/k", "uploadId=ABC", null);
        try {
            parser.parse(req);
            org.junit.jupiter.api.Assertions.fail("should have thrown");
        } catch (io.edap.s3.error.S3Exception e) {
            assertEquals("InvalidArgument", e.s3Code());
        }
    }

    @Test
    void abortMultipartFromDelete() throws Exception {
        HttpRequest req = makeRequest("DELETE", "/b/k", "uploadId=ABC", null);
        S3Request s3 = parser.parse(req);
        assertEquals(S3Operation.ABORT_MULTIPART, s3.operation());
    }

    @Test
    void listMultipartUploadsFromGet() throws Exception {
        HttpRequest req = makeRequest("GET", "/b", "uploads", null);
        S3Request s3 = parser.parse(req);
        assertEquals(S3Operation.LIST_MULTIPART_UPLOADS, s3.operation());
        assertEquals("b", s3.bucket());
        assertNull(s3.key());
    }

    @Test
    void listPartsFromGet() throws Exception {
        HttpRequest req = makeRequest("GET", "/b/k", "uploadId=ABC", null);
        S3Request s3 = parser.parse(req);
        assertEquals(S3Operation.LIST_PARTS, s3.operation());
        assertEquals("ABC", s3.queryParam("uploadId"));
        assertEquals("k", s3.key());
    }

    @Test
    void putObjectWithoutMultipartQueryStillWorks() throws Exception {
        // 回归 —— PUT_OBJECT 不带 query 仍然走 PUT_OBJECT,不被 multipart 分支误吞
        HttpRequest req = makeRequest("PUT", "/b/k", null, null);
        S3Request s3 = parser.parse(req);
        assertEquals(S3Operation.PUT_OBJECT, s3.operation());
    }

    // ===================== minimal stub for unsupported-method test =====================

    private HttpRequest stubRequest(String method, String path, String query) {
        return new HttpRequest() {
            @Override public String getMethod() { return method; }
            @Override public String getPath() { return path; }
            @Override public void setPath(String string) { }
            @Override public io.edap.http.PathInfo getPathInfo() { return null; }
            @Override public HeaderValue getHeaderValue(String name) { return null; }
            @Override public io.edap.util.ByteData getBody() { return null; }
            @Override public io.edap.util.ByteData getHeaderData() { return null; }
            @Override public String getParameter(String name) { return null; }
            @Override public int getContentLength() { return -1; }
            @Override public MethodInfo getMethodInfo() { return null; }
            @Override public HttpResponse getResponse() { return null; }
            @Override public HttpVersion getVersion() { return null; }
            @Override public io.edap.http.HttpNioSession getHttpNioSession() { return null; }
            @Override public String getClientAddr() { return null; }
            @Override public void setBody(io.edap.util.ByteData body) {}
            @Override public void reset() {}
        };
    }
}