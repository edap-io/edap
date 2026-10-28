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
import io.edap.raft.RaftLogger;
import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.Snapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 还没有落盘的日志条目与快照，等价于 etcd raft 的 {@code unstable}。
 *
 * <p>它承担两个角色：一是暂存新日志与可选快照，等待交给应用层持久化；
 * 二是即便已经交给应用层、正在写盘期间也继续持有它们，这样
 * {@link RaftLog} 在写入稳定之前仍能查到这些条目的 index/term。</p>
 *
 * <p>{@code entries.get(i)} 对应的 raft log 位置是 {@code i + offset}。
 * 注意 offset 可能小于存储里的最后一条日志的 index —— 这意味着下一次写盘
 * 需要先截断日志再写入这些条目。</p>
 */
public class Unstable {

    /** 待落盘的快照，没有为 null */
    private Snapshot snapshot;

    /** 尚未落盘的日志条目，{@code entries.get(i)} 的位置是 {@code i + offset} */
    private final List<Entry> entries = new ArrayList<>();

    /** {@code entries.get(0)} 对应的 raft log index */
    private long offset;

    /** 快照是否正在落盘 */
    private boolean snapshotInProgress;

    /**
     * 正在落盘的条目范围是 {@code [offset, offsetInProgress)}。
     * 与 offset 一样是开区间上界，含义是“最后一个正在写盘的条目的下一个位置”。
     * 不变式：{@code offset <= offsetInProgress}
     */
    private long offsetInProgress;

    /** 日志输出器，null 表示不记录 */
    private RaftLogger log;

    /**
     * 创建一个空的未落盘区，offset 是 {@code entries.get(0)} 对应的 raft log index
     */
    public Unstable(long offset) {
        this.offset = offset;
        this.offsetInProgress = offset;
    }

    /**
     * 测试专用：直接指定未落盘区的各个内部状态。
     *
     * <p>Go 的测试直接给 {@code unstable} 结构体赋值，
     * Java 侧用这个构造器达到同样的效果。</p>
     */
    Unstable(long offset, long offsetInProgress, List<Entry> entries,
             Snapshot snapshot, boolean snapshotInProgress) {
        this.offset = offset;
        this.offsetInProgress = offsetInProgress;
        if (entries != null) {
            this.entries.addAll(entries);
        }
        this.snapshot = snapshot;
        this.snapshotInProgress = snapshotInProgress;
    }

    /**
     * {@code entries.get(0)} 对应的 raft log index
     */
    public long getOffset() {
        return offset;
    }

    /**
     * 设置日志输出器，null 表示不记录
     */
    public void setLogger(RaftLogger log) {
        this.log = log;
    }

    /**
     * 正在落盘的条目范围上界（开区间），不变式 {@code offset <= offsetInProgress}
     */
    public long getOffsetInProgress() {
        return offsetInProgress;
    }

    /**
     * 未落盘区里的最后一条日志的 index。
     *
     * <p>有未落盘条目时是 {@code offset + entries.size() - 1}；没有条目但有快照时
     * 是快照点；两者都没有时返回 0。等价于 etcd 的 {@code maybeLastIndex}
     * 返回的 index，配合 {@link #maybeLastIndex()} 判断是否有效。</p>
     */
    public long lastIndex() {
        if (!entries.isEmpty()) {
            return offset + entries.size() - 1;
        }
        if (snapshot != null) {
            return snapshot.getLastIncludedIndex();
        }
        return 0;
    }

    /**
     * 未落盘区里的第一条日志的 index。
     *
     * <p>有未落盘快照时是快照 index + 1，否则返回 0。等价于 etcd 的
     * {@code maybeFirstIndex} 返回的 index，配合 {@link #maybeFirstIndex()}
     * 判断是否有效。</p>
     */
    public long firstIndex() {
        if (snapshot != null) {
            return snapshot.getLastIncludedIndex() + 1;
        }
        return 0;
    }

    /**
     * 待落盘的快照，没有返回 null
     */
    public Snapshot getSnapshot() {
        return snapshot;
    }

    /**
     * 快照是否正在落盘
     */
    public boolean isSnapshotInProgress() {
        return snapshotInProgress;
    }

