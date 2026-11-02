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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 老布局(每对象一个 {@code <key>.meta} sidecar)→ 新布局(per-bucket 索引 log)
 * 的<b>一次性迁移</b>。
 *
 * <p>迁移规则:
 * <ul>
 *   <li>sidecar 相对路径去掉 {@code .meta} 后缀 = 对象 key(所以带 {@code /}
 *       的 key 也在内)</li>
 *   <li>没有对应 body 的 sidecar(孤儿)跳过</li>
 *   <li>sidecar <b>不删</b> —— 回滚保险</li>
 *   <li>幂等:已有 {@code .index/log} 的桶不走迁移</li>
 * </ul>
 */
public class LegacyMetaMigrationTest {

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

    private static ListOptions unlimited() {
        return ListOptions.of("", "", ListOptions.MAX_KEYS_UNLIMITED);
    }

    /**
     * 手工铺一个老布局对象 —— body + sidecar。
     */
    private void writeLegacy(String key, String body, String etag,
                             String contentType, Map<String, String> userMeta) throws IOException {
        Path bucketDir = tmp.resolve("bucket1");
        Path bodyFile = bucketDir.resolve(key);
        Path parent = bodyFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(bodyFile, body.getBytes(StandardCharsets.UTF_8));
        ObjectMeta meta = new ObjectMeta(key, body.getBytes(StandardCharsets.UTF_8).length,
                etag, contentType, java.time.Instant.parse("2026-01-02T03:04:05Z"), userMeta);
        MetaFile.write(bucketDir.resolve(key + ".meta"), meta);
    }

    private static Map<String, String> userMeta(String k, String v) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    private List<String> keys() throws IOException {
        ListResult r = objectStore.list("bucket1", unlimited());
        return r.contents().stream().map(ObjectMeta::key).collect(java.util.stream.Collectors.toList());
    }

    // ================= 迁移触发 =================

    @Test
    void legacyBucketGetsIndexLogOnFirstAccess() throws Exception {
        writeLegacy("a.txt", "hello", "e1", "text/plain", Collections.emptyMap());
        assertFalse(Files.exists(tmp.resolve("bucket1/.index/log")));

        objectStore.init();          // 启动期钩子

        assertTrue(Files.exists(tmp.resolve("bucket1/.index/log")),
                "老桶应被迁移出索引 log");
        assertEquals(List.of("a.txt"), keys());
    }

    @Test
    void migrationHappensLazilyWithoutInit() throws Exception {
        writeLegacy("a.txt", "hello", "e1", "text/plain", Collections.emptyMap());
        // 不调 init() —— 第一次访问该桶时也要能正确列出
        assertEquals(List.of("a.txt"), keys());
        assertTrue(Files.exists(tmp.resolve("bucket1/.index/log")));
    }

    @Test
    void sidecarIsKeptAsRollbackInsurance() throws Exception {
        writeLegacy("a.txt", "hello", "e1", "text/plain", Collections.emptyMap());
        objectStore.init();
        assertTrue(Files.exists(tmp.resolve("bucket1/a.txt.meta")),
                "迁移不删 sidecar(回滚保险)");
    }

    @Test
    void migratedMetadataMatchesSidecar() throws Exception {
        writeLegacy("a.txt", "hello", "e1", "text/plain", userMeta("x-src", "legacy"));
        objectStore.init();

        ObjectMeta m = objectStore.head("bucket1", "a.txt");
        assertEquals("a.txt", m.key());
        assertEquals(5, m.size());
        assertEquals("e1", m.etag());
        assertEquals("text/plain", m.contentType());
        assertEquals("legacy", m.userMetadata().get("x-src"));
    }

    @Test
    void keysWithSlashAreMigrated() throws Exception {
        writeLegacy("dir/a.txt", "x", "e1", "text/plain", Collections.emptyMap());
        writeLegacy("dir/deep/b.txt", "y", "e2", "text/plain", Collections.emptyMap());
        writeLegacy("flat.txt", "z", "e3", "text/plain", Collections.emptyMap());
        objectStore.init();

        assertEquals(List.of("dir/a.txt", "dir/deep/b.txt", "flat.txt"), keys());
    }

