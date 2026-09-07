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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 磁盘 ObjectStore 的元数据 sidecar —— 每个对象对应一个 {@code .meta} 文件,
 * 存 ETag / size / contentType / lastModified / user metadata。
 *
 * <p>文件格式(简单 key=value,一行一对,便于人读 + 将来改):
 * <pre>
 *   etag=abcd1234...
 *   size=12345
 *   content-type=application/octet-stream
 *   last-modified=2026-09-06T10:30:00Z
 *   user-meta.foo=bar
 *   user-meta.x-source=test
 * </pre>
 *
 * <p>由 {@link DiskObjectStore} 读 / 写;不需要单独 lock —— ObjectStore 自身
 * 在 put 路径上同步。
 */
final class MetaFile {

    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private MetaFile() {}

    static void write(Path file, ObjectMeta meta) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("etag="); w.write(meta.etag() == null ? "" : meta.etag()); w.newLine();
            w.write("size="); w.write(String.valueOf(meta.size())); w.newLine();
            w.write("content-type=");
            w.write(meta.contentType() == null ? "" : meta.contentType()); w.newLine();
            w.write("last-modified=");
            w.write(meta.lastModified() == null ? "" : ISO_FMT.format(meta.lastModified()));
            w.newLine();
            if (meta.userMetadata() != null) {
                for (Map.Entry<String, String> e : meta.userMetadata().entrySet()) {
                    w.write("user-meta."); w.write(e.getKey());
                    w.write('='); w.write(e.getValue() == null ? "" : e.getValue());
                    w.newLine();
                }
            }
        }
    }

    static ObjectMeta read(Path file, String key) throws IOException {
        Map<String, String> userMeta = new LinkedHashMap<>();
        String etag = null;
        long size = 0;
        String contentType = null;
        Instant lastModified = null;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String name = line.substring(0, eq);
                String value = line.substring(eq + 1);
                switch (name) {
                    case "etag": etag = value; break;
                    case "size": size = Long.parseLong(value); break;
                    case "content-type": contentType = value.isEmpty() ? null : value; break;
                    case "last-modified":
                        lastModified = value.isEmpty() ? Instant.now() : Instant.from(ISO_FMT.parse(value));
                        break;
                    default:
                        if (name.startsWith("user-meta.")) {
                            userMeta.put(name.substring("user-meta.".length()), value);
                        }
                }
            }
        }
        return new ObjectMeta(key, size, etag, contentType, lastModified, userMeta);
    }
}