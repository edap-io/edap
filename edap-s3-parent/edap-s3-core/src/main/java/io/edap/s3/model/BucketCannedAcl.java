/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.model;

import io.edap.s3.error.S3ErrorCode;
import io.edap.s3.error.S3Exception;
import io.edap.s3.op.S3Operation;

/**
 * S3 桶 canned ACL —— 对齐 AWS S3 标准 canned ACL 语义,
 * 不实现完整 JSON bucket policy。
 *
 * <p>语义:
 * <ul>
 *   <li>{@link #PRIVATE} —— 所有操作必须签名(默认)</li>
 *   <li>{@link #PUBLIC_READ} —— GET/HEAD/LIST 对象可匿名;PUT/DELETE 仍需签名</li>
 *   <li>{@link #PUBLIC_READ_WRITE} —— 全部操作可匿名(谨慎使用)</li>
 * </ul>
 *
 * <p>wire 表达:
 * <ul>
 *   <li>{@code x-amz-acl: private} / {@code public-read} / {@code public-read-write}
 *       —— PUT /{bucket}?acl 时客户端写这个 header</li>
 * </ul>
 *
 * <p>设计取舍:
 * <ul>
 *   <li>不做 full JSON bucket policy —— 现阶段没必要,够用即可
 *   <li>不做 ACL XML body(AccessControlPolicy) —— canned ACL 走 header 更轻
 * </ul>
 */
public enum BucketCannedAcl {

    PRIVATE,
    PUBLIC_READ,
    PUBLIC_READ_WRITE;

    /** x-amz-acl header 值(小写、连字符,AWS 规范)。 */
    public String headerValue() {
        return switch (this) {
            case PRIVATE            -> "private";
            case PUBLIC_READ        -> "public-read";
            case PUBLIC_READ_WRITE  -> "public-read-write";
        };
    }

    /**
     * 解析 {@code x-amz-acl} header 值。
     *
     * @throws S3Exception INVALID_ARGUMENT 当 header 值不是合法 canned ACL 名
     */
    public static BucketCannedAcl fromHeader(String header) {
        if (header == null || header.isEmpty()) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "Missing x-amz-acl header value");
        }
        return switch (header.trim().toLowerCase()) {
            case "private"           -> PRIVATE;
            case "public-read"       -> PUBLIC_READ;
            case "public-read-write" -> PUBLIC_READ_WRITE;
            default -> throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "Unsupported canned ACL: " + header
                            + " (supported: private / public-read / public-read-write)");
        };
    }

    /**
     * 给定 op + 当前 ACL,判断匿名请求是否被允许。
     *
     * <p>不允许的操作(始终需要签名):
     * <ul>
     *   <li>{@link S3Operation#LIST_BUCKETS} —— 跨桶操作</li>
     *   <li>{@link S3Operation#CREATE_BUCKET} / {@link S3Operation#DELETE_BUCKET}
     *       / {@link S3Operation#HEAD_BUCKET} —— 桶级管理</li>
     *   <li>{@link S3Operation#PUT_BUCKET_ACL} —— 改 ACL 必须授权</li>
     * </ul>
     */
    public boolean allowsAnonymous(S3Operation op) {
        return switch (op) {
            // 桶级 ops:桶级 ACL 永远要求 auth(否则任何人能建/删桶 / 列所有桶)
            case LIST_BUCKETS, CREATE_BUCKET, DELETE_BUCKET, HEAD_BUCKET,
                 PUT_BUCKET_ACL ->
                false;
            // 读 ops:public-read 即可
            case GET_OBJECT, HEAD_OBJECT, LIST_OBJECTS_V2 ->
                this == PUBLIC_READ || this == PUBLIC_READ_WRITE;
            // 写 ops:必须 public-read-write
            case PUT_OBJECT, DELETE_OBJECT,
                 INIT_MULTIPART, UPLOAD_PART, COMPLETE_MULTIPART,
                 ABORT_MULTIPART, LIST_PARTS, LIST_MULTIPART_UPLOADS ->
                this == PUBLIC_READ_WRITE;
        };
    }
}
