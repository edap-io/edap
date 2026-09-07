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
 * S3 协议层异常 —— 携带 {@link S3ErrorCode},在 {@code S3HttpHandler} 统一捕获
 * 并生成对应 HTTP 状态码 + XML 错误 body。
 *
 * <p>extends {@link RuntimeException} —— 业务层 handler 直接抛,无需 throws 声明。
 */
public class S3Exception extends RuntimeException {

    private final S3ErrorCode code;
    private final String resource;       // 可选,生成 XML Error 时的 Resource 字段

    public S3Exception(S3ErrorCode code) {
        this(code, null, null, null);
    }

    public S3Exception(S3ErrorCode code, String message) {
        this(code, message, null, null);
    }

    public S3Exception(S3ErrorCode code, String message, String resource) {
        this(code, message, resource, null);
    }

    public S3Exception(S3ErrorCode code, String message, String resource, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.resource = resource;
    }

    public S3ErrorCode code() {
        return code;
    }

    public String resource() {
        return resource;
    }

    public int httpStatus() {
        return code.httpStatus;
    }

    public String s3Code() {
        return code.s3Code;
    }
}
