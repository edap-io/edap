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
import io.edap.raft.codec.ByteReader;
import io.edap.raft.codec.ByteWriter;
import io.edap.raft.protocol.ConfState;
import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.EntryType;
import io.edap.raft.protocol.HardState;
import io.edap.raft.protocol.Snapshot;
import io.edap.raft.protocol.SnapshotMetadata;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.CRC32;

/**
 * 文件实现的 {@link PersistentStorage}，把 raft 的日志与硬状态持久化到磁盘。
 *
 * <p>目录里有两个文件：</p>
 * <pre>
 *   wal.bin       magic("EDAPWAL") + version(1) + 若干条记录
 *                 记录 = length(4) + crc32(4) + payload
 *                 payload = type(1) + 记录体
 *                   type=1 硬状态：term(8) + vote(8) + commit(8)
 *                   type=2 日志条目：index(8) + term(8) + entryType(1) + dataLen(4) + data
 *   snapshot.bin  magic("EDAP") + version(1) + lastIncludedIndex(8) + lastIncludedTerm(8)
 *                 + confStateLen(4) + confState + dataLen(4) + data + crc32(4)
 * </pre>
 * <p>全部是大端序。{@code crc32} 覆盖它前面紧邻的所有字节（记录头之后）。</p>
 *
 * <p><b>硬状态与日志在同一个文件里</b>，因此一次落盘只需要一次 fsync。
 * 早期版本把硬状态放在 {@code meta.bin}、日志放在 {@code log.bin}，
 * 每次 Ready 要两次 fsync，其中一次只写 24 字节 —— 实测那一次占掉了
 * 27%~49% 的 fsync 预算。</p>
 *
 * <p><b>commit 不单独落盘</b>：硬状态记录只在 term 或 vote 变化时写，
 * commit 搭车写进去，落盘的是它的陈旧下界。节点重启后 commit 由 leader
 * 的 AppendEntries 重新建立（raft 论文 §5.4.2）。term 与 vote 则必须在
 * 回复投票请求之前落盘，这由「落盘先于消息投递」保证。</p>
 *
 * <p>{@code wal.bin} 的第一条条目记录是「占位记录」（dummy）：它只带 index 与 term，
 * 不带数据，作用与 {@link MemoryStorage} 里的 dummy entry 一致 ——
 * 标记被压缩掉的区间的终点，使得 {@link #term(long)} 仍能回答压缩点上的 term。
 * 真正的日志条目从它后面开始，index 连续递增。</p>
 *
 * <p>写入策略：</p>
 * <ul>
 *     <li>{@code wal.bin} 追加写；<b>整批拼成一个 buffer，一次 write、一次 force</b>。
 *         冲突截断时先截断再追加，每条记录带 CRC32，恢复时第一条校验不过的
 *         记录及其后全部丢弃并把文件截断（处理尾部半写）</li>
 *     <li>{@code snapshot.bin} 整体重写，走「写 .tmp → {@code force(true)} →
 *         {@code ATOMIC_MOVE}」，避免半写</li>
 * </ul>
 *
 * <p>所有方法都加锁：读侧在 Raft 核心线程上调用，写侧可能来自应用线程。</p>
 */
public class FileRaftStorage implements PersistentStorage, AutoCloseable {

    /** WAL 文件魔数 */
    private static final byte[] MAGIC = {'E', 'D', 'A', 'P', 'W', 'A', 'L'};

    /** 快照文件魔数 */
    private static final byte[] SNAP_MAGIC = {'E', 'D', 'A', 'P'};

    /** 格式版本 */
    private static final int VERSION = 1;

    /** 文件头长度：magic(8) + version(1) */
    private static final int FILE_HEADER_LEN = MAGIC.length + 1;

    /** 记录头部长度：length(4) + crc32(4) */
    private static final int HEADER_LEN = 8;

    /** 记录类型：硬状态 */
    private static final byte TYPE_HARD_STATE = 1;

    /** 记录类型：日志条目 */
    private static final byte TYPE_ENTRY = 2;

    /** 硬状态记录的定长长度：type(1) + term(8) + vote(8) + commit(8) */
    private static final int HARD_STATE_LEN = 25;

    /** 条目记录的定长长度：type(1) + index(8) + term(8) + requestId(8) + entryType(1) + dataLen(4) */
    private static final int ENTRY_FIXED_LEN = 30;

    /** 数据目录 */
    private final Path dir;

    /** WAL 文件路径 */
    private final Path walPath;

    /** 快照文件路径 */
    private final Path snapPath;

