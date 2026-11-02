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

import io.edap.s3.model.ObjectMeta;
import io.edap.s3.store.Utf8KeyOrder;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 老布局(每对象一个 {@code <key>.meta} sidecar)→ 新布局(per-bucket 索引 log)
 * 的<b>一次性迁移</b>。
 *
 * <p>流程:
 * <ol>
 *   <li>递归扫桶目录,找出所有 {@code *.meta} sidecar(跳过 {@code .index/} 与
 *       {@code .multipart/} 子树)</li>
 *   <li>sidecar 的相对路径去掉 {@code .meta} 后缀 = 对象 key</li>
 *   <li>没有对应 body 文件的 sidecar(孤儿)跳过</li>
 *   <li>按 {@link Utf8KeyOrder} 排序 ⇒ 写出的 log 是有序的</li>
 *   <li>写 {@code .index/log.tmp} → {@code ATOMIC_MOVE} 成 {@code .index/log}</li>
 * </ol>
 *
 * <p><b>不删除任何 {@code .meta}</b> —— 保留 sidecar 作为回滚保险(代价是老桶
 * 会一直占着这部分磁盘)。
 *
 * <p>幂等:已经存在 {@code .index/log} 的桶不会走这条路。
 */
final class LegacyMetaMigrator {

    private static final String META_SUFFIX = ".meta";

    private LegacyMetaMigrator() {}

    /**
     * 扫老布局,产出排序后的索引记录。
     *
     * @param bucketDir 桶目录
     */
    static List<IndexRecord> collect(Path bucketDir) throws IOException {
        List<IndexRecord> records = new ArrayList<>();
        if (!Files.exists(bucketDir)) {
            return records;
        }
        try (Stream<Path> walk = Files.walk(bucketDir)) {
            for (Iterator<Path> it = walk.iterator(); it.hasNext(); ) {
                Path metaFile = it.next();
                if (!Files.isRegularFile(metaFile)) continue;
                String rel = bucketDir.relativize(metaFile).toString();
                if (!rel.endsWith(META_SUFFIX)) continue;
                // 保留目录里的东西不是对象
                if (rel.startsWith(".index" + java.io.File.separator)) continue;
                if (rel.startsWith(".multipart" + java.io.File.separator)) continue;
                String key = rel.substring(0, rel.length() - META_SUFFIX.length());
                if (key.endsWith(".tmp")) continue;          // 崩溃残留的临时文件
                if (!Files.exists(bucketDir.resolve(key))) continue;   // 孤儿 sidecar
                ObjectMeta meta = MetaFile.read(metaFile, key);
                records.add(IndexRecord.putOf(meta));
            }
        }
        records.sort(Comparator.comparing(IndexRecord::key, Utf8KeyOrder.INSTANCE));
        return records;
    }

    /**
     * 把记录写成索引 log(tmp → atomic rename)。log 已存在时不覆盖。
     *
     * @return true = 本次新建了 log;false = log 已存在,没动
     */
    static boolean writeLog(Path logPath, List<IndexRecord> records) throws IOException {
        if (Files.exists(logPath)) {
            return false;
        }
        Path parent = logPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = logPath.resolveSibling("log.tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            w.write(IndexCodec.MAGIC);
            w.write('\n');
            for (IndexRecord rec : records) {
                w.write(IndexCodec.encodeLine(rec));
                w.write('\n');
            }
        }
        try {
            Files.move(tmp, logPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, logPath, StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    /**
     * 灾难恢复:索引 log 整体损坏时,从桶目录里的 <b>body 文件</b>重建索引
     * (etag 现算 MD5、size 取文件长度、lastModified 取文件 mtime)。
     *
     * <p>不能用老 sidecar 作数 —— 它们可能已经过时(对象删了但 sidecar 还在)。
     *
     * <p>sidecar 的判定:文件 {@code X.meta} 若同时存在 {@code X.meta.meta},
     * 说明它自己也是某个对象的 sidecar ⇒ 跳过。否则它就是一个 key 恰好以
     * {@code .meta} 结尾的对象 body。
     */
    static List<IndexRecord> rebuildFromBodies(Path bucketDir) throws IOException {
        List<IndexRecord> records = new ArrayList<>();
        if (!Files.exists(bucketDir)) {
            return records;
        }
        try (Stream<Path> walk = Files.walk(bucketDir)) {
            for (Iterator<Path> it = walk.iterator(); it.hasNext(); ) {
                Path p = it.next();
                if (!Files.isRegularFile(p)) continue;
                String rel = bucketDir.relativize(p).toString();
                if (rel.startsWith(".index" + java.io.File.separator)) continue;
                if (rel.startsWith(".multipart" + java.io.File.separator)) continue;
                if (rel.equals(".acl")) continue;
                if (rel.endsWith(".tmp")) continue;
                if (rel.endsWith(".meta") && Files.exists(p.resolveSibling(p.getFileName() + ".meta"))) {
                    continue;       // 这是老 sidecar,不是 body
                }
                records.add(IndexRecord.put(rel, md5Hex(p), Files.size(p),
                        null, p.toFile().lastModified(), null));
            }
        }
        records.sort(Comparator.comparing(IndexRecord::key, Utf8KeyOrder.INSTANCE));
        return records;
    }

    private static String md5Hex(Path file) throws IOException {
        java.security.MessageDigest md5;
        try {
            md5 = java.security.MessageDigest.getInstance("MD5");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("MD5 algorithm unavailable", e);
        }
        byte[] buf = new byte[8192];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                md5.update(buf, 0, n);
            }
        }
        byte[] digest = md5.digest();
        char[] out = new char[digest.length * 2];
        final char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < digest.length; i++) {
            int v = digest[i] & 0xff;
            out[i * 2] = hex[v >>> 4];
            out[i * 2 + 1] = hex[v & 0x0f];
        }
        return new String(out);
    }
}
