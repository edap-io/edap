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

import java.io.InputStream;

/**
 * 读取 S3 对象时的输出流包装 —— {@link io.edap.s3.store.ObjectStore#get}
 * 返回的对象。{@link #content} 由 backend 创建,调用方负责 close。
 *
 * <p>{@link #rangeStart} / {@link #rangeEnd} 表示返回的流对应的字节范围
 * (含两端,均从 0 起);{@code rangeEnd == -1} 表示读到末尾。
 * GetObjectHandler 据此决定响应 status(200 全量 / 206 部分)。
 */
public final class GetStream {

    private final InputStream content;
    private final ObjectMeta meta;
    private final long rangeStart;
    private final long rangeEnd;

    public GetStream(InputStream content, ObjectMeta meta, long rangeStart, long rangeEnd) {
        this.content = content;
        this.meta = meta;
        this.rangeStart = rangeStart;
        this.rangeEnd = rangeEnd;
    }

    public InputStream content() {
        return content;
    }

    public ObjectMeta meta() {
        return meta;
    }

    public long rangeStart() {
        return rangeStart;
    }

    public long rangeEnd() {
        return rangeEnd;
    }

    public boolean isPartial() {
        return rangeStart > 0 || rangeEnd >= 0;
    }
}