    /** WAL 文件句柄 */
    private RandomAccessFile walFile;

    /** 上次持久化的 HardState，从未持久化过为 null */
    private HardState hardState;

    /** 最近一次快照 */
    private Snapshot snapshot;

    /** 最近一次快照的 index，0 表示没有快照 */
    private long snapshotIndex;

    /** 占位记录的 index */
    private long dummyIndex;

    /** 占位记录的 term */
    private long dummyTerm;

    /** 日志条目索引：index → 记录在 WAL 里的位置与元信息 */
    private final TreeMap<Long, Rec> records = new TreeMap<>();

    /**
     * 写侧串行锁。
     *
     * <p>写盘（write + force）要几毫秒到几十毫秒，这段时间里不能占着对象本身的
     * 监视器：读侧（{@link #entries}、{@link #term}、{@link #firstIndex}）会被
     * 状态机线程在热路径上高频调用，占着监视器等于把状态机线程卡在 fsync 上。
     * 实测里 leader 的状态机线程在 {@code bcastAppend} 里读 firstIndex 时，
     * 被应用线程的 fsync 挡住整整 12ms。</p>
     *
     * <p>加锁顺序固定为 writeLock → 监视器，反过来永远不出现。</p>
     */
    private final Object writeLock = new Object();

    /** 真正写盘（write + force）的次数 */
    private long persistCount;

    /** 真正写盘的累计耗时（纳秒） */
    private long persistNanos;

    /** 真正写盘的条目总数 */
    private long persistEntries;

    /**
     * 打开（或创建）指定目录下的存储。
     *
     * @param dir 数据目录，不存在时会被创建
     */
    public FileRaftStorage(Path dir) {
        if (dir == null) {
            throw new RaftException("storage dir must not be null");
        }
        this.dir = dir.toAbsolutePath();
        this.walPath = this.dir.resolve("wal.bin");
        this.snapPath = this.dir.resolve("snapshot.bin");
        try {
            Files.createDirectories(this.dir);
            // 与 MemoryStorage 一致：没有快照时给一个空的快照
            this.snapshot = new Snapshot(new byte[0], new SnapshotMetadata(emptyConfState(), 0, 0));
            loadSnapshot();
            openWal();
        } catch (IOException e) {
            throw new RaftException("Open raft storage failed: " + this.dir, e);
        }
    }

    /**
     * 打开（或创建）指定目录下的存储。
     *
     * @param dir 数据目录
     */
    public static FileRaftStorage open(String dir) {
        return new FileRaftStorage(java.nio.file.Paths.get(dir));
    }

    // ------------------------------------------------------------------
    // Storage 只读接口
    // ------------------------------------------------------------------

    @Override
    public synchronized HardState initialState() {
        return hardState;
    }

    @Override
    public synchronized List<Entry> entries(long lo, long hi, long maxSize) {
        if (lo <= dummyIndex) {
            // 已经被压缩掉了
            throw new RaftException("requested index " + lo + " is unavailable due to compaction");
        }
        long last = lastIndex();
        if (hi > last + 1) {
            throw new RaftException("entries' hi(" + hi + ") is out of bound lastindex(" + last + ")");
        }
        if (records.isEmpty()) {
            // 只剩下占位记录，没有任何可用的日志
            throw new RaftException("requested entry at index " + lo + " is unavailable");
        }
        long to = Math.min(hi, last + 1);
        List<Entry> result = new ArrayList<>();
        for (long i = lo; i < to; i++) {
            result.add(readEntry(i));
        }
        // 复制一份，避免调用方往返回的列表里追加时破坏内部状态
        return RaftUtil.limitSize(result, maxSize);
    }

    @Override
    public synchronized Entry term(long index) {
        if (index < dummyIndex) {
            // 已经被压缩掉
            throw new RaftException("requested index " + index + " is unavailable due to compaction");
        }
        if (index == dummyIndex) {
            return new Entry(dummyIndex, dummyTerm, EntryType.NORMAL, null);
        }
        Rec rec = records.get(index);
        if (rec == null) {
            // 还没有这条日志
            throw new RaftException("requested entry at index " + index + " is unavailable");
        }
        return new Entry(index, rec.term, EntryType.NORMAL, null);
    }

    @Override
    public synchronized long lastIndex() {
        if (records.isEmpty()) {
            return dummyIndex;
        }
        return records.lastKey();
    }

    @Override
    public synchronized long firstIndex() {
        return dummyIndex + 1;
    }

