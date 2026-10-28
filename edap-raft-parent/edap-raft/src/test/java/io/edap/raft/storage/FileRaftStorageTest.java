/*
 * Copyright 2023 The edap Project
 *
 * The NetPy Project licenses this file to you under the Apache License,
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
import io.edap.raft.protocol.HardState;
import io.edap.raft.protocol.Snapshot;
import io.edap.raft.protocol.SnapshotMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FileRaftStorage} 的测试。
 *
 * <p>覆盖 etcd {@code storage_test.go} 里的语义（内存实现那份已经覆盖），
 * 重点放在文件实现特有的部分：重启后数据还在、冲突截断、尾部半写被丢弃、
 * 硬状态与成员配置的持久化。</p>
 */
public class FileRaftStorageTest {

    /**
     * 空的存储：没有硬状态，也没有日志
     */
    @Test
    void testInitialState(@TempDir Path dir) {
        try (FileRaftStorage s = new FileRaftStorage(dir)) {
            assertNull(s.initialState());
            assertEquals(1, s.firstIndex());
            assertEquals(0, s.lastIndex());
            assertNotNull(s.snapshot());
            assertEquals(0, s.snapshot().getLastIncludedIndex());
        }
    }

    /**
     * 追加的日志在重启之后还在
     */
    @Test
    void testAppendSurvivesRestart(@TempDir Path dir) {
        try (FileRaftStorage s = new FileRaftStorage(dir)) {
            s.append(entries(1, 1, 2, 3));
            assertEquals(1, s.firstIndex());
            assertEquals(3, s.lastIndex());
        }

        try (FileRaftStorage s = new FileRaftStorage(dir)) {
            assertEquals(1, s.firstIndex());
            assertEquals(3, s.lastIndex());
            assertEquals(1, s.term(1).getTerm());
            assertEquals(2, s.term(2).getTerm());
            assertEquals(3, s.term(3).getTerm());

            List<Entry> got = s.entries(1, 4);
            assertEquals(3, got.size());
            assertEquals(1, got.get(0).getIndex());
            assertEquals(3, got.get(2).getIndex());
        }
    }

    /**
     * 硬状态落盘，重启后能读回来
     */
    @Test
    void testHardStateSurvivesRestart(@TempDir Path dir) {
        try (FileRaftStorage s = new FileRaftStorage(dir)) {
            s.setHardState(new HardState(7, 3, 12));
        }

        try (FileRaftStorage s = new FileRaftStorage(dir)) {
            HardState hs = s.initialState();
            assertNotNull(hs);
            assertEquals(7, hs.getTerm());
            assertEquals(3, hs.getVote());
            assertEquals(12, hs.getCommit());
        }
    }

    /**
     * 压缩之后，被压缩掉的日志查不到了，但压缩点上的 term 还能查到
     */
    @Test
    void testCompact(@TempDir Path dir) {
        try (FileRaftStorage s = new FileRaftStorage(dir)) {
            s.append(entries(1, 1, 2, 3, 4));
            s.compact(3);

            assertEquals(4, s.firstIndex());
            assertEquals(4, s.lastIndex());
            // 压缩点上的 term 仍然可以查询
            assertEquals(3, s.term(3).getTerm());
            RaftException e = assertThrows(RaftException.class, () -> s.term(2));
            assertTrue(e.getMessage().contains("due to compaction"), e.getMessage());
        }

        // 重启后压缩结果依然成立
        try (FileRaftStorage s = new FileRaftStorage(dir)) {
            assertEquals(4, s.firstIndex());
            assertEquals(4, s.lastIndex());
            assertEquals(3, s.term(3).getTerm());
        }
    }

    /**
     * 快照与成员配置落盘，重启后能读回来
     */
    @Test
    void testSnapshotSurvivesRestart(@TempDir Path dir) {
        ConfState cs = new ConfState(Arrays.asList(1L, 2L, 3L), Collections.singletonList(4L),
                Collections.emptyList(), Collections.emptyList(), false);
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);

        try (FileRaftStorage s = new FileRaftStorage(dir)) {
            s.append(entries(1, 1, 2, 3));
            Snapshot snap = s.createSnapshot(3, cs, data);
            assertEquals(3, snap.getLastIncludedIndex());
            assertEquals(3, snap.getLastIncludedTerm());
        }

