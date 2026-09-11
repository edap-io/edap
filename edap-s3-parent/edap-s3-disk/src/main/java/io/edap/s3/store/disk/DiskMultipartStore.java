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
import io.edap.s3.model.CompletedObject;
import io.edap.s3.model.CompletedPart;
import io.edap.s3.model.MultipartPart;
import io.edap.s3.model.MultipartUpload;
import io.edap.s3.model.PutStream;
import io.edap.s3.store.BucketStore;
import io.edap.s3.store.MultipartStore;
import io.edap.s3.store.ObjectStore;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 磁盘 {@link MultipartStore} —— 布局见 {@link UploadMetaFile}。
 *
 * <p>状态机:
 * <ul>
 *   <li>ACTIVE —— {@code meta.json} 存在,uploadPart / listParts 可访问</li>
 *   <li>COMPLETING —— {@code meta.json} 被 atomic rename 成 {@code meta.json.completing},
 *       其他线程看到此标记 → {@code NO_SUCH_UPLOAD}(对齐 InMemory 的 uploads.remove)</li>
 *   <li>COMPLETED / ABORTED —— 整个 upload 目录已删除</li>
 * </ul>
 *
 * <p>并发:per-uploadId {@link ReentrantLock}(ConcurrentHashMap 持有)。
 * Phase 3 单进程 / 共享盘假设;跨进程并发不安全。</p>
 *
 * <p>流式:part bytes 走 {@link DigestInputStream} 边读边算 MD5;
 * complete 时用 {@link SequenceInputStream} 串各 part 流到 ObjectStore,
 * 堆上不缓存整对象,5GB upload 不 OOM。</p>
 */
public final class DiskMultipartStore implements MultipartStore {

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private final Path root;
    private final DiskBucketStore bucketStore;
    private final ObjectStore objectStore;

    /** per-uploadId 锁 —— 持状态机转换期间互斥 */
    private final ConcurrentMap<String, ReentrantLock> uploadLocks = new ConcurrentHashMap<>();

    public DiskMultipartStore(Path root,
                              DiskBucketStore bucketStore,
                              ObjectStore objectStore) {
        this.root = root;
        this.bucketStore = bucketStore;
        this.objectStore = objectStore;
    }

    /**
     * 启动期恢复 —— 见设计文档 §9。
     * 失败 log warn 跳过,不阻断启动。
     */
    public void startupRecovery() throws IOException {
        if (!Files.isDirectory(root)) return;
        try (DirectoryStream<Path> buckets = Files.newDirectoryStream(root)) {
            for (Path bucketDir : (Iterable<Path>) buckets::iterator) {
                if (!Files.isDirectory(bucketDir)) continue;
                String bucketName = bucketDir.getFileName().toString();
                if (bucketName.startsWith(".")) continue;
                Path mpDir = bucketDir.resolve(".multipart");
                if (!Files.isDirectory(mpDir)) continue;
                try (DirectoryStream<Path> uploads = Files.newDirectoryStream(mpDir)) {
                    for (Path uploadDir : (Iterable<Path>) uploads::iterator) {
                        if (Files.isDirectory(uploadDir)) {
                            try { recoverUploadDir(uploadDir); } catch (Exception ignored) {}
                        }
                    }
                }
            }
        }
    }

    // ===================== SPI 实现 =====================