    @Override
    public synchronized Snapshot snapshot() {
        return snapshot;
    }

    // ------------------------------------------------------------------
    // 写接口
    // ------------------------------------------------------------------

    /**
     * 一次落盘：硬状态与一批日志条目，整批拼成一个 buffer，一次 write、一次 force。
     *
     * <p>硬状态只有在 term 或 vote 变化时才写：commit 是易失的，落盘的是它的
     * 陈旧下界，重启后由 leader 重新建立。这样「只有 commit 变化」的轮次
     * 不产生任何 IO。</p>
     *
     * <p><b>锁的粒度</b>：写盘分两段。截断与 write 在对象监视器内完成
     * （两者都是微秒级的内存操作），保证读侧永远看不到写了一半的文件；
     * {@code force(true)}（fsync，要几毫秒）在监视器之外做，读侧不用等它。
     * 实测把 fsync 也放进监视器会把 leader 的状态机线程卡住 11~12ms。</p>
     */
    @Override
    public void save(HardState st, List<Entry> entries) {
        synchronized (writeLock) {
            // 1. 算出这一轮要写什么，并把它写进文件（短暂的临界区）
            WritePlan plan;
            long t0 = System.nanoTime();
            synchronized (this) {
                plan = planWrite(st, entries);
                if (plan == null) {
                    // 没有任何东西要写，也就不需要 force
                    return;
                }
                try {
                    FileChannel ch = walFile.getChannel();
                    if (plan.truncated) {
                        // 先把被截断的记录从索引里摘掉：读侧就不会再去读已经被截掉的偏移
                        ch.truncate(plan.truncateAt);
                        records.tailMap(plan.truncateIndex, true).clear();
                    }
                    ch.position(plan.appendAt);
                    ByteBuffer buf = ByteBuffer.wrap(plan.content);
                    while (buf.hasRemaining()) {
                        ch.write(buf);
                    }
                } catch (IOException e) {
                    throw new RaftException("Append wal failed: " + walPath, e);
                }
                // 更新内存索引
                for (Rec rec : plan.newRecs) {
                    records.put(rec.index, rec);
                }
                if (plan.writeState) {
                    hardState = new HardState(plan.term, plan.vote, plan.commit);
                }
            }

            // 2. fsync：这一段不持有对象监视器，状态机线程读日志不用等它
            try {
                walFile.getChannel().force(true);
            } catch (IOException e) {
                throw new RaftException("Sync wal failed: " + walPath, e);
            }
            long elapsed = System.nanoTime() - t0;

            synchronized (this) {
                persistCount++;
                persistNanos += elapsed;
                persistEntries += plan.entries.size();
            }
        }
    }