        try (FileRaftStorage s = new FileRaftStorage(dir)) {
            Snapshot snap = s.snapshot();
            assertEquals(3, snap.getLastIncludedIndex());
            assertEquals(3, snap.getLastIncludedTerm());
            assertArrayEquals(data, snap.getData());
            assertEquals(cs, snap.getConfState());
            assertEquals(cs, s.getConfState());
        }
    }

    /**
     * 应用快照之后，日志被压缩到快照点，重启后仍然成立
     */
    @Test
    void testApplySnapshot(@TempDir Path dir) {
        ConfState cs = new ConfState(Arrays.asList(1L, 2L, 3L), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), false);
        byte[] data = "state".getBytes(StandardCharsets.UTF_8);
        Snapshot snap = new Snapshot(data, new SnapshotMetadata(cs, 11, 11));

        try (FileRaftStorage s = new FileRaftStorage(dir)) {
            s.append(entries(1, 1, 2));
            s.applySnapshot(snap);

            assertEquals(12, s.firstIndex());
            assertEquals(11, s.lastIndex());
            assertEquals(11, s.term(11).getTerm());
        }

        try (FileRaftStorage s = new FileRaftStorage(dir)) {
            assertEquals(12, s.firstIndex());
            assertEquals(11, s.lastIndex());
            assertEquals(11, s.term(11).getTerm());
            assertEquals(cs, s.getConfState());
        }
    }

    /**
     * 冲突截断：重写已有日志的尾部
     */
    @Test
    void testConflictOverwrite(@TempDir Path dir) {
        try (FileRaftStorage s = new FileRaftStorage(dir)) {
            s.append(entries(1, 1, 2, 3));
            assertEquals(3, s.lastIndex());

            // 从 index 2 开始重写，term 也换掉
            s.append(entries(2, 5, 5));
            assertEquals(3, s.lastIndex());
            assertEquals(5, s.term(2).getTerm());
            assertEquals(5, s.term(3).getTerm());
        }

        // 截断的结果要落盘
        try (FileRaftStorage s = new FileRaftStorage(dir)) {
            assertEquals(3, s.lastIndex());
            assertEquals(5, s.term(2).getTerm());
            assertEquals(5, s.term(3).getTerm());
        }
    }

    /**
     * 追加不连续的日志要被拒绝
     */
    @Test
    void testAppendWithGap(@TempDir Path dir) {
        try (FileRaftStorage s = new FileRaftStorage(dir)) {
            s.append(entries(1, 1));
            RaftException e = assertThrows(RaftException.class, () -> s.append(entries(5, 1)));
            assertTrue(e.getMessage().contains("missing log entry"), e.getMessage());
        }
    }

    /**
     * 尾部半写的记录在重启时被丢弃，文件也被截断
     */
    @Test
    void testCorruptTailIsDropped(@TempDir Path dir) throws IOException {
        try (FileRaftStorage s = new FileRaftStorage(dir)) {
            s.append(entries(1, 1, 2));
        }

        // 往 log.bin 尾部追加一段垃圾（模拟写日志时崩溃）
        Path logPath = dir.resolve("log.bin");
        long size = Files.size(logPath);
        try (RandomAccessFile raf = new RandomAccessFile(logPath.toFile(), "rw")) {
            raf.seek(size);
            raf.write(new byte[]{0x00, 0x00, 0x00, 0x40, 0x01, 0x02, 0x03});
        }

        try (FileRaftStorage s = new FileRaftStorage(dir)) {
            // 前面的日志还在，半写的尾部被丢掉
            assertEquals(2, s.lastIndex());
            assertEquals(1, s.term(1).getTerm());
            assertEquals(2, s.term(2).getTerm());
            // 文件被截断回合法长度
            assertEquals(size, Files.size(logPath));
        }
    }

    /**
     * 记录内容被改坏（CRC 校验不过）时，同样只丢掉坏的那条
     */
    @Test
    void testCorruptRecordIsDropped(@TempDir Path dir) throws IOException {
        try (FileRaftStorage s = new FileRaftStorage(dir)) {
            s.append(entries(1, 1, 2));
        }

        // 改掉最后一条记录里的一个字节，CRC 就不对了
        Path logPath = dir.resolve("log.bin");
        byte[] content = Files.readAllBytes(logPath);
        content[content.length - 1] ^= 0xFF;
        Files.write(logPath, content);

        try (FileRaftStorage s = new FileRaftStorage(dir)) {
            assertEquals(1, s.lastIndex());
            assertEquals(1, s.term(1).getTerm());
        }
    }

    /**
     * 日志条目的内容（data）要能原样读回来
     */
    @Test
    void testEntryData(@TempDir Path dir) {
        byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);
        try (FileRaftStorage s = new FileRaftStorage(dir)) {
            s.append(Collections.singletonList(new Entry(1, 1, EntryType.NORMAL, payload)));

            List<Entry> got = s.entries(1, 2);
            assertEquals(1, got.size());
            assertArrayEquals(payload, got.get(0).getData());
            assertEquals(EntryType.NORMAL, got.get(0).getType());
        }
    }

    /**
     * maxSize 限制：至少返回一条
     */
    @Test
    void testEntriesLimitSize(@TempDir Path dir) {
        try (FileRaftStorage s = new FileRaftStorage(dir)) {
            s.append(entries(1, 1, 1, 1));
            assertEquals(1, s.entries(1, 4, 0).size());
            assertEquals(3, s.entries(1, 4, Long.MAX_VALUE).size());
        }
    }

    /**
     * 构造一批日志，等价于 Go 测试里的 {@code index(start).terms(...)}。
     */
    private static List<Entry> entries(long startIndex, long... terms) {
        Entry[] ents = new Entry[terms.length];
        for (int i = 0; i < terms.length; i++) {
            ents[i] = new Entry(startIndex + i, terms[i], EntryType.NORMAL, null);
        }
        return Arrays.asList(ents);
    }
}