    @Override
    public String initiate(String bucket,
                           String key,
                           String contentType,
                           Map<String, String> userMetadata) throws IOException {
        requireBucket(bucket);
        String uploadId = UUID.randomUUID().toString();
        Path uploadDir = multipartDir(bucket, uploadId);
        Files.createDirectories(uploadDir);
        UploadMetaFile.UploadMeta meta = new UploadMetaFile.UploadMeta();
        meta.uploadId = uploadId;
        meta.bucket = bucket;
        meta.key = key;
        meta.contentType = contentType == null ? "" : contentType;
        meta.initiated = Instant.now();
        meta.userMetadata = userMetadata == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(userMetadata);
        meta.parts = new LinkedHashMap<>();
        UploadMetaFile.writeAtomic(uploadDir, meta);
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
        Path uploadDir = locateUploadDir(uploadId);                  // NO_SUCH_UPLOAD if absent
        UploadMetaFile.UploadMeta meta = UploadMetaFile.read(uploadDir);

        // 写 part:tmp + atomic rename
        String partKey = String.format("%05d", partNumber);
        Path partFile = uploadDir.resolve(partKey + ".part");
        Path tmp = Files.createTempFile(uploadDir, ".part", ".tmp");
        long size;
        String etag;
        try {
            MessageDigest md5 = newMd5();
            try (InputStream in = new BufferedInputStream(body);
                 DigestInputStream dis = new DigestInputStream(in, md5);
                 OutputStream out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = dis.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
            }
            size = Files.size(tmp);
            etag = toHex(md5.digest());
        } catch (IOException | RuntimeException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw e;
        }
        // S3 spec: 同 partNumber 重传 last-write-wins
        Files.deleteIfExists(partFile);
        try {
            Files.move(tmp, partFile, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, partFile, StandardCopyOption.REPLACE_EXISTING);
        }

        // 更新 meta.json
        ReentrantLock lock = lockFor(uploadId);
        lock.lock();
        try {
            UploadMetaFile.UploadMeta current = UploadMetaFile.read(uploadDir);
            if (current == null) {
                // 并发 abort 把目录删了 —— 抛 NO_SUCH_UPLOAD(虽然我们刚写完 part)
                throw new S3Exception(S3ErrorCode.NO_SUCH_UPLOAD,
                        "No such upload: " + uploadId);
            }
            UploadMetaFile.PartMeta pm = new UploadMetaFile.PartMeta();
            pm.etag = etag;
            pm.size = size;
            pm.lastModified = Instant.now();
            current.parts.put(partKey, pm);
            UploadMetaFile.writeAtomic(uploadDir, current);
        } finally {
            lock.unlock();
        }
        return etag;
    }

    @Override
    public CompletedObject complete(String uploadId, List<CompletedPart> parts) throws IOException {
        Path uploadDir;
        UploadMetaFile.UploadMeta meta;
        ReentrantLock lock = lockFor(uploadId);
        lock.lock();
        try {
            uploadDir = locateUploadDir(uploadId);
            meta = UploadMetaFile.read(uploadDir);
            // 占所有权:meta.json → meta.json.completing(atomic rename)
            Path metaFile = uploadDir.resolve(UploadMetaFile.META_FILE);
            Path completing = uploadDir.resolve(UploadMetaFile.COMPLETING_MARKER);
            try {
                try {
                    Files.move(metaFile, completing, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(metaFile, completing);
                }
            } catch (NoSuchFileException e) {
                throw new S3Exception(S3ErrorCode.NO_SUCH_UPLOAD,
                        "No such upload: " + uploadId);
            }
        } finally {
            lock.unlock();
        }

        // 校验 parts 列表
        List<CompletedPart> ordered = new ArrayList<>(parts);
        ordered.sort((a, b) -> Integer.compare(a.partNumber(), b.partNumber()));
        long totalSize = 0;
        for (CompletedPart cp : ordered) {
            String pnKey = String.format("%05d", cp.partNumber());
            UploadMetaFile.PartMeta stored = meta.parts.get(pnKey);
            if (stored == null) {
                rollbackCompleting(uploadDir);
                throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                        "Part " + cp.partNumber() + " not uploaded");
            }
            if (!stored.etag.equals(cp.etag())) {
                rollbackCompleting(uploadDir);
                throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                        "Part " + cp.partNumber() + " ETag mismatch: stored="
                                + stored.etag + " declared=" + cp.etag());
            }
            totalSize += stored.size;
        }

        // 流式拼装:SequenceInputStream 串各 part 的 FileInputStream
        // 同时算复合 ETag(MD5(concat of part MD5 raw bytes))hex + "-" + count
        List<InputStream> partStreams = new ArrayList<>(ordered.size());
        MessageDigest concat = newMd5();
        for (CompletedPart cp : ordered) {
            String pnKey = String.format("%05d", cp.partNumber());
            UploadMetaFile.PartMeta pm = meta.parts.get(pnKey);
            concat.update(hexToBytes(pm.etag));
            Path partFile = uploadDir.resolve(pnKey + ".part");
            partStreams.add(new BufferedInputStream(Files.newInputStream(partFile)));
        }
        String finalEtag = toHex(concat.digest()) + "-" + ordered.size();

        InputStream body = new SequenceInputStream(Collections.enumeration(partStreams));
        PutStream put = new PutStream(new AutoCloseSequence(partStreams, body),
                meta.contentType.isEmpty() ? null : meta.contentType,
                meta.userMetadata,
                totalSize,
                null);
        try {
            objectStore.put(meta.bucket, meta.key, put);
        } catch (IOException | RuntimeException e) {
            // put 失败:保留 .completing 等下次启动恢复 / 客户端重试
            throw e;
        }

        // 成功:删整目录(包含 .completing + 所有 .part)
        deleteRecursive(uploadDir);

