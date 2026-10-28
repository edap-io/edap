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

package io.edap.raft.log;

import io.edap.raft.RaftException;
import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.EntryType;
import io.edap.raft.protocol.Snapshot;
import io.edap.raft.protocol.SnapshotMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * etcd {@code log_unstable_test.go} 的 Java 移植。
 */
public class UnstableTest {

    /**
     * Go: TestUnstableMaybeFirstIndex
     */
    @Test
    public void testUnstableMaybeFirstIndex() {
        // no snapshot
        assertMaybeFirstIndex(entries(5, 1), 5, null, false, 0);
        assertMaybeFirstIndex(Collections.<Entry>emptyList(), 0, null, false, 0);
        // has snapshot
        assertMaybeFirstIndex(entries(5, 1), 5, snapshot(4, 1), true, 5);
        assertMaybeFirstIndex(Collections.<Entry>emptyList(), 5, snapshot(4, 1), true, 5);
    }

    private static void assertMaybeFirstIndex(List<Entry> ents, long offset, Snapshot snap,
                                              boolean expectedOk, long expectedIndex) {
        Unstable u = new Unstable(offset, offset, ents, snap, false);
        assertEquals(expectedOk, u.maybeFirstIndex(), "ok");
        assertEquals(expectedIndex, u.firstIndex(), "index");
    }

    /**
     * Go: TestMaybeLastIndex
     */
    @Test
    public void testMaybeLastIndex() {
        // last in entries
        assertMaybeLastIndex(entries(5, 1), 5, null, true, 5);
        assertMaybeLastIndex(entries(5, 1), 5, snapshot(4, 1), true, 5);
        // last in snapshot
        assertMaybeLastIndex(Collections.<Entry>emptyList(), 5, snapshot(4, 1), true, 4);
        // empty unstable
        assertMaybeLastIndex(Collections.<Entry>emptyList(), 0, null, false, 0);
    }

    private static void assertMaybeLastIndex(List<Entry> ents, long offset, Snapshot snap,
                                             boolean expectedOk, long expectedIndex) {
        Unstable u = new Unstable(offset, offset, ents, snap, false);
        assertEquals(expectedOk, u.maybeLastIndex(), "ok");
        assertEquals(expectedIndex, u.lastIndex(), "index");
    }

    /**
     * Go: TestUnstableMaybeTerm
     */
    @Test
    public void testUnstableMaybeTerm() {
        // term from entries
        assertMaybeTerm(entries(5, 1), 5, null, 5, true, 1);
        assertMaybeTerm(entries(5, 1), 5, null, 6, false, 0);
        assertMaybeTerm(entries(5, 1), 5, null, 4, false, 0);
        assertMaybeTerm(entries(5, 1), 5, snapshot(4, 1), 5, true, 1);
        assertMaybeTerm(entries(5, 1), 5, snapshot(4, 1), 6, false, 0);
        // term from snapshot
        assertMaybeTerm(entries(5, 1), 5, snapshot(4, 1), 4, true, 1);
        assertMaybeTerm(entries(5, 1), 5, snapshot(4, 1), 3, false, 0);
        assertMaybeTerm(Collections.<Entry>emptyList(), 5, snapshot(4, 1), 5, false, 0);
        assertMaybeTerm(Collections.<Entry>emptyList(), 5, snapshot(4, 1), 4, true, 1);
        assertMaybeTerm(Collections.<Entry>emptyList(), 0, null, 5, false, 0);
    }

    private static void assertMaybeTerm(List<Entry> ents, long offset, Snapshot snap, long index,
                                        boolean expectedOk, long expectedTerm) {
        Unstable u = new Unstable(offset, offset, ents, snap, false);
        assertEquals(expectedOk, u.maybeTerm(index), "ok at index " + index);
        if (expectedOk) {
            assertEquals(expectedTerm, u.term(index), "term at index " + index);
        } else {
            // 查不到任期时 Java 侧抛异常，等价于 Go 返回 0
            assertThrows(RaftException.class, () -> u.term(index), "term at index " + index);
        }
    }

    /**
     * Go: TestUnstableRestore
     */
    @Test
    public void testUnstableRestore() {
        Unstable u = new Unstable(5, 6, entries(5, 1), snapshot(4, 1), true);
        Snapshot s = snapshot(6, 2);
        u.restore(s);

        assertEquals(s.getLastIncludedIndex() + 1, u.getOffset(), "offset");
        assertEquals(s.getLastIncludedIndex() + 1, u.getOffsetInProgress(), "offsetInProgress");
        assertEquals(0, u.getEntries().size(), "entries");
        assertEquals(s, u.getSnapshot(), "snapshot");
        assertFalse(u.isSnapshotInProgress(), "snapshotInProgress");
    }

