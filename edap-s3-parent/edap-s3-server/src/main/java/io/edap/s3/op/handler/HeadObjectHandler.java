/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.op.handler;

import io.edap.s3.error.S3ErrorCode;
import io.edap.s3.error.S3Exception;
import io.edap.s3.model.ObjectMeta;
import io.edap.s3.model.S3Request;
import io.edap.s3.model.S3Response;
import io.edap.s3.op.S3Operation;
import io.edap.s3.store.BucketStore;
import io.edap.s3.store.ObjectStore;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * HEAD /{bucket}/{key...} —— 取对象元数据。Phase 1 用 S3Response
 * 包装自定义 header + 空 body;edap HttpResponse 没法精确控制 HEAD 响应的
 * Content-Length=0,但 Content-Length 由 S3Response.headers 指定,客户端
 * 会按这个 header 解析(我们用手动 header 设)。
 */
public final class HeadObjectHandler extends AbstractS3Handler {

    private static final DateTimeFormatter RFC1123 =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'")
                    .withLocale(Locale.US)
                    .withZone(ZoneOffset.UTC);

    private final BucketStore bucketStore;
    private final ObjectStore objectStore;

    public HeadObjectHandler(BucketStore bucketStore, ObjectStore objectStore) {
        super(S3Operation.HEAD_OBJECT);
        this.bucketStore = bucketStore;
        this.objectStore = objectStore;
    }

    @Override
    public S3Response handle(S3Request request) {
        String bucket = request.bucket();
        String key = request.key();
        try {
            if (!bucketStore.exists(bucket)) {
                throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET,
                        "No such bucket: " + bucket, "/" + bucket);
            }
            ObjectMeta meta = objectStore.head(bucket, key);
            return S3Response.empty(200)
                    .withHeader("ETag", "\"" + meta.etag() + "\"")
                    .withHeader("Last-Modified", RFC1123.format(meta.lastModified()))
                    .withHeader("Content-Length", String.valueOf(meta.size()))
                    .withHeader("Content-Type", meta.contentType() == null
                            ? "application/octet-stream" : meta.contentType())
                    .withHeader("Accept-Ranges", "bytes");
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR,
                    "HeadObject failed: " + e.getMessage());
        }
    }
}