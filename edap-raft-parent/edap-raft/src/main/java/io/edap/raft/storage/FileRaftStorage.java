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
 * 文件实现的 {@link Storage}，把 raft 的日志与元状态持久化到磁盘。
 *
 * <p>目录里有三个文件：</p>
 * <pre>
 *   meta.bin      magic("EDAP") + version(1) + term(8) + vote(8) + commit(8) + crc32(4)
 *   log.bin       若干条记录：length(4) + crc32(4) + payload
 *                 payload = index(8) + term(8) + type(1) + dataLen(4) + data
 *   snapshot.bin  magic("EDAP") + version(1) + lastIncludedIndex(8) + lastIncludedTerm(8)
 *                 + confStateLen(4) + confState + dataLen(4) + data + crc32(4)
 * </pre>
 * <p>全部是大端序。{@code crc32} 覆盖它前面紧邻的所有字节。</p>
 *
 * <p>{@code log.bin} 的第一条记录是「占位记录」（dummy）：它只带 index 与 term，
 * 不带数据，作用与 {@link MemoryStorage} 里的 dummy entry 一致 ——
 * 标记被压缩掉的区间的终点，使得 {@link #term(long)} 仍能回答压缩点上的 term。
 * 真正的日志条目从它后面开始，index 连续递增。</p>
 *
 * <p>写入策略：</p>
 * <ul>
 *     <li>{@code meta.bin} 与 {@code snapshot.bin} 整体重写，走
 *         「写 .tmp → {@code force(true)} → {@code ATOMIC_MOVE}」，避免半写</li>
 *     <li>{@code log.bin} 追加写；冲突截断时先截断再追加，每条记录带 CRC32，
 *         恢复时第一条校验不过的记录及其后全部丢弃并把文件截断（处理尾部半写）</li>
 * </ul>
 *
 * <p>所有方法都加锁：读侧在 Raft 核心线程上调用，写侧可能来自应用线程。</p>
 */
public class FileRaftStorage implements Storage, AutoCloseable {

    /** 文件魔数 */
    private static final byte[] MAGIC = {'E', 'D', 'A', 'P'};

    /** 格式版本 */
    private static final int VERSION = 1;

    /** 日志记录头部长度：length(4) + crc32(4) */
    private static final int HEADER_LEN = 8;

    /** 日志记录定长部分长度：index(8) + term(8) + type(1) + dataLen(4) */
    private static final int PAYLOAD_FIXED_LEN = 21;

    /** 数据目录 */
    private final Path dir;

    /** 硬状态文件 */
    private final Path metaPath;

    /** 日志文件 */
    private final Path logPath;

    /** 快照文件 */
    private final Path snapPath;

    /** 日志文件句柄 */
    private RandomAccessFile logFile;

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

    /** 日志条目索引：index → 记录在文件里的位置与元信息 */
    private final TreeMap<Long, Rec> records = new TreeMap<>();

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
        this.metaPath = this.dir.resolve("meta.bin");
        this.logPath = this.dir.resolve("log.bin");
        this.snapPath = this.dir.resolve("snapshot.bin");
        try {
            Files.createDirectories(this.dir);
            // 与 MemoryStorage 一致：没有快照时给一个空的快照
            this.snapshot = new Snapshot(new byte[0], new SnapshotMetadata(emptyConfState(), 0, 0));
            loadMeta();
            loadSnapshot();
            openLog();
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
     * 保存当前的 HardState（term/vote/commit），应用层负责在回复投票请求前落盘
     */
    public synchronized void setHardState(HardState st) {
        this.hardState = st;
        byte[] body = new ByteWriter(64)
                .writeBytes(MAGIC)
                .writeByte(VERSION)
                .writeLong(st == null ? 0 : st.getTerm())
                .writeLong(st == null ? 0 : st.getVote())
                .writeLong(st == null ? 0 : st.getCommit())
                .toByteArray();
        byte[] crc = new byte[4];
        ByteBuffer.wrap(crc).putInt(crc32(body));
        byte[] content = new byte[body.length + 4];
        System.arraycopy(body, 0, content, 0, body.length);
        System.arraycopy(crc, 0, content, body.length, 4);
        try {
            writeAtomic(metaPath, content);
        } catch (IOException e) {
            throw new RaftException("Write meta failed: " + metaPath, e);
        }
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
        try {
            writeSnapshotFile(snap);
        } catch (IOException e) {
            throw new RaftException("Write snapshot failed: " + snapPath, e);
        }
        this.snapshot = snap;
        this.snapshotIndex = snapIndex;
        // 快照点之前的日志都被压缩掉了，只留一个占位记录
        rewriteLog(snapIndex, snap.getLastIncludedTerm(), Collections.emptyList());
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

    /**
     * 丢弃 index 之前（含 index）的日志条目。
     *
     * <p>调用方要保证不会压缩掉尚未 applied 的日志。index 处的条目保留为
     * 占位记录（只留 index 与 term），这样 {@link #term(long)} 仍能回答
     * 压缩点上的 term。</p>
     */
    public synchronized void compact(long index) {
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
        rewriteLog(index, term, remaining);
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
        long start = toAppend.get(0).getIndex();
        long truncateAt;
        Rec rec = records.get(start);
        if (rec != null) {
            // 与已有日志重叠，截断掉重叠部分
            truncateAt = rec.offset;
            records.tailMap(start, true).clear();
        } else if (start == lastIndex() + 1) {
            // 正好接在已有日志后面
            truncateAt = logSize();
        } else {
            throw new RaftException("missing log entry [last: " + lastIndex()
                    + ", append at: " + start + "]");
        }

        try {
            logFile.getChannel().truncate(truncateAt);
            logFile.getChannel().position(truncateAt);
            for (Entry e : toAppend) {
                byte[] payload = encodeEntry(e);
                ByteBuffer buf = ByteBuffer.allocate(HEADER_LEN + payload.length);
                buf.putInt(payload.length);
                buf.putInt(crc32(payload));
                buf.put(payload);
                buf.flip();
                logFile.getChannel().write(buf);
                Rec newRec = new Rec(e.getIndex(), truncateAt, HEADER_LEN + payload.length, e.getTerm(), e.getType());
                records.put(e.getIndex(), newRec);
                truncateAt += newRec.length;
            }
            logFile.getChannel().force(true);
        } catch (IOException e) {
            throw new RaftException("Append log failed: " + logPath, e);
        }
    }

    @Override
    public synchronized void close() {
        if (logFile != null) {
            try {
                logFile.close();
            } catch (IOException e) {
                // 关闭失败不影响数据（数据已经 force 过）
            }
            logFile = null;
        }
    }

    @Override
    public synchronized String toString() {
        return "FileRaftStorage{dir=" + dir + ", firstIndex=" + firstIndex()
                + ", lastIndex=" + lastIndex() + ", snapshotIndex=" + snapshotIndex + "}";
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 一条日志记录在文件里的位置与元信息
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

    private void openLog() throws IOException {
        logFile = new RandomAccessFile(logPath.toFile(), "rw");
        if (scanLog()) {
            // 日志文件是空的，或者落后于快照：重建占位记录
            rewriteLog(dummyIndex, dummyTerm, Collections.emptyList());
        }
    }

    /**
     * 扫描 log.bin 重建内存索引。
     *
     * <p>遇到以下情况就停止并把文件截断到该位置：长度字段越界、CRC 校验不过、
     * 记录不完整（尾部半写）、index 不连续。</p>
     *
     * @return true 表示日志文件需要重建（文件为空，或者落后于快照）
     */
    private boolean scanLog() throws IOException {
        long size = logFile.getChannel().size();
        long pos = 0;
        long expectIndex = -1;
        List<Rec> loaded = new ArrayList<>();

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
            if (payloadLen < PAYLOAD_FIXED_LEN || payloadLen > size - pos - HEADER_LEN) {
                // 长度字段不合法（半写或损坏）
                break;
            }
            byte[] payload = new byte[payloadLen];
            readAt(pos + HEADER_LEN, payload);
            if (crc32(payload) != expectCrc) {
                // 校验不过
                break;
            }
            ByteReader r = new ByteReader(payload);
            long index = r.readLong();
            long term = r.readLong();
            EntryType type = EntryType.of((byte) r.readUnsignedByte());
            int dataLen = r.readInt();
            if (dataLen != payloadLen - PAYLOAD_FIXED_LEN) {
                break;
            }
            if (expectIndex >= 0 && index != expectIndex) {
                // index 不连续
                break;
            }
            loaded.add(new Rec(index, pos, HEADER_LEN + payloadLen, term, type));
            expectIndex = index + 1;
            pos += HEADER_LEN + payloadLen;
        }

        if (pos < size) {
            // 丢弃尾部半写/损坏的记录
            logFile.getChannel().truncate(pos);
            logFile.getChannel().force(true);
        }

        records.clear();
        if (loaded.isEmpty()) {
            // 没有占位记录，从快照点开始
            dummyIndex = snapshotIndex;
            dummyTerm = snapshot != null ? snapshot.getLastIncludedTerm() : 0;
            return true;
        }

        // 第一条记录是占位记录
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
     * 重写 log.bin：写入一条占位记录，再写入 remaining 里的条目。
     *
     * <p>整体重写走「写 .tmp → force → ATOMIC_MOVE」，中途崩溃时旧文件仍然完整。</p>
     */
    private void rewriteLog(long newDummyIndex, long newDummyTerm, List<Entry> remaining) {
        ByteWriter w = new ByteWriter(4096);
        Entry dummy = new Entry(newDummyIndex, newDummyTerm, EntryType.NORMAL, null);
        appendRecord(w, dummy);
        for (Entry e : remaining) {
            appendRecord(w, e);
        }
        byte[] content = w.toByteArray();
        try {
            // 先关掉旧句柄，否则移动之后句柄还指向旧文件
            if (logFile != null) {
                logFile.close();
                logFile = null;
            }
            writeAtomic(logPath, content);
            // 重新打开日志文件并重建内存索引
            logFile = new RandomAccessFile(logPath.toFile(), "rw");
            // 刚写出的文件一定带着占位记录，不会再需要重建
            scanLog();
        } catch (IOException e) {
            throw new RaftException("Rewrite log failed: " + logPath, e);
        }
    }

    /**
     * 把一条条目编码成一条记录（length + crc32 + payload）追加到字节缓冲
     */
    private static void appendRecord(ByteWriter w, Entry e) {
        byte[] payload = encodeEntry(e);
        w.writeInt(payload.length);
        w.writeInt(crc32(payload));
        w.writeBytes(payload);
    }

    /**
     * 编码日志条目：index(8) + term(8) + type(1) + dataLen(4) + data
     */
    private static byte[] encodeEntry(Entry e) {
        byte[] data = e.getData() == null ? new byte[0] : e.getData();
        return new ByteWriter(PAYLOAD_FIXED_LEN + data.length)
                .writeLong(e.getIndex())
                .writeLong(e.getTerm())
                .writeByte(e.getType().getCode())
                .writeInt(data.length)
                .writeBytes(data)
                .toByteArray();
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
            long idx = r.readLong();
            long term = r.readLong();
            EntryType type = EntryType.of((byte) r.readUnsignedByte());
            int dataLen = r.readInt();
            return new Entry(idx, term, type, r.readBytes(dataLen));
        } catch (IOException e) {
            throw new RaftException("Read log entry failed: " + logPath, e);
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
     * 读取 meta.bin
     */
    private void loadMeta() throws IOException {
        if (!Files.exists(metaPath)) {
            return;
        }
        byte[] content = Files.readAllBytes(metaPath);
        if (content.length < 4 + 1 + 8 + 8 + 8 + 4) {
            throw new RaftException("Corrupted meta file: " + metaPath);
        }
        int bodyLen = content.length - 4;
        byte[] body = new byte[bodyLen];
        System.arraycopy(content, 0, body, 0, bodyLen);
        int expectCrc = ByteBuffer.wrap(content, bodyLen, 4).getInt();
        if (crc32(body) != expectCrc) {
            throw new RaftException("Corrupted meta file: " + metaPath);
        }
        ByteReader r = new ByteReader(body);
        byte[] magic = r.readBytes(4);
        for (int i = 0; i < magic.length; i++) {
            if (magic[i] != MAGIC[i]) {
                throw new RaftException("Bad magic in meta file: " + metaPath);
            }
        }
        int version = r.readUnsignedByte();
        if (version != VERSION) {
            throw new RaftException("Unsupported meta version " + version + ": " + metaPath);
        }
        long term = r.readLong();
        long vote = r.readLong();
        long commit = r.readLong();
        this.hardState = new HardState(term, vote, commit);
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
        byte[] magic = r.readBytes(4);
        for (int i = 0; i < magic.length; i++) {
            if (magic[i] != MAGIC[i]) {
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
                .writeBytes(MAGIC)
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
        logFile.seek(offset);
        logFile.readFully(dest);
    }

    private long logSize() {
        try {
            return logFile.getChannel().size();
        } catch (IOException e) {
            throw new RaftException("Read log size failed: " + logPath, e);
        }
    }

    private static int crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return (int) crc.getValue();
    }
}
