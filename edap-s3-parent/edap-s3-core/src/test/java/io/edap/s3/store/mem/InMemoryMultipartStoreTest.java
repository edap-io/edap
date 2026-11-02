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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InMemoryMultipartStore 单元测试 —— 覆盖单线程基础流程 + 关键并发场景。
 */
public class InMemoryMultipartStoreTest {

    private InMemoryBucketStore bucketStore;
    private InMemoryObjectStore objectStore;
    private InMemoryMultipartStore multipart;

    @BeforeEach
    void setUp() throws IOException {
        bucketStore = new InMemoryBucketStore();
        objectStore = new InMemoryObjectStore(bucketStore);
        multipart = new InMemoryMultipartStore(bucketStore, objectStore);
        bucketStore.create("test-bucket");
    }

    // ===================== 基础 =====================

    @Test
    void initiate() throws Exception {
        String uploadId = multipart.initiate("test-bucket", "key", "text/plain",
                Collections.emptyMap());
        assertNotNull(uploadId);
        assertTrue(uploadId.length() >= 32, "UUID uploadId should be at least 32 chars");
    }

    @Test
    void initiateFailsOnMissingBucket() {
        S3Exception ex = assertThrows(S3Exception.class,
                () -> multipart.initiate("no-such", "key", null, null));
        assertEquals(S3ErrorCode.NO_SUCH_BUCKET, ex.code());
    }

    @Test
    void uploadPartOutOfOrder() throws Exception {
        String uid = multipart.initiate("test-bucket", "k", null, null);
        String etag2 = multipart.uploadPart(uid, 2, streamOf("BB"), 2);
        String etag1 = multipart.uploadPart(uid, 1, streamOf("A"), 1);
        String etag3 = multipart.uploadPart(uid, 3, streamOf("CCC"), 3);
        // uploadPart 后 listParts 应按 partNumber 升序返回
        List<MultipartPart> parts = multipart.listParts(uid);
        assertEquals(3, parts.size());
        assertEquals(1, parts.get(0).partNumber());
        assertEquals(2, parts.get(1).partNumber());
        assertEquals(3, parts.get(2).partNumber());
        assertEquals(etag1, parts.get(0).etag());
        assertEquals(etag2, parts.get(1).etag());
        assertEquals(etag3, parts.get(2).etag());
    }

    @Test
    void completeAssemblesBytesAndEtag() throws Exception {
        String uid = multipart.initiate("test-bucket", "k", "text/plain",
                Collections.singletonMap("foo", "bar"));

        // 三个 part: "AAA"(3B) "BBBBB"(5B) "CC"(2B) → 拼接后 "AAABBBBBCC"
        String e1 = multipart.uploadPart(uid, 1, streamOf("AAA"), 3);
        String e2 = multipart.uploadPart(uid, 2, streamOf("BBBBB"), 5);
        String e3 = multipart.uploadPart(uid, 3, streamOf("CC"), 2);

        List<CompletedPart> cps = new ArrayList<>();
        // 故意乱序提交,内部要按 partNumber 升序组装
        cps.add(new CompletedPart(3, e3));
        cps.add(new CompletedPart(1, e1));
        cps.add(new CompletedPart(2, e2));

        CompletedObject done = multipart.complete(uid, cps);
        assertEquals("test-bucket", done.bucket());
        assertEquals("k", done.key());
        assertEquals(10, done.size());

        // 复合 ETag 验证:MD5(concat(MD5("AAA"),MD5("BBBBB"),MD5("CC"))) + "-3"
        String expectedComposite = md5Hex(concat(md5Raw("AAA"), md5Raw("BBBBB"), md5Raw("CC")))
                + "-3";
        assertEquals(expectedComposite, done.etag());

        // 通过 ObjectStore 验证能读出正确 bytes
        var stream = objectStore.get("test-bucket", "k", 0, -1);
        byte[] actual = stream.content().readAllBytes();
        assertEquals("AAABBBBBCC", new String(actual, StandardCharsets.UTF_8));
        // meta 验证 userMetadata 也透传
        assertEquals("bar", stream.meta().userMetadata().get("foo"));
    }

    @Test
    void completeRejectsBadEtag() throws Exception {
        String uid = multipart.initiate("test-bucket", "k", null, null);
        multipart.uploadPart(uid, 1, streamOf("AAA"), 3);
        List<CompletedPart> bad = Collections.singletonList(new CompletedPart(1, "deadbeef"));
        S3Exception ex = assertThrows(S3Exception.class, () -> multipart.complete(uid, bad));
        assertEquals(S3ErrorCode.INVALID_ARGUMENT, ex.code());
    }

    @Test
    void completeRejectsMissingPart() throws Exception {
        String uid = multipart.initiate("test-bucket", "k", null, null);
        String e1 = multipart.uploadPart(uid, 1, streamOf("AAA"), 3);
        // 客户端声明 part 99 但没上传过
        S3Exception ex = assertThrows(S3Exception.class, () ->
                multipart.complete(uid, Collections.singletonList(
                        new CompletedPart(99, e1))));
        assertEquals(S3ErrorCode.INVALID_ARGUMENT, ex.code());
    }

    @Test
    void completeRejectsUnknownUpload() {
        S3Exception ex = assertThrows(S3Exception.class, () ->
                multipart.complete("not-a-real-uuid", Collections.emptyList()));
        assertEquals(S3ErrorCode.NO_SUCH_UPLOAD, ex.code());
    }

