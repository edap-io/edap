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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link IndexLog} —— 追加 / 回放 / 尾部损坏修复 / magic 损坏。
 */
public class IndexLogTest {

    @TempDir
    Path tmp;

    private Path logPath() {
        return tmp.resolve(".index/log");
    }

    private void appendRaw(String content) throws IOException {
        Files.createDirectories(tmp.resolve(".index"));
        Files.write(logPath(), content.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    @Test
    void freshLogGetsMagicLine() throws Exception {
        try (IndexLog log = IndexLog.open(logPath(), false)) {
            assertEquals(List.of(), log.replay());
        }
        String content = new String(Files.readAllBytes(logPath()), StandardCharsets.UTF_8);
        assertEquals(IndexCodec.MAGIC + "\n", content);
    }

    @Test
    void appendAndReplayRoundTrip() throws Exception {
        try (IndexLog log = IndexLog.open(logPath(), false)) {
            log.replay();
            log.append(IndexRecord.put("a", "e1", 1, "t", 100L, null));
            log.append(IndexRecord.delete("b"));
            log.append(IndexRecord.put("c/d", "e2", 2, "t", 200L, null));
        }
        try (IndexLog log = IndexLog.open(logPath(), false)) {
            List<IndexRecord> recs = log.replay();
            assertEquals(3, recs.size());
            assertEquals("a", recs.get(0).key());
            assertEquals(IndexRecord.Op.DELETE, recs.get(1).op());
            assertEquals("c/d", recs.get(2).key());
        }
    }

    @Test
    void truncatedTailRecordIsDroppedAndFileTruncated() throws Exception {
        try (IndexLog log = IndexLog.open(logPath(), false)) {
            log.replay();
            log.append(IndexRecord.put("good", "e", 1, "t", 0L, null));
        }
        // 追加一条只写了一半的记录(没有换行)
        appendRaw("P\tbroken\te\t1\tt\t0\t0");

        long sizeBefore = Files.size(logPath());
        try (IndexLog log = IndexLog.open(logPath(), false)) {
            List<IndexRecord> recs = log.replay();
            assertEquals(1, recs.size());
            assertEquals("good", recs.get(0).key());
        }
        long sizeAfter = Files.size(logPath());
        assertTrue(sizeAfter < sizeBefore, "尾部半条应被截断");
    }

    @Test
    void crcMismatchDropsThatLineAndEverythingAfter() throws Exception {
        try (IndexLog log = IndexLog.open(logPath(), false)) {
            log.replay();
            log.append(IndexRecord.put("first", "e", 1, "t", 0L, null));
            log.append(IndexRecord.put("second", "e", 1, "t", 0L, null));
            log.append(IndexRecord.put("third", "e", 1, "t", 0L, null));
        }
        // 把第二条记录的 payload 改坏(CRC 不再匹配)
        String content = new String(Files.readAllBytes(logPath()), StandardCharsets.UTF_8);
        int idx = content.indexOf("second");
        assertTrue(idx > 0);
        content = content.substring(0, idx) + "SECOND" + content.substring(idx + 6);
        Files.write(logPath(), content.getBytes(StandardCharsets.UTF_8));

        try (IndexLog log = IndexLog.open(logPath(), false)) {
            List<IndexRecord> recs = log.replay();
            assertEquals(1, recs.size());
            assertEquals("first", recs.get(0).key());
        }
    }

    @Test
    void badMagicIsReportedAsCorrupt() throws Exception {
        Files.createDirectories(tmp.resolve(".index"));
        Files.write(logPath(), "GARBAGE\nP\tk\n".getBytes(StandardCharsets.UTF_8));
        try (IndexLog log = IndexLog.open(logPath(), false)) {
            IndexLog.CorruptException ex = assertThrows(IndexLog.CorruptException.class, log::replay);
            assertTrue(ex.getMessage().contains("magic"));
        }
    }

    @Test
    void emptyFileGetsMagicOnReplay() throws Exception {
        Files.createDirectories(tmp.resolve(".index"));
        Files.write(logPath(), new byte[0]);
        try (IndexLog log = IndexLog.open(logPath(), false)) {
            assertEquals(List.of(), log.replay());
        }
        String content = new String(Files.readAllBytes(logPath()), StandardCharsets.UTF_8);
        assertEquals(IndexCodec.MAGIC + "\n", content);
    }

    @Test
    void appendAfterRepairContinuesCleanly() throws Exception {
        try (IndexLog log = IndexLog.open(logPath(), false)) {
            log.replay();
            log.append(IndexRecord.put("a", "e", 1, "t", 0L, null));
        }
        appendRaw("P\thalf");
        try (IndexLog log = IndexLog.open(logPath(), false)) {
            log.replay();                       // 修复尾巴
            log.append(IndexRecord.put("b", "e", 1, "t", 0L, null));
        }
        try (IndexLog log = IndexLog.open(logPath(), false)) {
            List<IndexRecord> recs = log.replay();
            assertEquals(2, recs.size());
            assertEquals("a", recs.get(0).key());
            assertEquals("b", recs.get(1).key());
        }
    }
}
