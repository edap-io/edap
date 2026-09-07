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
import io.edap.s3.model.PutStream;
import io.edap.s3.model.S3Request;
import io.edap.s3.model.S3Response;
import io.edap.s3.op.S3Operation;
import io.edap.s3.store.MultipartStore;

import java.io.IOException;

/**
 * PUT /{bucket}/{key}?partNumber=N&uploadId=ID —— 上传一个 part。
 * 响应 200 + ETag header(part MD5 hex,带引号 —— S3 规范)。
 *
 * <p>request body 通过 {@link S3Request#putBody()} 拿到(由 parser 在
 * UPLOAD_PART 时填充)。handler 不需要 close body —— backend 接管。
 */
public final class UploadPartHandler extends AbstractS3Handler {

    private final MultipartStore multipartStore;

    public UploadPartHandler(MultipartStore multipartStore) {
        super(S3Operation.UPLOAD_PART);
        this.multipartStore = multipartStore;
    }

    @Override
    public S3Response handle(S3Request request) {
        String uploadId = request.queryParam("uploadId");
        String partNumberStr = request.queryParam("partNumber");
        if (uploadId == null || uploadId.isEmpty()) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Missing uploadId");
        }
        if (partNumberStr == null || partNumberStr.isEmpty()) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Missing partNumber");
        }
        int partNumber;
        try {
            partNumber = Integer.parseInt(partNumberStr);
        } catch (NumberFormatException e) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "Invalid partNumber: " + partNumberStr);
        }
        PutStream body = request.putBody();
        if (body == null) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "UploadPart requires request body");
        }
        try {
            String etag = multipartStore.uploadPart(
                    uploadId, partNumber,
                    body.content(), body.contentLength());
            return S3Response.empty(200).withHeader("ETag", "\"" + etag + "\"");
        } catch (S3Exception e) {
            throw e;
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR,
                    "UploadPart failed: " + e.getMessage());
        }
    }
}
