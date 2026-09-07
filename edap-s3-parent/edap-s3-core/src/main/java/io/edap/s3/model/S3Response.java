/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.model;

import java.io.InputStream;
import java.util.Collections;
import java.util.Map;

/**
 * S3 操作响应 —— 由 {@code S3OperationHandler} 返回,交给
 * {@code S3HttpHandler} 写回 HTTP 响应。
 *
 * <p>三种 body 来源:
 * <ul>
 *   <li>{@link #bodyBytes} —— XML 响应 / 小对象(桶级操作)</li>
 *   <li>{@link #bodyStream} —— 大对象 / 流式响应(GetObject)</li>
 *   <li>两者都为 null —— 204 No Content(HeadBucket / DeleteObject)</li>
 * </ul>
 *
 * <p>{@link #headers} 是除 Content-Type / Content-Length / ETag /
 * Last-Modified 之外的自定义 header,主要给 {@code x-amz-meta-*}
 * 和 {@code x-amz-request-id} 等使用。
 */
public final class S3Response {

    private final int status;
    private final String contentType;
    private final byte[] bodyBytes;
    private final InputStream bodyStream;
    private final long bodyStreamLength;        // -1 if unknown
    private final Map<String, String> headers;

    private S3Response(int status,
                       String contentType,
                       byte[] bodyBytes,
                       InputStream bodyStream,
                       long bodyStreamLength,
                       Map<String, String> headers) {
        this.status = status;
        this.contentType = contentType;
        this.bodyBytes = bodyBytes;
        this.bodyStream = bodyStream;
        this.bodyStreamLength = bodyStreamLength;
        this.headers = headers == null ? Collections.emptyMap() : headers;
    }

    public static S3Response noContent() {
        return new S3Response(204, null, null, null, -1, null);
    }

    public static S3Response empty(int status) {
        return new S3Response(status, null, null, null, -1, null);
    }

    public static S3Response xml(byte[] body) {
        return new S3Response(200, "application/xml", body, null, -1, null);
    }

    public static S3Response xml(int status, byte[] body) {
        return new S3Response(status, "application/xml", body, null, -1, null);
    }

    public static S3Response stream(String contentType, InputStream stream, long length) {
        return new S3Response(200, contentType, null, stream, length, null);
    }

    public static S3Response streamPartial(String contentType, InputStream stream, long length) {
        return new S3Response(206, contentType, null, stream, length, null);
    }

    public S3Response withHeader(String name, String value) {
        Map<String, String> newHeaders = new java.util.HashMap<>(this.headers);
        newHeaders.put(name, value);
        return new S3Response(status, contentType, bodyBytes, bodyStream, bodyStreamLength, newHeaders);
    }

    public int status() {
        return status;
    }

    public String contentType() {
        return contentType;
    }

    public byte[] bodyBytes() {
        return bodyBytes;
    }

    public InputStream bodyStream() {
        return bodyStream;
    }

    public long bodyStreamLength() {
        return bodyStreamLength;
    }

    public Map<String, String> headers() {
        return headers;
    }
}
