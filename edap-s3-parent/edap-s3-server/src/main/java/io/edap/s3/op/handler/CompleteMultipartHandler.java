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
import io.edap.s3.model.CompletedObject;
import io.edap.s3.model.CompletedPart;
import io.edap.s3.model.S3Request;
import io.edap.s3.model.S3Response;
import io.edap.s3.op.S3Operation;
import io.edap.s3.store.MultipartStore;
import io.edap.s3.xml.XmlRequestParser;
import io.edap.s3.xml.XmlResponseBuilder;

import java.io.IOException;
import java.util.List;

/**
 * POST /{bucket}/{key}?uploadId=ID —— 完成 multipart upload。
 * 请求 body 是 {@code <CompleteMultipartUpload>} XML,包含 client 声明的
 * parts 列表(每个 {@code <Part>}<PartNumber/>+<ETag/>)。
 * 响应 200 + CompleteMultipartUploadResult XML(含最终 ETag / Location)。
 */
public final class CompleteMultipartHandler extends AbstractS3Handler {

    private final MultipartStore multipartStore;

    public CompleteMultipartHandler(MultipartStore multipartStore) {
        super(S3Operation.COMPLETE_MULTIPART);
        this.multipartStore = multipartStore;
    }

    @Override
    public S3Response handle(S3Request request) {
        String uploadId = request.queryParam("uploadId");
        if (uploadId == null || uploadId.isEmpty()) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Missing uploadId");
        }
        byte[] body = request.xmlBody();
        if (body == null) {
            throw new S3Exception(S3ErrorCode.MALFORMED_XML,
                    "CompleteMultipartUpload requires XML body");
        }
        List<CompletedPart> parts = XmlRequestParser.completeMultipartBody(body);
        if (parts.isEmpty()) {
            throw new S3Exception(S3ErrorCode.MALFORMED_XML,
                    "CompleteMultipartUpload body must list at least one Part");
        }
        try {
            CompletedObject done = multipartStore.complete(uploadId, parts);
            // Location 跟 AWS virtual-hosted 不同,Phase 2 给个简单 path-style
            String location = "/" + done.bucket() + "/" + done.key();
            return S3Response.xml(XmlResponseBuilder.completeMultipartResult(
                    done.bucket(), done.key(), done.etag(), location));
        } catch (S3Exception e) {
            throw e;
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR,
                    "CompleteMultipart failed: " + e.getMessage());
        }
    }
}
