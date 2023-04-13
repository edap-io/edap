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
import io.edap.util.UnsafeUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
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
        clear(true);
    }

    public long limit() {
        return writePos;
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

    public int write(byte[] bs, int offset, int len) {
        int remain = (int)(endAddress - writePos);
        if (len > remain) {
            len = remain;
        }
        UnsafeUtil.copyMemory(bs, offset, writePos, len);
        writePos += len;
        return len;
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
        if (isSyncByteBuffer) {
            buf.clear();
        }
    }
}
