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
import io.edap.s3.store.MultipartStore;

import java.io.IOException;

/**
 * DELETE /{bucket}/{key}?uploadId=ID —— 中止 multipart upload。
 * 响应 204 No Content。
 */
public final class AbortMultipartHandler extends AbstractS3Handler {

    private final MultipartStore multipartStore;

    public AbortMultipartHandler(MultipartStore multipartStore) {
        super(S3Operation.ABORT_MULTIPART);
        this.multipartStore = multipartStore;
    }

    @Override
    public S3Response handle(S3Request request) {
        String uploadId = request.queryParam("uploadId");
        if (uploadId == null || uploadId.isEmpty()) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Missing uploadId");
        }
        try {
            multipartStore.abort(uploadId);
        } catch (S3Exception e) {
            throw e;
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR,
                    "AbortMultipart failed: " + e.getMessage());
        }
        return S3Response.noContent();
    }
}
