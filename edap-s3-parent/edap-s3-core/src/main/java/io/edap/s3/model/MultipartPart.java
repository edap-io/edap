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

import java.time.Instant;

/**
 * S3 Multipart Upload 的单个 part —— partNumber / ETag(MD5 hex)/ size /
 * lastModified / body(byte[])。immutable。
 *
 * <p>{@link #body} 在 listParts 时不需要(响应里没 body),但 InMemory 实现
 * 内部持有,等 complete 时再拿出来拼接成最终对象。
 */
public final class MultipartPart {

    private final int partNumber;
    private final String etag;             // MD5 hex(裸 hex,无引号)
    private final long size;
    private final Instant lastModified;
    private final byte[] body;

    public MultipartPart(int partNumber,
                         String etag,
                         long size,
                         Instant lastModified,
                         byte[] body) {
        this.partNumber = partNumber;
        this.etag = etag;
        this.size = size;
        this.lastModified = lastModified;
        this.body = body;
    }

    public int partNumber() {
        return partNumber;
    }

    public String etag() {
        return etag;
    }

    public long size() {
        return size;
    }

    public Instant lastModified() {
        return lastModified;
    }

    public byte[] body() {
        return body;
    }
}
