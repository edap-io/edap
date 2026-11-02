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

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * per-bucket 的 append-only 索引 log —— <b>元数据的唯一真源</b>。
 *
 * <p>文件形态:
 * <pre>
 *   #EDAPIDX1\n
 *   <op>\t<field>...\t<crc32hex8>\n
 *   ...
 * </pre>
 * 一条记录一行;字段全部 percent-encoding(见 {@link IndexCodec});
 * CRC32 覆盖 payload(不含 crc 字段自身)。
 *
 * <p><b>尾部损坏的处理</b>(三种形态):
 * <ol>
 *   <li>末条记录被截断(没有以 {@code \n} 结尾)⇒ 丢弃该行 + 截断文件</li>
 *   <li>某行 CRC 不匹配 ⇒ 该行<b>及其后所有行</b>不可信(位置已乱)⇒
 *       全部丢弃 + 截断文件到该行之前</li>
 *   <li>文件头(magic)不对 ⇒ 整个 log 作废 ⇒ 抛 {@link CorruptException},
 *       由调用方改名归档并回落走「老桶迁移」路径</li>
 * </ol>
 *
 * <p><b>不 fsync</b>:与改造前(每对象一个 {@code .meta} sidecar)保持一致 ——
 * 只保证进程崩溃一致,不保证机器掉电一致。
 *
 * <p>线程安全:<b>append 必须由调用方串行化</b>(见 {@link BucketIndex} 的
 * per-bucket 锁)。{@link #replay()} 只在加载期调用。
 */
public final class IndexLog implements Closeable {

    /** 索引 log 整体作废(magic 不对 / 结构无法解析)。 */
    public static class CorruptException extends IOException {
        public CorruptException(String message) {
            super(message);
        }
    }

    private static final int READ_CHUNK = 64 * 1024;

    private final Path path;
    private final FileChannel channel;
    /** 逻辑写入位置(= 文件有效长度)。 */
    private long writePos;
    private final boolean fsync;

    private IndexLog(Path path, FileChannel channel, boolean fsync) {
        this.path = path;
        this.channel = channel;
        this.fsync = fsync;
    }

    /**
     * 打开(或创建)索引 log —— 文件不存在时创建并写入 magic 行。
     *
     * @param fsync true = 每次 append 后 force 到盘(保证机器掉电一致,但慢)
     * @throws CorruptException 已存在的文件首行不是 magic
     */
    public static IndexLog open(Path path, boolean fsync) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        boolean fresh = !Files.exists(path);
        FileChannel ch = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        IndexLog log = new IndexLog(path, ch, fsync);
        if (fresh) {
            log.writeMagic();
        } else {
            // 已有文件:写入位置 = 文件长度(否则不调 replay() 直接 append 会
            // 从位置 0 开始覆盖,把已有记录写坏)
            log.writePos = ch.size();
        }
        return log;
    }

    private void writeMagic() throws IOException {
        byte[] bytes = (IndexCodec.MAGIC + "\n").getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        long pos = 0;
        while (buf.hasRemaining()) {
            int n = channel.write(buf, pos);
            if (n <= 0) throw new IOException("short write on index magic");
            pos += n;
        }
        writePos = pos;
    }

    /**
     * 回放全部记录 —— 同时修复尾部损坏(截断半条 / CRC 坏行)。
     *
     * @throws CorruptException magic 不对(整个 log 作废)
     */
    public List<IndexRecord> replay() throws IOException {
        long fileSize = channel.size();
        if (fileSize == 0) {
            // 上次创建写到一半就挂了 ⇒ 补上 magic
            writeMagic();
            return Collections.emptyList();
        }

        List<IndexRecord> records = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.allocate(READ_CHUNK);
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        long pos = 0;             // 当前处理到的字节的绝对偏移
        long goodEnd = 0;         // 最后一条可信记录的结束偏移(exclusive)
        boolean magicChecked = false;
        boolean broken = false;

        channel.position(0);
        while (pos < fileSize) {
            buf.clear();
            int n = channel.read(buf);
            if (n <= 0) break;
            buf.flip();
            for (int i = 0; i < n; i++) {
                byte b = buf.get(i);
                if (b != '\n') {
                    pending.write(b);
                    pos++;
                    continue;
                }
                String line = new String(pending.toByteArray(), StandardCharsets.UTF_8);
                pending.reset();
                long lineEnd = ++pos;
                if (broken) continue;              // 已损坏 ⇒ 后面的行全部跳过
                if (!magicChecked) {
                    magicChecked = true;
                    if (!IndexCodec.MAGIC.equals(line)) {
                        throw new CorruptException("index magic mismatch: " + line);
                    }
                    goodEnd = lineEnd;
                    continue;
                }
                try {
                    records.add(IndexCodec.decodeLine(line));
                    goodEnd = lineEnd;
                } catch (IOException e) {
                    broken = true;                  // 这条及其后全部不可信
                }
            }
        }
        if (!magicChecked) {
            throw new CorruptException("index file has no magic line");
        }
        if (goodEnd < fileSize) {
            // 尾部半条 或 CRC 坏行 ⇒ 截回最后一个可信记录之后
            channel.truncate(goodEnd);
        }
        writePos = goodEnd;
        return records;
    }

    /**
     * 追加一条记录 —— <b>调用方负责串行化</b>。
     * 写的是完整的一行(含 LF),中途崩溃最多留下半条 ⇒ 由 {@link #replay()} 丢弃。
     */
    public void append(IndexRecord rec) throws IOException {
        byte[] bytes = (IndexCodec.encodeLine(rec) + "\n").getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        long pos = writePos;
        while (buf.hasRemaining()) {
            int n = channel.write(buf, pos);
            if (n <= 0) throw new IOException("short write on index log");
            pos += n;
        }
        writePos = pos;
        if (fsync) {
            channel.force(false);
        }
    }

    /** 当前 log 的有效字节数(用于启动期压缩判定)。 */
    public long size() {
        return writePos;
    }

    public Path path() {
        return path;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
