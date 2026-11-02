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
import io.edap.s3.model.ListOptions;
import io.edap.s3.model.ListResult;
import io.edap.s3.model.ObjectMeta;
import io.edap.s3.model.PutStream;
import io.edap.s3.store.BucketStore;
import io.edap.s3.store.ObjectStore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 磁盘 {@link ObjectStore} —— body 写到 {@code dataDir/<bucket>/<key>},
 * 元数据写到 <b>per-bucket 的索引 log</b>{@code dataDir/<bucket>/.index/log}。
 *
 * <p><b>元数据唯一真源 = 索引 log</b>(不再是每对象一个 {@code .meta} sidecar)。
 * 启动时把 log 回放成一张按键序排列的内存表(见 {@link BucketIndex}),
 * 于是 LIST / HEAD 都是纯内存操作 —— 不再全目录扫描 + 逐个读 sidecar。
 *
 * <p><b>崩溃一致性:索引是提交点</b>。
 * <ul>
 *   <li>PUT:body 落盘(temporary → atomic rename)→ <b>append 索引</b> → 更新内存表。
 *       崩溃在两步之间 ⇒ 只留「body 有、索引无」的孤儿(LIST 看不到、GET 404,
 *       语义自洽)。</li>
 *   <li>DELETE:<b>先 append DELETE 索引</b> → 再删 body。崩溃 ⇒ 只留孤儿 body
 *       (占空间,不影响正确性)。</li>
 * </ul>
 *
 * <p><b>不 fsync</b>(与改造前一致):只保证进程崩溃一致,不保证机器掉电一致。
 * {@link #DiskObjectStore(DiskBucketStore, boolean)} 可以打开 fsync。
 *
 * <p>老桶(只有 {@code <key>.meta} sidecar、没有 {@code .index/log})在首次访问
 * 时自动迁移 —— 一次性把 sidecar 转成索引 log,<b>sidecar 原样保留</b>(回滚保险)。
 */
public final class DiskObjectStore implements ObjectStore {

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    /** 索引 log 相对桶目录的路径。 */
    private static final String INDEX_LOG = ".index/log";

    /** 保留前缀 —— 这些名字被桶目录自己占用,用户 key 不许用。 */
    private static final List<String> RESERVED_PREFIXES =
            List.of(".index/", ".multipart/", ".acl/");
    private static final List<String> RESERVED_NAMES =
            List.of(".acl", ".index", ".multipart");

    private final DiskBucketStore bucketStore;
    /** bucket → 内存索引。 */
    private final ConcurrentMap<String, BucketIndex> indexes = new ConcurrentHashMap<>();
    /** 只在「首次为某桶建索引」时持有(建索引要读盘)。 */
    private final Object indexLock = new Object();
    private final boolean fsyncOnWrite;

    public DiskObjectStore(DiskBucketStore bucketStore) {
        this(bucketStore, false);
    }

    public DiskObjectStore(DiskBucketStore bucketStore, boolean fsyncOnWrite) {
        this.bucketStore = bucketStore;
        this.fsyncOnWrite = fsyncOnWrite;
    }

    // ================= 写 =================

    @Override
    public ObjectMeta put(String bucket, String key, PutStream body) throws IOException {
        requireBucket(bucket);
        checkKeyWritable(bucket, key);
        if (body.contentLength() < 0) {
            throw new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                    "Content-Length required (chunked not supported)",
                    "/" + bucket + "/" + key);
        }
        Path bucketDir = bucketStore.bucketDir(bucket);
        Path dataFile = bucketDir.resolve(key);
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
        try (OutputStream out = Files.newOutputStream(tmpFile)) {
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
        if (fsyncOnWrite) {
            forceFile(tmpFile);
        }
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
        // ★ 提交点:先写索引 log,再更新内存表(两者在同一把锁内)
        indexFor(bucket).put(meta);
        return meta;
    }

    // ================= 读 =================

    @Override
    public GetStream get(String bucket, String key, long rangeStart, long rangeEnd) throws IOException {
        requireBucket(bucket);
        checkKeyReadable(bucket, key);
        BucketIndex index = indexFor(bucket);
        ObjectMeta meta = index.head(key);
        if (meta == null) {
            throw noSuchKey(bucket, key);
        }
        Path dataFile = bucketStore.bucketDir(bucket).resolve(key);
        if (!Files.exists(dataFile)) {
            // 索引说有、body 没有(有人手工删了 body)⇒ 自愈:把条目从索引里摘掉
            index.delete(key);
            throw noSuchKey(bucket, key);
        }
        long size = meta.size();
        long start = Math.max(0, rangeStart);
        long end = rangeEnd < 0 ? size - 1 : Math.min(rangeEnd, size - 1);
        if (start >= size || start > end) {
            // 越界:返回空 body
            return new GetStream(new ByteArrayInputStream(new byte[0]), meta, start, end);
        }
        long len = end - start + 1;
        InputStream slice = new RangeInputStream(dataFile, start, len);
        return new GetStream(slice, meta, start, end);
    }

    @Override
    public ObjectMeta head(String bucket, String key) throws IOException {
        requireBucket(bucket);
        checkKeyReadable(bucket, key);
        ObjectMeta meta = indexFor(bucket).head(key);
        if (meta == null) {
            throw noSuchKey(bucket, key);
        }
        return meta;
    }

    // ================= 删 =================

    @Override
    public void delete(String bucket, String key) throws IOException {
        requireBucket(bucket);
        checkKeyWritable(bucket, key);
        // ★ 先提交索引(对象消失),再删 body —— 崩溃最多留孤儿 body
        indexFor(bucket).delete(key);
        Path bucketDir = bucketStore.bucketDir(bucket);
        Path dataFile = bucketDir.resolve(key);
        Files.deleteIfExists(dataFile);
        Files.deleteIfExists(bucketDir.resolve(key + ".meta"));   // 老布局 sidecar 顺手清
        Files.deleteIfExists(bucketDir.resolve(key + ".tmp"));
        pruneEmptyParents(bucketDir, dataFile);
    }

    // ================= 列 =================

    @Override
    public ListResult list(String bucket, ListOptions options) throws IOException {
        requireBucket(bucket);
        return indexFor(bucket).list(options);
    }

    @Override
    public boolean isEmpty(String bucket) throws IOException {
        requireBucket(bucket);
        // 索引里没有对象 ⇒ 桶空。天然排除 .acl / .index / .multipart / 残留空目录 /
        // 孤儿 body —— 这正是老实现永远删不掉桶的原因。
        return indexFor(bucket).isEmpty();
    }

    // ================= 生命周期 =================

    @Override
    public void init() throws IOException {
        for (String bucket : bucketStore.list()) {
            indexFor(bucket);
        }
    }

    @Override
    public void forgetBucket(String bucket) {
        BucketIndex idx = indexes.remove(bucket);
        if (idx != null) {
            try {
                idx.close();
            } catch (IOException ignore) {
                // 关闭失败不影响删桶
            }
        }
    }

    @Override
    public void close() throws IOException {
        IOException first = null;
        for (BucketIndex idx : indexes.values()) {
            try {
                idx.close();
            } catch (IOException e) {
                if (first == null) first = e;
            }
        }
        indexes.clear();
        if (first != null) throw first;
    }

    // ================= 索引加载 =================

    private BucketIndex indexFor(String bucket) throws IOException {
        BucketIndex idx = indexes.get(bucket);
        if (idx != null) return idx;
        synchronized (indexLock) {
            idx = indexes.get(bucket);
            if (idx == null) {
                idx = openIndex(bucket);
                indexes.put(bucket, idx);
            }
            return idx;
        }
    }

    /**
     * 打开桶的索引 —— 有 log 就回放,没有就迁移老布局。
     * log 损坏(magic 不对)⇒ 归档成 {@code log.corrupt-<ts>} 并从 body 重建。
     */
    private BucketIndex openIndex(String bucket) throws IOException {
        Path bucketDir = bucketStore.bucketDir(bucket);
        Path logPath = bucketDir.resolve(INDEX_LOG);
        if (Files.exists(logPath)) {
            try {
                return BucketIndex.load(IndexLog.open(logPath, fsyncOnWrite));
            } catch (IndexLog.CorruptException e) {
                archiveCorrupt(logPath);
                return openIndexRebuild(bucketDir, logPath);
            }
        }
        // 没有 log(全新桶 / 老桶)⇒ 迁移老 .meta sidecar
        List<IndexRecord> records = LegacyMetaMigrator.collect(bucketDir);
        LegacyMetaMigrator.writeLog(logPath, records);
        return BucketIndex.load(IndexLog.open(logPath, fsyncOnWrite));
    }

    /**
     * 索引 log 整体损坏时的恢复 —— 归档损坏文件,并从桶目录里的 body 文件重建
     * (etag 现算 MD5)。老 sidecar 不作数(可能已经过时)。
     */
    private BucketIndex openIndexRebuild(Path bucketDir, Path logPath) throws IOException {
        List<IndexRecord> records = LegacyMetaMigrator.rebuildFromBodies(bucketDir);
        LegacyMetaMigrator.writeLog(logPath, records);
        return BucketIndex.load(IndexLog.open(logPath, fsyncOnWrite));
    }

    private void archiveCorrupt(Path logPath) throws IOException {
        Path parent = logPath.getParent();
        Path archived = (parent == null ? logPath : parent)
                .resolve("log.corrupt-" + System.currentTimeMillis());
        Files.move(logPath, archived);
    }

    // ================= key 校验 =================

    /**
     * 写路径的 key 校验 —— 保留名直接拒(INVALID_ARGUMENT)。
     */
    static void checkKeyWritable(String bucket, String key) {
        checkKeyCommon(bucket, key);
        for (String p : RESERVED_PREFIXES) {
            if (key.startsWith(p)) {
                throw invalidKey(bucket, key, "reserved prefix: " + p);
            }
        }
        for (String n : RESERVED_NAMES) {
            if (key.equals(n)) {
                throw invalidKey(bucket, key, "reserved name: " + n);
            }
        }
    }

    /**
     * 读路径的 key 校验 —— 保留名报 NO_SUCH_KEY 而不是 INVALID_ARGUMENT,
     * 免得 {@code GET /bucket/.index/log} 泄露整个索引。
     */
    static void checkKeyReadable(String bucket, String key) {
        try {
            checkKeyWritable(bucket, key);
        } catch (S3Exception e) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_KEY,
                    "No such key: " + bucket + "/" + key, "/" + bucket + "/" + key);
        }
    }

    private static void checkKeyCommon(String bucket, String key) {
        if (key == null || key.isEmpty()) {
            throw invalidKey(bucket, key, "key must not be empty");
        }
        // key 会被拼到桶目录后面 ⇒ 绝对不能让它逃出桶目录
        if (key.startsWith("/")) {
            throw invalidKey(bucket, key, "key must be relative");
        }
        for (String seg : key.split("/", -1)) {
            if (seg.equals("..")) {
                throw invalidKey(bucket, key, "key must not contain '..'");
            }
        }
    }

    private static S3Exception invalidKey(String bucket, String key, String why) {
        return new S3Exception(S3ErrorCode.INVALID_ARGUMENT,
                "Invalid object key: " + why, "/" + bucket + "/" + key);
    }

    // ================= 工具 =================

    private void requireBucket(String bucket) throws IOException {
        if (!bucketStore.exists(bucket)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_BUCKET,
                    "No such bucket: " + bucket, "/" + bucket);
        }
    }

    private static S3Exception noSuchKey(String bucket, String key) {
        return new S3Exception(S3ErrorCode.NO_SUCH_KEY,
                "No such key: " + bucket + "/" + key, "/" + bucket + "/" + key);
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

    private static void forceFile(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.WRITE)) {
            ch.force(true);
        }
    }

    /**
     * 删完 body 后清掉变空的父目录(止步于桶目录)—— S3 语义里目录不存在。
     */
    private static void pruneEmptyParents(Path bucketDir, Path file) {
        Path parent = file.getParent();
        while (parent != null && !parent.equals(bucketDir) && parent.startsWith(bucketDir)) {
            try {
                Files.delete(parent);
            } catch (IOException e) {
                return;      // 目录非空(或删不掉)⇒ 停
            }
            parent = parent.getParent();
        }
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
