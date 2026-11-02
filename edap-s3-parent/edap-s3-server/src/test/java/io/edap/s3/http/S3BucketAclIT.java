/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.http;

import io.edap.Edap;
import io.edap.http.server.HttpServer;
import io.edap.s3.auth.AccessKeyResolver;
import io.edap.s3.auth.ConfigAccessKeyResolver;
import io.edap.s3.op.handler.CreateBucketHandler;
import io.edap.s3.op.handler.DeleteBucketHandler;
import io.edap.s3.op.handler.DeleteObjectHandler;
import io.edap.s3.op.handler.GetObjectHandler;
import io.edap.s3.op.handler.HeadBucketHandler;
import io.edap.s3.op.handler.HeadObjectHandler;
import io.edap.s3.op.handler.ListBucketsHandler;
import io.edap.s3.op.handler.ListObjectsV2Handler;
import io.edap.s3.op.handler.PutBucketAclHandler;
import io.edap.s3.op.handler.PutObjectHandler;
import io.edap.s3.store.BucketStore;
import io.edap.s3.store.MultipartStore;
import io.edap.s3.store.ObjectStore;
import io.edap.s3.store.mem.InMemoryBucketStore;
import io.edap.s3.store.mem.InMemoryMultipartStore;
import io.edap.s3.store.mem.InMemoryObjectStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 桶 ACL 集成测试 —— 验证 canned ACL 的 wire-level 行为:
 * <ul>
 *   <li>PUBLIC_READ:匿名 GET / HEAD / LIST 放行;PUT 仍要签名</li>
 *   <li>PUBLIC_READ_WRITE:匿名 GET + PUT 都放行</li>
 *   <li>PRIVATE:所有非签请求都拒</li>
 *   <li>PUT_BUCKET_ACL 自身永远要求签名(任何 ACL 下)</li>
 * </ul>
 */
public class S3BucketAclIT {

    private static final String AKID = "AKIDEXAMPLE";
    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
    private static final String REGION = "us-east-1";

    private int port;
    private HttpServer server;
    private Edap edap;
    private BucketStore bucketStore;
    private ObjectStore objectStore;
    private MultipartStore multipartStore;
    private S3TestClient client;

    @BeforeEach
    void setUp() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        client = new S3TestClient(port, AKID, SECRET, REGION);
        bucketStore = new InMemoryBucketStore();
        objectStore = new InMemoryObjectStore(bucketStore);
        multipartStore = new InMemoryMultipartStore(bucketStore, objectStore);

        Properties p = new Properties();
        p.setProperty("s3.accessKey." + AKID + ".secret", SECRET);
        p.setProperty("s3.accessKey." + AKID + ".region", REGION);
        AccessKeyResolver resolver = ConfigAccessKeyResolver.fromProperties(p);

        // 不显式注 authVerifier → build() 自动用 BucketPolicyAwareVerifier 包装
        server = new S3ServerBuilder()
                .bucketStore(bucketStore)
                .objectStore(objectStore)
                .multipartStore(multipartStore)
                .accessKeyResolver(resolver)
                .register(new ListBucketsHandler(bucketStore))
                .register(new CreateBucketHandler(bucketStore))
                .register(new DeleteBucketHandler(bucketStore, objectStore))
                .register(new HeadBucketHandler(bucketStore))
                .register(new ListObjectsV2Handler(bucketStore, objectStore))
                .register(new PutObjectHandler(bucketStore, objectStore))
                .register(new GetObjectHandler(bucketStore, objectStore))
                .register(new HeadObjectHandler(bucketStore, objectStore))
                .register(new DeleteObjectHandler(bucketStore, objectStore))
                .registerBucketAclHandler()
                .listen(port)
                .build();

