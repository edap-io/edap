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

import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * S3 对象元数据 —— 不含 body,只有 ETag / size / lastModified / contentType
 * / user metadata。{@link ObjectStore#head} / {@link ObjectStore#list} 返回。
 *
 * <p>immutable;所有字段在构造时一次性确定。{@link #userMetadata} 在外部
 * 不可变(unmodifiableMap)。
 */
public final class ObjectMeta {

    private final String key;
    private final long size;
    private final String etag;                 // MD5 hex(不带引号)
    private final String contentType;
    private final Instant lastModified;
    private final Map<String, String> userMetadata;

    public ObjectMeta(String key,
                      long size,
                      String etag,
                      String contentType,
                      Instant lastModified,
                      Map<String, String> userMetadata) {
        this.key = key;
        this.size = size;
        this.etag = etag;
        this.contentType = contentType;
        this.lastModified = lastModified;
        this.userMetadata = userMetadata == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(userMetadata);
    }

    public String key() {
        return key;
    }

    public long size() {
        return size;
    }

    /**
     * ETag(MD5 hex,无引号)。S3 规范要求响应里带双引号,这里是裸 hex,
     * 序列化时由 XmlResponseBuilder / GetObjectHandler 加引号。
     */
    public String etag() {
        return etag;
    }

    public String contentType() {
        return contentType;
    }

    public Instant lastModified() {
        return lastModified;
    }

    public Map<String, String> userMetadata() {
        return userMetadata;
    }
}
