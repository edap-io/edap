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
 * PUT /{bucket} —— 创建桶。桶名 3-63 字符,仅小写字母数字 + "-" + "."。
 * Phase 1 不处理 CreateBucketConfiguration / LocationConstraint。
 */
public final class CreateBucketHandler extends AbstractS3Handler {

    private final BucketStore bucketStore;

    public CreateBucketHandler(BucketStore bucketStore) {
        super(S3Operation.CREATE_BUCKET);
        this.bucketStore = bucketStore;
    }

    @Override
    public S3Response handle(S3Request request) {
        String name = request.bucket();
        validateName(name);
        try {
            if (bucketStore.exists(name)) {
                throw new S3Exception(S3ErrorCode.BUCKET_ALREADY_OWNED_BY_YOU,
                        "Bucket already exists: " + name, "/" + name);
            }
            bucketStore.create(name);
            return S3Response.empty(200).withHeader("Location", "/" + name);
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR,
                    "CreateBucket failed: " + e.getMessage());
        }
    }

    static void validateName(String name) {
        if (name == null || name.isEmpty()) {
            throw new S3Exception(S3ErrorCode.INVALID_BUCKET_NAME,
                    "Bucket name is empty");
        }
        if (name.length() < 3 || name.length() > 63) {
            throw new S3Exception(S3ErrorCode.INVALID_BUCKET_NAME,
                    "Bucket name length must be 3..63: " + name);
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.';
            if (!ok) {
                throw new S3Exception(S3ErrorCode.INVALID_BUCKET_NAME,
                        "Invalid character in bucket name: " + name);
            }
        }
        if (name.startsWith("-") || name.endsWith("-")) {
            throw new S3Exception(S3ErrorCode.INVALID_BUCKET_NAME,
                    "Bucket name cannot start or end with '-': " + name);
        }
    }
}