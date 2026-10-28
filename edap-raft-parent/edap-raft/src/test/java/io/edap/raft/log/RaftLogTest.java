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
import io.edap.raft.RaftTestSupport;
import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.EntryType;
import io.edap.raft.protocol.RaftCodec;
import io.edap.raft.protocol.Snapshot;
import io.edap.raft.protocol.SnapshotMetadata;
import io.edap.raft.storage.MemoryStorage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * etcd {@code log_test.go} 的 Java 移植。
 */
public class RaftLogTest {

    /**
     * Go: TestFindConflict
     */
    @Test
    public void testFindConflict() {
        List<Entry> previousEnts = entries(1, 1, 2, 3);
        // {ents, wconflict}
        List<Entry>[] ents = new List[]{
                null,
                entries(1, 1, 2, 3),
                entries(2, 2, 3),
                entries(3, 3),
                // no conflict, but has new entries
                entries(1, 1, 2, 3, 4, 4),
                entries(2, 2, 3, 4, 5),
                entries(3, 3, 4, 4),
                entries(4, 4, 4),
                // conflicts with existing entries
                entries(1, 4, 4),
                entries(2, 1, 4, 4),
                entries(3, 1, 2, 4, 4),
        };
        long[] wconflict = {0, 0, 0, 0, 4, 4, 4, 4, 1, 2, 3};

        for (int i = 0; i < ents.length; i++) {
            RaftLog raftLog = new RaftLog(new MemoryStorage());
            raftLog.append(previousEnts);
            assertEquals(wconflict[i], raftLog.findConflict(ents[i]), "case " + i);
        }
    }

    /**
     * Go: TestFindConflictByTerm
     */
    @Test
    public void testFindConflictByTerm() {
        // {ents, index, term, want}
        List<Entry>[] ents = new List[]{
                // Log starts from index 1.
                entries(0, 0, 2, 2, 5, 5, 5),
                entries(0, 0, 2, 2, 5, 5, 5),
                entries(0, 0, 2, 2, 5, 5, 5),
                entries(0, 0, 2, 2, 5, 5, 5),
                entries(0, 0, 2, 2, 5, 5, 5),
                entries(0, 0, 2, 2, 5, 5, 5),
                entries(0, 0, 2, 2, 5, 5, 5),
                entries(0, 0, 2, 2, 5, 5, 5),
                entries(0, 0, 2, 2, 5, 5, 5),
                // Log with compacted entries.
                entries(10, 3, 3, 3, 4, 4, 4),
                entries(10, 3, 3, 3, 4, 4, 4),
                entries(10, 3, 3, 3, 4, 4, 4),
                entries(10, 3, 3, 3, 4, 4, 4),
                entries(10, 3, 3, 3, 4, 4, 4),
                entries(10, 3, 3, 3, 4, 4, 4),
                entries(10, 3, 3, 3, 4, 4, 4),
                entries(10, 3, 3, 3, 4, 4, 4),
                entries(10, 3, 3, 3, 4, 4, 4),
                entries(10, 3, 3, 3, 4, 4, 4),
                entries(10, 3, 3, 3, 4, 4, 4),
                entries(10, 3, 3, 3, 4, 4, 4),
        };
        long[] indexes = {100, 5, 5, 5, 5, 5, 1, 1, 0, 30, 14, 14, 14, 14, 11, 10, 10, 10, 9, 4, 0};
        long[] terms = {2, 6, 5, 4, 2, 1, 2, 1, 0, 3, 9, 4, 3, 2, 5, 5, 3, 2, 2, 2, 0};
        long[] wants = {100, 5, 5, 2, 2, 0, 1, 0, 0, 30, 14, 14, 12, 9, 11, 10, 10, 9, 9, 4, 0};

        for (int i = 0; i < ents.length; i++) {
            List<Entry> tt = ents[i];
            assertFalse(tt.isEmpty(), "case " + i);
            MemoryStorage st = new MemoryStorage();
            st.applySnapshot(snapshot(tt.get(0).getIndex(), tt.get(0).getTerm()));
            RaftLog l = new RaftLog(st);
            l.append(tt.subList(1, tt.size()));

            long[] r = l.findConflictByTerm(indexes[i], terms[i]);
            assertEquals(wants[i], r[0], "index of case " + i);
            long wantTerm = l.zeroTermOnOutOfBounds(r[0]);
            assertEquals(wantTerm, r[1], "term of case " + i);
        }
    }

    /**
     * Go: TestIsUpToDate
     */
    @Test
    public void testIsUpToDate() {
        List<Entry> previousEnts = entries(1, 1, 2, 3);
        RaftLog raftLog = new RaftLog(new MemoryStorage());
        raftLog.append(previousEnts);
        long last = raftLog.lastIndex();

        // {lastIndex, term, wUpToDate}
        long[][] tests = {
                // greater term, ignore lastIndex
                {last - 1, 4, 1},
                {last, 4, 1},
                {last + 1, 4, 1},
                // smaller term, ignore lastIndex
                {last - 1, 2, 0},
                {last, 2, 0},
                {last + 1, 2, 0},
                // equal term, equal or lager lastIndex wins
                {last - 1, 3, 0},
                {last, 3, 1},
                {last + 1, 3, 1},
        };

        for (int i = 0; i < tests.length; i++) {
            boolean expected = tests[i][2] != 0;
            assertEquals(expected, raftLog.isUpToDate(tests[i][0], tests[i][1]), "case " + i);
        }
    }

