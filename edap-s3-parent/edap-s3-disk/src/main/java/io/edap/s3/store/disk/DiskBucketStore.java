/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.store.disk;

import io.edap.s3.error.S3ErrorCode;
import io.edap.s3.error.S3Exception;
import io.edap.s3.store.BucketStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * 磁盘 {@link BucketStore} —— 一个桶 = {@code dataDir/<bucket>/} 目录。
 *
 * <p>目录存在 = 桶存在;不存在 = 桶不存在。
 * {@link #create} 用 {@code Files.createDirectory},原子失败(已存在)→
 * BUCKET_ALREADY_EXISTS。
 */
public final class DiskBucketStore implements BucketStore {

    private final Path root;

    public DiskBucketStore(Path root) throws IOException {
        this.root = root.toAbsolutePath();
        Files.createDirectories(this.root);
    }

    @Override
    public void create(String bucket) throws IOException {
        Path dir = bucketDir(bucket);
        try {
            Files.createDirectory(dir);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            throw new S3Exception(S3ErrorCode.BUCKET_ALREADY_OWNED_BY_YOU,
                    "Bucket already exists: " + bucket, "/" + bucket);
        }
    }

    @Override
    public void delete(String bucket) throws IOException {
        Path dir = bucketDir(bucket);
        if (!Files.exists(dir)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET,
                    "No such bucket: " + bucket, "/" + bucket);
        }
        // 非空检查交给 DeleteBucketHandler(它会调 ObjectStore.isEmpty)
        Files.delete(dir);
    }

    @Override
    public boolean exists(String bucket) throws IOException {
        return Files.exists(bucketDir(bucket));
    }

    @Override
    public List<String> list() throws IOException {
        if (!Files.exists(root)) return Collections.emptyList();
        List<String> names = new ArrayList<>();
        try (Stream<Path> stream = Files.list(root)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (Files.isDirectory(p)) {
                    Path fname = p.getFileName();
                    if (fname != null) names.add(fname.toString());
                }
            }
        }
        Collections.sort(names);
        return names;
    }

    Path bucketDir(String bucket) {
        return root.resolve(bucket);
    }
}