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

import io.edap.s3.model.S3Request;
import io.edap.s3.model.S3Response;
import io.edap.s3.op.S3Operation;
import io.edap.s3.store.BucketStore;
import io.edap.s3.xml.XmlResponseBuilder;

import java.io.IOException;

/**
 * GET / —— 返回当前 owner 拥有的桶列表。
 *
 * <p>Phase 1 简化:owner 写死成"edap-default-owner",所有桶归属该 owner
 * (S3 鉴权是基于 access key 的,Phase 1 不做 per-user namespace)。
 */
public final class ListBucketsHandler extends AbstractS3Handler {

    private static final String OWNER_ID = "edap-default-owner";
    private static final String OWNER_NAME = "edap-default-owner";

    private final BucketStore bucketStore;

    public ListBucketsHandler(BucketStore bucketStore) {
        super(S3Operation.LIST_BUCKETS);
        this.bucketStore = bucketStore;
    }

    @Override
    public S3Response handle(S3Request request) {
        try {
            byte[] body = XmlResponseBuilder.listAllMyBucketsResult(
                    OWNER_ID, OWNER_NAME, bucketStore.list());
            return S3Response.xml(200, body);
        } catch (IOException e) {
            throw new io.edap.s3.error.S3Exception(
                    io.edap.s3.error.S3ErrorCode.INTERNAL_ERROR,
                    "ListBuckets failed: " + e.getMessage());
        }
    }
}