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
import io.edap.s3.model.CompletedObject;
import io.edap.s3.model.CompletedPart;
import io.edap.s3.model.MultipartPart;
import io.edap.s3.model.MultipartUpload;
import io.edap.s3.model.PutStream;
import io.edap.s3.store.BucketStore;
import io.edap.s3.store.MultipartStore;
import io.edap.s3.store.ObjectStore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存 {@link MultipartStore} —— uploadId → MultipartUpload 用 ConcurrentHashMap,
 * 每个 upload 内部 parts 用 ConcurrentSkipListMap(partNumber → MultipartPart)。
 *
 * <p>跟 Phase 1 {@link InMemoryObjectStore} 同风格:无锁,无快照层,无全局状态。
 *
 * <p>complete() 算法:
 * <ol>
 *   <li>{@code uploads.remove(uploadId)} 原子取走整个 MultipartUpload
 *       —— 后续并发 uploadPart / abort 拿到 null → 抛 NO_SUCH_UPLOAD</li>
 *   <li>按 partNumber 升序遍历 parts,拼接 body bytes</li>
 *   <li>拼接每 part 的 MD5 raw bytes(从 hex 解码),对整体算 MD5,
 *       hex + "-" + partCount → 最终 ETag(S3 规范)</li>
 *   <li>用 {@link PutStream} 包好 final bytes,
 *       调 {@link ObjectStore#put} 持久化</li>
 *   <li>返回 {@link CompletedObject}</li>
 * </ol>
 *
 * <p><b>内存上限</b>:跟 InMemoryObjectStore 同 —— 所有 part bytes 都堆内,
 * 单 upload 内 part 总大小超过 1GB 直接 OOM。Phase 2 测试用例限 16MB 以内。
 */
public class InMemoryMultipartStore implements MultipartStore {

    private final BucketStore bucketStore;
    private final ObjectStore objectStore;
    private final ConcurrentMap<String, MultipartUpload> uploads = new ConcurrentHashMap<>();

    public InMemoryMultipartStore(BucketStore bucketStore, ObjectStore objectStore) {
        this.bucketStore = bucketStore;
        this.objectStore = objectStore;
    }

    @Override
    public String initiate(String bucket,
                           String key,
                           String contentType,
                           Map<String, String> userMetadata) throws IOException {
        requireBucket(bucket);
        String uploadId = UUID.randomUUID().toString();
        uploads.put(uploadId, new MultipartUpload(
                uploadId, bucket, key, contentType, Instant.now(), userMetadata));
        return uploadId;
    }

    @Override
    public String uploadPart(String uploadId,
                             int partNumber,
                             InputStream body,
                             long contentLength) throws IOException {
        if (partNumber < 1 || partNumber > 10000) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "Part number must be in [1, 10000], got " + partNumber);
        }
        if (contentLength < 0) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "Content-Length required for UploadPart (chunked not supported)");
        }
        MultipartUpload upload = uploads.get(uploadId);
        if (upload == null) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_UPLOAD,
                    "No such upload: " + uploadId);
        }

        // 流式读 body 到 byte[],同时算 MD5
        byte[] data = new byte[(int) contentLength];
        int total = 0;
        MessageDigest md5 = newMd5();
        byte[] buf = new byte[8192];
        int n;
        while ((n = body.read(buf)) != -1) {
            md5.update(buf, 0, n);
            int allowed = Math.min(n, data.length - total);
            if (allowed > 0) {
                System.arraycopy(buf, 0, data, total, allowed);
                total += allowed;
            }
            if (total >= data.length) break;
        }
        String etag = HexFormat.of().formatHex(md5.digest());
        MultipartPart part = new MultipartPart(
                partNumber, etag, total, Instant.now(), data);
        upload.addPart(part);
        return etag;
    }

    @Override
    public CompletedObject complete(String uploadId, List<CompletedPart> parts) throws IOException {
        // 原子取走 —— 后续并发 uploadPart/abort 拿到 null → NO_SUCH_UPLOAD
        MultipartUpload upload = uploads.remove(uploadId);
        if (upload == null) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_UPLOAD,
                    "No such upload: " + uploadId);
        }

        // 客户端声明的 parts 可能乱序,按 partNumber 升序排
        List<CompletedPart> ordered = new ArrayList<>(parts);
        ordered.sort((a, b) -> Integer.compare(a.partNumber(), b.partNumber()));

        // 1. 按声明顺序拼接 body bytes
        // 2. 同时收集每 part 的 raw MD5 bytes 算最终复合 ETag
        long totalSize = 0;
        // 单 upload body 超过 Integer.MAX_VALUE(~2GB)直接 OOM,Phase 2 测试远小于此
        int totalSizeInt = (int) computeTotalSize(upload, ordered);
        byte[] finalBytes = new byte[totalSizeInt];
        int offset = 0;
        MessageDigest concat = newMd5();
        for (CompletedPart cp : ordered) {
            MultipartPart stored = upload.parts().get(cp.partNumber());
            if (stored == null) {
                throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                        "Part " + cp.partNumber() + " not uploaded");
            }
            if (!stored.etag().equals(cp.etag())) {
                throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                        "Part " + cp.partNumber() + " ETag mismatch: stored="
                                + stored.etag() + " declared=" + cp.etag());
            }
            byte[] body = stored.body();
            System.arraycopy(body, 0, finalBytes, offset, body.length);
            offset += body.length;
            totalSize += body.length;
            concat.update(HexFormat.of().parseHex(stored.etag()));
        }

        String finalEtag = HexFormat.of().formatHex(concat.digest())
                + "-" + ordered.size();

        // 持久化到 ObjectStore
        PutStream put = new PutStream(
                new ByteArrayInputStream(finalBytes),
                upload.contentType(),
                upload.userMetadata(),
                totalSize,
                null);
        objectStore.put(upload.bucket(), upload.key(), put);

        return new CompletedObject(upload.bucket(), upload.key(), finalEtag, totalSize);
    }

    @Override
    public void abort(String uploadId) throws IOException {
        MultipartUpload removed = uploads.remove(uploadId);
        if (removed == null) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_UPLOAD,
                    "No such upload: " + uploadId);
        }
        // removed 整张 parts 表(ConcurrentSkipListMap)随引用消失即可 GC
    }

    @Override
    public List<MultipartUpload> listUploads(String bucket) throws IOException {
        requireBucket(bucket);
        List<MultipartUpload> result = new ArrayList<>();
        for (MultipartUpload u : uploads.values()) {
            if (u.bucket().equals(bucket)) result.add(u);
        }
        result.sort((a, b) -> a.initiated().compareTo(b.initiated()));
        return result;
    }

    @Override
    public List<MultipartPart> listParts(String uploadId) throws IOException {
        MultipartUpload upload = uploads.get(uploadId);
        if (upload == null) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_UPLOAD,
                    "No such upload: " + uploadId);
        }
        List<MultipartPart> result = new ArrayList<>(upload.parts().values());
        result.sort((a, b) -> Integer.compare(a.partNumber(), b.partNumber()));
        return result;
    }

    @Override
    public void close() throws IOException {
        uploads.clear();
    }

    // ===================== helpers =====================

    private void requireBucket(String bucket) throws IOException {
        if (!bucketStore.exists(bucket)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET,
                    "No such bucket: " + bucket, "/" + bucket);
        }
    }

    private static long computeTotalSize(MultipartUpload upload, List<CompletedPart> ordered) {
        long total = 0;
        for (CompletedPart cp : ordered) {
            MultipartPart p = upload.parts().get(cp.partNumber());
            if (p != null) total += p.size();
        }
        return total;
    }

    private static MessageDigest newMd5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    // 暴露给测试用 —— 列所有 uploads(忽略 bucket 过滤)
    Map<String, MultipartUpload> uploadsForTest() {
        return Collections.unmodifiableMap(uploads);
    }
}
