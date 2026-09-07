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
import io.edap.s3.model.GetStream;
import io.edap.s3.model.ObjectMeta;
import io.edap.s3.model.PutStream;
import io.edap.s3.store.BucketStore;
import io.edap.s3.store.ObjectStore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存 {@link ObjectStore} —— body 用 {@code byte[]} 缓存,元数据用
 * {@link ConcurrentMap} 存。桶维度由 {@link BucketStore} 单独管,
 * 这里只检查"对象所在桶是否存在",防止凭空在 bucket=null 写入。
 *
 * <p>线程安全:每个桶一把 ConcurrentMap,put/get/delete 各自无锁。
 *
 * <p>仅用于单测 / 本地开发;生产用 {@code DiskObjectStore}。
 *
 * <p><b>内存上限</b>:所有对象 body 都堆内,JVM heap 撑不住大对象;
 * 上 GB 量级直接 OOM。Phase 1 测试用例限 1MB 以内。
 */
public class InMemoryObjectStore implements ObjectStore {

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private final BucketStore bucketStore;
    private final ConcurrentMap<String, ConcurrentMap<String, StoredObject>> store
            = new ConcurrentHashMap<>();

    public InMemoryObjectStore(BucketStore bucketStore) {
        this.bucketStore = bucketStore;
    }

    @Override
    public ObjectMeta put(String bucket, String key, PutStream body) throws IOException {
        requireBucket(bucket);
        if (body.contentLength() < 0) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "Content-Length required for in-memory put (chunked not supported)", "/" + bucket + "/" + key);
        }
        // 流式读 body 到 byte[],同时算 MD5
        byte[] data = new byte[(int) body.contentLength()];
        int total = 0;
        MessageDigest md5;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("MD5 algorithm unavailable", e);
        }
        java.io.InputStream in = body.content();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            md5.update(buf, 0, n);
            if (total + n > data.length) {
                // 客户端声明 Content-Length 跟实际不符 —— 截断处理
                int allowed = data.length - total;
                if (allowed > 0) {
                    System.arraycopy(buf, 0, data, total, allowed);
                    total += allowed;
                }
                break;
            }
            System.arraycopy(buf, 0, data, total, n);
            total += n;
        }
        String etag = toHex(md5.digest());
        Instant now = Instant.now();
        ObjectMeta meta = new ObjectMeta(
                key, total, etag,
                body.contentType(),
                now,
                body.userMetadata());
        StoredObject obj = new StoredObject(data, meta);
        storeForBucket(bucket).put(key, obj);
        return meta;
    }

    @Override
    public GetStream get(String bucket, String key, long rangeStart, long rangeEnd) throws IOException {
        requireBucket(bucket);
        StoredObject obj = storeForBucket(bucket).get(key);
        if (obj == null) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_KEY,
                    "No such key: " + bucket + "/" + key, "/" + bucket + "/" + key);
        }
        long size = obj.meta.size();
        long start = Math.max(0, rangeStart);
        // rangeEnd == -1 表示"读到末尾",GetStream.isPartial() 据此判断全量/部分
        long end = rangeEnd < 0 ? -1 : Math.min(rangeEnd, size - 1);
        if (end >= 0 && (start > end || start >= size)) {
            // 越界:返回空 body
            return new GetStream(new ByteArrayInputStream(new byte[0]), obj.meta, start, end);
        }
        int len = end < 0 ? (int) size : (int) (end - start + 1);
        byte[] slice = new byte[len];
        if (len > 0) System.arraycopy(obj.data, (int) start, slice, 0, len);
        return new GetStream(new ByteArrayInputStream(slice), obj.meta, start, end);
    }

    @Override
    public ObjectMeta head(String bucket, String key) throws IOException {
        requireBucket(bucket);
        StoredObject obj = storeForBucket(bucket).get(key);
        if (obj == null) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_KEY,
                    "No such key: " + bucket + "/" + key, "/" + bucket + "/" + key);
        }
        return obj.meta;
    }

    @Override
    public void delete(String bucket, String key) throws IOException {
        requireBucket(bucket);
        storeForBucket(bucket).remove(key);   // S3 DELETE 是 idempotent
    }

    @Override
    public List<ObjectMeta> list(String bucket,
                                 String prefix,
                                 String delimiter,
                                 int maxKeys,
                                 String continuationToken) throws IOException {
        requireBucket(bucket);
        if (delimiter != null && !delimiter.isEmpty()) {
            // Phase 1 简化:不支持 CommonPrefixes 分组,按 prefix 全量返回
        }
        List<ObjectMeta> result = new ArrayList<>();
        ConcurrentMap<String, StoredObject> map = storeForBucket(bucket);
        for (Map.Entry<String, StoredObject> e : map.entrySet()) {
            if (prefix != null && !prefix.isEmpty() && !e.getKey().startsWith(prefix)) {
                continue;
            }
            result.add(e.getValue().meta);
            if (maxKeys > 0 && result.size() >= maxKeys) {
                break;
            }
        }
        // S3 列表按 key 字典序
        result.sort((a, b) -> a.key().compareTo(b.key()));
        return result;
    }

    @Override
    public boolean isEmpty(String bucket) throws IOException {
        requireBucket(bucket);
        return storeForBucket(bucket).isEmpty();
    }

    @Override
    public void close() throws IOException {
        store.clear();
    }

    private void requireBucket(String bucket) throws IOException {
        if (!bucketStore.exists(bucket)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET,
                    "No such bucket: " + bucket, "/" + bucket);
        }
    }

    private ConcurrentMap<String, StoredObject> storeForBucket(String bucket) {
        return store.computeIfAbsent(bucket, k -> new ConcurrentHashMap<>());
    }

    private static String toHex(byte[] data) {
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xff;
            out[i * 2] = HEX_CHARS[v >>> 4];
            out[i * 2 + 1] = HEX_CHARS[v & 0x0f];
        }
        return new String(out);
    }

    private static final class StoredObject {
        final byte[] data;
        final ObjectMeta meta;
        StoredObject(byte[] data, ObjectMeta meta) {
            this.data = data;
            this.meta = meta;
        }
    }
}