    /**
     * 算出一轮落盘要写的内容。调用时必须持有对象监视器。
     *
     * @return null 表示这一轮没有任何东西要写
     */
    private WritePlan planWrite(HardState st, List<Entry> entries) {
        // 1. 算出要追加的条目，以及可能的截断点
        List<Entry> toAppend = Collections.emptyList();
        long truncateAt = -1;
        long truncateIndex = -1;
        boolean truncated = false;
        if (entries != null && !entries.isEmpty()) {
            long first = firstIndex();
            long last = entries.get(0).getIndex() + entries.size() - 1;
            if (last >= first) {
                List<Entry> batch = entries;
                if (first > entries.get(0).getIndex()) {
                    // 丢掉已经被压缩掉的前缀
                    batch = entries.subList((int) (first - entries.get(0).getIndex()), entries.size());
                }
                long start = batch.get(0).getIndex();
                Rec rec = records.get(start);
                if (rec != null) {
                    // 与已有日志重叠，截断掉重叠部分
                    truncateAt = rec.offset;
                    truncateIndex = start;
                    truncated = true;
                } else if (start == lastIndex() + 1) {
                    // 正好接在已有日志后面
                    truncateAt = walSize();
                } else {
                    throw new RaftException("missing log entry [last: " + lastIndex()
                            + ", append at: " + start + "]");
                }
                toAppend = batch;
            }
        }

        // 2. 硬状态要不要写：term/vote 变了才写
        long term = 0;
        long vote = 0;
        long commit = 0;
        if (hardState != null) {
            term = hardState.getTerm();
            vote = hardState.getVote();
            commit = hardState.getCommit();
        }
        boolean writeState = false;
        if (st != null && st.getTerm() != 0) {
            // term 为 0 说明还没选过主，没有需要保护的 term/vote
            boolean changed = hardState == null
                    ? (st.getTerm() != 0 || st.getVote() != 0)
                    : (st.getTerm() != hardState.getTerm() || st.getVote() != hardState.getVote());
            if (changed) {
                writeState = true;
                term = st.getTerm();
                vote = st.getVote();
                commit = st.getCommit();
            }
        }
        if (truncated && hardState != null && !writeState) {
            // 截断可能把旧的硬状态记录一起截掉了，补写一条
            writeState = true;
        }
        if (!writeState && toAppend.isEmpty()) {
            return null;
        }

        // 3. commit 不能超过日志的最后一条，否则重启后 loadState 会认为状态非法
        long targetLast = toAppend.isEmpty()
                ? lastIndex() : toAppend.get(toAppend.size() - 1).getIndex();
        if (commit > targetLast) {
            commit = targetLast;
        }

        // 4. 整批拼成一个 buffer
        int total = writeState ? HEADER_LEN + HARD_STATE_LEN : 0;
        for (Entry e : toAppend) {
            total += HEADER_LEN + ENTRY_FIXED_LEN + (e.getData() == null ? 0 : e.getData().length);
        }
        ByteWriter w = new ByteWriter(total);
        long appendAt = truncateAt >= 0 ? truncateAt : walSize();
        long pos = appendAt;
        if (writeState) {
            appendHardStateRecord(w, term, vote, commit);
            pos += HEADER_LEN + HARD_STATE_LEN;
        }
        List<Rec> newRecs = new ArrayList<>(toAppend.size());
        for (Entry e : toAppend) {
            long recLen = appendEntryRecord(w, e);
            newRecs.add(new Rec(e.getIndex(), pos, recLen, e.getTerm(), e.getType()));
            pos += recLen;
        }

        WritePlan plan = new WritePlan();
        plan.entries = toAppend;
        plan.newRecs = newRecs;
        plan.content = w.toByteArray();
        plan.appendAt = appendAt;
        plan.truncateAt = truncateAt;
        plan.truncateIndex = truncateIndex;
        plan.truncated = truncated;
        plan.writeState = writeState;
        plan.term = term;
        plan.vote = vote;
        plan.commit = commit;
        return plan;
    }

    /**
     * 一轮落盘的计划。
     */
    private static final class WritePlan {

        /** 这一轮要写的条目 */
        List<Entry> entries;

        /** 新记录 */
        List<Rec> newRecs;

        /** 要写进文件的内容 */
        byte[] content;

        /** 写入的起始偏移 */
        long appendAt;

        /** 截断位置，-1 表示不截断 */
        long truncateAt;

        /** 被截断的起始 index */
        long truncateIndex;

        /** 是否需要截断 */
        boolean truncated;

        /** 是否要写硬状态记录 */
        boolean writeState;

        /** 硬状态的 term */
        long term;

        /** 硬状态的 vote */
        long vote;

        /** 硬状态的 commit */
        long commit;
    }

    /**
     * 追加日志条目。
     */
    @Override
    public void append(List<Entry> entries) {
        save(null, entries);
    }

    /**
     * 保存当前的 HardState（term/vote/commit）。
     */
    @Override
    public void setHardState(HardState st) {
        save(st, Collections.emptyList());
    }

    /**
     * 快照点处生效的成员配置，没有保存过返回空配置
     */
    public synchronized ConfState getConfState() {
        if (snapshot != null && snapshot.getConfState() != null) {
            return snapshot.getConfState();
        }
        return emptyConfState();
    }

    /**
     * 用给定的快照整体覆盖当前存储内容。
     *
     * <p>快照之前（含快照点）的日志会被丢弃，之后要继续写日志时从
     * {@code snapshotIndex + 1} 开始。快照比当前快照还旧时抛
     * {@link RaftException}（etcd 的 {@code ErrSnapOutOfDate}）。</p>
     */
    @Override
    public void applySnapshot(Snapshot snap) {
        if (snap == null) {
            throw new RaftException("snapshot must not be null");
        }
        synchronized (writeLock) {
            synchronized (this) {
                long snapIndex = snap.getLastIncludedIndex();
                // bootstrap 时应用可能只带 ConfState，此时快照的 index/term 都是 0；
                // 这种快照只有在本存储还没有任何快照时才是合法的
                if (this.snapshotIndex != 0 && this.snapshotIndex >= snapIndex) {
                    throw new RaftException("requested index " + snapIndex
                            + " is older than the existing snapshot " + this.snapshotIndex);
                }
                try {
                    writeSnapshotFile(snap);
                } catch (IOException e) {
                    throw new RaftException("Write snapshot failed: " + snapPath, e);
                }
                this.snapshot = snap;
                this.snapshotIndex = snapIndex;
                // 快照点之前的日志都被压缩掉了，只留一个占位记录
                rewriteWal(snapIndex, snap.getLastIncludedTerm(), Collections.emptyList());
            }
        }
    }