        edap = new Edap();
        edap.addServer(server);
        edap.run();
        Thread.sleep(50);
    }

    @AfterEach
    void tearDown() {
        if (edap != null) edap.stop();
    }

    @Test
    void privateBucketRejectsAnonymousGet() throws Exception {
        String bucket = "private-bkt";
        signed("PUT", "/" + bucket, null, null, null);
        // 先签名 PUT 一个对象
        signed("PUT", "/" + bucket + "/img.png", null,
                "x".getBytes(StandardCharsets.UTF_8), null);

        // 匿名 GET → 403
        S3TestClient.HttpResp r = anonymous("GET", "/" + bucket + "/img.png");
        assertEquals(403, r.status);
    }

    @Test
    void publicReadAllowsAnonymousGetAndHead() throws Exception {
        String bucket = "public-bkt";
        String key = "img.png";
        String body = "PNG-BYTES";

        signed("PUT", "/" + bucket, null, null, null);
        signed("PUT", "/" + bucket + "/" + key, null,
                body.getBytes(StandardCharsets.UTF_8), null);
        // 设 ACL = public-read(签名)
        // 注意:acl 是 sub-resource,必须作为 query 参数单独传 —— canonical request 里
        // canonicalUri 只含 path,canonicalQuery 才是 "acl="。写进 path 会算错签名。
        S3TestClient.HttpResp aclResp = signed("PUT", "/" + bucket, Map.of("acl", ""), null,
                Map.of("x-amz-acl", "public-read"));
        assertEquals(200, aclResp.status, "set public-read failed: " + aclResp.body);

        // 匿名 GET → 200 + body
        S3TestClient.HttpResp r1 = anonymous("GET", "/" + bucket + "/" + key);
        assertEquals(200, r1.status, "anon GET should be 200: " + r1.body);
        assertEquals(body, r1.body);

        // 匿名 HEAD → 200
        S3TestClient.HttpResp r2 = anonymous("HEAD", "/" + bucket + "/" + key);
        assertEquals(200, r2.status, "anon HEAD should be 200");

        // 匿名 PUT → 403(public-read 不允许匿名写)
        S3TestClient.HttpResp r3 = anonymous("PUT", "/" + bucket + "/other.png",
                "no".getBytes(StandardCharsets.UTF_8));
        assertEquals(403, r3.status, "anon PUT should be 403");
    }

    @Test
    void publicReadWriteAllowsAnonymousPut() throws Exception {
        String bucket = "public-rw";
        signed("PUT", "/" + bucket, null, null, null);
        S3TestClient.HttpResp aclResp = signed("PUT", "/" + bucket, Map.of("acl", ""), null,
                Map.of("x-amz-acl", "public-read-write"));
        assertEquals(200, aclResp.status);

        // 匿名 PUT → 200
        S3TestClient.HttpResp r1 = anonymous("PUT", "/" + bucket + "/upload.png",
                "DATA".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, r1.status, "anon PUT should be 200");

        // 匿名 GET → 200
        S3TestClient.HttpResp r2 = anonymous("GET", "/" + bucket + "/upload.png");
        assertEquals(200, r2.status);
        assertEquals("DATA", r2.body);
    }

    @Test
    void putBucketAclItselfRequiresSignature() throws Exception {
        String bucket = "acl-bkt";
        signed("PUT", "/" + bucket, null, null, null);

        // 匿名 PUT ?acl → 403(改 ACL 必须授权)
        S3TestClient.HttpResp r = anonymous("PUT", "/" + bucket + "?acl",
                null, Map.of("x-amz-acl", "public-read"));
        assertEquals(403, r.status, "anon PUT?acl must be 403");
        // 桶 ACL 仍然是 PRIVATE → 匿名 GET 也得 403
        S3TestClient.HttpResp r2 = anonymous("GET", "/" + bucket + "/x");
        assertEquals(403, r2.status);
    }

    @Test
    void anonymousReadOnNonPublicBucket403() throws Exception {
        String bucket = "default-bkt";
        signed("PUT", "/" + bucket, null, null, null);
        signed("PUT", "/" + bucket + "/k", null, "v".getBytes(StandardCharsets.UTF_8), null);

        // 没设 ACL → 默认 PRIVATE
        S3TestClient.HttpResp r = anonymous("GET", "/" + bucket + "/k");
        assertEquals(403, r.status);
    }

    @Test
    void listBucketsNeverAllowsAnonymous() throws Exception {
        // 即使 PUBLIC_READ_WRITE 也不行(桶级管理类 op)
        String bucket = "lba-bkt";
        signed("PUT", "/" + bucket, null, null, null);
        signed("PUT", "/" + bucket, Map.of("acl", ""), null,
                Map.of("x-amz-acl", "public-read-write"));

        S3TestClient.HttpResp r = anonymous("GET", "/");
        assertEquals(403, r.status, "LIST_BUCKETS should always require auth");
    }

    @Test
    void invalidAclHeaderReturns400() throws Exception {
        String bucket = "bad-acl-bkt";
        signed("PUT", "/" + bucket, null, null, null);
        S3TestClient.HttpResp r = signed("PUT", "/" + bucket, Map.of("acl", ""), null,
                Map.of("x-amz-acl", "authenticated-read"));
        assertEquals(400, r.status, "unsupported ACL must be 400: " + r.body);
        assertTrue(r.body.contains("InvalidArgument") || r.body.contains("INVALID_ARGUMENT"));
    }

    // ===================== Helpers =====================

    private S3TestClient.HttpResp signed(String method,
                                         String path,
                                         Map<String, String> queryParams,
                                         byte[] body,
                                         Map<String, String> extraHeaders) throws IOException {
        return client.signed(method, path, queryParams, body, extraHeaders);
    }

    private S3TestClient.HttpResp anonymous(String method, String path) throws IOException {
        return client.anonymous(method, path);
    }

    private S3TestClient.HttpResp anonymous(String method,
                                             String path,
                                             byte[] body) throws IOException {
        return client.anonymous(method, path, body);
    }

    private S3TestClient.HttpResp anonymous(String method,
                                             String path,
                                             byte[] body,
                                             Map<String, String> extraHeaders) throws IOException {
        return client.anonymous(method, path, body, extraHeaders);
    }
}