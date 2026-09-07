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
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * S3 Multipart Upload 的"进行中"状态 —— 由 {@link io.edap.s3.store.MultipartStore}
 * 在 {@code initiate} 时创建,所有 part 上传时往 {@link #parts} 里加,
 * {@code complete} / {@code abort} 时整个对象从 store 里移除并 GC。
 *
 * <p>并发模型:
 * <ul>
 *   <li>不同 uploadId → 不同的 MultipartUpload 实例,互不干扰</li>
 *   <li>同 uploadId 内并发上传不同 partNumber → {@link ConcurrentSkipListMap} 安全</li>
 *   <li>同 uploadId 内并发上传同一 partNumber → last-write-wins(S3 标准)</li>
 * </ul>
 */
public final class MultipartUpload {

    private final String uploadId;
    private final String bucket;
    private final String key;
    private final String contentType;
    private final Instant initiated;
    private final Map<String, String> userMetadata;
    private final ConcurrentSkipListMap<Integer, MultipartPart> parts;

    public MultipartUpload(String uploadId,
                           String bucket,
                           String key,
                           String contentType,
                           Instant initiated,
                           Map<String, String> userMetadata) {
        this.uploadId = uploadId;
        this.bucket = bucket;
        this.key = key;
        this.contentType = contentType;
        this.initiated = initiated;
        this.userMetadata = userMetadata == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(userMetadata);
        this.parts = new ConcurrentSkipListMap<>();
    }

    public String uploadId() {
        return uploadId;
    }

    public String bucket() {
        return bucket;
    }

    public String key() {
        return key;
    }

    public String contentType() {
        return contentType;
    }

    public Instant initiated() {
        return initiated;
    }

    public Map<String, String> userMetadata() {
        return userMetadata;
    }

    /**
     * parts 集合 —— 上传完成时由 complete() 按 partNumber 升序遍历,
     * abort() 时整张表丢给 GC。
     */
    public ConcurrentSkipListMap<Integer, MultipartPart> parts() {
        return parts;
    }

    /**
     * last-write-wins —— 同 partNumber 多次上传,最后一次胜出。
     * 返回被替换的旧 part(如果有)。
     */
    public MultipartPart addPart(MultipartPart part) {
        return parts.put(part.partNumber(), part);
    }
}