    /**
     * Go: TestAppend
     */
    @Test
    public void testAppend() {
        List<Entry> previousEnts = entries(1, 1, 2);
        // {ents, windex, wents, wunstable}
        List<Entry>[] ents = new List[]{
                null,
                entries(3, 2),
                entries(1, 2),
                entries(2, 3, 3),
        };
        long[] windex = {2, 3, 1, 3};
        List<Entry>[] wents = new List[]{
                entries(1, 1, 2),
                entries(1, 1, 2, 2),
                entries(1, 2),
                entries(1, 1, 3, 3),
        };
        long[] wunstable = {3, 3, 1, 2};

        for (int i = 0; i < ents.length; i++) {
            MemoryStorage storage = new MemoryStorage();
            storage.append(previousEnts);
            RaftLog raftLog = new RaftLog(storage);
            assertEquals(windex[i], raftLog.append(ents[i]), "index of case " + i);
            List<Entry> g = raftLog.entries(1, raftLog.lastIndex() + 1);
            RaftTestSupport.assertEqualEntries(wents[i], g);
            assertEquals(wunstable[i], raftLog.getUnstable().getOffset(), "unstable offset of case " + i);
        }
    }

    /**
     * Go: TestLogMaybeAppend
     *
     * <p>如果给定的 (index, term) 与现有日志匹配：</p>
     * <ol>
     *     <li>如果新条目与已有条目冲突（index 相同 term 不同），删掉已有条目以及它之后的所有条目</li>
     *     <li>追加日志中还没有的新条目</li>
     * </ol>
     * <p>如果 (index, term) 与现有日志不匹配，返回失败。</p>
     */
    @Test
    public void testLogMaybeAppend() {
        List<Entry> previousEnts = entries(1, 1, 2, 3);
        long lastindex = 3;
        long lastterm = 3;
        long commit = 1;

        // {prevTerm, prevIndex, leaderCommit, ents, wlasti, wappend, wcommit, wpanic}
        MaybeAppendCase[] tests = {
                // not match: term is different
                new MaybeAppendCase(lastterm - 1, lastindex, lastindex, entries(lastindex + 1, 4),
                        0, false, commit, false),
                // not match: index out of bound
                new MaybeAppendCase(lastterm, lastindex + 1, lastindex, entries(lastindex + 2, 4),
                        0, false, commit, false),
                // match with the last existing entry
                new MaybeAppendCase(lastterm, lastindex, lastindex, null,
                        lastindex, true, lastindex, false),
                new MaybeAppendCase(lastterm, lastindex, lastindex + 1, null,
                        lastindex, true, lastindex, false),
                new MaybeAppendCase(lastterm, lastindex, lastindex - 1, null,
                        lastindex, true, lastindex - 1, false),
                new MaybeAppendCase(lastterm, lastindex, 0, null,
                        lastindex, true, commit, false),
                new MaybeAppendCase(0, 0, lastindex, null,
                        0, true, commit, false),
                new MaybeAppendCase(lastterm, lastindex, lastindex, entries(lastindex + 1, 4),
                        lastindex + 1, true, lastindex, false),
                new MaybeAppendCase(lastterm, lastindex, lastindex + 1, entries(lastindex + 1, 4),
                        lastindex + 1, true, lastindex + 1, false),
                new MaybeAppendCase(lastterm, lastindex, lastindex + 2, entries(lastindex + 1, 4),
                        lastindex + 1, true, lastindex + 1, false),
                new MaybeAppendCase(lastterm, lastindex, lastindex + 2, entries(lastindex + 1, 4, 4),
                        lastindex + 2, true, lastindex + 2, false),
                // match with the entry in the middle
                new MaybeAppendCase(lastterm - 1, lastindex - 1, lastindex, entries(lastindex, 4),
                        lastindex, true, lastindex, false),
                new MaybeAppendCase(lastterm - 2, lastindex - 2, lastindex, entries(lastindex - 1, 4),
                        lastindex - 1, true, lastindex - 1, false),
                new MaybeAppendCase(lastterm - 3, lastindex - 3, lastindex, entries(lastindex - 2, 4),
                        lastindex - 2, true, lastindex - 2, true),
                new MaybeAppendCase(lastterm - 2, lastindex - 2, lastindex, entries(lastindex - 1, 4, 4),
                        lastindex, true, lastindex, false),
        };

        for (int i = 0; i < tests.length; i++) {
            MaybeAppendCase tt = tests[i];
            RaftLog raftLog = new RaftLog(new MemoryStorage());
            raftLog.append(previousEnts);
            raftLog.commitTo(commit);

            LogSlice slice = new LogSlice(tt.prevTerm,
                    new EntryId(tt.prevTerm, tt.prevIndex), tt.ents);

            if (tt.wpanic) {
                assertThrows(RaftException.class, () -> raftLog.maybeAppend(
                        slice, tt.leaderCommit), "case " + i);
                continue;
            }

            long lasti = raftLog.maybeAppend(slice, tt.leaderCommit);
            // Go 的 maybeAppend 失败时返回 0，Java 侧用 -1 表示失败
            assertEquals(tt.wappend ? tt.wlasti : -1, lasti, "lasti of case " + i);
            assertEquals(tt.wcommit, raftLog.committed(), "commit of case " + i);
            if (tt.wappend && tt.ents != null && !tt.ents.isEmpty()) {
                List<Entry> gents = raftLog.slice(
                        raftLog.lastIndex() - tt.ents.size() + 1, raftLog.lastIndex() + 1, Long.MAX_VALUE);
                assertEquals(tt.ents, gents, "entries of case " + i);
            }
        }
    }

