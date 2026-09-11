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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * multipart upload 元数据持久化 —— 同 {@link MetaFile} 一样的 key=value 文本格式,
 * 但带嵌套 parts map。
 *
 * <p>文件布局:
 * <pre>
 *   upload-id=8b1f2e30-...
 *   bucket=bucket1
 *   key=uploads/2026/photo.jpg
 *   content-type=image/jpeg
 *   initiated=2026-09-11T10:23:45Z
 *   user-meta.foo=bar
 *   user-meta.x-source=test
 *   part.00001.etag=5d41402abc4b2a76b9719d911017c592
 *   part.00001.size=3
 *   part.00001.last-modified=2026-09-11T10:24:01Z
 *   part.00002.etag=...
 *   part.00002.size=5
 *   part.00002.last-modified=...
 * </pre>
 *
 * <p>原子写:写到 {@code meta.json.tmp},fsync 后 rename 成 {@code meta.json}
 * (atomic)。</p>
 */
final class UploadMetaFile {

    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    static final String META_FILE = "meta.json";
    static final String COMPLETING_MARKER = "meta.json.completing";

    private UploadMetaFile() {}

    /** 在 upload 目录里原子写 meta.json(.tmp + rename)。 */
    static void writeAtomic(Path uploadDir, UploadMeta meta) throws IOException {
        Path metaFile = uploadDir.resolve(META_FILE);
        Path tmp = Files.createTempFile(uploadDir, ".meta.", ".tmp");
        try {
            try (BufferedWriter w = Files.newBufferedWriter(tmp, java.nio.charset.StandardCharsets.UTF_8)) {
                w.write("upload-id="); w.write(nullSafe(meta.uploadId)); w.newLine();
                w.write("bucket=");    w.write(nullSafe(meta.bucket));    w.newLine();
                w.write("key=");       w.write(nullSafe(meta.key));       w.newLine();
                w.write("content-type="); w.write(nullSafe(meta.contentType)); w.newLine();
                w.write("initiated="); w.write(meta.initiated == null
                        ? ISO_FMT.format(Instant.now()) : ISO_FMT.format(meta.initiated));
                w.newLine();
                if (meta.userMetadata != null) {
                    for (Map.Entry<String, String> e : meta.userMetadata.entrySet()) {
                        w.write("user-meta."); w.write(e.getKey());
                        w.write('='); w.write(nullSafe(e.getValue()));
                        w.newLine();
                    }
                }
                if (meta.parts != null) {
                    // 按 partNumber 升序写,便于人读
                    List<Integer> pns = new ArrayList<>(meta.parts.size());
                    for (String k : meta.parts.keySet()) {
                        try { pns.add(Integer.parseInt(k)); } catch (NumberFormatException ignored) {}
                    }
                    Collections.sort(pns);
                    for (int pn : pns) {
                        PartMeta pm = meta.parts.get(String.format("%05d", pn));
                        if (pm == null) continue;
                        String key = "part." + String.format("%05d", pn);
                        w.write(key); w.write(".etag="); w.write(nullSafe(pm.etag)); w.newLine();
                        w.write(key); w.write(".size="); w.write(String.valueOf(pm.size)); w.newLine();
                        w.write(key); w.write(".last-modified=");
                        w.write(pm.lastModified == null
                                ? ISO_FMT.format(Instant.now())
                                : ISO_FMT.format(pm.lastModified));
                        w.newLine();
                    }
                }
            }
            try {
                Files.move(tmp, metaFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, metaFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            throw e;
        }
    }

    static UploadMeta read(Path uploadDir) throws IOException {
        return readFrom(uploadDir.resolve(META_FILE));
    }

    /** 从任意 key=value 文件读 upload 元数据(兼容 meta.json 和 meta.json.completing)。 */
    static UploadMeta readFrom(Path file) throws IOException {
        UploadMeta meta = new UploadMeta();
        meta.userMetadata = new LinkedHashMap<>();
        meta.parts = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (BufferedReader r = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String name = line.substring(0, eq);
                String value = line.substring(eq + 1);
                if ("upload-id".equals(name)) {
                    meta.uploadId = value;
                } else if ("bucket".equals(name)) {
                    meta.bucket = value;
                } else if ("key".equals(name)) {
                    meta.key = value;
                } else if ("content-type".equals(name)) {
                    // 空字符串保持 ""，不转 null —— initiate() 用 "" 表达"未指定",
                    // complete() 用 meta.contentType.isEmpty() 判断,转 null 会 NPE
                    meta.contentType = value;
                } else if ("initiated".equals(name)) {
                    meta.initiated = value.isEmpty() ? Instant.now() : Instant.from(ISO_FMT.parse(value));
                } else if (name.startsWith("user-meta.")) {
                    meta.userMetadata.put(name.substring("user-meta.".length()), value);
                } else if (name.startsWith("part.")) {
                    // 形式:part.NNNNN.etag / .size / .last-modified
                    String rest = name.substring("part.".length());      // NNNNN.etag
                    int dot = rest.indexOf('.');
                    if (dot < 0) continue;
                    String pnKey = rest.substring(0, dot);                // NNNNN
                    String field = rest.substring(dot + 1);              // etag/size/last-modified
                    PartMeta pm = meta.parts.computeIfAbsent(pnKey, k -> new PartMeta());
                    switch (field) {
                        case "etag":
                            pm.etag = value;
                            break;
                        case "size":
                            try { pm.size = Long.parseLong(value); } catch (NumberFormatException ignored) {}
                            break;
                        case "last-modified":
                            pm.lastModified = value.isEmpty() ? Instant.now() : Instant.from(ISO_FMT.parse(value));
                            break;
                    }
                }
            }
        }
        return meta;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    /** multipart upload 元数据 —— 跟 {@link MetaFile} 一样 immutable after read。 */
    static final class UploadMeta {
        String uploadId;
        String bucket;
        String key;
        String contentType;
        Instant initiated;
        Map<String, String> userMetadata;
        /** key = 5 位补零的 partNumber 字符串 */
        Map<String, PartMeta> parts;
    }

    static final class PartMeta {
        String etag;
        long   size;
        Instant lastModified;
    }
}