        return new CompletedObject(meta.bucket, meta.key, finalEtag, totalSize);
    }

    @Override
    public void abort(String uploadId) throws IOException {
        Path uploadDir = locateUploadDir(uploadId);
        deleteRecursive(uploadDir);
    }

    @Override
    public List<MultipartUpload> listUploads(String bucket) throws IOException {
        requireBucket(bucket);
        Path mpDir = multipartDir(bucket, "");
        if (!Files.isDirectory(mpDir)) {
            return Collections.emptyList();
        }
        List<MultipartUpload> result = new ArrayList<>();
        try (DirectoryStream<Path> children = Files.newDirectoryStream(mpDir)) {
            for (Path uploadDir : (Iterable<Path>) children::iterator) {
                if (!Files.isDirectory(uploadDir)) continue;
                UploadMetaFile.UploadMeta meta = UploadMetaFile.read(uploadDir);
                if (meta == null) continue;            // 孤儿跳过(启动恢复还没清)
                Map<String, String> um = meta.userMetadata == null
                        ? Collections.emptyMap() : meta.userMetadata;
                MultipartUpload mu = new MultipartUpload(
                        meta.uploadId, meta.bucket, meta.key,
                        meta.contentType,
                        meta.initiated == null ? Instant.now() : meta.initiated,
                        um);
                result.add(mu);
            }
        }
        result.sort(Comparator.comparing(MultipartUpload::initiated));
        return result;
    }

    @Override
    public List<MultipartPart> listParts(String uploadId) throws IOException {
        Path uploadDir = locateUploadDir(uploadId);
        UploadMetaFile.UploadMeta meta = UploadMetaFile.read(uploadDir);
        if (meta == null) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_UPLOAD,
                    "No such upload: " + uploadId);
        }
        List<MultipartPart> result = new ArrayList<>();
        if (meta.parts != null) {
            for (Map.Entry<String, UploadMetaFile.PartMeta> e : meta.parts.entrySet()) {
                try {
                    int pn = Integer.parseInt(e.getKey());
                    UploadMetaFile.PartMeta pm = e.getValue();
                    // body=null —— listParts 不返回 body,跟 InMemory 行为对齐
                    result.add(new MultipartPart(pn, pm.etag, pm.size,
                            pm.lastModified == null ? Instant.now() : pm.lastModified,
                            null));
                } catch (NumberFormatException ignored) {}
            }
        }
        result.sort((a, b) -> Integer.compare(a.partNumber(), b.partNumber()));
        return result;
    }

    @Override
    public void close() throws IOException {
        uploadLocks.clear();
    }

    // ===================== 启动恢复 =====================

    private void recoverUploadDir(Path uploadDir) throws IOException {
        // 1. 清残留 .tmp
        try (DirectoryStream<Path> files = Files.newDirectoryStream(uploadDir, "*.tmp")) {
            for (Path tmp : (Iterable<Path>) files::iterator) {
                Files.deleteIfExists(tmp);
            }
        }
        Path metaFile = uploadDir.resolve(UploadMetaFile.META_FILE);
        Path completingFile = uploadDir.resolve(UploadMetaFile.COMPLETING_MARKER);

        // 2. meta.json 不存在 → 检查 .completing(meta.json rename 过去,内容完整)
        if (!Files.isRegularFile(metaFile)) {
            if (Files.isRegularFile(completingFile)) {
                // .completing 存在 = complete() 跑到 rename 之后
                //   a) ObjectStore 已有 key → complete.put() 已成功,只是没清目录 → 删孤儿
                //   b) ObjectStore 没 key  → complete.put() 没跑到就崩了 → 还原 meta.json 让重试
                UploadMetaFile.UploadMeta cm = UploadMetaFile.readFrom(completingFile);
                if (cm != null && cm.bucket != null && cm.key != null) {
                    try {
                        objectStore.head(cm.bucket, cm.key);
                        deleteRecursive(uploadDir);
                        return;
                    } catch (S3Exception e) {
                        if (e.code() != S3ErrorCode.NO_SUCH_KEY) {
                            return;       // 其他错误,跳过本次恢复
                        }
                        // NO_SUCH_KEY 走下面还原路径
                    }
                }
                Files.move(completingFile, metaFile);
            } else {
                // 纯孤儿目录
                deleteRecursive(uploadDir);
            }
            return;
        }

        // 3. meta.json 存在,清 meta.parts 没记录的 .part 孤儿
        UploadMetaFile.UploadMeta meta = UploadMetaFile.read(uploadDir);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(uploadDir, "*.part")) {
            for (Path partFile : (Iterable<Path>) files::iterator) {
                String name = partFile.getFileName().toString();
                // 形如 00001.part
                if (name.length() != 9 || !name.endsWith(".part")) continue;
                String pnKey = name.substring(0, 5);
                if (meta == null || !meta.parts.containsKey(pnKey)) {
                    Files.deleteIfExists(partFile);
                }
            }
        }

        // 4. 反查 ObjectStore:key 已存在 → 完成过但目录没清
        if (meta != null) {
            try {
                objectStore.head(meta.bucket, meta.key);
                deleteRecursive(uploadDir);
                return;
            } catch (S3Exception e) {
                if (e.code() != S3ErrorCode.NO_SUCH_KEY) {
                    // 其他错误跳过(网络/权限),保留 uploadDir 不动
                    return;
                }
                // key 不存在 = 上传未完成,保留
            }
        }

        // 5. .completing 残留 + meta.json 也在(恢复没把它移走)→ 把 .completing 还原
        if (Files.isRegularFile(completingFile) && Files.isRegularFile(metaFile)) {
            Files.move(completingFile, metaFile);
        }
    }

    // ===================== 路径 / IO helpers =====================

    private Path multipartDir(String bucket, String uploadId) {
        Path bucketDir = bucketStore.bucketDir(bucket);
        Path mpDir = bucketDir.resolve(".multipart");
        return uploadId.isEmpty() ? mpDir : mpDir.resolve(uploadId);
    }

    /**
     * 解析 uploadId → upload 目录。{@code .completing} 标记视为不存在(NO_SUCH_UPLOAD)。
     */
    private Path locateUploadDir(String uploadId) throws IOException {
        // Phase 3 简化:遍历桶目录查找 uploadId —— O(桶数 × upload 数) 最坏;
        // 后续可优化:per-bucket index
        try (DirectoryStream<Path> buckets = Files.newDirectoryStream(root)) {
            for (Path bucketDir : (Iterable<Path>) buckets::iterator) {
                String bn = bucketDir.getFileName().toString();
                if (bn.startsWith(".")) continue;
                Path mpDir = bucketDir.resolve(".multipart");
                if (!Files.isDirectory(mpDir)) continue;
                Path target = mpDir.resolve(uploadId);
                if (!Files.isDirectory(target)) continue;
                // .completing 存在 = 已被另一个 complete 占走
                if (Files.isRegularFile(target.resolve(UploadMetaFile.COMPLETING_MARKER))) {
                    throw new S3Exception(S3ErrorCode.NO_SUCH_UPLOAD,
                            "No such upload: " + uploadId);
                }
                return target;
            }
        }
        throw new S3Exception(S3ErrorCode.NO_SUCH_UPLOAD,
                "No such upload: " + uploadId);
    }

    private void rollbackCompleting(Path uploadDir) throws IOException {
        Path completing = uploadDir.resolve(UploadMetaFile.COMPLETING_MARKER);
        Path metaFile = uploadDir.resolve(UploadMetaFile.META_FILE);
        if (Files.isRegularFile(completing)) {
            Files.move(completing, metaFile);
        }
    }

    private void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        List<Path> all = new ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.forEach(all::add);
        }
        all.sort(Comparator.reverseOrder());
        for (Path p : all) {
            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
        }
    }

    private void requireBucket(String bucket) throws IOException {
        if (!bucketStore.exists(bucket)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET,
                    "No such bucket: " + bucket, "/" + bucket);
        }
    }

    private ReentrantLock lockFor(String uploadId) {
        return uploadLocks.computeIfAbsent(uploadId, k -> new ReentrantLock());
    }

    private static MessageDigest newMd5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }

    private static String toHex(byte[] data) {
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xff;
            out[i * 2]     = HEX_CHARS[v >>> 4];
            out[i * 2 + 1] = HEX_CHARS[v & 0x0f];
        }
        return new String(out);
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(hex.charAt(i), 16);
            int lo = Character.digit(hex.charAt(i + 1), 16);
            out[i / 2] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /**
     * SequenceInputStream 不传播 close 到子流 —— close 时手动关每个 part 流。
     */
    private static final class AutoCloseSequence extends InputStream {
        private final List<InputStream> parts;
        private final InputStream delegate;

        AutoCloseSequence(List<InputStream> parts, InputStream delegate) {
            this.parts = parts;
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            return delegate.read(buf, off, len);
        }

        @Override
        public void close() throws IOException {
            IOException first = null;
            try {
                delegate.close();
            } catch (IOException e) {
                first = e;
            }
            for (InputStream s : parts) {
                try {
                    s.close();
                } catch (IOException e) {
                    if (first == null) first = e;
                }
            }
            if (first != null) throw first;
        }
    }
}
