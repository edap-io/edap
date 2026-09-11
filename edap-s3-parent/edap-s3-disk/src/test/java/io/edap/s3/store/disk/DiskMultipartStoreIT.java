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
import io.edap.s3.model.GetStream;
import io.edap.s3.model.MultipartPart;
import io.edap.s3.model.ObjectMeta;
import io.edap.s3.model.PutStream;
import io.edap.s3.store.ObjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DiskMultipartStore} 集成测试 —— 真实磁盘 + 真实 {@link DiskObjectStore},
 * 端到端走完 initiate → uploadPart → complete → get 全链路,以及各种崩溃 /
 * 并发场景下的行为校验。
 *
 * <p>对应设计文档 §10.2。
 */
public class DiskMultipartStoreIT {

    private static final String BUCKET = "test-bucket";

    @TempDir
    Path tmp;

    private DiskBucketStore bucketStore;
    private DiskObjectStore objectStore;
    private DiskMultipartStore multipart;

    @BeforeEach
    void setUp() throws Exception {
        bucketStore = new DiskBucketStore(tmp);
        objectStore = new DiskObjectStore(bucketStore);
        multipart = new DiskMultipartStore(tmp, bucketStore, objectStore);
        bucketStore.create(BUCKET);
    }

    // ===================== §10.2.1 e2e_uploadComplete =====================

    @Test
    void e2e_uploadComplete() throws Exception {
        // initiate
        String uid = multipart.initiate(BUCKET, "uploads/photo.jpg", "image/jpeg",
                Collections.singletonMap("x-amz-meta-source", "e2e"));

        // uploadPart × 3
        byte[] p1 = bytes("PART-1-AAA");
        byte[] p2 = bytes("PART-2-BBBBBBB");
        byte[] p3 = bytes("PART-3-CC");
        String e1 = multipart.uploadPart(uid, 1, new ByteArrayInputStream(p1), p1.length);
        String e2 = multipart.uploadPart(uid, 2, new ByteArrayInputStream(p2), p2.length);
        String e3 = multipart.uploadPart(uid, 3, new ByteArrayInputStream(p3), p3.length);

        // complete
        List<CompletedPart> parts = new ArrayList<>();
        parts.add(new CompletedPart(1, e1));
        parts.add(new CompletedPart(2, e2));
        parts.add(new CompletedPart(3, e3));
        CompletedObject done = multipart.complete(uid, parts);

        assertEquals(BUCKET, done.bucket());
        assertEquals("uploads/photo.jpg", done.key());
        assertEquals(p1.length + p2.length + p3.length, done.size());

        // 复合 ETag = MD5(concat(part md5 raw)) + "-" + count
        String expected = md5Hex(concat(md5Raw(p1), md5Raw(p2), md5Raw(p3))) + "-3";
        assertEquals(expected, done.etag());

        // get 拿回完整对象,byte 一致
        GetStream stream = objectStore.get(BUCKET, done.key(), 0, -1);
        byte[] actual = readAll(stream.content());
        assertEquals(p1.length + p2.length + p3.length, actual.length);
        for (int i = 0; i < p1.length; i++) assertEquals(p1[i], actual[i]);
        for (int i = 0; i < p2.length; i++) assertEquals(p2[i], actual[p1.length + i]);
        for (int i = 0; i < p3.length; i++) assertEquals(p3[i], actual[p1.length + p2.length + i]);
        // userMetadata 透传
        assertEquals("e2e", stream.meta().userMetadata().get("x-amz-meta-source"));

        // listParts / listUploads 都看不到已完成的 upload
        assertThrows(S3Exception.class, () -> multipart.listParts(uid));
        assertTrue(multipart.listUploads(BUCKET).isEmpty());
    }

    // ===================== §10.2.2 largePart_doesNotOom =====================

