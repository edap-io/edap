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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InMemoryObjectStoreTest {

    private InMemoryBucketStore bucketStore;
    private InMemoryObjectStore objectStore;

    @BeforeEach
    void setUp() throws Exception {
        bucketStore = new InMemoryBucketStore();
        bucketStore.create("bucket1");
        objectStore = new InMemoryObjectStore(bucketStore);
    }

    private PutStream putStream(String content) {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        return new PutStream(new ByteArrayInputStream(data), "text/plain",
                Collections.emptyMap(), data.length, null);
    }

    @Test
    void putGetRoundtripPreservesBody() throws Exception {
        ObjectMeta m = objectStore.put("bucket1", "hello.txt", putStream("Hello S3!"));
        assertEquals("hello.txt", m.key());
        assertEquals(9, m.size());
        assertNotNull(m.etag());
        assertNotNull(m.lastModified());

        GetStream g = objectStore.get("bucket1", "hello.txt", 0, -1);
        byte[] got = g.content().readAllBytes();
        assertEquals("Hello S3!", new String(got, StandardCharsets.UTF_8));
    }

    @Test
    void getNonExistentKeyThrowsNoSuchKey() {
        S3Exception ex = assertThrows(S3Exception.class,
                () -> objectStore.get("bucket1", "missing", 0, -1));
        assertEquals(S3ErrorCode.NO_SUCH_KEY, ex.code());
    }

    @Test
    void getInNonExistentBucketThrowsNoSuchBucket() {
        S3Exception ex = assertThrows(S3Exception.class,
                () -> objectStore.get("ghost", "k", 0, -1));
        assertEquals(S3ErrorCode.NO_SUCH_BUCKET, ex.code());
    }

    @Test
    void headReturnsMetaWithoutBody() throws Exception {
        objectStore.put("bucket1", "k", putStream("payload"));
        ObjectMeta m = objectStore.head("bucket1", "k");
        assertEquals("k", m.key());
        assertEquals(7, m.size());
    }

    @Test
    void deleteIsIdempotent() throws Exception {
        objectStore.put("bucket1", "k", putStream("v"));
        objectStore.delete("bucket1", "k");
        // second delete 不报错
        objectStore.delete("bucket1", "k");
        assertTrue(objectStore.isEmpty("bucket1"));
    }

    @Test
    void isEmptyTrueOnFreshBucket() throws Exception {
        assertTrue(objectStore.isEmpty("bucket1"));
    }

    @Test
    void isEmptyFalseAfterPut() throws Exception {
        objectStore.put("bucket1", "k", putStream("v"));
        assertFalse(objectStore.isEmpty("bucket1"));
    }

    @Test
    void listReturnsAllObjects() throws Exception {
        objectStore.put("bucket1", "a", putStream("a"));
        objectStore.put("bucket1", "b", putStream("b"));
        objectStore.put("bucket1", "c", putStream("c"));
        List<ObjectMeta> all = objectStore.list("bucket1", "", null, 0, null);
        assertEquals(3, all.size());
        // 字典序
        assertEquals("a", all.get(0).key());
        assertEquals("b", all.get(1).key());
        assertEquals("c", all.get(2).key());
    }

    @Test
    void listPrefixFilters() throws Exception {
        objectStore.put("bucket1", "docs/a.txt", putStream("a"));
        objectStore.put("bucket1", "docs/b.txt", putStream("b"));
        objectStore.put("bucket1", "images/x.png", putStream("x"));
        List<ObjectMeta> docs = objectStore.list("bucket1", "docs/", null, 0, null);
        assertEquals(2, docs.size());
        for (ObjectMeta m : docs) {
            assertTrue(m.key().startsWith("docs/"));
        }
    }

    @Test
    void listMaxKeysTruncates() throws Exception {
        for (int i = 0; i < 5; i++) {
            objectStore.put("bucket1", "k" + i, putStream("v"));
        }
        List<ObjectMeta> truncated = objectStore.list("bucket1", "", null, 2, null);
        assertEquals(2, truncated.size());
    }

    @Test
    void rangeGetSlicesContent() throws Exception {
        byte[] data = "0123456789".getBytes(StandardCharsets.UTF_8);
        PutStream ps = new PutStream(new ByteArrayInputStream(data), "text/plain",
                Collections.emptyMap(), data.length, null);
        objectStore.put("bucket1", "k", ps);

        GetStream g = objectStore.get("bucket1", "k", 2, 5);
        byte[] got = g.content().readAllBytes();
        assertEquals("2345", new String(got, StandardCharsets.UTF_8));
        assertTrue(g.isPartial());
    }

    @Test
    void negativeContentLengthThrows() {
        PutStream bad = new PutStream(new ByteArrayInputStream(new byte[0]),
                "text/plain", Collections.emptyMap(), -1, null);
        S3Exception ex = assertThrows(S3Exception.class,
                () -> objectStore.put("bucket1", "k", bad));
        assertEquals(S3ErrorCode.INVALID_ARGUMENT, ex.code());
    }

    @Test
    void userMetadataRoundtrips() throws Exception {
        Map<String, String> meta = new HashMap<>();
        meta.put("x-source", "test");
        PutStream ps = new PutStream(new ByteArrayInputStream("v".getBytes(StandardCharsets.UTF_8)),
                "text/plain", meta, 1, null);
        ObjectMeta m = objectStore.put("bucket1", "k", ps);
        assertEquals("test", m.userMetadata().get("x-source"));
    }
}