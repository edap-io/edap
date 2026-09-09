/*
 * Copyright 2020 The edap Project
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

package io.edap.buffer;

import io.edap.pool.BasePoolEntry;
import io.edap.util.ByteData;
import io.edap.util.UnsafeUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Paths;

import static java.nio.channels.FileChannel.MapMode.READ_WRITE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

public class FastBuf extends BasePoolEntry {

    /**
     * 由于java的NIO操作必须使用ByteBuffer，而且为了减少网络数据的内存copy的次数，所以
     * 该ByteBuffer为DirectByteBuffer来作为基本的封装数据
     */
    private final ByteBuffer buf;
    /**
     * 该Buf内存的开始地址
     */
    private final long address;
    /**
     * 该Buf内存的结束地址，由于下标是内存地址，所以容量和下标换算的问题使用结束地址更方便
     */
    private final long endAddress;
    /**
     * Buf的类型
     */
    private final BufType type;
    /**
     * Buf当前写操作位置
     */
    private       long writePos;
    /**
     * Buf当前读操作位置
     */
    private       long readPos;

    @Override
    public void reset() {
        this.writePos = address;
        this.readPos = address;
    }

    public long limit() {
        return writePos;
    }

    public byte get(long pos) {
        return (byte)UnsafeUtil.readByte(pos);
    }

    public byte get() {
        return UnsafeUtil.UNSAFE.getByte(readPos++);
    }

    public int get(byte[] data) {
        int len = data.length;
        if (readPos + len > writePos) {
            len = (int)(writePos - readPos);
        }
        UnsafeUtil.copyMemory(readPos, data, 0, len);
        readPos += len;
        return len;
    }

    public int get(long pos, byte[] bs) {
        int len = bs.length;
        if (pos + len > writePos) {
            len = (int)(writePos - pos);
        }
        UnsafeUtil.copyMemory(pos, bs, 0, len);
        return len;
    }

    public int get2(long pos, int len) {
//        long _pos = pos;
//        long _writePos = writePos;
//        if (len < _writePos - _pos) {
//            long l = _writePos - _pos;
//        }
        byte[] data = new byte[len];
        //UnsafeUtil.copyMemory(pos, data, 0, len);
        return len;
    }

    public int get(long pos, byte[] bs, int start, int len) {
        if (pos + len > writePos) {
            len = (int)(writePos - pos);
        }
        UnsafeUtil.copyMemory(pos, bs, start, len);
        return len;
    }

    public int get(byte[] data, int len) {
        long pos = readPos;
        if (pos + len > writePos) {
            len = (int)(writePos - pos);
        }
        UnsafeUtil.copyMemory(pos, data, 0, len);
        readPos += len;
        return len;
    }

    public int get(byte[] data, int start, int len) {
        long pos = readPos;
        if (pos + len > writePos) {
            len = (int)(writePos - pos);
        }
        UnsafeUtil.copyMemory(pos, data, start, len);
        readPos += len;
        return len;
    }

    public int writeRemain() {
        return (int)(endAddress - writePos);
    }

    public void rewind() {
        readPos = address;
    }

    /**
     * FastBuf的类型，分为内存以及文件映射两种
     */
    public enum BufType {
        /**
         * 内存方式
         */
        MEMORY,
        /**
         * 文件映射的方式
         */
        MAPPED_FILE;
    }

    public FastBuf(int capacity) {
        this.buf     = ByteBuffer.allocateDirect(capacity);
        this.address = UnsafeUtil.address(buf);
        this.type    = BufType.MEMORY;
        this.endAddress = address + buf.capacity();
        this.writePos = address;
        this.readPos  = address;
    }

    public FastBuf(int capacity, String path) throws IOException {
        FileChannel fc = FileChannel.open(Paths.get(path), READ, WRITE);

        this.buf     = fc.map(READ_WRITE, 0, capacity);
        this.type    = BufType.MAPPED_FILE;
        this.address = UnsafeUtil.address(buf);
        this.endAddress = address + buf.capacity();
        this.writePos = address;
        this.readPos  = address;
    }

    public FastBuf(ByteBuffer buf) {
        if (!buf.isDirect()) {
            throw new RuntimeException("buf not direct ByteBuffer");
        }
        this.buf = buf;
        this.address = UnsafeUtil.address(buf);
        this.type    = BufType.MEMORY;
        this.endAddress = address + buf.capacity();
        this.writePos = address;
        this.readPos  = address;
    }

    public int write(byte b1) {
        if (writeRemain() > 0) {
            UnsafeUtil.writeByte(writePos++, b1);
            return 1;
        }
        return 0;
    }

    public void writeNotCheck(byte b1) {
        UnsafeUtil.writeByte(writePos++, b1);
    }

    public void writeNotCheck(long address, byte b1) {
        UnsafeUtil.writeByte(address, b1);
    }

    public int write(byte[] bs) {
        return write(bs, 0, bs.length);
    }

    public int write(ByteData byteData) {
        return write(byteData.getBytes(), byteData.getOffset(), byteData.getLength());
    }

    public int write(byte[] bs, int offset, int len) {
        int remain = (int)(endAddress - writePos);
        if (len > remain) {
            len = remain;
        }
        UnsafeUtil.copyMemory(bs, offset, writePos, len);
        writePos += len;
        return len;
    }

    public int getInt(long address) {
        return UnsafeUtil.getInt(address);
    }

    public long getLong(long address) {
        return UnsafeUtil.getLong(address);
    }

    public int writeTo(byte[] bs, int offseet, int len) {
        UnsafeUtil.copyMemory(readPos, bs, offseet, len);
        return len;
    }

    public void writeByte(byte b) {

    }

    public ByteBuffer byteBuffer() {
        return this.buf;
    }

    public long address() {
        return this.address;
    }

    public int remain() {
        return (int)(writePos - readPos);
    }

    public FastBuf wpos(long wpos) {
        writePos = wpos;
        return this;
    }

    public long rpos() {
        return readPos;
    }

    public FastBuf rpos(long rpos) {
        readPos = rpos;
        return this;
    }

    public long wpos() {
        return writePos;
    }

    public void syncToByteBuffer() {

    }

    public void clear() {
        clear(false);
    }

    /**
     * 清空buf中的数据，将所有读写指针回归到原始状态，以备下一次使用
     * @param isSyncByteBuffer 是否同步byteBuffer的状态
     */
    public void clear(boolean isSyncByteBuffer) {
        this.writePos = address;
        this.readPos = address;
//        if (isSyncByteBuffer) {
//            buf.clear();
//        }
    }

    /**
     * @return FastBuf 底层类型（堆外直接 buffer 或文件映射 buffer）。
     */
    public BufType getType() {
        return type;
    }

    /**
     * 在 FastBuf 底层 ByteBuffer 上切一个 positioned、limited、只读切片视图。
     * 调用方必须在 FastBuf 池化回收前用完该视图。
     *
     * @param startAddress 起始绝对地址（必须落在 [address, endAddress) 区间内）
     * @param length 切片长度
     * @return 只读 ByteBuffer 切片
     */
    public ByteBuffer byteBufferSlice(long startAddress, int length) {
        int pos = (int) (startAddress - address);
        if (pos < 0 || length < 0 || (long) pos + length > buf.capacity()) {
            throw new IndexOutOfBoundsException(
                    "startAddress=" + startAddress + ", length=" + length
                            + ", buf.capacity()=" + buf.capacity());
        }
        return ((ByteBuffer) buf.duplicate().position(pos).limit(pos + length)).slice();
    }

    /**
     * 从 FastBuf 的指定地址区间零拷贝（堆外实现）写出到 {@link WritableByteChannel}。
     *
     * <p>对 MEMORY 类型（{@link ByteBuffer#allocateDirect}）的 FastBuf，调用
     * {@code ch.write(byteBuffer)} 走 NIO 直接通道传输；对 MAPPED_FILE 类型
     * 同样零拷贝（page cache 直接写出）。
     *
     * @param ch 目标通道
     * @param startAddress 起始绝对地址（必须落在 [address, writePos) 区间内）
     * @param length 写出长度
     * @return 实际写入字节数
     */
    public long transferTo(WritableByteChannel ch, long startAddress, int length) throws IOException {
        ByteBuffer slice = byteBufferSlice(startAddress, length);
        long written = 0;
        while (slice.hasRemaining()) {
            int n = ch.write(slice);
            if (n <= 0) {
                break;
            }
            written += n;
        }
        return written;
    }

    /**
     * 把 FastBuf 当前 readPos 起的字节读入 ByteBuffer 的剩余空间，返回实际读取字节数。
     * 同时推进 readPos。ByteBuffer 必须有足够的 remaining 空间。
     *
     * @param dst 目标 ByteBuffer（direct 或 heap 均可）
     * @return 实际读取字节数（0 表示 FastBuf 无剩余）
     */
    public int get(ByteBuffer dst) {
        int remaining = (int) (writePos - readPos);
        int want = dst.remaining();
        if (remaining <= 0 || want <= 0) {
            return 0;
        }
        int n = Math.min(remaining, want);
        if (dst.isDirect()) {
            long dstAddr = UnsafeUtil.address(dst) + dst.position();
            UnsafeUtil.copyMemory(readPos, dstAddr, n);
        } else {
            // heap ByteBuffer: 临时 byte[] 中转
            byte[] tmp = new byte[n];
            UnsafeUtil.copyMemory(readPos, tmp, 0, n);
            dst.put(tmp);
        }
        dst.position(dst.position() + n);
        readPos += n;
        return n;
    }
}
