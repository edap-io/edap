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
import io.edap.s3.model.BucketCannedAcl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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

    @Test
    void aclDefaultsToPrivate() throws Exception {
        store.create("foo");
        assertEquals(BucketCannedAcl.PRIVATE, store.getCannedAcl("foo"));
    }

    @Test
    void setAclPersistsToDotAclFile() throws Exception {
        store.create("foo");
        store.setCannedAcl("foo", BucketCannedAcl.PUBLIC_READ);
        Path aclFile = tmp.resolve("foo").resolve(".acl");
        assertTrue(Files.exists(aclFile));
        assertEquals("PUBLIC_READ", Files.readString(aclFile).trim());
    }

    @Test
    void aclSurvivesRestart() throws Exception {
        store.create("foo");
        store.setCannedAcl("foo", BucketCannedAcl.PUBLIC_READ_WRITE);
        // 模拟重启:重新构造 store
        DiskBucketStore reopened = new DiskBucketStore(tmp);
        assertEquals(BucketCannedAcl.PUBLIC_READ_WRITE, reopened.getCannedAcl("foo"));
    }

    @Test
    void aclOnMissingBucketThrows() {
        S3Exception ex1 = assertThrows(S3Exception.class,
                () -> store.setCannedAcl("ghost", BucketCannedAcl.PUBLIC_READ));
        assertEquals(S3ErrorCode.NO_SUCH_BUCKET, ex1.code());
        S3Exception ex2 = assertThrows(S3Exception.class,
                () -> store.getCannedAcl("ghost"));
        assertEquals(S3ErrorCode.NO_SUCH_BUCKET, ex2.code());
    }

    @Test
    void corruptedAclFileFailsOpenToPrivate() throws Exception {
        store.create("foo");
        Path aclFile = tmp.resolve("foo").resolve(".acl");
        Files.writeString(aclFile, "INVALID_ACL_NAME");
        assertEquals(BucketCannedAcl.PRIVATE, store.getCannedAcl("foo"),
                "corrupt .acl should fail-open to PRIVATE");
    }
}