/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.store.mem;

import io.edap.s3.error.S3ErrorCode;
import io.edap.s3.error.S3Exception;
import io.edap.s3.store.BucketStore;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存 {@link BucketStore} —— 用 {@link ConcurrentHashMap} 存桶名。
 *
 * <p>线程安全:create/delete/exists 都走 keySet;list 走 snapshot 避免并发修改异常。
 *
 * <p>仅用于单测 / 本地开发;生产用 {@code DiskBucketStore}。
 */
public class InMemoryBucketStore implements BucketStore {

    private final Set<String> buckets = ConcurrentHashMap.newKeySet();

    @Override
    public void create(String bucket) throws IOException {
        if (!buckets.add(bucket)) {
            throw new S3Exception(S3ErrorCode.BUCKET_ALREADY_EXISTS,
                    "Bucket already exists: " + bucket, "/" + bucket);
        }
    }

    @Override
    public void delete(String bucket) throws IOException {
        // 不在这里判空 / 不存在 —— 让上层 DeleteBucketHandler 配合 ObjectStore.isEmpty 决定
        // 这里只做 key 删除:桶不存在时幂等返回(S3 DELETE 是 idempotent)
        buckets.remove(bucket);
    }

    @Override
    public boolean exists(String bucket) throws IOException {
        return buckets.contains(bucket);
    }

    @Override
    public List<String> list() throws IOException {
        List<String> snapshot = new ArrayList<>(buckets);
        Collections.sort(snapshot);
        return snapshot;
    }
}
