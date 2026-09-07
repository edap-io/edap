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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DiskObjectStoreTest {

    @TempDir
    Path tmp;

    private DiskBucketStore bucketStore;
    private DiskObjectStore objectStore;

    @BeforeEach
    void setUp() throws Exception {
        bucketStore = new DiskBucketStore(tmp);
        bucketStore.create("bucket1");
        objectStore = new DiskObjectStore(bucketStore);
    }

    private PutStream putStream(String content) {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        return new PutStream(new ByteArrayInputStream(data), "text/plain",
                Collections.emptyMap(), data.length, null);
    }

    @Test
    void putGetRoundtrip() throws Exception {
        objectStore.put("bucket1", "hello.txt", putStream("Hello Disk!"));
        GetStream g = objectStore.get("bucket1", "hello.txt", 0, -1);
        byte[] got = g.content().readAllBytes();
        assertEquals("Hello Disk!", new String(got, StandardCharsets.UTF_8));
        ObjectMeta m = g.meta();
        assertEquals(11, m.size());
        assertNotNull(m.etag());
    }

    @Test
    void dataAndMetaFilesExist() throws Exception {
        objectStore.put("bucket1", "k", putStream("v"));
        Path data = tmp.resolve("bucket1/k");
        Path meta = tmp.resolve("bucket1/k.meta");
        assertTrue(Files.exists(data));
        assertTrue(Files.exists(meta));
    }

    @Test
    void getNonExistentThrowsNoSuchKey() {
        S3Exception ex = assertThrows(S3Exception.class,
                () -> objectStore.get("bucket1", "ghost", 0, -1));
        assertEquals(S3ErrorCode.NO_SUCH_KEY, ex.code());
    }

    @Test
    void headReturnsMeta() throws Exception {
        objectStore.put("bucket1", "k", putStream("payload"));
        ObjectMeta m = objectStore.head("bucket1", "k");
        assertEquals("k", m.key());
        assertEquals(7, m.size());
    }

    @Test
    void deleteRemovesFiles() throws Exception {
        objectStore.put("bucket1", "k", putStream("v"));
        objectStore.delete("bucket1", "k");
        assertTrue(objectStore.isEmpty("bucket1"));
        assertThrows(S3Exception.class, () -> objectStore.get("bucket1", "k", 0, -1));
    }

    @Test
    void deleteIsIdempotent() throws Exception {
        objectStore.delete("bucket1", "never-existed");   // 不存在不报错
        objectStore.put("bucket1", "k", putStream("v"));
        objectStore.delete("bucket1", "k");
        objectStore.delete("bucket1", "k");               // 第二次也不报错
    }

    @Test
    void rangeGetSlices() throws Exception {
        byte[] data = "0123456789".getBytes(StandardCharsets.UTF_8);
        PutStream ps = new PutStream(new ByteArrayInputStream(data), "text/plain",
                Collections.emptyMap(), data.length, null);
        objectStore.put("bucket1", "k", ps);

        GetStream g = objectStore.get("bucket1", "k", 3, 7);
        byte[] got = g.content().readAllBytes();
        assertEquals("34567", new String(got, StandardCharsets.UTF_8));
        assertEquals(3, g.rangeStart());
        assertEquals(7, g.rangeEnd());
        assertTrue(g.isPartial());
    }

    @Test
    void rangeOutOfBoundsReturnsEmpty() throws Exception {
        objectStore.put("bucket1", "k", putStream("v"));
        GetStream g = objectStore.get("bucket1", "k", 1000, 2000);
        assertEquals(0, g.content().readAllBytes().length);
    }

    @Test
    void listReturnsAllObjects() throws Exception {
        objectStore.put("bucket1", "a", putStream("a"));
        objectStore.put("bucket1", "b", putStream("b"));
        objectStore.put("bucket1", "c", putStream("c"));
        assertEquals(3, objectStore.list("bucket1", "", null, 0, null).size());
    }

    @Test
    void listIgnoresTmpAndMetaFiles() throws Exception {
        objectStore.put("bucket1", "k", putStream("v"));
        // 模拟残留 .tmp —— 不应出现在 list 结果里
        Files.createFile(tmp.resolve("bucket1/orphan.tmp"));
        Files.createFile(tmp.resolve("bucket1/orphan.meta"));
        assertEquals(1, objectStore.list("bucket1", "", null, 0, null).size());
    }

    @Test
    void getInNonExistentBucketThrows() {
        S3Exception ex = assertThrows(S3Exception.class,
                () -> objectStore.get("ghost", "k", 0, -1));
        assertEquals(S3ErrorCode.NO_SUCH_BUCKET, ex.code());
    }
}