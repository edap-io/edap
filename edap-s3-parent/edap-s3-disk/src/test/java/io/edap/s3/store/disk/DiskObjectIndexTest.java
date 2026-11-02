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
import io.edap.s3.model.ListOptions;
import io.edap.s3.model.ListResult;
import io.edap.s3.model.ObjectMeta;
import io.edap.s3.model.PutStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 索引化后的 {@link DiskObjectStore} —— 逐个覆盖改造前坐实的 4 个 bug,
 * 外加索引本身的语义。
 *
 * <ol>
 *   <li>分页完全失效(continuationToken 从未被引用)</li>
 *   <li>带 {@code /} 的 key 在 LIST 里完全不可见</li>
 *   <li>删光对象后 {@code isEmpty()} 仍 false(残留空目录)</li>
 *   <li>{@code .acl} 让桶永远非空</li>
 * </ol>
 */
public class DiskObjectIndexTest {

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

    private static List<String> keysOf(List<ObjectMeta> metas) {
        List<String> out = new ArrayList<>();
        for (ObjectMeta m : metas) {
            out.add(m.key());
        }
        return out;
    }

    private static ListOptions unlimited() {
        return ListOptions.of("", "", ListOptions.MAX_KEYS_UNLIMITED);
    }

    // ================= bug ①:分页完全失效 =================

    @Test
    void continuationTokenActuallyPaginates() throws Exception {
        for (int i = 0; i < 10; i++) {
            objectStore.put("bucket1", String.format("k%02d", i), putStream("v"));
        }
        ListResult p1 = objectStore.list("bucket1", ListOptions.of("", "", 4));
        assertEquals(4, p1.contents().size());
        assertTrue(p1.truncated());
        assertNotNull(p1.nextContinuationToken());

        ListResult p2 = objectStore.list("bucket1",
                new ListOptions("", "", 4, p1.nextContinuationToken(), null));
        assertEquals(4, p2.contents().size());
        assertTrue(p2.truncated());

        ListResult p3 = objectStore.list("bucket1",
                new ListOptions("", "", 4, p2.nextContinuationToken(), null));
        assertEquals(2, p3.contents().size());
        assertFalse(p3.truncated());

        // 三页合起来 = 全部 10 个 key,且无重复
        Set<String> seen = new LinkedHashSet<>();
        seen.addAll(keysOf(p1.contents()));
        seen.addAll(keysOf(p2.contents()));
        seen.addAll(keysOf(p3.contents()));
        assertEquals(10, seen.size());
    }

    @Test
    void listIsSortedBeforeTruncation() throws Exception {
        // 插入顺序刻意乱序 ⇒ 第一页必须是字典序第一页(老实现是「扫→截断→排序」)
        for (String k : new String[]{"m", "a", "z", "b", "y"}) {
            objectStore.put("bucket1", k, putStream("v"));
        }
        ListResult p1 = objectStore.list("bucket1", ListOptions.of("", "", 2));
        assertEquals(List.of("a", "b"), keysOf(p1.contents()));
    }

    @Test
    void delimiterGroupIsNotRepeatedAcrossPages() throws Exception {
        objectStore.put("bucket1", "a/1", putStream("v"));
        objectStore.put("bucket1", "a/2", putStream("v"));
        objectStore.put("bucket1", "b", putStream("v"));
        objectStore.put("bucket1", "c", putStream("v"));

        ListResult p1 = objectStore.list("bucket1", ListOptions.of("", "/", 1));
        assertEquals(List.of("a/"), p1.commonPrefixes());
        assertTrue(p1.truncated());

        ListResult p2 = objectStore.list("bucket1",
                new ListOptions("", "/", 1000, p1.nextContinuationToken(), null));
        assertTrue(p2.commonPrefixes().isEmpty());
        assertEquals(List.of("b", "c"), keysOf(p2.contents()));
    }

    // ================= bug ②:带 "/" 的 key 隐形 =================

    @Test
    void keysWithSlashAreVisibleInList() throws Exception {
        objectStore.put("bucket1", "flat.txt", putStream("v"));
        objectStore.put("bucket1", "dir/a.txt", putStream("v"));
        objectStore.put("bucket1", "dir/b.txt", putStream("v"));
        objectStore.put("bucket1", "dir/deep/c.txt", putStream("v"));

        ListResult r = objectStore.list("bucket1", unlimited());
        assertEquals(List.of("dir/a.txt", "dir/b.txt", "dir/deep/c.txt", "flat.txt"),
                keysOf(r.contents()));
    }

    @Test
    void keysWithSlashSurviveRestart() throws Exception {
        objectStore.put("bucket1", "dir/deep/c.txt", putStream("v"));
        objectStore.close();

        DiskObjectStore reopened = new DiskObjectStore(bucketStore);
        reopened.init();
        assertEquals(List.of("dir/deep/c.txt"),
                keysOf(reopened.list("bucket1", unlimited()).contents()));
        assertEquals("dir/deep/c.txt", reopened.head("bucket1", "dir/deep/c.txt").key());
        reopened.close();
    }

    // ================= bug ③:删光后 isEmpty 仍 false =================

    @Test
    void isEmptyAfterDeletingAllObjects() throws Exception {
        objectStore.put("bucket1", "dir/a.txt", putStream("v"));
        objectStore.put("bucket1", "b.txt", putStream("v"));
        assertFalse(objectStore.isEmpty("bucket1"));

        objectStore.delete("bucket1", "dir/a.txt");
        objectStore.delete("bucket1", "b.txt");
        assertTrue(objectStore.isEmpty("bucket1"),
                "删光对象后应为空(空目录残留不算对象)");
    }

    // ================= bug ④:.acl 让桶永远非空 =================