    /**
     * Go: TestCompactionSideEffects
     *
     * <p>压缩之后所有与日志相关的功能都还能正常工作。</p>
     */
    @Test
    public void testCompactionSideEffects() {
        // 1000 条日志：750 条在稳定存储里，250 条在未落盘区
        long lastIndex = 1000;
        long unstableIndex = 750;
        MemoryStorage storage = new MemoryStorage();
        storage.append(termRange(1, 1, unstableIndex + 1));
        RaftLog raftLog = new RaftLog(storage);
        raftLog.append(termRange(unstableIndex + 1, unstableIndex + 1, lastIndex + 1));

        assertTrue(raftLog.maybeCommit(raftLog.lastTerm(), raftLog.lastIndex()), "maybeCommit");
        raftLog.appliedTo(raftLog.committed(), 0);

        long offset = 500;
        storage.compact(offset);
        assertEquals(lastIndex, raftLog.lastIndex(), "lastIndex");

        for (long j = offset; j <= raftLog.lastIndex(); j++) {
            assertEquals(j, raftLog.term(j), "term of " + j);
        }

        for (long j = offset; j <= raftLog.lastIndex(); j++) {
            assertTrue(raftLog.matchTerm(j, j), "matchTerm of " + j);
        }

        List<Entry> unstableEnts = raftLog.nextUnstableEnts();
        assertEquals(250, unstableEnts.size(), "unstable entries size");
        assertEquals(751, unstableEnts.get(0).getIndex(), "first unstable index");

        long prev = raftLog.lastIndex();
        raftLog.append(Collections.singletonList(
                new Entry(raftLog.lastIndex() + 1, raftLog.lastIndex() + 1, EntryType.NORMAL, null)));
        assertEquals(prev + 1, raftLog.lastIndex(), "lastIndex after append");

        List<Entry> ents = raftLog.entries(raftLog.lastIndex(), raftLog.lastIndex() + 1);
        assertEquals(1, ents.size(), "entries size");
    }

    /**
     * Go: TestHasNextCommittedEnts
     */
    @Test
    public void testHasNextCommittedEnts() {
        Snapshot snap = snapshot(3, 1);
        List<Entry> ents = entries(4, 1, 1, 1);
        // {applied, applying, allowUnstable, paused, snap, whasNext}
        long[][] tests = {
                {3, 3, 1, 0, 0, 1},
                {3, 4, 1, 0, 0, 1},
                {3, 5, 1, 0, 0, 0},
                {4, 4, 1, 0, 0, 1},
                {4, 5, 1, 0, 0, 0},
                {5, 5, 1, 0, 0, 0},
                // Don't allow unstable entries.
                {3, 3, 0, 0, 0, 1},
                {3, 4, 0, 0, 0, 0},
                {3, 5, 0, 0, 0, 0},
                {4, 4, 0, 0, 0, 0},
                {4, 5, 0, 0, 0, 0},
                {5, 5, 0, 0, 0, 0},
                // Paused.
                {3, 3, 1, 1, 0, 0},
                // With snapshot.
                {3, 3, 1, 0, 1, 0},
        };

        for (int i = 0; i < tests.length; i++) {
            long[] tt = tests[i];
            MemoryStorage storage = new MemoryStorage();
            storage.applySnapshot(snap);
            storage.append(ents.subList(0, 1));

            RaftLog raftLog = new RaftLog(storage);
            raftLog.append(ents);
            raftLog.stableTo(4, 1);
            raftLog.maybeCommit(1, 5);
            raftLog.appliedTo(tt[0], 0);
            raftLog.acceptApplying(tt[1], 0, tt[2] != 0);
            raftLog.setApplyingEntsPaused(tt[3] != 0);
            if (tt[4] != 0) {
                raftLog.restore(snapshot(snap.getLastIncludedIndex() + 1, snap.getLastIncludedTerm()));
            }
            assertEquals(tt[5] != 0, raftLog.hasNextCommittedEnts(tt[2] != 0), "case " + i);
        }
    }

