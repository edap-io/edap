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

/**
 * CompleteMultipartUpload 完成后,MultipartStore 把组装好的对象塞进 ObjectStore
 * 后返回的元数据。{@link #etag} 是 S3 规范的复合 ETag({@code <md5-of-parts>-<count>})。
 */
public final class CompletedObject {

    private final String bucket;
    private final String key;
    private final String etag;
    private final long size;

    public CompletedObject(String bucket, String key, String etag, long size) {
        this.bucket = bucket;
        this.key = key;
        this.etag = etag;
        this.size = size;
    }

    public String bucket() {
        return bucket;
    }

    public String key() {
        return key;
    }

    public String etag() {
        return etag;
    }

    public long size() {
        return size;
    }
}