    /**
     * 是否存在待落盘的快照。存在时第一条日志的 index 是快照 index + 1
     */
    public boolean maybeFirstIndex() {
        return snapshot != null;
    }

    /**
     * 未落盘区里是否有内容（有未落盘条目或者有待落盘的快照）
     */
    public boolean maybeLastIndex() {
        return !entries.isEmpty() || snapshot != null;
    }

    /**
     * index 处的任期是否已知
     */
    public boolean maybeTerm(long i) {
        if (i < offset) {
            // 落在快照点上的话，任期可以从快照里拿到
            return snapshot != null && snapshot.getLastIncludedIndex() == i;
        }
        long last = lastIndex();
        if (!maybeLastIndex() || i > last) {
            return false;
        }
        long pos = i - offset;
        return pos >= 0 && pos < entries.size();
    }

    /**
     * index 处的任期，来自快照或未落盘条目；未知时抛 {@link RaftException}
     */
    public long term(long i) {
        if (i < offset) {
            if (snapshot != null && snapshot.getLastIncludedIndex() == i) {
                return snapshot.getLastIncludedTerm();
            }
            throw new RaftException("requested entry at index " + i + " is unavailable");
        }
        long last = lastIndex();
        if (!maybeLastIndex() || i > last) {
            throw new RaftException("requested entry at index " + i + " is unavailable");
        }
        long pos = i - offset;
        if (pos < 0 || pos >= entries.size()) {
            throw new RaftException("requested entry at index " + i + " is unavailable");
        }
        return entries.get((int) pos).getTerm();
    }