    @Test
    void orphanSidecarWithoutBodyIsSkipped() throws Exception {
        writeLegacy("a.txt", "hello", "e1", "text/plain", Collections.emptyMap());
        // 只有 sidecar、没有 body ⇒ 孤儿,不迁移
        ObjectMeta ghost = new ObjectMeta("ghost.txt", 3, "eg", "text/plain",
                java.time.Instant.parse("2026-01-02T03:04:05Z"), Collections.emptyMap());
        MetaFile.write(tmp.resolve("bucket1/ghost.txt.meta"), ghost);

        objectStore.init();

        assertEquals(List.of("a.txt"), keys());
    }

    @Test
    void migrationIsIdempotent() throws Exception {
        writeLegacy("a.txt", "hello", "e1", "text/plain", Collections.emptyMap());
        objectStore.init();
        Path log = tmp.resolve("bucket1/.index/log");
        long sizeAfterFirst = Files.size(log);

        // 再开一次 store —— 不该重写 log
        objectStore.close();
        DiskObjectStore reopened = new DiskObjectStore(bucketStore);
        reopened.init();
        assertEquals(sizeAfterFirst, Files.size(log));
        assertEquals(List.of("a.txt"), keys());
        reopened.close();
    }

    @Test
    void newObjectsAfterMigrationAppendToSameLog() throws Exception {
        writeLegacy("a.txt", "hello", "e1", "text/plain", Collections.emptyMap());
        objectStore.init();

        byte[] data = "v2".getBytes(StandardCharsets.UTF_8);
        objectStore.put("bucket1", "b.txt", new PutStream(new ByteArrayInputStream(data),
                "text/plain", Collections.emptyMap(), data.length, null));

        assertEquals(List.of("a.txt", "b.txt"), keys());
        // 只有一个 log 文件(不是每个桶两个)
        assertTrue(Files.exists(tmp.resolve("bucket1/.index/log")));
    }

    @Test
    void deleteAfterMigrationRemovesFromIndex() throws Exception {
        writeLegacy("a.txt", "hello", "e1", "text/plain", Collections.emptyMap());
        objectStore.init();
        objectStore.delete("bucket1", "a.txt");

        assertTrue(keys().isEmpty());
        assertTrue(objectStore.isEmpty("bucket1"));
        assertFalse(Files.exists(tmp.resolve("bucket1/a.txt")));
        // 老 sidecar 也顺手清掉
        assertFalse(Files.exists(tmp.resolve("bucket1/a.txt.meta")));
    }

    @Test
    void migratedBucketIsEmptyAfterDeletingAll() throws Exception {
        writeLegacy("a.txt", "hello", "e1", "text/plain", Collections.emptyMap());
        writeLegacy("dir/b.txt", "x", "e2", "text/plain", Collections.emptyMap());
        objectStore.init();

        objectStore.delete("bucket1", "a.txt");
        objectStore.delete("bucket1", "dir/b.txt");

        assertTrue(objectStore.isEmpty("bucket1"),
                "迁移后的桶删光对象也应为空(老 sidecar 残留不算对象)");
    }

    @Test
    void emptyLegacyBucketMigratesToEmptyIndex() throws Exception {
        objectStore.init();
        assertTrue(Files.exists(tmp.resolve("bucket1/.index/log")));
        assertTrue(keys().isEmpty());
        assertTrue(objectStore.isEmpty("bucket1"));
    }

    @Test
    void aclFileIsNotMigratedAsObject() throws Exception {
        writeLegacy("a.txt", "hello", "e1", "text/plain", Collections.emptyMap());
        bucketStore.setCannedAcl("bucket1", io.edap.s3.model.BucketCannedAcl.PUBLIC_READ);
        objectStore.init();

        assertEquals(List.of("a.txt"), keys());
        assertNotNull(objectStore.head("bucket1", "a.txt"));
        // .acl 是保留名 ⇒ 读路径报 NO_SUCH_KEY(不能泄露桶配置)
        assertThrows(S3Exception.class, () -> objectStore.head("bucket1", ".acl"));
    }
}
