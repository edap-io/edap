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

package io.edap.raft.transport;

import io.edap.raft.RaftException;
import io.edap.raft.protocol.RaftCodec;
import io.edap.raft.protocol.Message;
import io.edap.raft.protocol.MessageType;

/**
 * 增量式的 raft 帧解析器。
 *
 * <p>网络上的字节流是任意切分的，一帧可能跨多次读到达。这里先把收到的字节累积起来，
 * 凑齐一整帧再交给 {@link RaftCodec} 解码。</p>
 *
 * <p>帧格式：{@code [4B length][1B type][payload]}，length 是 payload 的字节数，全部大端。</p>
 *
 * <p>不是线程安全的，每个连接用一个实例。</p>
 */
public class RaftFrameReader {

    /** 帧头长度：length(4) + type(1) */
    private static final int HEADER_SIZE = 5;

    private byte[] buf;
    /** 已经累积的有效字节数 */
    private int    size;
    /** 已经消费到的位置 */
    private int    pos;

    public RaftFrameReader() {
        this(1024);
    }

    public RaftFrameReader(int capacity) {
        this.buf = new byte[Math.max(capacity, 64)];
    }

    /**
     * 把新读到的字节追加到累积缓冲里
     */
    public void append(byte[] data, int offset, int len) {
        if (data == null || len <= 0) {
            return;
        }
        ensureCapacity(len);
        System.arraycopy(data, offset, buf, size, len);
        size += len;
    }

    /**
     * 取出一条完整的消息，没有凑齐一帧时返回 null
     *
     * @throws RaftException 帧格式非法
     */
    public Message next() {
        int remain = size - pos;
        if (remain < HEADER_SIZE) {
            return null;
        }
        int length = ((buf[pos] & 0xFF) << 24)
                | ((buf[pos + 1] & 0xFF) << 16)
                | ((buf[pos + 2] & 0xFF) << 8)
                | (buf[pos + 3] & 0xFF);
        if (length < 0) {
            throw new RaftException("Illegal raft frame length: " + length);
        }
        if (remain - HEADER_SIZE < length) {
            return null;
        }
        MessageType type = MessageType.of((byte) (buf[pos + 4] & 0xFF));
        byte[] body = new byte[length];
        System.arraycopy(buf, pos + HEADER_SIZE, body, 0, length);
        // 先推进消费位置，解码失败时也不会重复解析同一帧
        pos += HEADER_SIZE + length;
        compact();
        return RaftCodec.decodePayload(body, type);
    }

    /**
     * 当前累积缓冲里还没解析的字节数
     */
    public int remain() {
        return size - pos;
    }

    private void ensureCapacity(int extra) {
        if (size + extra <= buf.length) {
            return;
        }
        compact();
        if (size + extra <= buf.length) {
            return;
        }
        int capacity = buf.length;
        while (capacity < size + extra) {
            capacity <<= 1;
        }
        byte[] newBuf = new byte[capacity];
        System.arraycopy(buf, 0, newBuf, 0, size);
        buf = newBuf;
    }

    /**
     * 把还没消费的字节挪到缓冲区头部，空了就直接复位
     */
    private void compact() {
        if (pos == size) {
            pos = 0;
            size = 0;
            return;
        }
        if (pos == 0) {
            return;
        }
        System.arraycopy(buf, pos, buf, 0, size - pos);
        size -= pos;
        pos = 0;
    }
}
