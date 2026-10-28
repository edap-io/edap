/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.raft.storage;

import io.edap.raft.RaftException;
import io.edap.raft.protocol.ConfState;
import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.EntryType;
import io.edap.raft.protocol.RaftCodec;
import io.edap.raft.protocol.Snapshot;
import io.edap.raft.protocol.SnapshotMetadata;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * etcd {@code storage_test.go} 的 Java 实现。
 */
public class MemoryStorageTest {

    /**
     * Go: TestStorageTerm
     *
     * <p>查不到日志时要区分两种情况：index 落在占位条目之前说明日志已经被
     * 压缩掉（ErrCompacted），index 超出最后一条日志说明这条日志还不存在
     * （ErrUnavailable）。</p>
     */
    @Test
    // Go: TestStorageTerm
    void testStorageTerm() {
        List<Entry> ents = entries(3, 3, 4, 5);

        long[] indexes = {2, 3, 4, 5, 6};
        // 期望的错误信息片段，null 表示不报错
        String[] werr = {"due to compaction", null, null, null, "is unavailable"};
        long[] wterm = {0, 3, 4, 5, 0};

        for (int i = 0; i < indexes.length; i++) {
            final long index = indexes[i];
            MemoryStorage s = new MemoryStorage(ents);
            if (werr[i] == null) {
                assertEquals(wterm[i], s.term(index).getTerm(), "term of case " + i);
            } else {
                RaftException e = assertThrows(RaftException.class, () -> s.term(index),
                        "term of case " + i);
                assertTrue(e.getMessage().contains(werr[i]),
                        "term of case " + i + ": " + e.getMessage());
            }
        }
    }

    /**
     * Go: TestStorageEntries
     */
    @Test
    // Go: TestStorageEntries
    void testStorageEntries() {
        List<Entry> ents = entries(3, 3, 4, 5, 6);
        // Go 里用 proto.Size 计算每条日志的编码长度，Java 侧用 RaftCodec.entrySize
        long size1 = RaftCodec.entrySize(ents.get(1));
        long size2 = RaftCodec.entrySize(ents.get(2));
        long size3 = RaftCodec.entrySize(ents.get(3));

        long max = Long.MAX_VALUE;
        long[][] cases = {
                // lo, hi, maxSize
                {2, 6, max},
                {3, 4, max},
                {4, 5, max},
                {4, 6, max},
                {4, 7, max},
                // 即使 maxSize 是 0，也要返回第一条
                {4, 7, 0},
                // 限制到 2 条
                {4, 7, size1 + size2},
                // 限制到 2 条
                {4, 7, size1 + size2 + size3 / 2},
                {4, 7, size1 + size2 + size3 - 1},
                // 全部
                {4, 7, size1 + size2 + size3},
        };
        // 期望的错误信息片段，null 表示不报错
        String[] werr = {
                "due to compaction",
                "due to compaction",
                null, null, null, null, null, null, null, null,
        };
        List<List<Entry>> wentries = Arrays.asList(
                null,
                null,
                entries(4, 4),
                entries(4, 4, 5),
                entries(4, 4, 5, 6),
                entries(4, 4),
                entries(4, 4, 5),
                entries(4, 4, 5),
                entries(4, 4, 5),
                entries(4, 4, 5, 6));

        for (int i = 0; i < cases.length; i++) {
            final long lo = cases[i][0];
            final long hi = cases[i][1];
            final long maxSize = cases[i][2];
            MemoryStorage s = new MemoryStorage(ents);
            if (werr[i] != null) {
                RaftException e = assertThrows(RaftException.class, () -> s.entries(lo, hi, maxSize),
                        "entries of case " + i);
                assertTrue(e.getMessage().contains(werr[i]),
                        "entries of case " + i + ": " + e.getMessage());
                continue;
            }
            List<Entry> got = s.entries(lo, hi, maxSize);
            assertEquals(wentries.get(i), got, "entries of case " + i);
        }
    }

    /**
     * Go: TestStorageLastIndex
     */
    @Test
    // Go: TestStorageLastIndex
    void testStorageLastIndex() {
        List<Entry> ents = entries(3, 3, 4, 5);
        MemoryStorage s = new MemoryStorage(ents);

        assertEquals(5, s.lastIndex(), "lastIndex");

        s.append(entries(6, 5));
        assertEquals(6, s.lastIndex(), "lastIndex after append");
    }

    /**
     * Go: TestStorageFirstIndex
     */
    @Test
    // Go: TestStorageFirstIndex
    void testStorageFirstIndex() {
        List<Entry> ents = entries(3, 3, 4, 5);
        MemoryStorage s = new MemoryStorage(ents);

        assertEquals(4, s.firstIndex(), "firstIndex");

        s.compact(4);
        assertEquals(5, s.firstIndex(), "firstIndex after compact");
    }

