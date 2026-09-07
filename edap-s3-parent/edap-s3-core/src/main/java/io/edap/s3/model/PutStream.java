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
 * 写入 S3 时的输入流包装 —— {@link io.edap.s3.store.ObjectStore#put}
 * 接收的对象。body 由调用方(InputStream)提供,元数据从 HTTP 头部提取。
 *
 * <p>{@link #contentLength} = -1 表示 chunked / 未知长度(Phase 1 不支持,
 * PutObjectHandler 会校验必须非负);{@link #contentMd5} 客户端传的 base64
 * MD5,可选,backend 应在写入完成后校验一致性。
 */
public final class PutStream {

    private final InputStream content;
    private final String contentType;
    private final Map<String, String> userMetadata;
    private final long contentLength;
    private final String contentMd5;

    public PutStream(InputStream content,
                     String contentType,
                     Map<String, String> userMetadata,
                     long contentLength,
                     String contentMd5) {
        this.content = content;
        this.contentType = contentType;
        this.userMetadata = userMetadata == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(userMetadata);
        this.contentLength = contentLength;
        this.contentMd5 = contentMd5;
    }

    public InputStream content() {
        return content;
    }

    public String contentType() {
        return contentType;
    }

    public Map<String, String> userMetadata() {
        return userMetadata;
    }

    public long contentLength() {
        return contentLength;
    }

    public String contentMd5() {
        return contentMd5;
    }
}