    /**
     * Go: TestNextCommittedEnts
     */
    @Test
    public void testNextCommittedEnts() {
        Snapshot snap = snapshot(3, 1);
        List<Entry> ents = entries(4, 1, 1, 1);
        // {applied, applying, allowUnstable, paused, snap, wents}
        Object[][] tests = {
                {3L, 3L, 1L, 0L, 0L, ents.subList(0, 2)},
                {3L, 4L, 1L, 0L, 0L, ents.subList(1, 2)},
                {3L, 5L, 1L, 0L, 0L, Collections.<Entry>emptyList()},
                {4L, 4L, 1L, 0L, 0L, ents.subList(1, 2)},
                {4L, 5L, 1L, 0L, 0L, Collections.<Entry>emptyList()},
                {5L, 5L, 1L, 0L, 0L, Collections.<Entry>emptyList()},
                // Don't allow unstable entries.
                {3L, 3L, 0L, 0L, 0L, ents.subList(0, 1)},
                {3L, 4L, 0L, 0L, 0L, Collections.<Entry>emptyList()},
                {3L, 5L, 0L, 0L, 0L, Collections.<Entry>emptyList()},
                {4L, 4L, 0L, 0L, 0L, Collections.<Entry>emptyList()},
                {4L, 5L, 0L, 0L, 0L, Collections.<Entry>emptyList()},
                {5L, 5L, 0L, 0L, 0L, Collections.<Entry>emptyList()},
                // Paused.
                {3L, 3L, 1L, 1L, 0L, Collections.<Entry>emptyList()},
                // With snapshot.
                {3L, 3L, 1L, 0L, 1L, Collections.<Entry>emptyList()},
        };

        for (int i = 0; i < tests.length; i++) {
            Object[] tt = tests[i];
            long applied = (Long) tt[0];
            long applying = (Long) tt[1];
            boolean allowUnstable = (Long) tt[2] != 0;
            MemoryStorage storage = new MemoryStorage();
            storage.applySnapshot(snap);
            storage.append(ents.subList(0, 1));

            RaftLog raftLog = new RaftLog(storage);
            raftLog.append(ents);
            raftLog.stableTo(4, 1);
            raftLog.maybeCommit(1, 5);
            raftLog.appliedTo(applied, 0);
            raftLog.acceptApplying(applying, 0, allowUnstable);
            raftLog.setApplyingEntsPaused((Long) tt[3] != 0);
            if ((Long) tt[4] != 0) {
                raftLog.restore(snapshot(snap.getLastIncludedIndex() + 1, snap.getLastIncludedTerm()));
            }
            @SuppressWarnings("unchecked")
            List<Entry> want = (List<Entry>) tt[5];
            assertEquals(want, raftLog.nextCommittedEnts(allowUnstable), "case " + i);
        }
    }

    /**
     * Go: TestAcceptApplying
     */
    @Test
    public void testAcceptApplying() {
        long maxSize = 100;
        Snapshot snap = snapshot(3, 1);
        List<Entry> ents = entries(4, 1, 1, 1);
        // {index, allowUnstable, size, wpaused}
        long[][] tests = {
                {3, 1, maxSize - 1, 1},
                {3, 1, maxSize, 1},
                {3, 1, maxSize + 1, 1},
                {4, 1, maxSize - 1, 1},
                {4, 1, maxSize, 1},
                {4, 1, maxSize + 1, 1},
                {5, 1, maxSize - 1, 0},
                {5, 1, maxSize, 1},
                {5, 1, maxSize + 1, 1},
                // Don't allow unstable entries.
                {3, 0, maxSize - 1, 1},
                {3, 0, maxSize, 1},
                {3, 0, maxSize + 1, 1},
                {4, 0, maxSize - 1, 0},
                {4, 0, maxSize, 1},
                {4, 0, maxSize + 1, 1},
                {5, 0, maxSize - 1, 0},
                {5, 0, maxSize, 1},
                {5, 0, maxSize + 1, 1},
        };

        for (int i = 0; i < tests.length; i++) {
            long[] tt = tests[i];
            MemoryStorage storage = new MemoryStorage();
            storage.applySnapshot(snap);
            storage.append(ents.subList(0, 1));

            RaftLog raftLog = new RaftLog(storage);
            raftLog.setMaxApplyingEntsSize(maxSize);
            raftLog.append(ents);
            raftLog.stableTo(4, 1);
            raftLog.maybeCommit(1, 5);
            raftLog.appliedTo(3, 0);

            raftLog.acceptApplying(tt[0], tt[2], tt[1] != 0);
            assertEquals(tt[3] != 0, raftLog.isApplyingEntsPaused(), "case " + i);
        }
    }

    /**
     * Go: TestAppliedTo
     */
    @Test
    public void testAppliedTo() {
        long maxSize = 100;
        long overshoot = 5;
        Snapshot snap = snapshot(3, 1);
        List<Entry> ents = entries(4, 1, 1, 1);
        // {index, size, wapplyingSize, wpaused}
        long[][] tests = {
                // Apply some of in-progress entries (applying = 5 below).
                {4, overshoot - 1, maxSize + 1, 1},
                {4, overshoot, maxSize, 1},
                {4, overshoot + 1, maxSize - 1, 0},
                // Apply all of in-progress entries.
                {5, overshoot - 1, maxSize + 1, 1},
                {5, overshoot, maxSize, 1},
                {5, overshoot + 1, maxSize - 1, 0},
                // Apply all of outstanding bytes.
                {4, maxSize + overshoot, 0, 0},
                // Apply more than outstanding bytes.
                // Incorrect accounting doesn't underflow applyingSize.
                {4, maxSize + overshoot + 1, 0, 0},
        };

        for (int i = 0; i < tests.length; i++) {
            long[] tt = tests[i];
            MemoryStorage storage = new MemoryStorage();
            storage.applySnapshot(snap);
            storage.append(ents.subList(0, 1));

            RaftLog raftLog = new RaftLog(storage);
            raftLog.setMaxApplyingEntsSize(maxSize);
            raftLog.append(ents);
            raftLog.stableTo(4, 1);
            raftLog.maybeCommit(1, 5);
            raftLog.appliedTo(3, 0);
            raftLog.acceptApplying(5, maxSize + overshoot, false);

            raftLog.appliedTo(tt[0], tt[1]);
            assertEquals(tt[0], raftLog.applied(), "applied of case " + i);
            assertEquals(5, raftLog.applying(), "applying of case " + i);
            assertEquals(tt[2], raftLog.getApplyingEntsSize(), "applyingEntsSize of case " + i);
            assertEquals(tt[3] != 0, raftLog.isApplyingEntsPaused(), "paused of case " + i);
        }
    }

