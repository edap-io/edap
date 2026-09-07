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
 * DELETE /{bucket} —— 删桶;桶非空 → 409 BucketNotEmpty;桶不存在 → 404。
 */
public final class DeleteBucketHandler extends AbstractS3Handler {

    private final BucketStore bucketStore;
    private final ObjectStore objectStore;

    public DeleteBucketHandler(BucketStore bucketStore, ObjectStore objectStore) {
        super(S3Operation.DELETE_BUCKET);
        this.bucketStore = bucketStore;
        this.objectStore = objectStore;
    }

    @Override
    public S3Response handle(S3Request request) {
        String name = request.bucket();
        try {
            if (!bucketStore.exists(name)) {
                throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET,
                        "No such bucket: " + name, "/" + name);
            }
            if (!objectStore.isEmpty(name)) {
                throw new S3Exception(S3ErrorCode.BUCKET_NOT_EMPTY,
                        "Bucket is not empty: " + name, "/" + name);
            }
            bucketStore.delete(name);
            return S3Response.empty(204);
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR,
                    "DeleteBucket failed: " + e.getMessage());
        }
    }
}