    @Test
    void largePart_doesNotOom() throws Exception {
        // 16MB part —— 验证流式 DigestInputStream 边读边算 MD5 不堆内聚集
        // (Phase 2 InMemory 版同 fixture 会 OOM,Xmx256m 时尤其明显)
        int partSize = 16 * 1024 * 1024;
        byte[] p1 = randomBytes(partSize, 0xC0FFEE);
        byte[] p2 = randomBytes(partSize / 2, 0xBEEF);    // 8MB part 2
        long expectedTotal = (long) p1.length + p2.length;

        String uid = multipart.initiate(BUCKET, "big.bin", "application/octet-stream", null);
        String e1 = multipart.uploadPart(uid, 1, new ByteArrayInputStream(p1), p1.length);
        String e2 = multipart.uploadPart(uid, 2, new ByteArrayInputStream(p2), p2.length);

        CompletedObject done = multipart.complete(uid, List.of(
                new CompletedPart(1, e1),
                new CompletedPart(2, e2)));
        assertEquals(expectedTotal, done.size());

        // 复合 ETag 校验 + get 拿回字节跟原 bytes hash 一致
        String expectedEtag = md5Hex(concat(md5Raw(p1), md5Raw(p2))) + "-2";
        assertEquals(expectedEtag, done.etag());

        // get + 整流 MD5 比对(不堆内比较 24MB,改用流式 hash)
        GetStream stream = objectStore.get(BUCKET, "big.bin", 0, -1);
        MessageDigest md5 = MessageDigest.getInstance("MD5");
        byte[] buf = new byte[8192];
        try (InputStream in = stream.content()) {
            int n;
            while ((n = in.read(buf)) != -1) md5.update(buf, 0, n);
        }
        // DiskObjectStore 存的是完整对象,它的 ETag 是对整个对象算的 MD5
        ObjectMeta headMeta = objectStore.head(BUCKET, "big.bin");
        assertEquals(headMeta.etag(), toHex(md5.digest()));
    }

    // ===================== §10.2.3 processCrash_simulated =====================

    @Test
    void processCrash_simulated() throws Exception {
        // 1) initiate + uploadPart × 2
        String uid = multipart.initiate(BUCKET, "k", null, null);
        byte[] p1 = bytes("first");
        byte[] p2 = bytes("second");
        String e1 = multipart.uploadPart(uid, 1, new ByteArrayInputStream(p1), p1.length);
        String e2 = multipart.uploadPart(uid, 2, new ByteArrayInputStream(p2), p2.length);

        // 2) 模拟进程崩溃:close 当前 store,不 abort
        multipart.close();

        // 3) 进程重启:新建 DiskMultipartStore,显式跑 startupRecovery
        DiskMultipartStore restarted = new DiskMultipartStore(tmp, bucketStore, objectStore);
        restarted.startupRecovery();

        // 4) listParts 仍能看到全部 part
        List<MultipartPart> parts = restarted.listParts(uid);
        assertEquals(2, parts.size());
        assertEquals(1, parts.get(0).partNumber());
        assertEquals(e1, parts.get(0).etag());
        assertEquals(p1.length, parts.get(0).size());
        assertEquals(2, parts.get(1).partNumber());
        assertEquals(e2, parts.get(1).etag());

        // 5) complete 成功
        CompletedObject done = restarted.complete(uid, List.of(
                new CompletedPart(1, e1),
                new CompletedPart(2, e2)));
        assertEquals(BUCKET, done.bucket());
        assertEquals("k", done.key());
        assertEquals(p1.length + p2.length, done.size());

        // 6) 上传目录已清
        Path uploadDir = tmp.resolve(BUCKET).resolve(".multipart").resolve(uid);
        assertFalse(Files.exists(uploadDir));

        restarted.close();
    }

    // ===================== §10.2.4 processCrash_afterCompleteBeforeDelete =====================

