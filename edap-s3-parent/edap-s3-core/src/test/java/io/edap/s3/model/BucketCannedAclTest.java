/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.model;

import io.edap.s3.error.S3ErrorCode;
import io.edap.s3.error.S3Exception;
import io.edap.s3.op.S3Operation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BucketCannedAclTest {

    @Test
    void headerValueRoundTrip() {
        assertEquals("private", BucketCannedAcl.PRIVATE.headerValue());
        assertEquals("public-read", BucketCannedAcl.PUBLIC_READ.headerValue());
        assertEquals("public-read-write", BucketCannedAcl.PUBLIC_READ_WRITE.headerValue());

        assertEquals(BucketCannedAcl.PRIVATE, BucketCannedAcl.fromHeader("private"));
        assertEquals(BucketCannedAcl.PUBLIC_READ, BucketCannedAcl.fromHeader("public-read"));
        assertEquals(BucketCannedAcl.PUBLIC_READ_WRITE,
                BucketCannedAcl.fromHeader("public-read-write"));
        // 大小写不敏感
        assertEquals(BucketCannedAcl.PRIVATE, BucketCannedAcl.fromHeader("PRIVATE"));
        assertEquals(BucketCannedAcl.PUBLIC_READ, BucketCannedAcl.fromHeader(" Public-Read "));
    }

    @Test
    void unknownHeaderThrows() {
        S3Exception ex = assertThrows(S3Exception.class,
                () -> BucketCannedAcl.fromHeader("authenticated-read"));
        assertEquals(S3ErrorCode.INVALID_ARGUMENT, ex.code());
    }

    @Test
    void missingHeaderThrows() {
        S3Exception ex1 = assertThrows(S3Exception.class,
                () -> BucketCannedAcl.fromHeader(null));
        assertEquals(S3ErrorCode.INVALID_ARGUMENT, ex1.code());
        S3Exception ex2 = assertThrows(S3Exception.class,
                () -> BucketCannedAcl.fromHeader(""));
        assertEquals(S3ErrorCode.INVALID_ARGUMENT, ex2.code());
    }

    @Test
    void privateBucketNeverAllowsAnonymous() {
        for (S3Operation op : S3Operation.values()) {
            assertFalse(BucketCannedAcl.PRIVATE.allowsAnonymous(op),
                    "PRIVATE must never allow anonymous for op=" + op);
        }
    }

    @Test
    void publicReadAllowsReadOpsOnly() {
        assertTrue(BucketCannedAcl.PUBLIC_READ.allowsAnonymous(S3Operation.GET_OBJECT));
        assertTrue(BucketCannedAcl.PUBLIC_READ.allowsAnonymous(S3Operation.HEAD_OBJECT));
        assertTrue(BucketCannedAcl.PUBLIC_READ.allowsAnonymous(S3Operation.LIST_OBJECTS_V2));

        assertFalse(BucketCannedAcl.PUBLIC_READ.allowsAnonymous(S3Operation.PUT_OBJECT));
        assertFalse(BucketCannedAcl.PUBLIC_READ.allowsAnonymous(S3Operation.DELETE_OBJECT));
        assertFalse(BucketCannedAcl.PUBLIC_READ.allowsAnonymous(S3Operation.INIT_MULTIPART));
        assertFalse(BucketCannedAcl.PUBLIC_READ.allowsAnonymous(S3Operation.UPLOAD_PART));
        assertFalse(BucketCannedAcl.PUBLIC_READ.allowsAnonymous(S3Operation.COMPLETE_MULTIPART));
        assertFalse(BucketCannedAcl.PUBLIC_READ.allowsAnonymous(S3Operation.ABORT_MULTIPART));
    }

    @Test
    void publicReadWriteAllowsReadAndWriteOps() {
        assertTrue(BucketCannedAcl.PUBLIC_READ_WRITE.allowsAnonymous(S3Operation.GET_OBJECT));
        assertTrue(BucketCannedAcl.PUBLIC_READ_WRITE.allowsAnonymous(S3Operation.HEAD_OBJECT));
        assertTrue(BucketCannedAcl.PUBLIC_READ_WRITE.allowsAnonymous(S3Operation.LIST_OBJECTS_V2));
        assertTrue(BucketCannedAcl.PUBLIC_READ_WRITE.allowsAnonymous(S3Operation.PUT_OBJECT));
        assertTrue(BucketCannedAcl.PUBLIC_READ_WRITE.allowsAnonymous(S3Operation.DELETE_OBJECT));
        assertTrue(BucketCannedAcl.PUBLIC_READ_WRITE.allowsAnonymous(S3Operation.INIT_MULTIPART));
        assertTrue(BucketCannedAcl.PUBLIC_READ_WRITE.allowsAnonymous(S3Operation.UPLOAD_PART));
        assertTrue(BucketCannedAcl.PUBLIC_READ_WRITE.allowsAnonymous(S3Operation.COMPLETE_MULTIPART));
        assertTrue(BucketCannedAcl.PUBLIC_READ_WRITE.allowsAnonymous(S3Operation.ABORT_MULTIPART));
    }

    @Test
    void bucketLevelOpsAlwaysRequireAuth() {
        for (BucketCannedAcl acl : BucketCannedAcl.values()) {
            assertFalse(acl.allowsAnonymous(S3Operation.LIST_BUCKETS),
                    acl + " must not allow anonymous LIST_BUCKETS");
            assertFalse(acl.allowsAnonymous(S3Operation.CREATE_BUCKET),
                    acl + " must not allow anonymous CREATE_BUCKET");
            assertFalse(acl.allowsAnonymous(S3Operation.DELETE_BUCKET),
                    acl + " must not allow anonymous DELETE_BUCKET");
            assertFalse(acl.allowsAnonymous(S3Operation.HEAD_BUCKET),
                    acl + " must not allow anonymous HEAD_BUCKET");
            assertFalse(acl.allowsAnonymous(S3Operation.PUT_BUCKET_ACL),
                    acl + " must not allow anonymous PUT_BUCKET_ACL");
        }
    }
}