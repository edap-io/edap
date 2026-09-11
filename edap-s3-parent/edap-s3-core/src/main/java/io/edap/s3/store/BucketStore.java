/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.store;

import io.edap.s3.model.BucketCannedAcl;

import java.io.IOException;
import java.util.List;

/**
 * S3 桶存储 SPI —— 桶的存在性 / 创建 / 删除 / 列表 / canned ACL。
 *
 * <p>{@link #create} 时若桶已存在,实现选择:
 * <ul>
 *   <li>抛 {@link io.edap.s3.error.S3Exception} BUCKET_ALREADY_EXISTS(默认)</li>
 *   <li>或 idempotent 直接返回(MinIO 风格)—— Phase 1 选前者,S3 规范更严</li>
 * </ul>
 *
 * <p>{@link #delete} 时若桶非空,backend 应抛 BUCKET_NOT_EMPTY
 * (或让上层 handler 配合 {@link ObjectStore#isEmpty} 检测)。
 *
 * <p>{@link #setCannedAcl} / {@link #getCannedAcl} —— 桶级 canned ACL,
 * 用于支持 public-read / public-read-write。getCannedAcl 对未设置 ACL 的桶
 * 返回 {@link BucketCannedAcl#PRIVATE}(默认私有)。
 */
public interface BucketStore {

    void create(String bucket) throws IOException;

    void delete(String bucket) throws IOException;

    boolean exists(String bucket) throws IOException;

    List<String> list() throws IOException;

    /**
     * 设桶的 canned ACL(覆盖)。桶不存在抛 NO_SUCH_BUCKET。
     */
    void setCannedAcl(String bucket, BucketCannedAcl acl) throws IOException;

    /**
     * 读桶的 canned ACL。桶不存在抛 NO_SUCH_BUCKET;
     * 桶存在但未设过 ACL 返回 PRIVATE。
     */
    BucketCannedAcl getCannedAcl(String bucket) throws IOException;
}
