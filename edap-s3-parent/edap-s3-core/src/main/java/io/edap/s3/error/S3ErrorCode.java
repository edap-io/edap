/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.error;

/**
 * S3 错误码枚举 —— 对应 AWS S3 API 规范的错误响应 XML 里的 Code 字段。
 *
 * <p>每个错误码绑定一个 HTTP status —— {@link #httpStatus} 用于响应行,
 * {@link #s3Code} 用于响应 XML body 的 {@code <Code>} 元素。
 */
public enum S3ErrorCode {

    NO_SUCH_BUCKET(404, "NoSuchBucket"),
    NO_SUCH_KEY(404, "NoSuchKey"),

    BUCKET_ALREADY_EXISTS(409, "BucketAlreadyExists"),
    BUCKET_ALREADY_OWNED_BY_YOU(409, "BucketAlreadyOwnedByYou"),
    BUCKET_NOT_EMPTY(409, "BucketNotEmpty"),

    INVALID_BUCKET_NAME(400, "InvalidBucketName"),
    INVALID_ARGUMENT(400, "InvalidArgument"),
    INVALID_REQUEST(400, "InvalidRequest"),

    INVALID_ACCESS_KEY_ID(403, "InvalidAccessKeyId"),
    SIGNATURE_DOES_NOT_MATCH(403, "SignatureDoesNotMatch"),
    ACCESS_DENIED(403, "AccessDenied"),

    METHOD_NOT_ALLOWED(405, "MethodNotAllowed"),

    INTERNAL_ERROR(500, "InternalError");

    public final int httpStatus;
    public final String s3Code;

    S3ErrorCode(int httpStatus, String s3Code) {
        this.httpStatus = httpStatus;
        this.s3Code = s3Code;
    }
}
