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
import io.edap.s3.model.MultipartPart;
import io.edap.s3.model.S3Request;
import io.edap.s3.model.S3Response;
import io.edap.s3.op.S3Operation;
import io.edap.s3.store.MultipartStore;
import io.edap.s3.xml.XmlResponseBuilder;

import java.io.IOException;
import java.util.List;

/**
 * GET /{bucket}/{key}?uploadId=ID —— 列出 upload 已上传的 parts。
 * 响应 200 + ListPartsResult XML(每个 part 含 PartNumber / ETag / Size / LastModified)。
 */
public final class ListPartsHandler extends AbstractS3Handler {

    private final MultipartStore multipartStore;

    public ListPartsHandler(MultipartStore multipartStore) {
        super(S3Operation.LIST_PARTS);
        this.multipartStore = multipartStore;
    }

    @Override
    public S3Response handle(S3Request request) {
        String uploadId = request.queryParam("uploadId");
        if (uploadId == null || uploadId.isEmpty()) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT, "Missing uploadId");
        }
        try {
            List<MultipartPart> parts = multipartStore.listParts(uploadId);
            return S3Response.xml(XmlResponseBuilder.listPartsResult(
                    request.bucket(), request.key(), uploadId, parts));
        } catch (S3Exception e) {
            throw e;
        } catch (IOException e) {
            throw new S3Exception(S3ErrorCode.INTERNAL_ERROR,
                    "ListParts failed: " + e.getMessage());
        }
    }
}