    /**
     * Go: TestNextUnstableEnts
     *
     * <p>确保 nextUnstableEnts 正确返回未落盘的那部分条目。</p>
     */
    @Test
    public void testNextUnstableEnts() {
        List<Entry> previousEnts = entries(1, 1, 2);
        // {unstable, wents}
        long[] unstables = {3, 1};
        List<List<Entry>> wents = new ArrayList<>();
        wents.add(Collections.<Entry>emptyList());
        wents.add(previousEnts);

        for (int i = 0; i < unstables.length; i++) {
            long unstable = unstables[i];
            // 把稳定的条目追加到存储里
            MemoryStorage storage = new MemoryStorage();
            storage.append(previousEnts.subList(0, (int) (unstable - 1)));

            // 把未落盘的条目追加到 raftlog
            RaftLog raftLog = new RaftLog(storage);
            raftLog.append(previousEnts.subList((int) (unstable - 1), previousEnts.size()));

            List<Entry> ents = raftLog.nextUnstableEnts();
            if (!ents.isEmpty()) {
                Entry last = ents.get(ents.size() - 1);
                raftLog.stableTo(last.getIndex(), last.getTerm());
            }
            assertEquals(wents.get(i), ents, "entries of case " + i);
            assertEquals(previousEnts.get(previousEnts.size() - 1).getIndex() + 1,
                    raftLog.getUnstable().getOffset(), "unstable offset of case " + i);
        }
    }

    /**
     * Go: TestCommitTo
     */
    @Test
    public void testCommitTo() {
        List<Entry> previousEnts = entries(1, 1, 2, 3);
        long commit = 2;
        // {commit, wcommit, wpanic}
        long[][] tests = {
                {3, 3, 0},
                {1, 2, 0},   // never decrease
                {4, 0, 1},   // commit out of range -> panic
        };

        for (int i = 0; i < tests.length; i++) {
            long[] tt = tests[i];
            RaftLog raftLog = new RaftLog(new MemoryStorage());
            raftLog.append(previousEnts);
            raftLog.commitTo(commit);
            if (tt[2] != 0) {
                assertThrows(RaftException.class, () -> raftLog.commitTo(tt[0]), "case " + i);
                continue;
            }
            raftLog.commitTo(tt[0]);
            assertEquals(tt[1], raftLog.committed(), "commit of case " + i);
        }
    }

    /**
     * Go: TestStableTo
     */
    @Test
    public void testStableTo() {
        // {stablei, stablet, wunstable}
        long[][] tests = {
                {1, 1, 2},
                {2, 2, 3},
                {2, 1, 1},   // bad term
                {3, 1, 1},   // bad index
        };

        for (int i = 0; i < tests.length; i++) {
            long[] tt = tests[i];
            RaftLog raftLog = new RaftLog(new MemoryStorage());
            raftLog.append(entries(1, 1, 2));
            raftLog.stableTo(tt[0], tt[1]);
            assertEquals(tt[2], raftLog.getUnstable().getOffset(), "unstable offset of case " + i);
        }
    }

    /**
     * Go: TestStableToWithSnap
     */
    @Test
    public void testStableToWithSnap() {
        long snapi = 5;
        long snapt = 2;
        // {stablei, stablet, newEnts, wunstable}
        List<Entry> newEnts = entries(snapi + 1, snapt);
        Object[][] tests = {
                {snapi + 1, snapt, null, snapi + 1},
                {snapi, snapt, null, snapi + 1},
                {snapi - 1, snapt, null, snapi + 1},
                {snapi + 1, snapt + 1, null, snapi + 1},
                {snapi, snapt + 1, null, snapi + 1},
                {snapi - 1, snapt + 1, null, snapi + 1},
                {snapi + 1, snapt, newEnts, snapi + 2},
                {snapi, snapt, newEnts, snapi + 1},
                {snapi - 1, snapt, newEnts, snapi + 1},
                {snapi + 1, snapt + 1, newEnts, snapi + 1},
                {snapi, snapt + 1, newEnts, snapi + 1},
                {snapi - 1, snapt + 1, newEnts, snapi + 1},
        };

        for (int i = 0; i < tests.length; i++) {
            Object[] tt = tests[i];
            MemoryStorage s = new MemoryStorage();
            s.applySnapshot(snapshot(snapi, snapt));
            RaftLog raftLog = new RaftLog(s);
            @SuppressWarnings("unchecked")
            List<Entry> ents = (List<Entry>) tt[2];
            raftLog.append(ents);
            raftLog.stableTo((Long) tt[0], (Long) tt[1]);
            assertEquals((Long) tt[3], raftLog.getUnstable().getOffset(), "unstable offset of case " + i);
        }
    }

