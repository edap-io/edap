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
import io.edap.s3.model.BucketCannedAcl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InMemoryBucketStoreTest {

    private InMemoryBucketStore store;

    @BeforeEach
    void setUp() throws IOException {
        store = new InMemoryBucketStore();
    }

    @Test
    void newBucketDefaultsToPrivate() throws IOException {
        store.create("alpha");
        assertEquals(BucketCannedAcl.PRIVATE, store.getCannedAcl("alpha"));
    }

    @Test
    void setAndGetCannedAcl() throws IOException {
        store.create("bravo");
        store.setCannedAcl("bravo", BucketCannedAcl.PUBLIC_READ);
        assertEquals(BucketCannedAcl.PUBLIC_READ, store.getCannedAcl("bravo"));
        // 覆盖
        store.setCannedAcl("bravo", BucketCannedAcl.PUBLIC_READ_WRITE);
        assertEquals(BucketCannedAcl.PUBLIC_READ_WRITE, store.getCannedAcl("bravo"));
    }

    @Test
    void aclOnMissingBucketThrows404() {
        S3Exception ex = assertThrows(S3Exception.class,
                () -> store.setCannedAcl("ghost", BucketCannedAcl.PUBLIC_READ));
        assertEquals(S3ErrorCode.NO_SUCH_BUCKET, ex.code());

        S3Exception ex2 = assertThrows(S3Exception.class,
                () -> store.getCannedAcl("ghost"));
        assertEquals(S3ErrorCode.NO_SUCH_BUCKET, ex2.code());
    }

    @Test
    void deleteRemovesAcl() throws IOException {
        store.create("charlie");
        store.setCannedAcl("charlie", BucketCannedAcl.PUBLIC_READ_WRITE);
        store.delete("charlie");
        // 删桶后再 get → 404,不会再泄露上次 ACL
        S3Exception ex = assertThrows(S3Exception.class,
                () -> store.getCannedAcl("charlie"));
        assertEquals(S3ErrorCode.NO_SUCH_BUCKET, ex.code());
    }

    @Test
    void existsAndListAreUnchangedByAclOps() throws IOException {
        store.create("delta");
        store.setCannedAcl("delta", BucketCannedAcl.PUBLIC_READ);
        assertTrue(store.exists("delta"));
        assertFalse(store.exists("echo"));
        assertEquals(1, store.list().size());
    }
}