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

import io.edap.raft.RaftException;

import java.util.Arrays;

/**
 * 字节读取器，与 {@link ByteWriter} 对应，**全部使用大端序**。
 */
public class ByteReader {

    private final byte[] buf;
    private       int    pos;

    public ByteReader(byte[] buf) {
        this.buf = buf == null ? new byte[0] : buf;
    }

    private void require(int n) {
        if (pos + n > buf.length) {
            throw new RaftException("Unexpected end of buffer: need " + n + " bytes, remaining " + (buf.length - pos));
        }
    }

    public int readUnsignedByte() {
        require(1);
        return buf[pos++] & 0xFF;
    }

    public boolean readBoolean() {
        return readUnsignedByte() != 0;
    }

    public int readInt() {
        require(4);
        return ((buf[pos++] & 0xFF) << 24)
                | ((buf[pos++] & 0xFF) << 16)
                | ((buf[pos++] & 0xFF) << 8)
                | (buf[pos++] & 0xFF);
    }

    public long readLong() {
        require(8);
        return ((long) (buf[pos++] & 0xFF) << 56)
                | ((long) (buf[pos++] & 0xFF) << 48)
                | ((long) (buf[pos++] & 0xFF) << 40)
                | ((long) (buf[pos++] & 0xFF) << 32)
                | ((long) (buf[pos++] & 0xFF) << 24)
                | ((long) (buf[pos++] & 0xFF) << 16)
                | ((long) (buf[pos++] & 0xFF) << 8)
                | (buf[pos++] & 0xFF);
    }

    public byte[] readBytes(int len) {
        if (len <= 0) {
            return new byte[0];
        }
        require(len);
        byte[] result = Arrays.copyOfRange(buf, pos, pos + len);
        pos += len;
        return result;
    }
}
