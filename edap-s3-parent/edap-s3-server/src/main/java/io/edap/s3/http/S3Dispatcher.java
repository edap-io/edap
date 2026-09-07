/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.http;

import io.edap.s3.error.S3ErrorCode;
import io.edap.s3.error.S3Exception;
import io.edap.s3.model.S3Request;
import io.edap.s3.model.S3Response;
import io.edap.s3.op.S3Operation;
import io.edap.s3.op.S3OperationHandler;

import java.util.EnumMap;
import java.util.Map;

import static io.edap.s3.error.S3ErrorCode.INTERNAL_ERROR;

/**
 * Operation → Handler 路由表。由 {@code S3ServerBuilder} 在 build 前
 * 注入全部 handler;{@code S3HttpHandler} 在每个请求 dispatch 时调用。
 *
 * <p>未注册的 operation 抛 {@link S3Exception}(METHOD_NOT_ALLOWED)。
 */
public final class S3Dispatcher {

    private final Map<S3Operation, S3OperationHandler<?>> handlers =
            new EnumMap<>(S3Operation.class);

    public void register(S3OperationHandler<?> handler) {
        if (handler == null) throw new IllegalArgumentException("handler is null");
        handlers.put(handler.operation(), handler);
    }

    public S3Response dispatch(S3Request req) throws S3Exception {
        S3OperationHandler<?> h = handlers.get(req.operation());
        if (h == null) {
            throw new S3Exception(S3ErrorCode.METHOD_NOT_ALLOWED,
                    "No handler registered for operation: " + req.operation());
        }
        try {
            return invoke(req, h);
        } catch (S3Exception e) {
            throw e;
        } catch (Exception e) {
            throw new S3Exception(INTERNAL_ERROR, e.getMessage());
        }
    }

    /**
     * 通用泛型 dispatch —— 把 {@code S3OperationHandler<S3Request>} 的 handle
     * 直接强转后调用(handler 收到的就是 {@link S3Request},所有 handler 的
     * 范型实参都是 S3Request)。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static S3Response invoke(S3Request req, S3OperationHandler raw) {
        try {
            return (S3Response) raw.handle(req);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new S3Exception(INTERNAL_ERROR, e.getMessage());
        }
    }
}