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

import io.edap.s3.op.S3Operation;

import java.util.Collections;
import java.util.Map;

/**
 * 解析后的 S3 请求 —— 由 {@code S3RequestParser} 从 {@code HttpRequest}
 * 构造出来,交给 {@code S3Dispatcher} 路由到对应的
 * {@code S3OperationHandler}。
 *
 * <p>{@link #bucket} / {@link #key} 可能为 null:
 * <ul>
 *   <li>{@code LIST_BUCKETS}:两者都 null</li>
 *   <li>{@code CREATE/DELETE/HEAD_BUCKET} 和 {@code LIST_OBJECTS_V2}:bucket 非空,key 为 null</li>
 *   <li>{@code PUT/GET/HEAD/DELETE_OBJECT}:两者都非空(key 可能含 "/" 路径段)</li>
 * </ul>
 *
 * <p>{@link #putBody} 仅 {@code PUT_OBJECT} 和 {@code UPLOAD_PART} 有值;
 * {@link #xmlBody} 仅 {@code COMPLETE_MULTIPART} 有值;
 * {@link #queryParams} 保留所有 query 参数供 ListObjectsV2 等分页用。
 */
public final class S3Request {

    private final S3Operation operation;
    private final String bucket;
    private final String key;
    private final Map<String, String> queryParams;
    private final Map<String, String> headers;
    private final PutStream putBody;
    private final byte[] xmlBody;
    private final String rawHttpRequest;        // SigV4 验签时重建 canonical request 用

    public S3Request(S3Operation operation,
                     String bucket,
                     String key,
                     Map<String, String> queryParams,
                     Map<String, String> headers,
                     PutStream putBody,
                     byte[] xmlBody,
                     String rawHttpRequest) {
        this.operation = operation;
        this.bucket = bucket;
        this.key = key;
        this.queryParams = queryParams == null ? Collections.emptyMap() : queryParams;
        this.headers = headers == null ? Collections.emptyMap() : headers;
        this.putBody = putBody;
        this.xmlBody = xmlBody;
        this.rawHttpRequest = rawHttpRequest;
    }

    public S3Operation operation() {
        return operation;
    }

    public String bucket() {
        return bucket;
    }

    public String key() {
        return key;
    }

    public Map<String, String> queryParams() {
        return queryParams;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public PutStream putBody() {
        return putBody;
    }

    /**
     * COMPLETE_MULTIPART 请求的 XML body(其他 op 一律 null)。
     * handler 不需要 close —— 内部是 byte[] 不持有外部资源。
     */
    public byte[] xmlBody() {
        return xmlBody;
    }

    public String rawHttpRequest() {
        return rawHttpRequest;
    }

    /**
     * 取查询参数值(常见于 list-type=2 / continuation-token / prefix / delimiter 等)。
     */
    public String queryParam(String name) {
        return queryParams.get(name);
    }

    /**
     * 取 header 值(常见于 Range / Content-MD5 / x-amz-meta-* 等)。
     */
    public String header(String name) {
        return headers.get(name.toLowerCase());
    }
}
