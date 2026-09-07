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
import io.edap.s3.model.MultipartUpload;
import io.edap.s3.model.S3Request;
import io.edap.s3.model.S3Response;
import io.edap.s3.op.S3Operation;
import io.edap.s3.store.MultipartStore;
import io.edap.s3.xml.XmlResponseBuilder;

import java.io.IOException;
import java.util.List;

/**
 * GET /{bucket}?uploads —— 列出桶内进行中的 multipart uploads。
 * 响应 200 + ListMultipartUploadsResult XML。
 */
public final class ListMultipartUploadsHandler extends AbstractS3Handler {

    private final MultipartStore multipartStore;

    public ListMultipartUploadsHandler(MultipartStore multipartStore) {
        super(S3Operation.LIST_MULTIPART_UPLOADS);
        this.multipartStore = multipartStore;
    }

    @Override
    public S3Response handle(S3Request request) {
        try {
            List<MultipartUpload> uploads = multipartStore.listUploads(request.bucket());
            return S3Response.xml(XmlResponseBuilder.listMultipartUploadsResult(
                    request.bucket(), uploads));
        } catch (S3Exception e) {
            throw e;
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR,
                    "ListMultipartUploads failed: " + e.getMessage());
        }
    }
}