    @Test
    void isEmptyIgnoresAclFile() throws Exception {
        bucketStore.setCannedAcl("bucket1", BucketCannedAcl.PUBLIC_READ);
        assertTrue(Files.exists(tmp.resolve("bucket1/.acl")));
        assertTrue(objectStore.isEmpty("bucket1"),
                "桶级 ACL 不是对象,不该让桶永远非空");
    }

    @Test
    void bucketCanBeDeletedAfterAclAndIndexExist() throws Exception {
        bucketStore.setCannedAcl("bucket1", BucketCannedAcl.PUBLIC_READ);
        objectStore.put("bucket1", "k", putStream("v"));
        objectStore.delete("bucket1", "k");
        assertTrue(objectStore.isEmpty("bucket1"));
        bucketStore.delete("bucket1");          // 递归删,不留 .acl / .index
        assertFalse(Files.exists(tmp.resolve("bucket1")));
    }

    // ================= 索引本身 =================

    @Test
    void indexLogIsTheOnlyMetadataSource() throws Exception {
        objectStore.put("bucket1", "k", putStream("v"));
        assertTrue(Files.exists(tmp.resolve("bucket1/.index/log")));
        assertFalse(Files.exists(tmp.resolve("bucket1/k.meta")),
                "不再写 per-object .meta sidecar");
    }

    @Test
    void deleteRemovesFromIndexAndBody() throws Exception {
        objectStore.put("bucket1", "k", putStream("v"));
        objectStore.delete("bucket1", "k");
        assertTrue(objectStore.list("bucket1", unlimited()).contents().isEmpty());
        assertFalse(Files.exists(tmp.resolve("bucket1/k")));
        // 删完的 key 再 put 一次,索引里仍是一条
        objectStore.put("bucket1", "k", putStream("v2"));
        assertEquals(1, objectStore.list("bucket1", unlimited()).contents().size());
    }

    @Test
    void reservedKeysAreRejected() throws Exception {
        assertThrows(S3Exception.class,
                () -> objectStore.put("bucket1", ".index/log", putStream("v")));
        assertThrows(S3Exception.class,
                () -> objectStore.put("bucket1", ".acl", putStream("v")));
        assertThrows(S3Exception.class,
                () -> objectStore.put("bucket1", ".multipart/x", putStream("v")));
        // 读路径报 NO_SUCH_KEY(不能泄露索引)
        S3Exception ex = assertThrows(S3Exception.class,
                () -> objectStore.get("bucket1", ".index/log", 0, -1));
        assertEquals(S3ErrorCode.NO_SUCH_KEY, ex.code());
    }

    @Test
    void pathTraversalKeysAreRejected() throws Exception {
        assertThrows(S3Exception.class,
                () -> objectStore.put("bucket1", "../escape.txt", putStream("v")));
        assertThrows(S3Exception.class,
                () -> objectStore.put("bucket1", "/etc/passwd", putStream("v")));
    }

    @Test
    void dataSurvivesRestart() throws Exception {
        objectStore.put("bucket1", "a.txt", putStream("hello"));
        objectStore.put("bucket1", "dir/b.txt", putStream("world"));
        objectStore.close();

        DiskObjectStore reopened = new DiskObjectStore(bucketStore);
        reopened.init();
        ObjectMeta m = reopened.head("bucket1", "a.txt");
        assertEquals(5, m.size());
        assertEquals("hello",
                new String(reopened.get("bucket1", "a.txt", 0, -1).content().readAllBytes(),
                        StandardCharsets.UTF_8));
        assertEquals(2, reopened.list("bucket1", unlimited()).contents().size());
        reopened.close();
    }

    @Test
    void deleteThenRestartKeepsObjectDeleted() throws Exception {
        objectStore.put("bucket1", "a", putStream("v"));
        objectStore.put("bucket1", "b", putStream("v"));
        objectStore.delete("bucket1", "a");
        objectStore.close();

        DiskObjectStore reopened = new DiskObjectStore(bucketStore);
        reopened.init();
        assertEquals(List.of("b"), keysOf(reopened.list("bucket1", unlimited()).contents()));
        assertThrows(S3Exception.class, () -> reopened.head("bucket1", "a"));
        reopened.close();
    }

    @Test
    void overwriteKeepsSingleIndexEntry() throws Exception {
        objectStore.put("bucket1", "k", putStream("v1"));
        objectStore.put("bucket1", "k", putStream("v2"));
        ListResult r = objectStore.list("bucket1", unlimited());
        assertEquals(1, r.contents().size());
        assertEquals(2, r.contents().get(0).size());
    }

    @Test
    void forgetBucketReleasesIndex() throws Exception {
        objectStore.put("bucket1", "k", putStream("v"));
        objectStore.forgetBucket("bucket1");
        // 忘记之后重新加载索引 —— 数据还在(索引 log 是真源)
        assertEquals(List.of("k"), keysOf(objectStore.list("bucket1", unlimited()).contents()));
    }

    @Test
    void startAfterSkipsEarlierKeys() throws Exception {
        objectStore.put("bucket1", "a", putStream("v"));
        objectStore.put("bucket1", "b", putStream("v"));
        objectStore.put("bucket1", "c", putStream("v"));
        ListResult r = objectStore.list("bucket1", new ListOptions("", "", 1000, null, "a"));
        assertEquals(List.of("b", "c"), keysOf(r.contents()));
    }

    @Test
    void emptyBucketListIsEmpty() throws Exception {
        ListResult r = objectStore.list("bucket1", unlimited());
        assertTrue(r.contents().isEmpty());
        assertFalse(r.truncated());
        assertNull(r.nextContinuationToken());
    }
}
