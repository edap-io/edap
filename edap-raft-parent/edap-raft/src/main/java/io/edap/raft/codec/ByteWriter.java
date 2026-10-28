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

package io.edap.raft.codec;

import java.util.Arrays;

/**
 * 可增长的字节写入器，**全部使用大端序**。
 *
 * <p>项目里的 {@code io.edap.util.ByteArrayBuilder} 写的是十进制字符串，
 * 不能用于二进制协议；FastBuf 又没有 readInt/writeInt 之类的接口，
 * 所以 raft 的线格式统一用这个类拼装。</p>
 */
public class ByteWriter {

    private byte[] buf;
    private int    size;

    public ByteWriter() {
        this(64);
    }

    public ByteWriter(int capacity) {
        this.buf = new byte[Math.max(capacity, 8)];
    }

    private void ensure(int extra) {
        if (size + extra > buf.length) {
            int newCap = Math.max(buf.length * 2, size + extra);
            buf = Arrays.copyOf(buf, newCap);
        }
    }

    public ByteWriter writeByte(int b) {
        ensure(1);
        buf[size++] = (byte) b;
        return this;
    }

    public ByteWriter writeBoolean(boolean b) {
        return writeByte(b ? 1 : 0);
    }

    public ByteWriter writeInt(int v) {
        ensure(4);
        buf[size++] = (byte) ((v >>> 24) & 0xFF);
        buf[size++] = (byte) ((v >>> 16) & 0xFF);
        buf[size++] = (byte) ((v >>> 8) & 0xFF);
        buf[size++] = (byte) (v & 0xFF);
        return this;
    }

    public ByteWriter writeLong(long v) {
        ensure(8);
        buf[size++] = (byte) ((v >>> 56) & 0xFF);
        buf[size++] = (byte) ((v >>> 48) & 0xFF);
        buf[size++] = (byte) ((v >>> 40) & 0xFF);
        buf[size++] = (byte) ((v >>> 32) & 0xFF);
        buf[size++] = (byte) ((v >>> 24) & 0xFF);
        buf[size++] = (byte) ((v >>> 16) & 0xFF);
        buf[size++] = (byte) ((v >>> 8) & 0xFF);
        buf[size++] = (byte) (v & 0xFF);
        return this;
    }

    public ByteWriter writeBytes(byte[] b) {
        if (b == null || b.length == 0) {
            return this;
        }
        return writeBytes(b, 0, b.length);
    }

    public ByteWriter writeBytes(byte[] b, int offset, int len) {
        if (b == null || len <= 0) {
            return this;
        }
        ensure(len);
        System.arraycopy(b, offset, buf, size, len);
        size += len;
        return this;
    }

    public int size() {
        return size;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buf, size);
    }
}
