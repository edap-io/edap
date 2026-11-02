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
import io.edap.s3.model.ListOptions;
import io.edap.s3.model.ListResult;
import io.edap.s3.model.ObjectMeta;
import io.edap.s3.model.PutStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 崩溃一致性 —— 「索引是提交点」的语义验证。
 *
 * <p>各场景都模拟进程在某个点崩掉,然后重启看桶里是什么状态:
 * <ul>
 *   <li>PUT 崩在 body 落盘之后、append 索引之前 ⇒ 孤儿 body(LIST 看不到、
 *       GET 404)</li>
 *   <li>DELETE 崩在 append 索引之后、删 body 之前 ⇒ 孤儿 body(对象已消失)</li>
 *   <li>索引 log 尾部半条记录 ⇒ 丢弃半条、修回 log,之前的记录完好</li>
 *   <li>索引 log magic 损坏 ⇒ 归档 + 从 body 重建</li>
 * </ul>
 */
public class DiskObjectStoreCrashTest {

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

    private static ListOptions unlimited() {
        return ListOptions.of("", "", ListOptions.MAX_KEYS_UNLIMITED);
    }

    private List<String> keys(DiskObjectStore store) throws IOException {
        ListResult r = store.list("bucket1", unlimited());
        return r.contents().stream().map(ObjectMeta::key).collect(Collectors.toList());
    }

    private Path logPath() {
        return tmp.resolve("bucket1/.index/log");
    }

    private DiskObjectStore reopen() throws IOException {
        objectStore.close();
        DiskObjectStore reopened = new DiskObjectStore(bucketStore);
        reopened.init();
        return reopened;
    }

    // ================= 崩溃点 ①:body 落盘后、append 索引前 =================

    @Test
    void orphanBodyIsInvisibleAfterRestart() throws Exception {
        objectStore.put("bucket1", "good", putStream("v"));
        // 模拟崩溃:body 已在盘上,但索引里没有这条
        Files.write(tmp.resolve("bucket1/orphan"), "x".getBytes(StandardCharsets.UTF_8));
        objectStore.close();

        DiskObjectStore reopened = new DiskObjectStore(bucketStore);
        reopened.init();

        assertEquals(List.of("good"), keys(reopened));
        S3Exception ex = assertThrows(S3Exception.class,
                () -> reopened.get("bucket1", "orphan", 0, -1));
        assertEquals(S3ErrorCode.NO_SUCH_KEY, ex.code());
        reopened.close();
    }

    // ================= 崩溃点 ②:DELETE 已提交索引、body 还没删 =================

    @Test
    void deletedObjectStaysDeletedAfterRestart() throws Exception {
        objectStore.put("bucket1", "a", putStream("v"));
        objectStore.put("bucket1", "b", putStream("v"));

        // 模拟「DELETE 索引已提交、body 还没删就崩了」—— 手工追加一条 DELETE 记录
        try (IndexLog log = IndexLog.open(logPath(), false)) {
            log.replay();
            log.append(IndexRecord.delete("a"));
        }
        objectStore.close();

        DiskObjectStore reopened = new DiskObjectStore(bucketStore);
        reopened.init();
        assertEquals(List.of("b"), keys(reopened));
        reopened.close();
    }

    // ================= 索引 log 尾部损坏 =================

    @Test
    void halfRecordAtTailIsDroppedAndLogRepaired() throws Exception {
        objectStore.put("bucket1", "a", putStream("v"));
        objectStore.put("bucket1", "b", putStream("v"));
        objectStore.close();

        // 追加一条只写了一半的记录(没有换行)
        Files.write(logPath(), "P\thalf\te\t1\tt\t0\t0".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);
        long sizeBefore = Files.size(logPath());

        DiskObjectStore reopened = new DiskObjectStore(bucketStore);
        reopened.init();
        assertEquals(List.of("a", "b"), keys(reopened));
        assertTrue(Files.size(logPath()) < sizeBefore, "半条记录应被截回");
        reopened.close();
    }

