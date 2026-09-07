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
import io.edap.s3.xml.XmlResponseBuilder;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * GET /{bucket}?list-type=2 —— ListObjectsV2。
 * Phase 1 支持 prefix / delimiter(→ CommonPrefixes) / max-keys;不带
 * continuation-token 时不分页。
 */
public final class ListObjectsV2Handler extends AbstractS3Handler {

    private final BucketStore bucketStore;
    private final ObjectStore objectStore;

    public ListObjectsV2Handler(BucketStore bucketStore, ObjectStore objectStore) {
        super(S3Operation.LIST_OBJECTS_V2);
        this.bucketStore = bucketStore;
        this.objectStore = objectStore;
    }

    @Override
    public S3Response handle(S3Request request) {
        String bucket = request.bucket();
        try {
            if (!bucketStore.exists(bucket)) {
                throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET,
                        "No such bucket: " + bucket, "/" + bucket);
            }
            String prefix = request.queryParam("prefix");
            if (prefix == null) prefix = "";
            String delimiter = request.queryParam("delimiter");
            String maxKeysRaw = request.queryParam("max-keys");
            int maxKeys = 1000;
            if (maxKeysRaw != null) {
                try {
                    maxKeys = Integer.parseInt(maxKeysRaw);
                    if (maxKeys < 0) maxKeys = 0;
                } catch (NumberFormatException ignore) {}
            }
            String continuationToken = request.queryParam("continuation-token");
            List<io.edap.s3.model.ObjectMeta> contents = objectStore.list(
                    bucket, prefix, delimiter, maxKeys, continuationToken);
            List<String> commonPrefixes = XmlResponseBuilder.computeCommonPrefixes(
                    contents, prefix, delimiter);

            byte[] body = XmlResponseBuilder.listBucketResultV2(
                    bucket, prefix, maxKeys, false, null,
                    contents,
                    commonPrefixes.isEmpty() ? null : commonPrefixes);
            return S3Response.xml(200, body);
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR,
                    "ListObjectsV2 failed: " + e.getMessage());
        }
    }
}