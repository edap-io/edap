/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.op;

/**
 * S3 操作类型枚举 —— Phase 1 只支持桶级 + 对象级基础操作,
 * multipart / versioning / ACL 等留 Phase 2。
 *
 * <p>由 {@code S3RequestParser} 根据 HTTP method + path 段数 + query 参数
 * 推断出当前请求属于哪个 operation,再由 {@code S3Dispatcher} 路由到对应
 * {@link S3OperationHandler}。
 */
public enum S3Operation {

    // ===== 桶级 =====
    LIST_BUCKETS,          // GET /
    CREATE_BUCKET,         // PUT /{bucket}
    DELETE_BUCKET,         // DELETE /{bucket}
    HEAD_BUCKET,           // HEAD /{bucket}
    LIST_OBJECTS_V2,       // GET /{bucket}?list-type=2
    PUT_BUCKET_ACL,        // PUT /{bucket}?acl  (x-amz-acl header)

    // ===== 对象级 =====
    PUT_OBJECT,            // PUT /{bucket}/{key...}
    GET_OBJECT,            // GET /{bucket}/{key...}
    HEAD_OBJECT,           // HEAD /{bucket}/{key...}
    DELETE_OBJECT,         // DELETE /{bucket}/{key...}

    // ===== Multipart =====
    INIT_MULTIPART,         // POST   /{bucket}/{key...}?uploads
    UPLOAD_PART,            // PUT    /{bucket}/{key...}?partNumber=N&uploadId=ID
    COMPLETE_MULTIPART,     // POST   /{bucket}/{key...}?uploadId=ID
    ABORT_MULTIPART,        // DELETE /{bucket}/{key...}?uploadId=ID
    LIST_MULTIPART_UPLOADS, // GET    /{bucket}?uploads
    LIST_PARTS              // GET    /{bucket}/{key...}?uploadId=ID
}