    /**
     * Go: TestCompaction
     *
     * <p>确保压缩之后日志条目的数量是正确的。</p>
     */
    @Test
    public void testCompaction() {
        // {lastIndex, compact, wleft, wallow}
        CompactionCase[] tests = {
                // out of upper bound
                new CompactionCase(1000, new long[]{1001}, new long[]{-1}, false),
                new CompactionCase(1000, new long[]{300, 500, 800, 900},
                        new long[]{700, 500, 200, 100}, true),
                // out of lower bound
                new CompactionCase(1000, new long[]{300, 299}, new long[]{700, -1}, false),
        };

        for (int i = 0; i < tests.length; i++) {
            CompactionCase tt = tests[i];
            long lastIndex = tt.lastIndex;
            long[] compact = tt.compact;
            long[] wleft = tt.wleft;

            MemoryStorage storage = new MemoryStorage();
            storage.append(termRange(1, 1, lastIndex + 1));
            RaftLog raftLog = new RaftLog(storage);
            // term 为 0 时这是一次空操作
            raftLog.maybeCommit(0, lastIndex);

            raftLog.appliedTo(raftLog.committed(), 0);
            for (int j = 0; j < compact.length; j++) {
                try {
                    storage.compact(compact[j]);
                } catch (RaftException e) {
                    assertFalse(tt.wallow, "case " + i + " compact " + compact[j] + " should not be allowed");
                    continue;
                }
                assertEquals(wleft[j], raftLog.allEntries().size(),
                        "left entries of case " + i + " compact " + compact[j]);
            }
        }
    }

    /**
     * Go: TestLogRestore
     */
    @Test
    public void testLogRestore() {
        long index = 1000;
        long term = 1000;
        MemoryStorage storage = new MemoryStorage();
        storage.applySnapshot(snapshot(index, term));
        RaftLog raftLog = new RaftLog(storage);

        assertEquals(0, raftLog.allEntries().size(), "entries");
        assertEquals(index + 1, raftLog.firstIndex(), "firstIndex");
        assertEquals(index, raftLog.committed(), "committed");
        assertEquals(index + 1, raftLog.getUnstable().getOffset(), "unstable offset");
        assertEquals(term, raftLog.term(index), "term");
    }

    /**
     * Go: TestIsOutOfBounds
     */
    @Test
    public void testIsOutOfBounds() {
        long offset = 100;
        long num = 100;
        MemoryStorage storage = new MemoryStorage();
        storage.applySnapshot(snapshot(offset, 0));
        RaftLog l = new RaftLog(storage);
        l.append(termRange(offset + 1, offset + 1, offset + num + 1));

        long first = offset + 1;
        // {lo, hi, wpanic, wErrCompacted}
        long[][] tests = {
                {first - 2, first + 1, 0, 1},
                {first - 1, first + 1, 0, 1},
                {first, first, 0, 0},
                {first + num / 2, first + num / 2, 0, 0},
                {first + num - 1, first + num - 1, 0, 0},
                {first + num, first + num, 0, 0},
                {first + num, first + num + 1, 1, 0},
                {first + num + 1, first + num + 1, 1, 0},
        };

        for (int i = 0; i < tests.length; i++) {
            long[] tt = tests[i];
            if (tt[2] != 0) {
                assertThrows(RaftException.class, () -> l.mustCheckOutOfBounds(tt[0], tt[1]), "case " + i);
                continue;
            }
            if (tt[3] != 0) {
                RaftException e = assertThrows(RaftException.class,
                        () -> l.mustCheckOutOfBounds(tt[0], tt[1]), "case " + i);
                assertTrue(e.getMessage().contains("due to compaction"),
                        "case " + i + " expected ErrCompacted but got: " + e.getMessage());
                continue;
            }
            l.mustCheckOutOfBounds(tt[0], tt[1]);
        }
    }

    /**
     * Go: TestTerm
     */
    @Test
    public void testTerm() {
        long offset = 100;
        long num = 100;

        MemoryStorage storage = new MemoryStorage();
        storage.applySnapshot(snapshot(offset, 1));
        RaftLog l = new RaftLog(storage);
        l.append(termRange(offset + 1, 1, num));

        // {idx, term, err}
        long[][] tests = {
                {offset - 1, 0, 1},                 // ErrCompacted
                {offset, 1, 0},
                {offset + num / 2, num / 2, 0},
                {offset + num - 1, num - 1, 0},
                {offset + num, 0, 2},               // ErrUnavailable
        };

        for (int i = 0; i < tests.length; i++) {
            long[] tt = tests[i];
            if (tt[2] == 0) {
                assertEquals(tt[1], l.term(tt[0]), "term of case " + i);
                continue;
            }
            RaftException e = assertThrows(RaftException.class, () -> l.term(tt[0]), "case " + i);
            if (tt[2] == 1) {
                assertTrue(e.getMessage().contains("due to compaction"),
                        "case " + i + " expected ErrCompacted but got: " + e.getMessage());
            } else {
                assertTrue(e.getMessage().contains("is unavailable"),
                        "case " + i + " expected ErrUnavailable but got: " + e.getMessage());
            }
        }
    }

    /**
     * Go: TestTermWithUnstableSnapshot
     */
    @Test
    public void testTermWithUnstableSnapshot() {
        long storagesnapi = 100;
        long unstablesnapi = storagesnapi + 5;

        MemoryStorage storage = new MemoryStorage();
        storage.applySnapshot(snapshot(storagesnapi, 1));
        RaftLog l = new RaftLog(storage);
        l.restore(snapshot(unstablesnapi, 1));

        // {idx, term, err}
        long[][] tests = {
                // cannot get term from storage
                {storagesnapi, 0, 1},
                // cannot get term from the gap between storage ents and unstable snapshot
                {storagesnapi + 1, 0, 1},
                {unstablesnapi - 1, 0, 1},
                // get term from unstable snapshot index
                {unstablesnapi, 1, 0},
                // the log beyond the unstable snapshot is empty
                {unstablesnapi + 1, 0, 2},
        };

        for (int i = 0; i < tests.length; i++) {
            long[] tt = tests[i];
            if (tt[2] == 0) {
                assertEquals(tt[1], l.term(tt[0]), "term of case " + i);
                continue;
            }
            RaftException e = assertThrows(RaftException.class, () -> l.term(tt[0]), "case " + i);
            if (tt[2] == 1) {
                assertTrue(e.getMessage().contains("due to compaction"),
                        "case " + i + " expected ErrCompacted but got: " + e.getMessage());
            } else {
                assertTrue(e.getMessage().contains("is unavailable"),
                        "case " + i + " expected ErrUnavailable but got: " + e.getMessage());
            }
        }
    }

