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

/**
 * CompleteMultipartUpload 请求 XML body 里 {@code <Part>} 元素的解析结果。
 *
 * <p>{@link #etag} 是裸 hex(客户端发过来时带引号,解析时去掉),用于跟已上传
 * part 的 etag 严格比对。
 */
public final class CompletedPart {

    private final int partNumber;
    private final String etag;

    public CompletedPart(int partNumber, String etag) {
        this.partNumber = partNumber;
        this.etag = etag;
    }

    public int partNumber() {
        return partNumber;
    }

    public String etag() {
        return etag;
    }
}