    @Test
    void processCrash_afterCompleteBeforeDelete() throws Exception {
        // 模拟 complete() 在 put ObjectStore 成功后、deleteRecursive(uploadDir) 前崩溃
        //
        // 残留状态:
        //   <root>/<bucket>/<key>                       (ObjectStore 数据)
        //   <root>/<bucket>/<key>.meta                  (ObjectStore meta)
        //   <root>/<bucket>/.multipart/<uid>/           (上传目录)
        //   <root>/<bucket>/.multipart/<uid>/meta.json.completing  (rename 自 meta.json)
        //   <root>/<bucket>/.multipart/<uid>/00001.part
        //
        // startupRecovery 应:反查 ObjectStore 有 key → 删孤儿目录

        String key = "uploads/finished.bin";

        // 1) 通过 multipart 走正常 initiate + uploadPart,得到真实的 meta.json + .part
        String uid = multipart.initiate(BUCKET, key, "application/octet-stream", null);
        byte[] body = bytes("OBJECT_BYTES");
        multipart.uploadPart(uid, 1, new ByteArrayInputStream(body), body.length);

        // 2) 模拟 complete() 跑到 put ObjectStore 成功的状态:
        //    a) ObjectStore 写入对象(完整 put 流程)
        //    b) 把 meta.json rename 成 meta.json.completing(没删目录)
        PutStream ps = new PutStream(new ByteArrayInputStream(body),
                "application/octet-stream", Collections.emptyMap(), body.length, null);
        objectStore.put(BUCKET, key, ps);

        Path uploadDir = tmp.resolve(BUCKET).resolve(".multipart").resolve(uid);
        Path metaFile = uploadDir.resolve(UploadMetaFile.META_FILE);
        Path completingFile = uploadDir.resolve(UploadMetaFile.COMPLETING_MARKER);
        Files.move(metaFile, completingFile);

        // 3) 启动恢复
        DiskMultipartStore fresh = new DiskMultipartStore(tmp, bucketStore, objectStore);
        fresh.startupRecovery();

        // upload 目录被删(因为 ObjectStore 已经有 key → 视为已完成)
        assertFalse(Files.exists(uploadDir));

        // ObjectStore 的对象仍然在 —— 启动恢复不会清 ObjectStore 数据
        Path bucketDir = tmp.resolve(BUCKET);
        assertTrue(Files.exists(bucketDir.resolve(key)));
        assertTrue(Files.exists(bucketDir.resolve(key + ".meta")));

        fresh.close();
    }

    // ===================== §10.2.5 multipartAbortAfterCrash =====================

    @Test
    void multipartAbortAfterCrash() throws Exception {
        // 模拟 abort() 中途崩:upload 目录里残留 .tmp 文件
        //
        // 残留状态:
        //   <root>/<bucket>/.multipart/<uid>/meta.json          (完整)
        //   <root>/<bucket>/.multipart/<uid>/00001.part          (完整)
        //   <root>/<bucket>/.multipart/<uid>/00002.part.tmp      (残留)
        //
        // startupRecovery 应:清 .tmp 残留,保留 meta.json + .part,upload 仍 ACTIVE

        String uid = multipart.initiate(BUCKET, "k", null, null);
        multipart.uploadPart(uid, 1, new ByteArrayInputStream(bytes("AAA")), 3);

        Path uploadDir = tmp.resolve(BUCKET).resolve(".multipart").resolve(uid);
        // 手工塞一个 .tmp 进去(模拟 abort 中途崩)
        Files.write(uploadDir.resolve("00002.part.tmp"), bytes("half-written-data"));

        // 启动恢复
        DiskMultipartStore fresh = new DiskMultipartStore(tmp, bucketStore, objectStore);
        fresh.startupRecovery();

        // .tmp 残留被清
        try (var stream = Files.list(uploadDir)) {
            List<String> names = new ArrayList<>();
            stream.forEach(p -> names.add(p.getFileName().toString()));
            assertFalse(names.contains("00002.part.tmp"),
                    "残留 .tmp 应该被 startupRecovery 清掉, 实际目录: " + names);
        }
        // meta.json 保留(对象未上传完 → 不应清目录)
        assertTrue(Files.isRegularFile(uploadDir.resolve("meta.json")));
        // part 1 保留
        assertTrue(Files.isRegularFile(uploadDir.resolve("00001.part")));

        // upload 仍可继续用
        List<MultipartPart> parts = fresh.listParts(uid);
        assertEquals(1, parts.size());
        assertEquals(1, parts.get(0).partNumber());

        fresh.close();
    }

