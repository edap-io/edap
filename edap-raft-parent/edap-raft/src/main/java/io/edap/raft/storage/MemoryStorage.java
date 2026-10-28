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
import io.edap.raft.RaftUtil;
import io.edap.raft.protocol.ConfState;
import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.EntryType;
import io.edap.raft.protocol.HardState;
import io.edap.raft.protocol.Snapshot;
import io.edap.raft.protocol.SnapshotMetadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 内存实现的 {@link Storage}，等价于 etcd raft 的 {@code MemoryStorage}。
 *
 * <p>内部用一个数组保存日志：{@code ents.get(i)} 对应的 raft log 位置是
 * {@code i + ents.get(0).getIndex()}。数组的第 0 个元素是占位条目
 * （etcd 里的 dummy entry），它的 index 就是当前保留区间的起点，
 * 因此 {@link #firstIndex()} 恒等于 {@code ents.get(0).getIndex() + 1}，
 * {@link #lastIndex()} 恒等于 {@code ents.get(0).getIndex() + ents.size() - 1}。</p>
 *
 * <p>所有方法都加锁：读侧在 Raft 核心线程上调用，写侧（{@link #append} 等）
 * 可能来自应用线程。</p>
 */
public class MemoryStorage implements Storage {

    /**
     * 占位条目：etcd 的 dummy entry，index/term 都是 0，表示“还没有任何日志”。
     */
    private static final Entry DUMMY_ENTRY = new Entry(0, 0, EntryType.NORMAL, null);

    /** 上次持久化的 HardState，从未持久化过为 null */
    private HardState hardState;

    /** 最近一次快照 */
    private Snapshot snapshot;

    /** 快照点处生效的成员配置 */
    private ConfState confState;

    /**
     * 保留的日志条目，{@code ents.get(i)} 的 raft log 位置是 {@code i + ents.get(0).getIndex()}
     */
    private final List<Entry> ents = new ArrayList<>();

    /** 最近一次快照的 index */
    private long snapshotIndex;

    /**
     * 创建一个空的内存存储，没有成员配置也没有日志
     */
    public MemoryStorage() {
        this((ConfState) null);
    }

    /**
     * 创建一个空的内存存储，并把给定的成员配置作为初始快照的配置。
     *
     * <p>etcd 里 bootstrap 时就是这样把集群成员写进存储的：
     * 配置跟着快照一起保存，节点重启后从快照里读回来。</p>
     */
    public MemoryStorage(ConfState cs) {
        this.confState = cs == null ? emptyConfState() : cs;
        this.snapshot = new Snapshot(new byte[0], new SnapshotMetadata(this.confState, 0, 0));
        this.snapshotIndex = 0;
        this.ents.add(DUMMY_ENTRY);
    }

    private static ConfState emptyConfState() {
        return new ConfState(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), false);
    }

    /**
     * 测试专用：直接指定存储里的日志条目。
     *
     * <p>Go 的测试直接给 {@code MemoryStorage} 的 {@code ents} 字段赋值，
     * Java 侧用这个构造器达到同样的效果。{@code ents.get(0)} 是占位条目，
     * 它的 index 就是保留区间的起点。</p>
     */
    MemoryStorage(List<Entry> ents) {
        this.confState = emptyConfState();
        this.snapshot = new Snapshot(new byte[0], new SnapshotMetadata(this.confState, 0, 0));
        this.snapshotIndex = 0;
        if (ents != null) {
            this.ents.addAll(ents);
        }
    }

    /**
     * 测试专用：存储里保留的日志条目，第 0 个是占位条目。
     */
    synchronized List<Entry> getEnts() {
        return Collections.unmodifiableList(new ArrayList<>(ents));
    }

    /**
     * 测试专用：整体替换存储里保留的日志条目。
     *
     * <p>Go 的测试直接给 {@code MemoryStorage} 的 {@code ents} 字段赋值，
     * Java 侧用这个方法达到同样的效果。{@code ents.get(0)} 必须是占位条目，
     * 它的 index 就是保留区间的起点。</p>
     */
    synchronized void setEnts(List<Entry> ents) {
        this.ents.clear();
        if (ents != null) {
            this.ents.addAll(ents);
        }
    }

    @Override
    public synchronized HardState initialState() {
        return hardState;
    }

    /**
     * 保存当前的 HardState（term/vote/commit），应用层负责在回复投票请求前落盘
     */
    public synchronized void setHardState(HardState st) {
        this.hardState = st;
    }

    /**
     * 快照点处生效的成员配置，没有保存过返回 null
     */
    public synchronized ConfState getConfState() {
        return confState;
    }

    @Override
    public synchronized List<Entry> entries(long lo, long hi, long maxSize) {
        long offset = ents.get(0).getIndex();
        if (lo <= offset) {
            // 已经被压缩掉了
            throw new RaftException("requested index " + lo + " is unavailable due to compaction");
        }
        long last = lastIndex();
        if (hi > last + 1) {
            throw new RaftException("entries' hi(" + hi + ") is out of bound lastindex(" + last + ")");
        }
        if (ents.size() == 1) {
            // 只剩下占位条目，没有任何可用的日志
            throw new RaftException("requested entry at index " + lo + " is unavailable");
        }
        int from = (int) (lo - offset);
        int to = (int) (hi - offset);
        // 复制一份，避免调用方往返回的列表里追加时破坏内部状态
        return RaftUtil.limitSize(new ArrayList<>(ents.subList(from, to)), maxSize);
    }

    @Override
    public synchronized Entry term(long index) {
        long offset = ents.get(0).getIndex();
        if (index < offset) {
            // 已经被压缩掉
            throw new RaftException("requested index " + index + " is unavailable due to compaction");
        }
        int i = (int) (index - offset);
        if (i >= ents.size()) {
            // 还没有这条日志
            throw new RaftException("requested entry at index " + index + " is unavailable");
        }
        Entry entry = ents.get(i);
        return new Entry(entry.getIndex(), entry.getTerm(), EntryType.NORMAL, null);
    }

    @Override
    public synchronized long lastIndex() {
        return ents.get(0).getIndex() + ents.size() - 1;
    }

    @Override
    public synchronized long firstIndex() {
        return ents.get(0).getIndex() + 1;
    }

    @Override
    public synchronized Snapshot snapshot() {
        return snapshot;
    }

    /**
     * 用给定的快照整体覆盖当前存储内容。
     *
     * <p>快照之前（含快照点）的日志会被丢弃，之后要继续写日志时从
     * {@code snapshotIndex + 1} 开始。快照比当前快照还旧时抛
     * {@link RaftException}（etcd 的 {@code ErrSnapOutOfDate}）。</p>
     */
    public synchronized void applySnapshot(Snapshot snap) {
        if (snap == null) {
            throw new RaftException("snapshot must not be null");
        }
        long snapIndex = snap.getLastIncludedIndex();
        // bootstrap 时应用可能只带 ConfState，此时快照的 index/term 都是 0；
        // 这种快照只有在本存储还没有任何快照时才是合法的
        if (this.snapshotIndex != 0 && this.snapshotIndex >= snapIndex) {
            throw new RaftException("requested index " + snapIndex
                    + " is older than the existing snapshot " + this.snapshotIndex);
        }
        this.snapshot = snap;
        this.snapshotIndex = snapIndex;
        if (snap.getConfState() != null) {
            this.confState = snap.getConfState();
        }
        // 快照点之前的日志都被压缩掉了，只留一个占位条目
        this.ents.clear();
        this.ents.add(new Entry(snapIndex, snap.getLastIncludedTerm(), EntryType.NORMAL, null));
    }

    /**
     * 在 index 处生成一份快照，返回这份新快照。
     *
     * <p>如果自上次压缩以来发生过成员变更，调用方要把成员变更应用
     * 的结果（{@link ConfState}）传进来。</p>
     */
    public synchronized Snapshot createSnapshot(long index, ConfState cs, byte[] data) {
        if (index <= snapshotIndex) {
            throw new RaftException("requested index " + index
                    + " is older than the existing snapshot " + snapshotIndex);
        }
        long last = lastIndex();
        if (index > last) {
            throw new RaftException("snapshot " + index + " is out of bound lastindex(" + last + ")");
        }
        long offset = ents.get(0).getIndex();
        long term = ents.get((int) (index - offset)).getTerm();
        if (cs != null) {
            this.confState = cs;
        }
        this.snapshotIndex = index;
        this.snapshot = new Snapshot(data == null ? new byte[0] : data,
                new SnapshotMetadata(this.confState, index, term));
        return this.snapshot;
    }

    /**
     * 丢弃 index 之前（含 index）的日志条目。
     *
     * <p>调用方要保证不会压缩掉尚未 applied 的日志。</p>
     */
    public synchronized void compact(long index) {
        long offset = ents.get(0).getIndex();
        if (index <= offset) {
            throw new RaftException("requested index " + index + " is unavailable due to compaction");
        }
        long last = lastIndex();
        if (index > last) {
            throw new RaftException("compact " + index + " is out of bound lastindex(" + last + ")");
        }
        int i = (int) (index - offset);
        Entry compactEntry = ents.get(i);
        // 重新分配数组，避免外部还持有旧数组的引用
        List<Entry> compacted = new ArrayList<>(ents.size() - i);
        compacted.add(new Entry(compactEntry.getIndex(), compactEntry.getTerm(), EntryType.NORMAL, null));
        compacted.addAll(ents.subList(i + 1, ents.size()));
        ents.clear();
        ents.addAll(compacted);
    }

    /**
     * 把新的日志条目追加到存储里。
     *
     * <p>条目必须与已有的日志连续（第一条的 index 不超过 lastIndex + 1），
     * 否则抛 {@link RaftException}；与已有日志重叠的部分会被覆盖，
     * 用来实现冲突截断后的重写。</p>
     */
    public synchronized void append(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        long first = firstIndex();
        long last = entries.get(0).getIndex() + entries.size() - 1;
        if (last < first) {
            // 全部都已经被压缩掉了
            return;
        }
        List<Entry> toAppend = entries;
        if (first > entries.get(0).getIndex()) {
            // 丢掉已经被压缩掉的前缀
            toAppend = entries.subList((int) (first - entries.get(0).getIndex()), entries.size());
        }
        long offset = toAppend.get(0).getIndex() - ents.get(0).getIndex();
        if (ents.size() > offset) {
            // 与已有日志重叠，覆盖尾部
            ents.subList((int) offset, ents.size()).clear();
            ents.addAll(toAppend);
        } else if (ents.size() == offset) {
            // 正好接在已有日志后面
            ents.addAll(toAppend);
        } else {
            throw new RaftException("missing log entry [last: " + lastIndex()
                    + ", append at: " + toAppend.get(0).getIndex() + "]");
        }
    }

    @Override
    public synchronized String toString() {
        return "MemoryStorage{firstIndex=" + firstIndex() + ", lastIndex=" + lastIndex()
                + ", snapshotIndex=" + snapshotIndex + "}";
    }
}