    /**
     * Go: TestUnstableNextEntries
     */
    @Test
    public void testUnstableNextEntries() {
        // nothing in progress
        assertNextEntries(entries(5, 1, 1), 5, 5, entries(5, 1, 1));
        // partially in progress
        assertNextEntries(entries(5, 1, 1), 5, 6, entries(6, 1));
        // everything in progress
        assertNextEntries(entries(5, 1, 1), 5, 7, Collections.<Entry>emptyList());
    }

    private static void assertNextEntries(List<Entry> ents, long offset, long offsetInProgress,
                                          List<Entry> expected) {
        Unstable u = new Unstable(offset, offsetInProgress, ents, null, false);
        assertEquals(expected, u.nextEntries(), "nextEntries");
    }

    /**
     * Go: TestUnstableNextSnapshot
     */
    @Test
    public void testUnstableNextSnapshot() {
        Snapshot s = snapshot(4, 1);
        // snapshot not unstable
        assertNull(new Unstable(0, 0, null, null, false).nextSnapshot(), "nextSnapshot");
        // snapshot not in progress
        assertEquals(s, new Unstable(0, 0, null, s, false).nextSnapshot(), "nextSnapshot");
        // snapshot in progress
        assertNull(new Unstable(0, 0, null, s, true).nextSnapshot(), "nextSnapshot");
    }

    /**
     * Go: TestUnstableAcceptInProgress
     */
    @Test
    public void testUnstableAcceptInProgress() {
        Snapshot s = snapshot(4, 1);
        // 没有条目
        assertAcceptInProgress(Collections.<Entry>emptyList(), null, 5, false, 5, false);
        assertAcceptInProgress(entries(5, 1), null, 5, false, 6, false);
        assertAcceptInProgress(entries(5, 1, 1), null, 5, false, 7, false);
        assertAcceptInProgress(entries(5, 1, 1), null, 6, false, 7, false);
        assertAcceptInProgress(entries(5, 1, 1), null, 7, false, 7, false);
        // with snapshot
        assertAcceptInProgress(Collections.<Entry>emptyList(), s, 5, false, 5, true);
        assertAcceptInProgress(entries(5, 1), s, 5, false, 6, true);
        assertAcceptInProgress(entries(5, 1, 1), s, 5, false, 7, true);
        assertAcceptInProgress(entries(5, 1, 1), s, 6, false, 7, true);
        assertAcceptInProgress(entries(5, 1, 1), s, 7, false, 7, true);
        // snapshot already in progress
        assertAcceptInProgress(Collections.<Entry>emptyList(), s, 5, true, 5, true);
        assertAcceptInProgress(entries(5, 1), s, 5, true, 6, true);
        assertAcceptInProgress(entries(5, 1, 1), s, 5, true, 7, true);
        assertAcceptInProgress(entries(5, 1, 1), s, 6, true, 7, true);
        assertAcceptInProgress(entries(5, 1, 1), s, 7, true, 7, true);
    }

    private static void assertAcceptInProgress(List<Entry> ents, Snapshot snap, long offsetInProgress,
                                               boolean snapshotInProgress,
                                               long expectedOffsetInProgress, boolean expectedSnapshotInProgress) {
        Unstable u = new Unstable(0, offsetInProgress, ents, snap, snapshotInProgress);
        u.acceptInProgress();
        assertEquals(expectedOffsetInProgress, u.getOffsetInProgress(), "offsetInProgress");
        assertEquals(expectedSnapshotInProgress, u.isSnapshotInProgress(), "snapshotInProgress");
    }

