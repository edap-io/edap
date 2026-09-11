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
import io.edap.s3.model.GetStream;
import io.edap.s3.model.ObjectMeta;
import io.edap.s3.model.PutStream;
import io.edap.s3.store.BucketStore;
import io.edap.s3.store.ObjectStore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 磁盘 {@link ObjectStore} —— body 写到 {@code dataDir/<bucket>/<key>},
 * 元数据写到 {@code dataDir/<bucket>/<key>.meta}(由 {@link MetaFile} 序列化)。
 *
 * <p>put 流程:写到临时文件 {@code .tmp},算 MD5 后 rename 成正式名(原子替换),
 * 再写 .meta。失败时 .tmp 会残留,下次启动 / 首次访问时清理。
 *
 * <p>get Range:用 {@link RandomAccessFile#seek} + 跳过前 N 字节 → 切子流
 * (edap HttpResponse 无 chunked,handler 拿到 InputStream 后全 buffer 再写,
 * 但这里 backend 提供精准的 InputStream 字节段即可)。
 */
public final class DiskObjectStore implements ObjectStore {

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private final DiskBucketStore bucketStore;

    public DiskObjectStore(DiskBucketStore bucketStore) {
        this.bucketStore = bucketStore;
    }

    @Override
    public ObjectMeta put(String bucket, String key, PutStream body) throws IOException {
        requireBucket(bucket);
        if (body.contentLength() < 0) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "Content-Length required (chunked not supported)",
                    "/" + bucket + "/" + key);
        }
        Path bucketDir = bucketStore.bucketDir(bucket);
        Path dataFile = bucketDir.resolve(key);
        Path metaFile = bucketDir.resolve(key + ".meta");
        Path tmpFile = bucketDir.resolve(key + ".tmp");

        // S3 key 可含 "/" —— 需先建中间目录
        Path keyParent = dataFile.getParent();
        if (keyParent != null && !Files.isDirectory(keyParent)) {
            Files.createDirectories(keyParent);
        }

        // 写临时文件 + 算 MD5
        long total = 0;
        MessageDigest md5;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("MD5 algorithm unavailable", e);
        }
        try (java.io.OutputStream out = Files.newOutputStream(tmpFile)) {
            byte[] buf = new byte[8192];
            InputStream in = body.content();
            int n;
            while ((n = in.read(buf)) != -1) {
                md5.update(buf, 0, n);
                out.write(buf, 0, n);
                total += n;
            }
        }
        String etag = toHex(md5.digest());
        // 原子 rename
        try {
            Files.move(tmpFile, dataFile,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmpFile, dataFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Instant lm = Instant.now();
        ObjectMeta meta = new ObjectMeta(key, total, etag,
                body.contentType(), lm, body.userMetadata());
        MetaFile.write(metaFile, meta);
        return meta;
    }

    @Override
    public GetStream get(String bucket, String key, long rangeStart, long rangeEnd) throws IOException {
        requireBucket(bucket);
        Path dataFile = bucketStore.bucketDir(bucket).resolve(key);
        Path metaFile = bucketStore.bucketDir(bucket).resolve(key + ".meta");
        if (!Files.exists(dataFile) || !Files.exists(metaFile)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_KEY,
                    "No such key: " + bucket + "/" + key,
                    "/" + bucket + "/" + key);
        }
        ObjectMeta meta = MetaFile.read(metaFile, key);
        long size = meta.size();
        long start = Math.max(0, rangeStart);
        long end = rangeEnd < 0 ? size - 1 : Math.min(rangeEnd, size - 1);
        if (start >= size) {
            return new GetStream(new ByteArrayInputStream(new byte[0]), meta, start, end);
        }
        if (start > end) {
            return new GetStream(new ByteArrayInputStream(new byte[0]), meta, start, end);
        }
        long len = end - start + 1;
        InputStream slice = new RangeInputStream(dataFile, start, len);
        return new GetStream(slice, meta, start, end);
    }

    @Override
    public ObjectMeta head(String bucket, String key) throws IOException {
        requireBucket(bucket);
        Path metaFile = bucketStore.bucketDir(bucket).resolve(key + ".meta");
        if (!Files.exists(metaFile)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_KEY,
                    "No such key: " + bucket + "/" + key,
                    "/" + bucket + "/" + key);
        }
        return MetaFile.read(metaFile, key);
    }

    @Override
    public void delete(String bucket, String key) throws IOException {
        requireBucket(bucket);
        Path bucketDir = bucketStore.bucketDir(bucket);
        // S3 DELETE 是 idempotent:找不到不报错
        Files.deleteIfExists(bucketDir.resolve(key));
        Files.deleteIfExists(bucketDir.resolve(key + ".meta"));
        Files.deleteIfExists(bucketDir.resolve(key + ".tmp"));
    }

    @Override
    public List<ObjectMeta> list(String bucket,
                                 String prefix,
                                 String delimiter,
                                 int maxKeys,
                                 String continuationToken) throws IOException {
        requireBucket(bucket);
        if (prefix == null) prefix = "";
        Path bucketDir = bucketStore.bucketDir(bucket);
        if (!Files.exists(bucketDir)) {
            return Collections.emptyList();
        }
        List<ObjectMeta> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(bucketDir, "*.meta")) {
            for (Path metaFile : stream) {
                String fname = metaFile.getFileName().toString();
                if (!fname.endsWith(".meta")) continue;
                String objKey = fname.substring(0, fname.length() - ".meta".length());
                if (objKey.endsWith(".tmp")) continue;   // 跳过临时文件
                if (!objKey.startsWith(prefix)) continue;
                // 只列出有对应 data 文件的 meta(过滤 orphan meta)
                if (!Files.exists(bucketDir.resolve(objKey))) continue;
                ObjectMeta m = MetaFile.read(metaFile, objKey);
                result.add(m);
                if (maxKeys > 0 && result.size() >= maxKeys) break;
            }
        }
        result.sort((a, b) -> a.key().compareTo(b.key()));
        return result;
    }

    @Override
    public boolean isEmpty(String bucket) throws IOException {
        requireBucket(bucket);
        Path bucketDir = bucketStore.bucketDir(bucket);
        if (!Files.exists(bucketDir)) return true;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(bucketDir)) {
            return !stream.iterator().hasNext();
        }
    }

    @Override
    public void close() throws IOException {
        // no-op
    }

    private void requireBucket(String bucket) throws IOException {
        if (!bucketStore.exists(bucket)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET,
                    "No such bucket: " + bucket, "/" + bucket);
        }
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

    /**
     * 给定文件 + 起始偏移 + 长度,返回只读字节子流 —— 用 RandomAccessFile
     * 实现 seek + read,S3HttpHandler 拿到后整段缓冲再写出(Phase 1 无 chunked)。
     */
    private static final class RangeInputStream extends InputStream {
        private final RandomAccessFile raf;
        private final long length;
        private long pos = 0;

        RangeInputStream(Path file, long start, long length) throws IOException {
            this.raf = new RandomAccessFile(file.toFile(), "r");
            this.raf.seek(start);
            this.length = length;
        }

        @Override
        public int read() throws IOException {
            if (pos >= length) return -1;
            int b = raf.read();
            if (b >= 0) pos++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (pos >= length) return -1;
            long remain = length - pos;
            int toRead = (int) Math.min(len, remain);
            int n = raf.read(b, off, toRead);
            if (n > 0) pos += n;
            return n;
        }

        @Override
        public void close() throws IOException {
            raf.close();
        }
    }
}