    /**
     * 在 index 处生成一份快照，返回这份新快照。
     *
     * <p>如果自上次压缩以来发生过成员变更，调用方要把成员变更应用
     * 的结果（{@link ConfState}）传进来。</p>
     */
    @Override
    public Snapshot createSnapshot(long index, ConfState cs, byte[] data) {
        synchronized (writeLock) {
            synchronized (this) {
                if (index <= snapshotIndex) {
                    throw new RaftException("requested index " + index
                            + " is older than the existing snapshot " + snapshotIndex);
                }
                long last = lastIndex();
                if (index > last) {
                    throw new RaftException("snapshot " + index + " is out of bound lastindex(" + last + ")");
                }
                long term = termOf(index);
                ConfState confState = cs == null ? getConfState() : cs;
                Snapshot snap = new Snapshot(data == null ? new byte[0] : data,
                        new SnapshotMetadata(confState, index, term));
                try {
                    writeSnapshotFile(snap);
                } catch (IOException e) {
                    throw new RaftException("Write snapshot failed: " + snapPath, e);
                }
                this.snapshot = snap;
                this.snapshotIndex = index;
                return snap;
            }
        }
    }

    /**
     * 丢弃 index 之前（含 index）的日志条目。
     *
     * <p>调用方要保证不会压缩掉尚未 applied 的日志。index 处的条目保留为
     * 占位记录（只留 index 与 term），这样 {@link #term(long)} 仍能回答
     * 压缩点上的 term。</p>
     */
    @Override
    public void compact(long index) {
        synchronized (writeLock) {
            synchronized (this) {
                if (index <= dummyIndex) {
                    throw new RaftException("requested index " + index + " is unavailable due to compaction");
                }
                long last = lastIndex();
                if (index > last) {
                    throw new RaftException("compact " + index + " is out of bound lastindex(" + last + ")");
                }
                long term = termOf(index);
                List<Entry> remaining = new ArrayList<>();
                for (Long idx : new ArrayList<>(records.tailMap(index, false).keySet())) {
                    remaining.add(readEntry(idx));
                }
                rewriteWal(index, term, remaining);
            }
        }
    }

    @Override
    public void close() {
        synchronized (writeLock) {
            synchronized (this) {
                if (walFile != null) {
                    try {
                        walFile.close();
                    } catch (IOException e) {
                        // 关闭失败不影响数据（数据已经 force 过）
                    }
                    walFile = null;
                }
            }
        }
    }

    @Override
    public synchronized String toString() {
        return "FileRaftStorage{dir=" + dir + ", firstIndex=" + firstIndex()
                + ", lastIndex=" + lastIndex() + ", snapshotIndex=" + snapshotIndex + "}";
    }

    /**
     * 真正写盘（write + force）的次数。
     *
     * <p>只有真的要落盘时才计数：{@code save()} 遇到「term/vote 没变、
     * 也没有新条目」时不会产生任何 IO。</p>
     */
    public synchronized long getPersistCount() {
        return persistCount;
    }

    /**
     * 真正写盘的累计耗时（纳秒）
     */
    public synchronized long getPersistNanos() {
        return persistNanos;
    }