    // ===================== §10.2.6 concurrentCompleteAndUploadPart =====================

    @Test
    void concurrentCompleteAndUploadPart() throws Exception {
        // 用 BlockingObjectStore 让 complete() 卡在 put 阶段,
        // 验证此时同 uploadId 的 uploadPart 拿到 NO_SUCH_UPLOAD(.completing 标记生效)

        BlockingObjectStore blocking = new BlockingObjectStore(objectStore);

        String uid = multipart.initiate(BUCKET, "k", null, null);
        byte[] p1 = bytes("FIRST");
        byte[] p2 = bytes("SECOND");
        String e1 = multipart.uploadPart(uid, 1, new ByteArrayInputStream(p1), p1.length);

        // 把 multipart 切到 blocking store(完整重建一个 DiskMultipartStore)
        DiskMultipartStore racing = new DiskMultipartStore(tmp, bucketStore, blocking);
        racing.startupRecovery();
        // 重传 part 1(换 store 后 locks map 是新的,但 .part 文件还在)
        // 这里其实不用再传 —— 直接 complete 即可,part 1 已经在磁盘上
        blocking.armPutBlock();

        AtomicReference<Throwable> completeError = new AtomicReference<>();
        AtomicReference<CompletedObject> completeResult = new AtomicReference<>();
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            exec.submit(() -> {
                try {
                    completeResult.set(racing.complete(uid, List.of(new CompletedPart(1, e1))));
                } catch (Throwable t) {
                    completeError.set(t);
                }
            });

            // 等 complete 跑到 put —— 此时 meta.json → meta.json.completing 已发生
            assertTrue(blocking.putEntered.await(5, TimeUnit.SECONDS),
                    "complete() 应该进入 put 阶段");

            // 此时同 uploadId 上传 part 2 → 应该 NO_SUCH_UPLOAD(.completing 标记)
            S3Exception ex = assertThrows(S3Exception.class, () ->
                    racing.uploadPart(uid, 2, new ByteArrayInputStream(p2), p2.length));
            assertEquals(S3ErrorCode.NO_SUCH_UPLOAD, ex.code());

            // 释放 put,complete 跑完
            blocking.releasePut();
        } finally {
            exec.shutdown();
            assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS));
        }

        assertTrue(completeError.get() == null,
                "complete() 应该成功, 但抛了: " + completeError.get());
        CompletedObject done = completeResult.get();
        assertNotNull(done);
        assertEquals(BUCKET, done.bucket());
        assertEquals("k", done.key());
        assertEquals(p1.length, done.size());

        racing.close();
    }

    // ===================== §10.2.7 diskUsageMeasured =====================

    @Test
    void diskUsageMeasured() throws Exception {
        // 100 parts × 5MB = 500MB upload;verify on-disk size sum = declared
        // (spec 是 100 parts × 5MB;为兼顾测速,默认 5MB;CI 可调大)
        int partCount = 100;
        int partSize = 5 * 1024 * 1024;
        long expectedTotal = (long) partCount * partSize;

        MessageDigest md5All = MessageDigest.getInstance("MD5");
        List<CompletedPart> completed = new ArrayList<>();

        String uid = multipart.initiate(BUCKET, "bulk.bin", "application/octet-stream", null);
        long diskUsageBefore = diskUsage(tmp);
        for (int i = 1; i <= partCount; i++) {
            byte[] part = randomBytes(partSize, i);
            // 复合 ETag = MD5(concat(part md5 raw bytes)) + "-" + count
            md5All.update(md5Raw(part));
            String etag = multipart.uploadPart(uid, i,
                    new ByteArrayInputStream(part), partSize);
            completed.add(new CompletedPart(i, etag));
        }
        // 中途盘用量应该跟 part bytes 总量一致(meta.json 很小可忽略)
        long midUsage = diskUsage(tmp) - diskUsageBefore;
        // meta.json 是 KB 级,允许 1MB 容差
        assertTrue(Math.abs(midUsage - expectedTotal) < 1024 * 1024,
                "midway disk usage " + midUsage + " 应≈ expected " + expectedTotal);

        CompletedObject done = multipart.complete(uid, completed);
        assertEquals(expectedTotal, done.size());

        // complete 后:upload 目录已删,所有 bytes 都在 ObjectStore 的 data 文件里
        Path dataFile = tmp.resolve(BUCKET).resolve("bulk.bin");
        assertTrue(Files.exists(dataFile));
        assertEquals(expectedTotal, Files.size(dataFile));
        // .multipart 目录应该已经被 startupRecovery 扫过 / 本次 complete 已删
        Path mpDir = tmp.resolve(BUCKET).resolve(".multipart");
        assertFalse(Files.exists(mpDir.resolve(uid)));

        // 复合 ETag 校验
        String expectedEtag = toHex(md5All.digest()) + "-" + partCount;
        assertEquals(expectedEtag, done.etag());
    }

    // ===================== helpers =====================

    /** 递归算目录下所有 regular file 字节数。 */
    private static long diskUsage(Path root) throws IOException {
        if (!Files.isDirectory(root)) return 0;
        long total = 0;
        try (var walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (Files.isRegularFile(p)) total += Files.size(p);
            }
        }
        return total;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] randomBytes(int size, long seed) {
        byte[] out = new byte[size];
        new SecureRandom(Long.toString(seed).getBytes(StandardCharsets.UTF_8))
                .nextBytes(out);
        return out;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... arrs) {
        int total = 0;
        for (byte[] a : arrs) total += a.length;
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] a : arrs) {
            System.arraycopy(a, 0, out, off, a.length);
            off += a.length;
        }
        return out;
    }

    private static byte[] md5Raw(byte[] data) {
        try {
            return MessageDigest.getInstance("MD5").digest(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String md5Hex(byte[] data) {
        return toHex(md5Raw(data));
    }

    private static String toHex(byte[] data) {
        char[] hex = "0123456789abcdef".toCharArray();
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xff;
            out[i * 2]     = hex[v >>> 4];
            out[i * 2 + 1] = hex[v & 0x0f];
        }
        return new String(out);
    }

    /**
     * 包一层 ObjectStore,允许在 {@code put} 入口处阻塞;
     * 用于触发 "complete 已占所有权 → uploadPart 看到 .completing" 的并发场景。
     */
    private static final class BlockingObjectStore implements ObjectStore {

        private final ObjectStore delegate;
        private CountDownLatch putBlock;
        private final CountDownLatch putEntered = new CountDownLatch(1);

        BlockingObjectStore(ObjectStore delegate) {
            this.delegate = delegate;
        }

        void armPutBlock() {
            this.putBlock = new CountDownLatch(1);
        }

        void releasePut() {
            CountDownLatch b = putBlock;
            if (b != null) b.countDown();
        }

        @Override
        public ObjectMeta put(String bucket, String key, PutStream body) throws IOException {
            putEntered.countDown();
            CountDownLatch b = putBlock;
            if (b != null) {
                try {
                    if (!b.await(30, TimeUnit.SECONDS)) {
                        throw new IOException("test timeout waiting for put release");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }
            return delegate.put(bucket, key, body);
        }

        @Override
        public GetStream get(String bucket, String key, long rangeStart, long rangeEnd) throws IOException {
            return delegate.get(bucket, key, rangeStart, rangeEnd);
        }

        @Override
        public ObjectMeta head(String bucket, String key) throws IOException {
            return delegate.head(bucket, key);
        }

        @Override
        public void delete(String bucket, String key) throws IOException {
            delegate.delete(bucket, key);
        }

        @Override
        public List<ObjectMeta> list(String bucket, String prefix, String delimiter, int maxKeys, String continuationToken) throws IOException {
            return delegate.list(bucket, prefix, delimiter, maxKeys, continuationToken);
        }

        @Override
        public boolean isEmpty(String bucket) throws IOException {
            return delegate.isEmpty(bucket);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
