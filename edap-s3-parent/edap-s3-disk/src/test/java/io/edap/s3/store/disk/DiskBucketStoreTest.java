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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DiskBucketStoreTest {

    @TempDir
    Path tmp;

    private DiskBucketStore store;

    @BeforeEach
    void setUp() throws Exception {
        store = new DiskBucketStore(tmp);
    }

    @Test
    void createAndExists() throws Exception {
        store.create("foo");
        assertTrue(store.exists("foo"));
        assertFalse(store.exists("bar"));
    }

    @Test
    void createDuplicateThrows() throws Exception {
        store.create("foo");
        S3Exception ex = assertThrows(S3Exception.class, () -> store.create("foo"));
        assertEquals(S3ErrorCode.BUCKET_ALREADY_OWNED_BY_YOU, ex.code());
    }

    @Test
    void deleteRemoves() throws Exception {
        store.create("foo");
        store.delete("foo");
        assertFalse(store.exists("foo"));
    }

    @Test
    void deleteMissingThrowsNoSuchBucket() {
        S3Exception ex = assertThrows(S3Exception.class, () -> store.delete("ghost"));
        assertEquals(S3ErrorCode.NO_SUCH_BUCKET, ex.code());
    }

    @Test
    void listSortedAlphabetical() throws Exception {
        store.create("z");
        store.create("a");
        store.create("m");
        List<String> all = store.list();
        assertEquals(3, all.size());
        assertEquals("a", all.get(0));
        assertEquals("m", all.get(1));
        assertEquals("z", all.get(2));
    }
}