    /**
     * 真正写盘的条目总数
     */
    public synchronized long getPersistEntries() {
        return persistEntries;
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 一条日志记录在 WAL 里的位置与元信息
     */
    private static final class Rec {

        /** 条目的 index */
        private final long     index;

        /** 记录在文件里的起始偏移 */
        private final long     offset;

        /** 记录总长度 */
        private final long     length;

        /** 条目的 term */
        private final long     term;

        /** 条目的类型 */
        private final EntryType type;

        private Rec(long index, long offset, long length, long term, EntryType type) {
            this.index = index;
            this.offset = offset;
            this.length = length;
            this.term = term;
            this.type = type;
        }
    }

    private void openWal() throws IOException {
        walFile = new RandomAccessFile(walPath.toFile(), "rw");
        if (scanWal()) {
            // WAL 是空的，或者落后于快照：重建占位记录
            rewriteWal(dummyIndex, dummyTerm, Collections.emptyList());
        }
    }

    /**
     * 扫描 WAL 重建内存索引。
     *
     * <p>遇到以下情况就停止并把文件截断到该位置：长度字段越界、CRC 校验不过、
     * 记录不完整（尾部半写）、index 不连续、未知记录类型。</p>
     *
     * @return true 表示 WAL 需要重建（文件为空，或者落后于快照）
     */
    private boolean scanWal() throws IOException {
        long size = walFile.getChannel().size();
        if (size < FILE_HEADER_LEN) {
            // 空文件，或者文件头没写完
            walFile.getChannel().truncate(0);
            writeFileHeader();
            records.clear();
            hardState = null;
            dummyIndex = snapshotIndex;
            dummyTerm = snapshot != null ? snapshot.getLastIncludedTerm() : 0;
            return true;
        }
        byte[] fileHeader = new byte[FILE_HEADER_LEN];
        readAt(0, fileHeader);
        for (int i = 0; i < MAGIC.length; i++) {
            if (fileHeader[i] != MAGIC[i]) {
                throw new RaftException("Bad magic in wal file: " + walPath);
            }
        }
        int version = fileHeader[MAGIC.length] & 0xff;
        if (version != VERSION) {
            throw new RaftException("Unsupported wal version " + version + ": " + walPath);
        }

        long pos = FILE_HEADER_LEN;
        long expectIndex = -1;
        List<Rec> loaded = new ArrayList<>();
        HardState lastState = null;
        while (pos < size) {
            if (size - pos < HEADER_LEN) {
                // 头部都没写完
                break;
            }
            byte[] header = new byte[HEADER_LEN];
            readAt(pos, header);
            ByteBuffer hb = ByteBuffer.wrap(header);
            int payloadLen = hb.getInt();
            int expectCrc = hb.getInt();
            if (payloadLen <= 0 || payloadLen > size - pos - HEADER_LEN) {
                // 长度字段不合法（半写或损坏）
                break;
            }
            byte[] payload = new byte[payloadLen];
            readAt(pos + HEADER_LEN, payload);
            if (crc32(payload) != expectCrc) {
                // 校验不过
                break;
            }
            byte type = payload[0];
            if (type == TYPE_ENTRY) {
                if (payloadLen < ENTRY_FIXED_LEN) {
                    break;
                }
                ByteReader r = new ByteReader(payload);
                r.readUnsignedByte();
                long index = r.readLong();
                long term = r.readLong();
                r.readLong();          // requestId：扫描时用不上，但必须按同样的布局跳过
                EntryType entryType = EntryType.of((byte) r.readUnsignedByte());
                int dataLen = r.readInt();
                if (dataLen != payloadLen - ENTRY_FIXED_LEN) {
                    break;
                }
                if (expectIndex >= 0 && index != expectIndex) {
                    // index 不连续
                    break;
                }
                loaded.add(new Rec(index, pos, HEADER_LEN + payloadLen, term, entryType));
                expectIndex = index + 1;
            } else if (type == TYPE_HARD_STATE) {
                if (payloadLen != HARD_STATE_LEN) {
                    break;
                }
                ByteReader r = new ByteReader(payload);
                r.readUnsignedByte();
                long term = r.readLong();
                long vote = r.readLong();
                long commit = r.readLong();
                lastState = new HardState(term, vote, commit);
            } else {
                // 未知记录类型
                break;
            }
            pos += HEADER_LEN + payloadLen;
        }

        if (pos < size) {
            // 丢弃尾部半写/损坏的记录
            walFile.getChannel().truncate(pos);
            walFile.getChannel().force(true);
        }

        records.clear();
        hardState = lastState;
        if (loaded.isEmpty()) {
            // 没有占位记录，从快照点开始
            dummyIndex = snapshotIndex;
            dummyTerm = snapshot != null ? snapshot.getLastIncludedTerm() : 0;
            return true;
        }

        // 第一条条目记录是占位记录
        Rec dummy = loaded.get(0);
        dummyIndex = dummy.index;
        dummyTerm = dummy.term;
        for (int i = 1; i < loaded.size(); i++) {
            Rec rec = loaded.get(i);
            records.put(rec.index, rec);
        }

        if (dummyIndex < snapshotIndex) {
            // 日志落后于快照：整体丢弃，用快照点重建
            dummyIndex = snapshotIndex;
            dummyTerm = snapshot != null ? snapshot.getLastIncludedTerm() : 0;
            records.clear();
            return true;
        }
        return false;
    }

    /**
     * 写 WAL 的文件头
     */
    private void writeFileHeader() throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(FILE_HEADER_LEN);
        buf.put(MAGIC);
        buf.put((byte) VERSION);
        buf.flip();
        FileChannel ch = walFile.getChannel();
        ch.position(0);
        ch.write(buf);
        ch.force(true);
    }