    @Test
    void abortClearsParts() throws Exception {
        String uid = multipart.initiate("test-bucket", "k", null, null);
        multipart.uploadPart(uid, 1, streamOf("AAA"), 3);
        multipart.abort(uid);
        // abort 后 listParts 应该 NO_SUCH_UPLOAD
        S3Exception ex = assertThrows(S3Exception.class,
                () -> multipart.listParts(uid));
        assertEquals(S3ErrorCode.NO_SUCH_UPLOAD, ex.code());
    }

    @Test
    void abortUnknownThrows() {
        S3Exception ex = assertThrows(S3Exception.class,
                () -> multipart.abort("never-existed"));
        assertEquals(S3ErrorCode.NO_SUCH_UPLOAD, ex.code());
    }

    @Test
    void uploadPartUnknownUploadThrows() {
        S3Exception ex = assertThrows(S3Exception.class, () ->
                multipart.uploadPart("never-existed", 1, streamOf("X"), 1));
        assertEquals(S3ErrorCode.NO_SUCH_UPLOAD, ex.code());
    }

    @Test
    void uploadPartRejectsBadPartNumber() throws Exception {
        String uid = multipart.initiate("test-bucket", "k", null, null);
        S3Exception ex1 = assertThrows(S3Exception.class, () ->
                multipart.uploadPart(uid, 0, streamOf("X"), 1));
        assertEquals(S3ErrorCode.INVALID_ARGUMENT, ex1.code());
        S3Exception ex2 = assertThrows(S3Exception.class, () ->
                multipart.uploadPart(uid, 10001, streamOf("X"), 1));
        assertEquals(S3ErrorCode.INVALID_ARGUMENT, ex2.code());
    }

    @Test
    void uploadPartRejectsNegativeContentLength() throws Exception {
        String uid = multipart.initiate("test-bucket", "k", null, null);
        S3Exception ex = assertThrows(S3Exception.class, () ->
                multipart.uploadPart(uid, 1, streamOf("X"), -1));
        assertEquals(S3ErrorCode.INVALID_ARGUMENT, ex.code());
    }

    @Test
    void listUploadsFiltersByBucket() throws Exception {
        bucketStore.create("other-bucket");
        String u1 = multipart.initiate("test-bucket", "k1", null, null);
        String u2 = multipart.initiate("test-bucket", "k2", null, null);
        String u3 = multipart.initiate("other-bucket", "k3", null, null);

        List<io.edap.s3.model.MultipartUpload> testList = multipart.listUploads("test-bucket");
        List<io.edap.s3.model.MultipartUpload> otherList = multipart.listUploads("other-bucket");
        assertEquals(2, testList.size());
        assertEquals(1, otherList.size());
        // u3 in other, not in test
        assertEquals("other-bucket", otherList.get(0).bucket());
        // sanity
        assertEquals(u1, testList.get(0).uploadId());
        assertEquals(u2, testList.get(1).uploadId());
    }

    @Test
    void listUploadsFailsOnMissingBucket() {
        assertThrows(S3Exception.class, () -> multipart.listUploads("nope"));
    }

    // ===================== 并发 =====================

    @Test
    void samePartNumberLastWriteWins() throws Exception {
        // 两个并发 thread 同时给 partNumber=1 上传不同 bytes,
        // 期望 uploadPart 都不抛异常,最终以最后一次 put 的内容为准
        String uid = multipart.initiate("test-bucket", "k", null, null);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<String> e1 = new AtomicReference<>();
        AtomicReference<String> e2 = new AtomicReference<>();
        ExecutorService exec = Executors.newFixedThreadPool(2);
        try {
            exec.submit(() -> {
                try {
                    start.await();
                    e1.set(multipart.uploadPart(uid, 1, streamOf("FIRST"), 5));
                } catch (Exception ignored) {} finally { done.countDown(); }
            });
            exec.submit(() -> {
                try {
                    start.await();
                    e2.set(multipart.uploadPart(uid, 1, streamOf("SECOND_VERSION"), 14));
                } catch (Exception ignored) {} finally { done.countDown(); }
            });
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
        } finally {
            exec.shutdownNow();
        }
        // 不论谁最后写入,listParts 应该只有 1 个 part(partNumber=1)
        List<MultipartPart> parts = multipart.listParts(uid);
        assertEquals(1, parts.size());
        // 内容应该是其中之一(FIRST 或 SECOND_VERSION)
        String body = new String(parts.get(0).body(), StandardCharsets.UTF_8);
        assertTrue(body.equals("FIRST") || body.equals("SECOND_VERSION"),
                "body should be one of the two concurrent uploads, got: " + body);
        // ETag 应跟 body 匹配(MD5 hex)
        String expectedEtag = md5Hex(body.getBytes(StandardCharsets.UTF_8));
        assertEquals(expectedEtag, parts.get(0).etag());
    }

    // ===================== helpers =====================

    private static ByteArrayInputStream streamOf(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] md5Raw(String s) {
        try {
            return MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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

    private static String md5Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(data);
            char[] hex = "0123456789abcdef".toCharArray();
            char[] out = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int v = digest[i] & 0xff;
                out[i * 2] = hex[v >>> 4];
                out[i * 2 + 1] = hex[v & 0x0f];
            }
            return new String(out);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