    /**
     * Go: TestStorageCompact
     */
    @Test
    // Go: TestStorageCompact
    void testStorageCompact() {
        List<Entry> ents = entries(3, 3, 4, 5);

        long[] indexes = {2, 3, 4, 5};
        // 期望的错误信息片段，null 表示不报错
        String[] werr = {"due to compaction", "due to compaction", null, null};
        long[] windex = {3, 3, 4, 5};
        long[] wterm = {3, 3, 4, 5};
        int[] wlen = {3, 3, 2, 1};

        for (int i = 0; i < indexes.length; i++) {
            final long index = indexes[i];
            MemoryStorage s = new MemoryStorage(ents);
            if (werr[i] != null) {
                RaftException e = assertThrows(RaftException.class, () -> s.compact(index),
                        "compact of case " + i);
                assertTrue(e.getMessage().contains(werr[i]),
                        "compact of case " + i + ": " + e.getMessage());
            } else {
                s.compact(index);
            }
            List<Entry> got = s.getEnts();
            assertEquals(windex[i], got.get(0).getIndex(), "ents[0].Index of case " + i);
            assertEquals(wterm[i], got.get(0).getTerm(), "ents[0].Term of case " + i);
            assertEquals(wlen[i], got.size(), "len(ents) of case " + i);
        }
    }

    /**
     * Go: TestStorageCreateSnapshot
     */
    @Test
    // Go: TestStorageCreateSnapshot
    void testStorageCreateSnapshot() {
        List<Entry> ents = entries(3, 3, 4, 5);
        ConfState cs = new ConfState(Arrays.asList(1L, 2L, 3L), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), false);
        byte[] data = "data".getBytes();

        long[] indexes = {4, 5};
        Snapshot[] wsnap = {
                new Snapshot(data, new SnapshotMetadata(cs, 4, 4)),
                new Snapshot(data, new SnapshotMetadata(cs, 5, 5)),
        };

        for (int i = 0; i < indexes.length; i++) {
            MemoryStorage s = new MemoryStorage(ents);
            Snapshot snap = s.createSnapshot(indexes[i], cs, data);
            assertEquals(wsnap[i], snap, "snapshot of case " + i);
        }
    }

    /**
     * Go: TestStorageAppend
     */
    @Test
    // Go: TestStorageAppend
    void testStorageAppend() {
        List<Entry> ents = entries(3, 3, 4, 5);

        List<List<Entry>> cases = Arrays.asList(
                // 全部都已经被压缩掉了，什么都不做
                entries(1, 1, 2),
                // 完全覆盖
                entries(3, 3, 4, 5),
                entries(3, 3, 6, 6),
                entries(3, 3, 4, 5, 5),
                // 截断传入的日志，截断已有的日志再追加
                entries(2, 3, 3, 5),
                // 截断已有的日志再追加
                entries(4, 5),
                // 直接追加
                entries(6, 5));

        List<List<Entry>> wentries = Arrays.asList(
                entries(3, 3, 4, 5),
                entries(3, 3, 4, 5),
                entries(3, 3, 6, 6),
                entries(3, 3, 4, 5, 5),
                entries(3, 3, 5),
                entries(3, 3, 5),
                entries(3, 3, 4, 5, 5));

        for (int i = 0; i < cases.size(); i++) {
            MemoryStorage s = new MemoryStorage(ents);
            s.append(cases.get(i));
            assertEquals(wentries.get(i), s.getEnts(), "ents of case " + i);
        }
    }

    /**
     * Go: TestStorageApplySnapshot
     */
    @Test
    // Go: TestStorageApplySnapshot
    void testStorageApplySnapshot() {
        ConfState cs = new ConfState(Arrays.asList(1L, 2L, 3L), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), false);
        byte[] data = "data".getBytes();

        // 第一个快照：正常应用
        Snapshot snap4 = new Snapshot(data, new SnapshotMetadata(cs, 4, 4));
        // 第二个快照：比已有的快照还旧
        Snapshot snap3 = new Snapshot(data, new SnapshotMetadata(cs, 3, 3));
        // 第三个快照：bootstrap 时只带 ConfState，index/term 都是 0
        Snapshot bootstrap = new Snapshot(data, new SnapshotMetadata(cs, 0, 0));

        // 正常情况
        MemoryStorage normal = new MemoryStorage();
        normal.applySnapshot(snap4);

        // 快照过期
        MemoryStorage outdated = new MemoryStorage();
        outdated.applySnapshot(snap4);
        RaftException e = assertThrows(RaftException.class, () -> outdated.applySnapshot(snap3),
                "snapshot out of date");
        assertTrue(e.getMessage().contains("older than the existing snapshot"),
                "snapshot out of date: " + e.getMessage());

        // 只带 ConfState 的引导快照
        MemoryStorage bootstrapStorage = new MemoryStorage();
        bootstrapStorage.applySnapshot(bootstrap);
    }

    /**
     * 构造一批日志，等价于 Go 测试里的 {@code index(start).terms(...)}。
     */
    private static List<Entry> entries(long startIndex, long... terms) {
        List<Entry> ents = new ArrayList<>(terms.length);
        for (int i = 0; i < terms.length; i++) {
            ents.add(new Entry(startIndex + i, terms[i], EntryType.NORMAL, null));
        }
        return ents;
    }
}