    @Test
    void corruptCrcRecordDropsItAndEverythingAfter() throws Exception {
        objectStore.put("bucket1", "a", putStream("v"));
        objectStore.put("bucket1", "b", putStream("v"));
        objectStore.close();

        String content = new String(Files.readAllBytes(logPath()), StandardCharsets.UTF_8);
        int idx = content.indexOf("\tb\t");
        assertTrue(idx > 0);
        content = content.substring(0, idx) + "\tB" + content.substring(idx + 3);
        Files.write(logPath(), content.getBytes(StandardCharsets.UTF_8));

        DiskObjectStore reopened = new DiskObjectStore(bucketStore);
        reopened.init();
        // "b" 那条 CRC 坏了 ⇒ 它和它之后的全丢
        assertEquals(List.of("a"), keys(reopened));
        reopened.close();
    }

    // ================= 索引 log 整体损坏 =================

    @Test
    void corruptMagicTriggersRebuildFromBodies() throws Exception {
        objectStore.put("bucket1", "a", putStream("v"));
        objectStore.put("bucket1", "dir/b", putStream("v"));
        objectStore.close();

        // magic 写坏 ⇒ 整个 log 不可信
        Files.write(logPath(), "GARBAGE\nP\tk\n".getBytes(StandardCharsets.UTF_8));

        DiskObjectStore reopened = new DiskObjectStore(bucketStore);
        reopened.init();

        assertEquals(List.of("a", "dir/b"), keys(reopened));
        // 损坏文件被归档,不覆盖
        try (java.util.stream.Stream<Path> files = Files.list(tmp.resolve("bucket1/.index"))) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("log.corrupt-")));
        }
        reopened.close();
    }

    @Test
    void rebuildFromBodiesComputesEtagAndSize() throws Exception {
        objectStore.put("bucket1", "a", putStream("hello"));
        objectStore.close();
        Files.write(logPath(), "GARBAGE\n".getBytes(StandardCharsets.UTF_8));

        DiskObjectStore reopened = new DiskObjectStore(bucketStore);
        reopened.init();

        ObjectMeta m = reopened.head("bucket1", "a");
        assertEquals(5, m.size());
        assertEquals("5d41402abc4b2a76b9719d911017c592", m.etag());   // MD5("hello")
        reopened.close();
    }

    // ================= 重启后数据完整 =================

    @Test
    void dataAndIndexSurviveRestart() throws Exception {
        objectStore.put("bucket1", "a.txt", putStream("hello"));
        objectStore.put("bucket1", "dir/b.txt", putStream("world"));

        DiskObjectStore reopened = reopen();
        assertEquals(List.of("a.txt", "dir/b.txt"), keys(reopened));
        assertEquals("hello", new String(
                reopened.get("bucket1", "a.txt", 0, -1).content().readAllBytes(),
                StandardCharsets.UTF_8));
        assertEquals("world", new String(
                reopened.get("bucket1", "dir/b.txt", 0, -1).content().readAllBytes(),
                StandardCharsets.UTF_8));
        reopened.close();
    }

    @Test
    void overwriteThenRestartKeepsLatestValue() throws Exception {
        objectStore.put("bucket1", "k", putStream("v1"));
        objectStore.put("bucket1", "k", putStream("v2"));

        DiskObjectStore reopened = reopen();
        assertEquals(List.of("k"), keys(reopened));
        assertEquals("v2", new String(
                reopened.get("bucket1", "k", 0, -1).content().readAllBytes(),
                StandardCharsets.UTF_8));
        reopened.close();
    }

    @Test
    void appendAfterRestartContinuesSameLog() throws Exception {
        objectStore.put("bucket1", "a", putStream("v"));

        DiskObjectStore reopened = reopen();
        reopened.put("bucket1", "b", putStream("v"));
        reopened.close();

        DiskObjectStore again = new DiskObjectStore(bucketStore);
        again.init();
        assertEquals(List.of("a", "b"), keys(again));
        again.close();
    }

    @Test
    void bodyMissingButIndexPresentIsSelfHealed() throws Exception {
        objectStore.put("bucket1", "a", putStream("v"));
        // 有人手工删了 body
        Files.delete(tmp.resolve("bucket1/a"));
        assertFalse(Files.exists(tmp.resolve("bucket1/a")));

        // GET 时自愈:把索引条目摘掉
        assertThrows(S3Exception.class, () -> objectStore.get("bucket1", "a", 0, -1));
        assertTrue(objectStore.list("bucket1", unlimited()).contents().isEmpty(),
                "body 没了索引条目也要摘掉");
    }
}
