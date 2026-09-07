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

import java.io.IOException;

/**
 * HEAD /{bucket} —— 桶存在 → 200;不存在 → 404。
 * (Phase 1 不返回 BucketRegion 等元数据 header —— S3 真实服务端会带。)
 */
public final class HeadBucketHandler extends AbstractS3Handler {

    private final BucketStore bucketStore;

    public HeadBucketHandler(BucketStore bucketStore) {
        super(S3Operation.HEAD_BUCKET);
        this.bucketStore = bucketStore;
    }

    @Override
    public S3Response handle(S3Request request) {
        String name = request.bucket();
        try {
            if (!bucketStore.exists(name)) {
                throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET,
                        "No such bucket: " + name, "/" + name);
            }
            return S3Response.empty(200);
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR,
                    "HeadBucket failed: " + e.getMessage());
        }
    }
}