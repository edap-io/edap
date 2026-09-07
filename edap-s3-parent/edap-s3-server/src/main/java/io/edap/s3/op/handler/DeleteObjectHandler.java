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
import io.edap.s3.model.S3Request;
import io.edap.s3.model.S3Response;
import io.edap.s3.op.S3Operation;
import io.edap.s3.store.BucketStore;
import io.edap.s3.store.ObjectStore;

import java.io.IOException;

/**
 * DELETE /{bucket}/{key...} —— 删对象。S3 规范:idempotent,对象不存在也返回 204。
 */
public final class DeleteObjectHandler extends AbstractS3Handler {

    private final BucketStore bucketStore;
    private final ObjectStore objectStore;

    public DeleteObjectHandler(BucketStore bucketStore, ObjectStore objectStore) {
        super(S3Operation.DELETE_OBJECT);
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
            objectStore.delete(bucket, key);
            return S3Response.empty(204);
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR,
                    "DeleteObject failed: " + e.getMessage());
        }
    }
}