    /**
     * Go: TestSlice
     */
    @Test
    public void testSlice() {
        long offset = 100;
        long num = 100;
        long last = offset + num;
        long half = offset + num / 2;
        Entry halfe = new Entry(half, half, EntryType.NORMAL, null);

        MemoryStorage storage = new MemoryStorage();
        storage.applySnapshot(snapshot(offset, 0));
        storage.append(termRange(offset + 1, offset + 1, half));
        RaftLog l = new RaftLog(storage);
        l.append(termRange(half, half, last));

        long entrySize = RaftCodec.entrySize(halfe);
        long noLimit = Long.MAX_VALUE;
        // {lo, hi, lim, w}
        SliceCase[] tests = {
                // ErrCompacted.
                new SliceCase(offset - 1, offset + 1, noLimit, null, false),
                new SliceCase(offset, offset + 1, noLimit, null, false),
                // panics
                new SliceCase(half, half - 1, noLimit, null, true),
                new SliceCase(last, last + 1, noLimit, null, true),

                // No limit.
                new SliceCase(offset + 1, offset + 1, noLimit, Collections.<Entry>emptyList(), false),
                new SliceCase(offset + 1, half - 1, noLimit, termRange(offset + 1, offset + 1, half - 1), false),
                new SliceCase(offset + 1, half, noLimit, termRange(offset + 1, offset + 1, half), false),
                new SliceCase(offset + 1, half + 1, noLimit, termRange(offset + 1, offset + 1, half + 1), false),
                new SliceCase(offset + 1, last, noLimit, termRange(offset + 1, offset + 1, last), false),
                new SliceCase(half - 1, half, noLimit, termRange(half - 1, half - 1, half), false),
                new SliceCase(half - 1, half + 1, noLimit, termRange(half - 1, half - 1, half + 1), false),
                new SliceCase(half - 1, last, noLimit, termRange(half - 1, half - 1, last), false),
                new SliceCase(half, half + 1, noLimit, termRange(half, half, half + 1), false),
                new SliceCase(half, last, noLimit, termRange(half, half, last), false),
                new SliceCase(last - 1, last, noLimit, termRange(last - 1, last - 1, last), false),

                // At least one entry is always returned.
                new SliceCase(offset + 1, last, 0, termRange(offset + 1, offset + 1, offset + 2), false),
                new SliceCase(half - 1, half + 1, 0, termRange(half - 1, half - 1, half), false),
                new SliceCase(half, last, 0, termRange(half, half, half + 1), false),
                new SliceCase(half + 1, last, 0, termRange(half + 1, half + 1, half + 2), false),
                // Low limit.
                new SliceCase(offset + 1, last, entrySize - 1, termRange(offset + 1, offset + 1, offset + 2), false),
                new SliceCase(half - 1, half + 1, entrySize - 1, termRange(half - 1, half - 1, half), false),
                new SliceCase(half, last, entrySize - 1, termRange(half, half, half + 1), false),
                // Just enough for one limit.
                new SliceCase(offset + 1, last, entrySize, termRange(offset + 1, offset + 1, offset + 2), false),
                new SliceCase(half - 1, half + 1, entrySize, termRange(half - 1, half - 1, half), false),
                new SliceCase(half, last, entrySize, termRange(half, half, half + 1), false),
                // Not enough for two limit.
                new SliceCase(offset + 1, last, entrySize + 1, termRange(offset + 1, offset + 1, offset + 2), false),
                new SliceCase(half - 1, half + 1, entrySize + 1, termRange(half - 1, half - 1, half), false),
                new SliceCase(half, last, entrySize + 1, termRange(half, half, half + 1), false),
                // Enough for two limit.
                new SliceCase(offset + 1, last, entrySize * 2, termRange(offset + 1, offset + 1, offset + 3), false),
                new SliceCase(half - 2, half + 1, entrySize * 2, termRange(half - 2, half - 2, half), false),
                new SliceCase(half - 1, half + 1, entrySize * 2, termRange(half - 1, half - 1, half + 1), false),
                new SliceCase(half, last, entrySize * 2, termRange(half, half, half + 2), false),
                // Not enough for three.
                new SliceCase(half - 2, half + 1, entrySize * 3 - 1, termRange(half - 2, half - 2, half), false),
                // Enough for three.
                new SliceCase(half - 1, half + 2, entrySize * 3, termRange(half - 1, half - 1, half + 2), false),
        };

        for (int i = 0; i < tests.length; i++) {
            SliceCase tt = tests[i];
            if (tt.wpanic) {
                assertThrows(RaftException.class, () -> l.slice(tt.lo, tt.hi, tt.lim), "case " + i);
                continue;
            }
            List<Entry> g;
            try {
                g = l.slice(tt.lo, tt.hi, tt.lim);
            } catch (RaftException e) {
                // lo <= offset 时 Go 返回 ErrCompacted
                assertTrue(tt.lo <= offset && e.getMessage().contains("due to compaction"),
                        "case " + i + " unexpected error: " + e.getMessage());
                continue;
            }
            assertEquals(tt.w, g, "entries of case " + i);
        }
    }

