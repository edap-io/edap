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
import io.edap.s3.store.disk.DiskBucketStore;
import io.edap.s3.store.disk.DiskMultipartStore;
import io.edap.s3.store.disk.DiskObjectStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ListObjectsV2 的端到端测试 —— 跑真实 HTTP server + 真实磁盘 store,
 * 逐个验证改造前坐实的 4 个 bug 在 wire 层已经修掉:
 *
 * <ol>
 *   <li>分页完全失效(continuationToken 从未被引用)</li>
 *   <li>带 {@code /} 的 key 在 LIST 里完全不可见</li>
 *   <li>删光对象后 {@code isEmpty()} 仍 false(残留空目录)</li>
 *   <li>{@code .acl} 让桶永远非空</li>
 * </ol>
 */
public class S3ListObjectsIT {

    private static final String AKID = "AKIDEXAMPLE";
    private static final String SECRET = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
    private static final String REGION = "us-east-1";

    @TempDir
    Path tmp;

    private int port;
    private HttpServer server;
    private Edap edap;
    private DiskBucketStore bucketStore;
    private DiskObjectStore objectStore;
    private DiskMultipartStore multipartStore;
    private S3TestClient client;

    @BeforeEach
    void setUp() throws Exception {
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        client = new S3TestClient(port, AKID, SECRET, REGION);

        bucketStore = new DiskBucketStore(tmp);
        objectStore = new DiskObjectStore(bucketStore);
        multipartStore = new DiskMultipartStore(tmp, bucketStore, objectStore);

        Properties p = new Properties();
        p.setProperty("s3.accessKey." + AKID + ".secret", SECRET);
        p.setProperty("s3.accessKey." + AKID + ".region", REGION);
        AccessKeyResolver resolver = ConfigAccessKeyResolver.fromProperties(p);

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

    // ===================== Helpers =====================

    private void createBucket(String bucket) throws IOException {
        S3TestClient.HttpResp r = client.signed("PUT", "/" + bucket, null, null, null);
        assertEquals(200, r.status, "create bucket failed: " + r.body);
    }

    private void put(String bucket, String key, String body) throws IOException {
        S3TestClient.HttpResp r = client.signed("PUT", "/" + bucket + "/" + key, null,
                body.getBytes(StandardCharsets.UTF_8), null);
        assertEquals(200, r.status, "put " + key + " failed: " + r.body);
    }

    private S3TestClient.HttpResp list(String bucket, Map<String, String> query) throws IOException {
        S3TestClient.HttpResp r = client.signed("GET", "/" + bucket, query, null, null);
        assertEquals(200, r.status, "list failed: " + r.body);
        return r;
    }

    private static List<String> keysOf(String xml) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<Key>(.*?)</Key>").matcher(xml);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static List<String> commonPrefixesOf(String xml) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<CommonPrefixes><Prefix>(.*?)</Prefix></CommonPrefixes>")
                .matcher(xml);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">(.*?)</" + name + ">").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    // ===================== bug ①:分页真的能用 =====================

    @Test
    void paginationActuallyWorks() throws Exception {
        String bucket = "page-bkt";
        createBucket(bucket);
        for (int i = 0; i < 5; i++) {
            put(bucket, String.format("k%02d", i), "v" + i);
        }

        Map<String, String> q = new LinkedHashMap<>();
        q.put("list-type", "2");
        q.put("max-keys", "2");
        S3TestClient.HttpResp p1 = list(bucket, q);

        assertEquals("true", tag(p1.body, "IsTruncated"), "第 1 页应当是截断的");
        assertEquals(List.of("k00", "k01"), keysOf(p1.body));
        String token = tag(p1.body, "NextContinuationToken");
        assertNotNull(token, "第 1 页应当给出 continuation token");

        Map<String, String> q2 = new LinkedHashMap<>(q);
        q2.put("continuation-token", token);
        S3TestClient.HttpResp p2 = list(bucket, q2);
        assertEquals(List.of("k02", "k03"), keysOf(p2.body));
        assertEquals("true", tag(p2.body, "IsTruncated"));

        Map<String, String> q3 = new LinkedHashMap<>(q);
        q3.put("continuation-token", tag(p2.body, "NextContinuationToken"));
        S3TestClient.HttpResp p3 = list(bucket, q3);
        assertEquals(List.of("k04"), keysOf(p3.body));
        assertEquals("false", tag(p3.body, "IsTruncated"));

        // 三页合起来 = 5 个 key,无重复、无遗漏
        List<String> all = new ArrayList<>();
        all.addAll(keysOf(p1.body));
        all.addAll(keysOf(p2.body));
        all.addAll(keysOf(p3.body));
        assertEquals(5, all.size());
        assertEquals(5, new java.util.LinkedHashSet<>(all).size());
    }

    @Test
    void repeatedFirstPageIsNotTheWholeStory() throws Exception {
        // 改造前:continuationToken 从未被引用 ⇒ 两次 LIST 返回完全相同的 1000 个 key
        String bucket = "repeat-bkt";
        createBucket(bucket);
        for (int i = 0; i < 6; i++) {
            put(bucket, String.format("k%02d", i), "v");
        }
        Map<String, String> q = new LinkedHashMap<>();
        q.put("list-type", "2");
        q.put("max-keys", "2");
        String p1 = list(bucket, q).body;
        String p2 = list(bucket, q).body;
        assertEquals(keysOf(p1), keysOf(p2), "同一请求两次应当返回同一页(不是 bug)");

        // 真正的判据:带 token 的第二页必须和第一页不同
        Map<String, String> q2 = new LinkedHashMap<>(q);
        q2.put("continuation-token", tag(p1, "NextContinuationToken"));
        List<String> second = keysOf(list(bucket, q2).body);
        assertFalse(second.equals(keysOf(p1)), "第二页不能和第一页完全一样");
    }

    @Test
    void startAfterSkipsEarlierKeys() throws Exception {
        String bucket = "start-bkt";
        createBucket(bucket);
        for (String k : new String[]{"a", "b", "c"}) {
            put(bucket, k, "v");
        }
        Map<String, String> q = new LinkedHashMap<>();
        q.put("list-type", "2");
        q.put("start-after", "a");
        S3TestClient.HttpResp r = list(bucket, q);
        assertEquals(List.of("b", "c"), keysOf(r.body));
        assertEquals("a", tag(r.body, "StartAfter"), "StartAfter 应回显");
    }

    // ===================== bug ②:带 "/" 的 key 可见 =====================

    @Test
    void keysWithSlashAreListed() throws Exception {
        String bucket = "slash-bkt";
        createBucket(bucket);
        put(bucket, "flat.txt", "v");
        put(bucket, "dir/a.txt", "v");
        put(bucket, "dir/b.txt", "v");
        put(bucket, "dir/deep/c.txt", "v");

        Map<String, String> q = new LinkedHashMap<>();
        q.put("list-type", "2");
        S3TestClient.HttpResp r = list(bucket, q);
        assertEquals(List.of("dir/a.txt", "dir/b.txt", "dir/deep/c.txt", "flat.txt"),
                keysOf(r.body));
    }

    // ===================== delimiter 聚合 =====================

    @Test
    void delimiterAggregatesIntoCommonPrefixes() throws Exception {
        String bucket = "delim-bkt";
        createBucket(bucket);
        put(bucket, "a/1", "v");
        put(bucket, "a/2", "v");
        put(bucket, "b", "v");
        put(bucket, "c", "v");

        Map<String, String> q = new LinkedHashMap<>();
        q.put("list-type", "2");
        q.put("delimiter", "/");
        S3TestClient.HttpResp r = list(bucket, q);
        assertEquals(List.of("a/"), commonPrefixesOf(r.body));
        assertEquals(List.of("b", "c"), keysOf(r.body));
        assertEquals("/", tag(r.body, "Delimiter"), "Delimiter 应回显");
        assertEquals("3", tag(r.body, "KeyCount"), "KeyCount = contents + commonPrefixes");
    }

    @Test
    void commonPrefixIsNotRepeatedAcrossPages() throws Exception {
        String bucket = "delim-page-bkt";
        createBucket(bucket);
        put(bucket, "a/1", "v");
        put(bucket, "a/2", "v");
        put(bucket, "b", "v");
        put(bucket, "c", "v");

        Map<String, String> q = new LinkedHashMap<>();
        q.put("list-type", "2");
        q.put("delimiter", "/");
        q.put("max-keys", "1");
        S3TestClient.HttpResp p1 = list(bucket, q);
        assertEquals(List.of("a/"), commonPrefixesOf(p1.body));
        assertEquals("true", tag(p1.body, "IsTruncated"));

        // 第 2 页:整个 "a/" group 被跳过,不能再吐一次 "a/"
        Map<String, String> q2 = new LinkedHashMap<>(q);
        q2.put("continuation-token", tag(p1.body, "NextContinuationToken"));
        S3TestClient.HttpResp p2 = list(bucket, q2);
        assertTrue(commonPrefixesOf(p2.body).isEmpty(), "第 2 页不该重复 common prefix");
        assertEquals(List.of("b"), keysOf(p2.body));

        // 第 3 页:剩下的 "c"
        Map<String, String> q3 = new LinkedHashMap<>(q);
        q3.put("continuation-token", tag(p2.body, "NextContinuationToken"));
        S3TestClient.HttpResp p3 = list(bucket, q3);
        assertTrue(commonPrefixesOf(p3.body).isEmpty());
        assertEquals(List.of("c"), keysOf(p3.body));
    }

    // ===================== bug ③:删光对象后能删桶 =====================

    @Test
    void bucketCanBeDeletedAfterAllObjectsDeleted() throws Exception {
        String bucket = "del-bkt";
        createBucket(bucket);
        put(bucket, "dir/a.txt", "v");
        put(bucket, "b.txt", "v");

        S3TestClient.HttpResp d1 = client.signed("DELETE", "/" + bucket + "/dir/a.txt",
                null, null, null);
        assertEquals(204, d1.status);
        S3TestClient.HttpResp d2 = client.signed("DELETE", "/" + bucket + "/b.txt",
                null, null, null);
        assertEquals(204, d2.status);

        S3TestClient.HttpResp r = client.signed("DELETE", "/" + bucket, null, null, null);
        assertEquals(204, r.status, "删光对象后删桶应当成功: " + r.body);
    }

    // ===================== bug ④:设过 ACL 也能删桶 =====================

    @Test
    void bucketCanBeDeletedAfterSettingAcl() throws Exception {
        String bucket = "acl-bkt";
        createBucket(bucket);
        S3TestClient.HttpResp acl = client.signed("PUT", "/" + bucket,
                Map.of("acl", ""), null, Map.of("x-amz-acl", "public-read"));
        assertEquals(200, acl.status, "set acl failed: " + acl.body);

        S3TestClient.HttpResp r = client.signed("DELETE", "/" + bucket, null, null, null);
        assertEquals(204, r.status, "桶级 ACL 不是对象,不该让桶删不掉: " + r.body);
    }

    // ===================== 其它 =====================

    @Test
    void emptyBucketListsNothing() throws Exception {
        String bucket = "empty-bkt";
        createBucket(bucket);
        Map<String, String> q = new LinkedHashMap<>();
        q.put("list-type", "2");
        S3TestClient.HttpResp r = list(bucket, q);
        assertEquals(0, keysOf(r.body).size());
        assertEquals("false", tag(r.body, "IsTruncated"));
        assertEquals("0", tag(r.body, "KeyCount"));
    }

    @Test
    void listOnMissingBucketIs404() throws Exception {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("list-type", "2");
        S3TestClient.HttpResp r = client.signed("GET", "/no-such-bkt", q, null, null);
        assertEquals(404, r.status);
        assertTrue(r.body.contains("NoSuchBucket"), "body=" + r.body);
    }

    @Test
    void maxKeysIsCappedAt1000() throws Exception {
        String bucket = "cap-bkt";
        createBucket(bucket);
        Map<String, String> q = new LinkedHashMap<>();
        q.put("list-type", "2");
        q.put("max-keys", "5000");
        S3TestClient.HttpResp r = list(bucket, q);
        assertEquals("1000", tag(r.body, "MaxKeys"));
    }
}