    /**
     * 重写 WAL：文件头、一条硬状态记录（之前持久化过才有）、一条占位记录，
     * 再写入 remaining 里的条目。
     *
     * <p>整体重写走「写 .tmp → force → ATOMIC_MOVE」，中途崩溃时旧文件仍然完整。</p>
     */
    private void rewriteWal(long newDummyIndex, long newDummyTerm, List<Entry> remaining) {
        ByteWriter w = new ByteWriter(4096);
        w.writeBytes(MAGIC);
        w.writeByte(VERSION);
        if (hardState != null) {
            // 压缩之后日志里只剩占位记录，commit 不能超过它
            long commit = Math.min(hardState.getCommit(), newDummyIndex);
            appendHardStateRecord(w, hardState.getTerm(), hardState.getVote(), commit);
        }
        Entry dummy = new Entry(newDummyIndex, newDummyTerm, EntryType.NORMAL, null);
        appendEntryRecord(w, dummy);
        for (Entry e : remaining) {
            appendEntryRecord(w, e);
        }
        byte[] content = w.toByteArray();
        try {
            // 先关掉旧句柄，否则移动之后句柄还指向旧文件
            if (walFile != null) {
                walFile.close();
                walFile = null;
            }
            writeAtomic(walPath, content);
            // 重新打开 WAL 并重建内存索引
            walFile = new RandomAccessFile(walPath.toFile(), "rw");
            // 刚写出的文件一定带着占位记录，不会再需要重建
            scanWal();
        } catch (IOException e) {
            throw new RaftException("Rewrite wal failed: " + walPath, e);
        }
    }

    /**
     * 把一条硬状态记录追加到字节缓冲：length(4) + crc32(4) + type(1) + term + vote + commit
     */
    private static void appendHardStateRecord(ByteWriter w, long term, long vote, long commit) {
        byte[] payload = new ByteWriter(HARD_STATE_LEN)
                .writeByte(TYPE_HARD_STATE)
                .writeLong(term)
                .writeLong(vote)
                .writeLong(commit)
                .toByteArray();
        w.writeInt(payload.length);
        w.writeInt(crc32(payload));
        w.writeBytes(payload);
    }

    /**
     * 把一条条目记录追加到字节缓冲，返回这条记录的总长度
     */
    private static long appendEntryRecord(ByteWriter w, Entry e) {
        byte[] data = e.getData() == null ? new byte[0] : e.getData();
        byte[] payload = new ByteWriter(ENTRY_FIXED_LEN + data.length)
                .writeByte(TYPE_ENTRY)
                .writeLong(e.getIndex())
                .writeLong(e.getTerm())
                .writeLong(e.getRequestId())
                .writeByte(e.getType().getCode())
                .writeInt(data.length)
                .writeBytes(data)
                .toByteArray();
        w.writeInt(payload.length);
        w.writeInt(crc32(payload));
        w.writeBytes(payload);
        return HEADER_LEN + payload.length;
    }

    /**
     * 读取一条日志条目的完整内容（含 data）
     */
    private Entry readEntry(long index) {
        Rec rec = records.get(index);
        if (rec == null) {
            throw new RaftException("requested entry at index " + index + " is unavailable");
        }
        try {
            byte[] payload = new byte[(int) (rec.length - HEADER_LEN)];
            readAt(rec.offset + HEADER_LEN, payload);
            ByteReader r = new ByteReader(payload);
            r.readUnsignedByte();
            long idx = r.readLong();
            long term = r.readLong();
            long requestId = r.readLong();
            EntryType type = EntryType.of((byte) r.readUnsignedByte());
            int dataLen = r.readInt();
            return new Entry(idx, term, type, r.readBytes(dataLen), requestId);
        } catch (IOException e) {
            throw new RaftException("Read log entry failed: " + walPath, e);
        }
    }

    /**
     * 取某条日志的 term，index 可以是占位记录的位置
     */
    private long termOf(long index) {
        if (index == dummyIndex) {
            return dummyTerm;
        }
        Rec rec = records.get(index);
        if (rec == null) {
            throw new RaftException("requested entry at index " + index + " is unavailable");
        }
        return rec.term;
    }