    /**
     * 全部未落盘条目（包含正在落盘的那部分）
     */
    public List<Entry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * 区间 [lo, hi) 的未落盘条目，等价于 etcd 的 {@code unstable.slice}。
     *
     * <p>区间超出未落盘区时按未落盘区的边界裁剪。</p>
     */
    public List<Entry> entriesIn(long lo, long hi) {
        if (entries.isEmpty()) {
            return Collections.emptyList();
        }
        int from = (int) (lo - offset);
        int to = (int) (hi - offset);
        if (from < 0) {
            from = 0;
        }
        if (to > entries.size()) {
            to = entries.size();
        }
        if (to <= from) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(entries.subList(from, to)));
    }

    /**
     * 还没有进入落盘流程的未落盘条目，也就是 Ready 里需要写盘的那部分
     */
    public List<Entry> nextEntries() {
        long inProgress = offsetInProgress - offset;
        if (inProgress < 0) {
            inProgress = 0;
        }
        if (entries.size() == inProgress) {
            return Collections.emptyList();
        }
        if (inProgress >= entries.size()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(entries.subList((int) inProgress, entries.size())));
    }

    /**
     * 还没有进入落盘流程的快照，没有或者正在落盘返回 null
     */
    public Snapshot nextSnapshot() {
        if (snapshot == null || snapshotInProgress) {
            return null;
        }
        return snapshot;
    }

    /**
     * 标记当前的未落盘条目与快照已经开始落盘。
     *
     * <p>标记之后 {@link #nextEntries()} / {@link #nextSnapshot()} 不再返回它们，
     * 但之后新增的条目仍然会被返回，直到下一次调用本方法。</p>
     */
    public void acceptInProgress() {
        if (!entries.isEmpty()) {
            // offsetInProgress 是开区间上界，所以要 +1
            offsetInProgress = entries.get(entries.size() - 1).getIndex() + 1;
        }
        if (snapshot != null) {
            snapshotInProgress = true;
        }
    }

    /**
     * 标记 index 处的条目（任期为 term）已经成功落盘。
     *
     * <p>只有当调用方能保证这些条目不会被正在进行的日志追加覆盖时才能调用。
     * 找不到对应条目、index 落在快照上、任期不匹配时都直接忽略。</p>
     */
    public void stableTo(long i, long expectedTerm) {
        if (!maybeTerm(i)) {
            // 未落盘区里没有这条条目，忽略
            if (log != null) {
                log.info("entry at index %d missing from unstable log; ignoring", i);
            }
            return;
        }
        if (i < offset) {
            // 命中的是未落盘的快照而不是条目，忽略
            if (log != null) {
                log.info("entry at index %d matched unstable snapshot; ignoring", i);
            }
            return;
        }
        long gt = term(i);
        if (gt != expectedTerm) {
            // 任期不一致，说明落盘期间未落盘日志被替换过，忽略
            if (log != null) {
                log.info("entry at (index,term)=(%d,%d) mismatched with entry at (%d,%d) in unstable log; ignoring",
                        i, expectedTerm, i, gt);
            }
            return;
        }
        int num = (int) (i + 1 - offset);
        entries.subList(0, num).clear();
        offset = i + 1;
        offsetInProgress = Math.max(offsetInProgress, offset);
    }

    /**
     * 标记 index 处的快照已经成功落盘
     */
    public void stableSnapTo(long i) {
        if (snapshot != null && snapshot.getLastIncludedIndex() == i) {
            snapshot = null;
            snapshotInProgress = false;
        }
    }

    /**
     * 用给定的快照重置未落盘区：丢弃全部未落盘条目，把 offset 挪到快照点之后
     */
    public void restore(Snapshot s) {
        if (s == null) {
            throw new RaftException("snapshot must not be null");
        }
        this.offset = s.getLastIncludedIndex() + 1;
        this.offsetInProgress = this.offset;
        this.entries.clear();
        this.snapshot = s;
        this.snapshotInProgress = false;
    }

    /**
     * 追加新的日志条目，必要时先截断未落盘日志。
     *
     * <p>三种情况：</p>
     * <ul>
     *     <li>新条目的第一条正好接在未落盘日志后面：直接追加</li>
     *     <li>新条目的第一条落在未落盘区之内或之前：整个替换未落盘区</li>
     *     <li>新条目的第一条落在未落存区之后：截断到该位置再追加</li>
     * </ul>
     */
    public void truncateAndAppend(List<Entry> ents) {
        if (ents == null || ents.isEmpty()) {
            return;
        }
        long fromIndex = ents.get(0).getIndex();
        if (fromIndex == offset + entries.size()) {
            // 正好接在后面，直接追加
            entries.addAll(ents);
        } else if (fromIndex <= offset) {
            // 日志被截断到未落存区之前，整个替换掉
            if (log != null) {
                log.info("replace the unstable entries from index %d", fromIndex);
            }
            entries.clear();
            entries.addAll(ents);
            offset = fromIndex;
            offsetInProgress = offset;
        } else {
            // 截断到 fromIndex（不含），再追加新条目
            if (log != null) {
                log.info("truncate the unstable entries before index %d", fromIndex);
            }
            int keep = (int) (fromIndex - offset);
            List<Entry> kept = new ArrayList<>(entries.subList(0, keep));
            kept.addAll(ents);
            entries.clear();
            entries.addAll(kept);
            // fromIndex 之前才可能还有正在落盘的条目
            offsetInProgress = Math.min(offsetInProgress, fromIndex);
        }
    }

    /**
     * 丢弃 index 之后的全部未落盘条目。
     *
     * <p>index 早于当前 offset 时未落存区被整体清空，offset 随之回退到 index。</p>
     */
    public void truncateSuffix(long index) {
        if (entries.isEmpty()) {
            if (index < offset) {
                offset = index;
                offsetInProgress = Math.min(offsetInProgress, index);
            }
            return;
        }
        long last = offset + entries.size() - 1;
        if (index > last) {
            return;
        }
        if (index <= offset) {
            entries.clear();
            offset = index;
            offsetInProgress = Math.min(offsetInProgress, index);
            return;
        }
        int keep = (int) (index - offset);
        entries.subList(keep, entries.size()).clear();
        offsetInProgress = Math.min(offsetInProgress, index);
    }

    @Override
    public String toString() {
        return "Unstable{offset=" + offset + ", offsetInProgress=" + offsetInProgress
                + ", entries=" + entries.size() + ", snapshotIndex="
                + (snapshot == null ? -1 : snapshot.getLastIncludedIndex()) + "}";
    }
}
