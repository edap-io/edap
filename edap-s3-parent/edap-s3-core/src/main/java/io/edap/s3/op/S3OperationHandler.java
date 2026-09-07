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

import io.edap.s3.model.S3Request;
import io.edap.s3.model.S3Response;

/**
 * S3 单个操作的处理器 SPI —— 每个 S3 operation 对应一个实现类。
 *
 * <p>由 {@code S3Dispatcher} 通过 {@link #operation()} 路由:
 * <pre>{@code
 * dispatcher.register(new ListBucketsHandler(bucketStore));
 * dispatcher.register(new PutObjectHandler(objectStore));
 * }</pre>
 *
 * <p>泛型 T 是具体的 {@link S3Request} 子类 —— Phase 1 简化,所有 handler
 * 直接接 {@link S3Request}(只有 put body 等少数字段需要运行时区分),
 * T = S3Request 即可。Phase 2 引入 multipart 时,T 拆成 PutObjectRequest /
 * MultipartInitRequest 等。
 */
public interface S3OperationHandler<T extends S3Request> {

    /**
     * 本 handler 处理哪个 operation —— dispatcher 路由 key。
     */
    S3Operation operation();

    /**
     * 实际处理请求。失败抛 {@link io.edap.s3.error.S3Exception},由
     * {@code S3HttpHandler} 统一生成错误响应 XML。
     */
    S3Response handle(T request) throws Exception;
}
