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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

public class DiskMultipartStoreTest {

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
        bucketStore.create("test-bucket");
    }

    // ===================== 基础 =====================

    @Test
    void initiateCreatesMetaJson() throws Exception {
        String uploadId = multipart.initiate("test-bucket", "k", "text/plain",
                Collections.singletonMap("foo", "bar"));
        assertNotNull(uploadId);
        assertTrue(uploadId.length() >= 32, "UUID uploadId should be at least 32 chars");

        // meta.json 应当存在
        Path uploadDir = locateUploadDir(uploadId);
        assertTrue(Files.isRegularFile(uploadDir.resolve("meta.json")));
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
    void completeAssemblesBytesAndCompositeEtag() throws Exception {
        String uid = multipart.initiate("test-bucket", "k", "text/plain",
                Collections.singletonMap("foo", "bar"));

        // 三个 part: "AAA"(3B) "BBBBB"(5B) "CC"(2B) → 拼接后 "AAABBBBBCC"
        String e1 = multipart.uploadPart(uid, 1, streamOf("AAA"), 3);
        String e2 = multipart.uploadPart(uid, 2, streamOf("BBBBB"), 5);
        String e3 = multipart.uploadPart(uid, 3, streamOf("CC"), 2);

        List<CompletedPart> cps = new ArrayList<>();
        cps.add(new CompletedPart(3, e3));
        cps.add(new CompletedPart(1, e1));
        cps.add(new CompletedPart(2, e2));

        CompletedObject done = multipart.complete(uid, cps);
        assertEquals("test-bucket", done.bucket());
        assertEquals("k", done.key());
        assertEquals(10, done.size());

        // 复合 ETag 验证
        String expectedComposite = md5Hex(concat(md5Raw("AAA"), md5Raw("BBBBB"), md5Raw("CC")))
                + "-3";
        assertEquals(expectedComposite, done.etag());

        // 通过 ObjectStore 验证能读出正确 bytes
        var stream = objectStore.get("test-bucket", "k", 0, -1);
        byte[] actual = stream.content().readAllBytes();
        assertEquals("AAABBBBBCC", new String(actual, StandardCharsets.UTF_8));
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
    void abortClearsDirectory() throws Exception {
        String uid = multipart.initiate("test-bucket", "k", null, null);
        multipart.uploadPart(uid, 1, streamOf("AAA"), 3);
        Path uploadDir = locateUploadDir(uid);
        assertTrue(Files.isDirectory(uploadDir));

        multipart.abort(uid);
        assertTrue(!Files.exists(uploadDir));
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
        multipart.initiate("other-bucket", "k3", null, null);

        List<MultipartUpload> testList = multipart.listUploads("test-bucket");
        List<MultipartUpload> otherList = multipart.listUploads("other-bucket");
        assertEquals(2, testList.size());
        assertEquals(1, otherList.size());
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
                    e2.set(multipart.uploadPart(uid, 1, streamOf("SECOND_VERSION"), 13));
                } catch (Exception ignored) {} finally { done.countDown(); }
            });
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS));
        } finally {
            exec.shutdownNow();
        }
        List<MultipartPart> parts = multipart.listParts(uid);
        assertEquals(1, parts.size());
        // ETag 应跟 MD5 匹配(由 part bytes 算)
        String expectedEtag = md5Hex(parts.get(0).size() == 5
                ? "FIRST".getBytes(StandardCharsets.UTF_8)
                : "SECOND_VERSION".getBytes(StandardCharsets.UTF_8));
        assertEquals(expectedEtag, parts.get(0).etag());
        // 两个 ETag 至少有一个匹配上
        assertTrue(e1.get().equals(expectedEtag) || e2.get().equals(expectedEtag),
                "one of the concurrent uploadPart ETags should match the surviving part's MD5");
    }

    // ===================== helpers =====================

    private Path locateUploadDir(String uploadId) throws IOException {
        Path mpDir = tmp.resolve("test-bucket/.multipart").resolve(uploadId);
        if (!Files.isDirectory(mpDir)) {
            throw new S3Exception(S3ErrorCode.NO_SUCH_UPLOAD,
                    "No such upload: " + uploadId);
        }
        return mpDir;
    }

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