    /**
     * Go: TestScan
     */
    @Test
    public void testScan() {
        long offset = 47;
        long num = 20;
        long last = offset + num;
        long half = offset + num / 2;

        MemoryStorage storage = new MemoryStorage();
        storage.applySnapshot(snapshot(offset, 0));
        storage.append(termRange(offset + 1, offset + 1, half));
        RaftLog l = new RaftLog(storage);
        l.append(termRange(half, half, last));

        long entrySize = RaftCodec.entriesSize(termRange(half, half, half + 1));

        // 验证 scan() 在所有输入上都与 slice() 返回同样的条目
        long[] pageSizes = {0, 1, 10, 100, entrySize, entrySize + 1};
        for (long pageSize : pageSizes) {
            for (long lo = offset + 1; lo < last; lo++) {
                for (long hi = lo; hi <= last; hi++) {
                    final List<Entry> got = new ArrayList<>();
                    l.scan(lo, hi, pageSize, new RaftLog.PageConsumer() {
                        @Override
                        public boolean accept(List<Entry> ents) {
                            assertTrue(ents.size() == 1 || RaftCodec.entriesSize(ents) <= pageSize,
                                    "page size exceeded");
                            got.addAll(ents);
                            return true;
                        }
                    });
                    List<Entry> want = l.slice(lo, hi, Long.MAX_VALUE);
                    assertEquals(want, got,
                            "scan() and slice() mismatch on [" + lo + ", " + hi + ") @ " + pageSize);
                }
            }
        }

        // 回调提前结束时扫描要停下来
        final int[] iters = {0};
        l.scan(offset + 1, half, 0, new RaftLog.PageConsumer() {
            @Override
            public boolean accept(List<Entry> ents) {
                iters[0]++;
                // 第二次就停止
                return iters[0] != 2;
            }
        });
        assertEquals(2, iters[0], "iterations");

        // 扫描要顶到上限，而不是每次只返回一条
        l.scan(offset + 1, offset + 11, entrySize * 2, new RaftLog.PageConsumer() {
            @Override
            public boolean accept(List<Entry> ents) {
                assertEquals(2, ents.size(), "page size");
                assertEquals(entrySize * 2, RaftCodec.entriesSize(ents), "page bytes");
                return true;
            }
        });
    }

    /**
     * Go: index(i).terms(terms...)
     *
     * <p>生成一段日志条目，index 从 startIndex 开始递增，term 取自 terms。</p>
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
     * Go: index(from).termRange(from, to)
     *
     * <p>生成 from 到 to-1 的条目，index 与 term 相同。</p>
     */
    private static List<Entry> termRange(long from, long to) {
        List<Entry> ents = new ArrayList<>((int) (to - from));
        for (long i = from; i < to; i++) {
            ents.add(new Entry(i, i, EntryType.NORMAL, null));
        }
        return ents;
    }

    /**
     * Go: index(i).termRange(from, to)
     *
     * <p>生成 from 到 to-1 的条目，index 从 startIndex 开始递增，term 从 from 开始递增。</p>
     */
    private static List<Entry> termRange(long startIndex, long from, long to) {
        List<Entry> ents = new ArrayList<>((int) (to - from));
        long index = startIndex;
        for (long term = from; term < to; term++) {
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

    /**
     * {@code maybeAppend} 的测试用例
     */
    private static final class MaybeAppendCase {

        private final long       prevTerm;
        private final long       prevIndex;
        private final long       leaderCommit;
        private final List<Entry> ents;
        private final long       wlasti;
        private final boolean    wappend;
        private final long       wcommit;
        private final boolean    wpanic;

        private MaybeAppendCase(long prevTerm, long prevIndex, long leaderCommit, List<Entry> ents,
                                long wlasti, boolean wappend, long wcommit, boolean wpanic) {
            this.prevTerm = prevTerm;
            this.prevIndex = prevIndex;
            this.leaderCommit = leaderCommit;
            this.ents = ents;
            this.wlasti = wlasti;
            this.wappend = wappend;
            this.wcommit = wcommit;
            this.wpanic = wpanic;
        }
    }

    /**
     * {@code slice} 的测试用例
     */
    private static final class SliceCase {

        private final long       lo;
        private final long       hi;
        private final long       lim;
        private final List<Entry> w;
        private final boolean    wpanic;

        private SliceCase(long lo, long hi, long lim, List<Entry> w, boolean wpanic) {
            this.lo = lo;
            this.hi = hi;
            this.lim = lim;
            this.w = w;
            this.wpanic = wpanic;
        }
    }

    /**
     * {@code compact} 的测试用例
     */
    private static final class CompactionCase {

        private final long   lastIndex;
        private final long[] compact;
        private final long[] wleft;
        private final boolean wallow;

        private CompactionCase(long lastIndex, long[] compact, long[] wleft, boolean wallow) {
            this.lastIndex = lastIndex;
            this.compact = compact;
            this.wleft = wleft;
            this.wallow = wallow;
        }
    }
}
