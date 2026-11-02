/*
 * Copyright 2026 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.s3.op.handler;

import io.edap.s3.error.S3ErrorCode;
import io.edap.s3.error.S3Exception;
import io.edap.s3.model.ObjectMeta;
import io.edap.s3.model.PutStream;
import io.edap.s3.model.S3Request;
import io.edap.s3.model.S3Response;
import io.edap.s3.op.S3Operation;
import io.edap.s3.store.mem.InMemoryBucketStore;
import io.edap.s3.store.mem.InMemoryObjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ListObjectsV2Handler} —— query 参数解析 + 响应 XML 里的
 * 分页字段(改造前 isTruncated 写死 false、nextContinuationToken 恒为 null)。
 */
public class ListObjectsV2HandlerTest {

    private InMemoryBucketStore bucketStore;
    private InMemoryObjectStore objectStore;
    private ListObjectsV2Handler handler;

    @BeforeEach
    void setUp() throws Exception {
        bucketStore = new InMemoryBucketStore();
        bucketStore.create("b1");
        objectStore = new InMemoryObjectStore(bucketStore);
        handler = new ListObjectsV2Handler(bucketStore, objectStore);
    }

    private void put(String key) throws Exception {
        byte[] data = "v".getBytes(StandardCharsets.UTF_8);
        objectStore.put("b1", key, new PutStream(new ByteArrayInputStream(data),
                "text/plain", Collections.emptyMap(), data.length, null));
    }

    private S3Request request(Map<String, String> query) {
        return new S3Request(S3Operation.LIST_OBJECTS_V2, "b1", null,
                query, Collections.emptyMap(), null, null, "/b1");
    }

    private String body(S3Request req) {
        S3Response resp = handler.handle(req);
        assertEquals(200, resp.status());
        return new String(resp.bodyBytes(), StandardCharsets.UTF_8);
    }

    private static String tag(String xml, String name) {
        int i = xml.indexOf("<" + name + ">");
        if (i < 0) return null;
        int j = xml.indexOf("</" + name + ">", i);
        return xml.substring(i + name.length() + 2, j);
    }

    // ================= 分页字段 =================

    @Test
    void isTruncatedAndTokenComeFromResult() throws Exception {
        for (int i = 0; i < 5; i++) put("k" + i);
        Map<String, String> q = new HashMap<>();
        q.put("max-keys", "2");
        String xml = body(request(q));
        assertEquals("true", tag(xml, "IsTruncated"));
        assertNotNull(tag(xml, "NextContinuationToken"));
        assertEquals("2", tag(xml, "KeyCount"));
        assertEquals("2", tag(xml, "MaxKeys"));
    }

    @Test
    void lastPageIsNotTruncatedAndHasNoToken() throws Exception {
        put("only");
        Map<String, String> q = new HashMap<>();
        q.put("max-keys", "10");
        String xml = body(request(q));
        assertEquals("false", tag(xml, "IsTruncated"));
        assertNull(tag(xml, "NextContinuationToken"));
    }

    @Test
    void continuationTokenIsEchoedBack() throws Exception {
        for (int i = 0; i < 5; i++) put("k" + i);
        Map<String, String> q = new HashMap<>();
        q.put("max-keys", "2");
        String token = tag(body(request(q)), "NextContinuationToken");

        Map<String, String> q2 = new HashMap<>();
        q2.put("max-keys", "2");
        q2.put("continuation-token", token);
        String xml = body(request(q2));
        assertEquals(token, tag(xml, "ContinuationToken"), "ContinuationToken 应回显");
    }

    @Test
    void startAfterIsEchoedBack() throws Exception {
        put("a");
        Map<String, String> q = new HashMap<>();
        q.put("start-after", "a");
        assertEquals("a", tag(body(request(q)), "StartAfter"));
    }

    // ================= max-keys 解析 =================

    @Test
    void maxKeysIsCappedAt1000() throws Exception {
        Map<String, String> q = new HashMap<>();
        q.put("max-keys", "99999");
        assertEquals("1000", tag(body(request(q)), "MaxKeys"));
    }

    @Test
    void negativeMaxKeysBecomesZero() throws Exception {
        Map<String, String> q = new HashMap<>();
        q.put("max-keys", "-5");
        String xml = body(request(q));
        assertEquals("0", tag(xml, "MaxKeys"));
        assertEquals("0", tag(xml, "KeyCount"));
        assertEquals("false", tag(xml, "IsTruncated"));
    }

    @Test
    void nonNumericMaxKeysFallsBackToDefault() throws Exception {
        Map<String, String> q = new HashMap<>();
        q.put("max-keys", "abc");
        assertEquals("1000", tag(body(request(q)), "MaxKeys"));
    }

    @Test
    void absentMaxKeysUsesDefault() throws Exception {
        assertEquals("1000", tag(body(request(new HashMap<>())), "MaxKeys"));
    }

    // ================= 错误 =================

    @Test
    void missingBucketIs404() {
        S3Request req = new S3Request(S3Operation.LIST_OBJECTS_V2, "ghost", null,
                Collections.emptyMap(), Collections.emptyMap(), null, null, "/ghost");
        S3Exception ex = assertThrows(S3Exception.class, () -> handler.handle(req));
        assertEquals(S3ErrorCode.NO_SUCH_BUCKET, ex.code());
    }

    @Test
    void badContinuationTokenIs400() throws Exception {
        put("k");
        Map<String, String> q = new HashMap<>();
        q.put("continuation-token", "not-a-valid-token");
        S3Exception ex = assertThrows(S3Exception.class, () -> handler.handle(request(q)));
        assertEquals(S3ErrorCode.INVALID_ARGUMENT, ex.code());
    }

    @Test
    void tokenFromDifferentPrefixIsRejected() throws Exception {
        for (int i = 0; i < 5; i++) put("k" + i);
        Map<String, String> q = new HashMap<>();
        q.put("max-keys", "2");
        q.put("prefix", "k");
        String token = tag(body(request(q)), "NextContinuationToken");

        // 同一个 token 换个 prefix 用 ⇒ 语义会错乱 ⇒ 直接 400
        Map<String, String> q2 = new HashMap<>();
        q2.put("continuation-token", token);
        q2.put("prefix", "other");
        S3Exception ex = assertThrows(S3Exception.class, () -> handler.handle(request(q2)));
        assertEquals(S3ErrorCode.INVALID_ARGUMENT, ex.code());
    }

    // ================= 其它 =================

    @Test
    void prefixAndDelimiterAreEchoedBack() throws Exception {
        put("dir/a");
        Map<String, String> q = new HashMap<>();
        q.put("prefix", "dir/");
        q.put("delimiter", "/");
        String xml = body(request(q));
        assertEquals("dir/", tag(xml, "Prefix"));
        assertEquals("/", tag(xml, "Delimiter"));
        assertTrue(xml.contains("<Key>dir/a</Key>"));
    }
}