    /**
     * 读取 snapshot.bin
     */
    private void loadSnapshot() throws IOException {
        if (!Files.exists(snapPath)) {
            return;
        }
        byte[] content = Files.readAllBytes(snapPath);
        if (content.length < 4) {
            throw new RaftException("Corrupted snapshot file: " + snapPath);
        }
        int bodyLen = content.length - 4;
        byte[] body = new byte[bodyLen];
        System.arraycopy(content, 0, body, 0, bodyLen);
        int expectCrc = ByteBuffer.wrap(content, bodyLen, 4).getInt();
        if (crc32(body) != expectCrc) {
            throw new RaftException("Corrupted snapshot file: " + snapPath);
        }
        ByteReader r = new ByteReader(body);
        byte[] magic = r.readBytes(SNAP_MAGIC.length);
        for (int i = 0; i < magic.length; i++) {
            if (magic[i] != SNAP_MAGIC[i]) {
                throw new RaftException("Bad magic in snapshot file: " + snapPath);
            }
        }
        int version = r.readUnsignedByte();
        if (version != VERSION) {
            throw new RaftException("Unsupported snapshot version " + version + ": " + snapPath);
        }
        long index = r.readLong();
        long term = r.readLong();
        int csLen = r.readInt();
        ConfState cs = decodeConfState(r.readBytes(csLen));
        int dataLen = r.readInt();
        byte[] data = r.readBytes(dataLen);
        this.snapshot = new Snapshot(data, new SnapshotMetadata(cs, index, term));
        this.snapshotIndex = index;
    }

    /**
     * 写 snapshot.bin
     */
    private void writeSnapshotFile(Snapshot snap) throws IOException {
        byte[] csBytes = encodeConfState(snap.getConfState());
        byte[] data = snap.getData() == null ? new byte[0] : snap.getData();
        byte[] body = new ByteWriter(csBytes.length + data.length + 64)
                .writeBytes(SNAP_MAGIC)
                .writeByte(VERSION)
                .writeLong(snap.getLastIncludedIndex())
                .writeLong(snap.getLastIncludedTerm())
                .writeInt(csBytes.length)
                .writeBytes(csBytes)
                .writeInt(data.length)
                .writeBytes(data)
                .toByteArray();
        byte[] crc = new byte[4];
        ByteBuffer.wrap(crc).putInt(crc32(body));
        byte[] content = new byte[body.length + 4];
        System.arraycopy(body, 0, content, 0, body.length);
        System.arraycopy(crc, 0, content, body.length, 4);
        writeAtomic(snapPath, content);
    }

    /**
     * 编码成员配置：四组 id 列表 + autoLeave 标志
     */
    private static byte[] encodeConfState(ConfState cs) {
        ConfState state = cs == null ? emptyConfState() : cs;
        ByteWriter w = new ByteWriter(64);
        writeIds(w, state.getVoters());
        writeIds(w, state.getLearners());
        writeIds(w, state.getVotersOutgoing());
        writeIds(w, state.getLearnersNext());
        w.writeBoolean(state.isAutoLeave());
        return w.toByteArray();
    }

    private static void writeIds(ByteWriter w, List<Long> ids) {
        List<Long> list = ids == null ? Collections.emptyList() : ids;
        w.writeInt(list.size());
        for (Long id : list) {
            w.writeLong(id == null ? 0 : id);
        }
    }

    private static ConfState decodeConfState(byte[] bytes) {
        ByteReader r = new ByteReader(bytes);
        List<Long> voters = readIds(r);
        List<Long> learners = readIds(r);
        List<Long> outgoing = readIds(r);
        List<Long> learnersNext = readIds(r);
        boolean autoLeave = r.readBoolean();
        return new ConfState(voters, learners, outgoing, learnersNext, autoLeave);
    }

    private static List<Long> readIds(ByteReader r) {
        int count = r.readInt();
        List<Long> ids = new ArrayList<>(Math.max(count, 0));
        for (int i = 0; i < count; i++) {
            ids.add(r.readLong());
        }
        return ids;
    }

    private static ConfState emptyConfState() {
        return new ConfState(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), false);
    }

    /**
     * 原子写：写 .tmp → force → 移动到位
     */
    private static void writeAtomic(Path target, byte[] content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ch.write(ByteBuffer.wrap(content));
            ch.force(true);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void readAt(long offset, byte[] dest) throws IOException {
        walFile.seek(offset);
        walFile.readFully(dest);
    }

    private long walSize() {
        try {
            return walFile.getChannel().size();
        } catch (IOException e) {
            throw new RaftException("Read wal size failed: " + walPath, e);
        }
    }

    private static int crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return (int) crc.getValue();
    }
}
