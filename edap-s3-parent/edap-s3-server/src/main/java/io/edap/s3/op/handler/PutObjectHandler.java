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

/**
 * PUT /{bucket}/{key...} —— 上传对象。body 由 S3RequestParser 装成
 * {@link io.edap.s3.model.PutStream},handler 直接交给 ObjectStore。
 *
 * <p>Phase 1 简化:
 * <ul>
 *   <li>不做 Content-MD5 客户端校验(server 计算的 ETag 应等于客户端声明的 base64 解码后 hex)</li>
 *   <li>不支持 chunked(必须 Content-Length ≥ 0)</li>
 *   <li>返回 200 + ETag header;不返回 VersionId(Phase 2 加 versioning 时再加)</li>
 * </ul>
 */
public final class PutObjectHandler extends AbstractS3Handler {

    private final BucketStore bucketStore;
    private final ObjectStore objectStore;

    public PutObjectHandler(BucketStore bucketStore, ObjectStore objectStore) {
        super(S3Operation.PUT_OBJECT);
        this.bucketStore = bucketStore;
        this.objectStore = objectStore;
    }

    @Override
    public S3Response handle(S3Request request) {
        if (request.putBody() == null) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "PUT_OBJECT requires request body");
        }
        String bucket = request.bucket();
        String key = request.key();
        try {
            if (!bucketStore.exists(bucket)) {
                throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET,
                        "No such bucket: " + bucket, "/" + bucket);
            }
            ObjectMeta meta = objectStore.put(bucket, key, request.putBody());
            return S3Response.empty(200).withHeader("ETag", "\"" + meta.etag() + "\"");
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR,
                    "PutObject failed: " + e.getMessage());
        }
    }
}