    /**
     * Go: TestUnstableStableTo
     */
    @Test
    public void testUnstableStableTo() {
        Snapshot s = snapshot(4, 1);
        assertStableTo(Collections.<Entry>emptyList(), 0, 0, null, 5, 1, 0, 0, 0);
        // stable to the first entry
        assertStableTo(entries(5, 1), 5, 6, null, 5, 1, 6, 6, 0);
        assertStableTo(entries(5, 1, 1), 5, 6, null, 5, 1, 6, 6, 1);
        // stable to the first entry and in-progress ahead
        assertStableTo(entries(5, 1, 1), 5, 7, null, 5, 1, 6, 7, 1);
        // stable to the first entry and term mismatch
        assertStableTo(entries(6, 2), 6, 7, null, 6, 1, 6, 7, 1);
        // stable to old entry
        assertStableTo(entries(5, 1), 5, 6, null, 4, 1, 5, 6, 1);
        assertStableTo(entries(5, 1), 5, 6, null, 4, 2, 5, 6, 1);
        // with snapshot
        assertStableTo(entries(5, 1), 5, 6, s, 5, 1, 6, 6, 0);
        assertStableTo(entries(5, 1, 1), 5, 6, s, 5, 1, 6, 6, 1);
        assertStableTo(entries(5, 1, 1), 5, 7, s, 5, 1, 6, 7, 1);
        assertStableTo(entries(6, 2), 6, 7, snapshot(5, 1), 6, 1, 6, 7, 1);
        // stable to snapshot
        assertStableTo(entries(5, 1), 5, 6, s, 4, 1, 5, 6, 1);
        // stable to old entry
        assertStableTo(entries(5, 2), 5, 6, snapshot(4, 2), 4, 1, 5, 6, 1);
    }

    private static void assertStableTo(List<Entry> ents, long offset, long offsetInProgress, Snapshot snap,
                                       long index, long term,
                                       long expectedOffset, long expectedOffsetInProgress, int expectedLen) {
        Unstable u = new Unstable(offset, offsetInProgress, ents, snap, false);
        u.stableTo(index, term);
        assertEquals(expectedOffset, u.getOffset(), "offset");
        assertEquals(expectedOffsetInProgress, u.getOffsetInProgress(), "offsetInProgress");
        assertEquals(expectedLen, u.getEntries().size(), "entries");
    }

    /**
     * Go: TestUnstableTruncateAndAppend
     */
    @Test
    public void testUnstableTruncateAndAppend() {
        // append to the end
        assertTruncateAndAppend(entries(5, 1), 5, 5, null, entries(6, 1, 1), 5, 5, entries(5, 1, 1, 1));
        assertTruncateAndAppend(entries(5, 1), 5, 6, null, entries(6, 1, 1), 5, 6, entries(5, 1, 1, 1));
        // replace the unstable entries
        assertTruncateAndAppend(entries(5, 1), 5, 5, null, entries(5, 2, 2), 5, 5, entries(5, 2, 2));
        assertTruncateAndAppend(entries(5, 1), 5, 5, null, entries(4, 2, 2, 2), 4, 4, entries(4, 2, 2, 2));
        assertTruncateAndAppend(entries(5, 1), 5, 6, null, entries(5, 2, 2), 5, 5, entries(5, 2, 2));
        // truncate the existing entries and append
        assertTruncateAndAppend(entries(5, 1, 1, 1), 5, 5, null, entries(6, 2), 5, 5, entries(5, 1, 2));
        assertTruncateAndAppend(entries(5, 1, 1, 1), 5, 5, null, entries(7, 2, 2), 5, 5, entries(5, 1, 1, 2, 2));
        assertTruncateAndAppend(entries(5, 1, 1, 1), 5, 6, null, entries(6, 2), 5, 6, entries(5, 1, 2));
        assertTruncateAndAppend(entries(5, 1, 1, 1), 5, 7, null, entries(6, 2), 5, 6, entries(5, 1, 2));
    }

    private static void assertTruncateAndAppend(List<Entry> ents, long offset, long offsetInProgress,
                                                Snapshot snap, List<Entry> toAppend,
                                                long expectedOffset, long expectedOffsetInProgress,
                                                List<Entry> expectedEntries) {
        Unstable u = new Unstable(offset, offsetInProgress, ents, snap, false);
        u.truncateAndAppend(toAppend);
        assertEquals(expectedOffset, u.getOffset(), "offset");
        assertEquals(expectedOffsetInProgress, u.getOffsetInProgress(), "offsetInProgress");
        assertEquals(expectedEntries, u.getEntries(), "entries");
    }

    /**
     * 构造一段日志条目，index 从 startIndex 开始递增，term 取自 terms。
     *
     * <p>对应 Go 里 {@code index(5).terms(1, 1)} 这样的辅助函数。</p>
     */
    private static List<Entry> entries(long startIndex, long... terms) {
        List<Entry> ents = new ArrayList<>(terms.length);
        long index = startIndex;
        for (long term : terms) {
            ents.add(new Entry(index, term, EntryType.NORMAL, null));
            index++;
        }
        return ents;
    }

    /**
     * 构造一个快照
     */
    private static Snapshot snapshot(long index, long term) {
        return new Snapshot(new byte[0], new SnapshotMetadata(null, index, term));
    }
}
