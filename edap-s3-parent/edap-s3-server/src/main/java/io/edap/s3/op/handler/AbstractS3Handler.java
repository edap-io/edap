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

import io.edap.s3.model.S3Request;
import io.edap.s3.model.S3Response;
import io.edap.s3.op.S3Operation;
import io.edap.s3.op.S3OperationHandler;

/**
 * S3 operation handler 公共基类 —— 把范型声明 + operation() 返回抽出来,
 * 子类只关心业务 handle 逻辑。
 */
public abstract class AbstractS3Handler implements S3OperationHandler<S3Request> {

    private final S3Operation op;

    protected AbstractS3Handler(S3Operation op) {
        this.op = op;
    }

    @Override
    public final S3Operation operation() {
        return op;
    }

    @Override
    public abstract S3Response handle(S3Request request);
}