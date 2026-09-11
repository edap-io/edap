/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.auth;

import io.edap.s3.error.S3ErrorCode;
import io.edap.s3.error.S3Exception;
import io.edap.s3.model.BucketCannedAcl;
import io.edap.s3.model.S3Request;
import io.edap.s3.op.S3Operation;
import io.edap.s3.store.BucketStore;
import io.edap.s3.store.mem.InMemoryBucketStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BucketPolicyAwareVerifierTest {

    private InMemoryBucketStore bucketStore;
    private S3AuthVerifier delegate;
    private BucketPolicyAwareVerifier verifier;
    private int delegateCalls;

    @BeforeEach
    void setUp() throws IOException {
        bucketStore = new InMemoryBucketStore();
        delegateCalls = 0;
        delegate = new S3AuthVerifier() {
            @Override
            public void verify(String method, String path, Map<String, String> q,
                               Map<String, String> h, S3Request parsed) {
                delegateCalls++;
            }
            @Override
            public boolean allowAnonymous() {
                return false;
            }
        };
        verifier = new BucketPolicyAwareVerifier(delegate, bucketStore);
        bucketStore.create("alpha");
        bucketStore.create("bravo");
        bucketStore.setCannedAcl("bravo", BucketCannedAcl.PUBLIC_READ);
    }

    @Test
    void allowAnonymousIsTrue() {
        assertTrue(verifier.allowAnonymous());
    }

    @Test
    void signedRequestDelegates() throws Exception {
        Map<String, String> hdrs = new HashMap<>();
        hdrs.put("authorization", "AWS4-HMAC-SHA256 ...");
        S3Request req = new S3Request(S3Operation.GET_OBJECT, "alpha", "k", null,
                hdrs, null, null, "/alpha/k");
        verifier.verify("GET", "/alpha/k", null, hdrs, req);
        assertEquals(1, delegateCalls, "signed GET must delegate to inner verifier");
    }

    @Test
    void anonymousGetOnPublicReadPasses() throws Exception {
        Map<String, String> hdrs = new HashMap<>();
        S3Request req = new S3Request(S3Operation.GET_OBJECT, "bravo", "k", null,
                hdrs, null, null, "/bravo/k");
        verifier.verify("GET", "/bravo/k", null, hdrs, req);
        assertEquals(0, delegateCalls, "anonymous public-read GET must NOT call delegate");
    }

    @Test
    void anonymousPutOnPublicRead403() {
        Map<String, String> hdrs = new HashMap<>();
        S3Request req = new S3Request(S3Operation.PUT_OBJECT, "bravo", "k", null,
                hdrs, null, null, "/bravo/k");
        S3Exception ex = assertThrows(S3Exception.class,
                () -> verifier.verify("PUT", "/bravo/k", null, hdrs, req));
        assertEquals(S3ErrorCode.ACCESS_DENIED, ex.code());
        assertEquals(0, delegateCalls);
    }

    @Test
    void anonymousGetOnPrivate403() {
        Map<String, String> hdrs = new HashMap<>();
        S3Request req = new S3Request(S3Operation.GET_OBJECT, "alpha", "k", null,
                hdrs, null, null, "/alpha/k");
        S3Exception ex = assertThrows(S3Exception.class,
                () -> verifier.verify("GET", "/alpha/k", null, hdrs, req));
        assertEquals(S3ErrorCode.ACCESS_DENIED, ex.code());
    }

    @Test
    void anonymousListBucketsAlways403() {
        Map<String, String> hdrs = new HashMap<>();
        S3Request req = new S3Request(S3Operation.LIST_BUCKETS, null, null, null,
                hdrs, null, null, "/");
        S3Exception ex = assertThrows(S3Exception.class,
                () -> verifier.verify("GET", "/", null, hdrs, req));
        assertEquals(S3ErrorCode.ACCESS_DENIED, ex.code());
    }

    @Test
    void anonymousPutBucketAcl403() {
        Map<String, String> hdrs = new HashMap<>();
        S3Request req = new S3Request(S3Operation.PUT_BUCKET_ACL, "bravo", null, null,
                hdrs, null, null, "/bravo?acl");
        // bravo is public-read,但 PUT_BUCKET_ACL 必须签名
        S3Exception ex = assertThrows(S3Exception.class,
                () -> verifier.verify("PUT", "/bravo?acl", null, hdrs, req));
        assertEquals(S3ErrorCode.ACCESS_DENIED, ex.code());
    }

    @Test
    void bucketStoreReadFailureBubblesUp() {
        BucketStore throwing = new BucketStore() {
            @Override public void create(String b) {}
            @Override public void delete(String b) {}
            @Override public boolean exists(String b) { return true; }
            @Override public java.util.List<String> list() { return java.util.Collections.emptyList(); }
            @Override public void setCannedAcl(String b, BucketCannedAcl acl) {}
            @Override public BucketCannedAcl getCannedAcl(String b) throws IOException {
                throw new IOException("disk error");
            }
        };
        BucketPolicyAwareVerifier v = new BucketPolicyAwareVerifier(delegate, throwing);
        Map<String, String> hdrs = new HashMap<>();
        S3Request req = new S3Request(S3Operation.GET_OBJECT, "alpha", "k", null,
                hdrs, null, null, "/alpha/k");
        S3Exception ex = assertThrows(S3Exception.class,
                () -> v.verify("GET", "/alpha/k", null, hdrs, req));
        assertEquals(S3ErrorCode.INTERNAL_ERROR, ex.code());
    }
}