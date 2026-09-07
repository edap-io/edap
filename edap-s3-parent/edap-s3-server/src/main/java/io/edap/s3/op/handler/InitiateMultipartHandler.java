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

import io.edap.s3.error.S3Exception;
import io.edap.s3.model.S3Request;
import io.edap.s3.model.S3Response;
import io.edap.s3.op.S3Operation;
import io.edap.s3.store.MultipartStore;
import io.edap.s3.xml.XmlResponseBuilder;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * POST /{bucket}/{key}?uploads —— 启动 multipart upload,返回 uploadId。
 * 响应 body 是 InitiateMultipartUploadResult XML,200 OK。
 *
 * <p>Phase 2 不强校验 5MB 最小 part / 最大 10000 parts 限制;
 * 在 {@code uploadPart} 那里 partNumber 超界会拒。
 */
public final class InitiateMultipartHandler extends AbstractS3Handler {

    private static final String USER_META_PREFIX = "x-amz-meta-";

    private final MultipartStore multipartStore;

    public InitiateMultipartHandler(MultipartStore multipartStore) {
        super(S3Operation.INIT_MULTIPART);
        this.multipartStore = multipartStore;
    }

    @Override
    public S3Response handle(S3Request request) {
        try {
            String uploadId = multipartStore.initiate(
                    request.bucket(),
                    request.key(),
                    request.header("content-type"),
                    extractUserMeta(request.headers()));
            return S3Response.xml(XmlResponseBuilder.initiateMultipartResult(
                    request.bucket(), request.key(), uploadId));
        } catch (S3Exception e) {
            throw e;
        } catch (IOException e) {
            throw new S3Exception(io.edap.s3.error.S3ErrorCode.INTERNAL_ERROR,
                    "InitiateMultipart failed: " + e.getMessage());
        }
    }

    private static Map<String, String> extractUserMeta(Map<String, String> headers) {
        Map<String, String> meta = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            if (k.startsWith(USER_META_PREFIX)) {
                meta.put(k.substring(USER_META_PREFIX.length()), e.getValue());
            }
        }
        return meta;
    }
}
