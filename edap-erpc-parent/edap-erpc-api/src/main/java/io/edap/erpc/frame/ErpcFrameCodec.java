/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.erpc.frame;

import io.edap.buffer.FastBuf;

/**
 * eRPC 增量分帧器 —— 把一段段读到的字节拼成一个个完整的帧。
 *
 * <p><b>载荷不拷贝</b>：切帧时只记录 {@code offset/length}，真正的字节拷贝推迟到
 * 解码业务 DTO 那一步（否则每帧会多一次全量拷贝）。</p>
 *
 * <p>用法（服务端 {@code ErpcServerSession.decode} 与客户端 {@code ErpcChannel}
 * 的读线程共用同一套语义）：</p>
 * <pre>{@code
 *   codec.append(buf, off, len);
 *   while (codec.hasFrame()) {
 *       ErpcFrameHeader h = codec.header();
 *       byte[] payload = codec.payload();
 *       codec.consume();
 *       ...
 *   }
 * }</pre>
 *
 * <p><b>为什么要自己攒</b>：底层读分发器（{@code DisruptorReadDispatcher}）每次 read
 * 前会 {@code buf.reset()}，跨 read 的字节不会保留 ⇒ 半包必须由上层自己攒。</p>
 *
 * <p>★ <b>非线程安全</b>：每个连接一个实例，只在读线程（或写线程）里用。</p>
 */
public final class ErpcFrameCodec {

    /** 初始容量：够放 4 个最小帧（32 字节头）。 */
    private static final int INITIAL_CAPACITY = 128;

    private byte[] buf;
    /** 累积缓冲里有效字节数。 */
    private int limit;
    /** 已消费的字节数（{@code limit - pos} = 还没解析的字节数）。 */
    private int pos;

    public ErpcFrameCodec() {
        this(INITIAL_CAPACITY);
    }

    public ErpcFrameCodec(int initialCapacity) {
        this.buf = new byte[Math.max(initialCapacity, INITIAL_CAPACITY)];
    }

    /**
     * 追加一段读到的字节。
     */
    public void append(byte[] data, int off, int len) {
        if (len <= 0) {
            return;
        }
        ensureCapacity(limit + len - pos);
        System.arraycopy(data, off, buf, limit, len);
        limit += len;
    }

    /**
     * 追加一段从<b>堆外</b>读缓冲里读到的字节（服务端路径）。
     *
     * <p>★ 直接从堆外拷进累积缓冲（<b>一次拷贝</b>）：服务端的读缓冲是堆外的
     * {@link FastBuf}，先拷成 byte[] 再进累积缓冲就是两次拷贝。</p>
     *
     * <p>会推进入参的读指针（{@code rpos}）⇒ 同一个读缓冲反复调用只有第一次有效，
     * 正好符合「一次 read 累积一次」的语义。</p>
     */
    public void append(FastBuf src) {
        int len = src.remain();
        if (len <= 0) {
            return;
        }
        ensureCapacity(limit + len - pos);
        src.get(buf, limit, len);
        limit += len;
    }

    /**
     * 缓冲里是否已经攒够一个完整的<b>帧头</b>。
     *
     * <p>★ magic / 版本校验必须在帧头一到就做 —— 不能等到整帧收全：
     * 非 eRPC 流量的 {@code length} 字段多半是个巨大的数 ⇒ 整帧永远收不全
     * ⇒ 等整帧才校验的话，这条连接会一直挂着不报错。</p>
     */
    public boolean hasHeader() {
        return limit - pos >= ErpcFrameHeader.HEADER_LENGTH;
    }

    /**
     * 缓冲里是否已经攒够一个完整的帧。
     *
     * <p>帧头的 {@code length} 字段是载荷字节数 ⇒ 完整帧的长度是
     * {@code 32 + length}。</p>
     */
    public boolean hasFrame() {
        int avail = limit - pos;
        if (avail < ErpcFrameHeader.HEADER_LENGTH) {
            return false;
        }
        int length = ErpcFrameHeader.readLength(buf, pos);
        if (length < 0) {
            // 长度字段是 unsigned int，这里落到负数说明是异常帧
            return false;
        }
        return avail - ErpcFrameHeader.HEADER_LENGTH >= length;
    }

    /**
     * 解析当前帧的帧头（不推进 {@code pos}）。
     */
    public ErpcFrameHeader header() {
        return ErpcFrameHeader.readFrom(buf, pos);
    }

    /**
     * 当前帧载荷在累积缓冲里的起始下标。
     */
    public int payloadOffset() {
        return pos + ErpcFrameHeader.HEADER_LENGTH;
    }

    /**
     * 当前帧载荷的字节数。
     */
    public int payloadLength() {
        return ErpcFrameHeader.readLength(buf, pos);
    }

    /**
     * 取出当前帧的载荷（**拷贝**一份）。
     */
    public byte[] payload() {
        int len = payloadLength();
        byte[] out = new byte[len];
        System.arraycopy(buf, payloadOffset(), out, 0, len);
        return out;
    }

    /**
     * 当前帧的 magic 是否正确 —— 不对就不是 eRPC 流量。
     */
    public boolean checkMagic() {
        return ErpcFrameHeader.checkMagic(buf, pos);
    }

    /**
     * 当前帧的协议版本。
     */
    public int version() {
        return buf[pos + ErpcFrameHeader.OFF_VERSION] & 0xff;
    }

    /**
     * 消费掉当前帧（帧头 + 载荷），把缓冲前移。
     */
    public void consume() {
        pos += ErpcFrameHeader.HEADER_LENGTH + payloadLength();
        compact();
    }

    /**
     * 还没解析的字节数。
     */
    public int buffered() {
        return limit - pos;
    }

    /**
     * 清空缓冲（连接关闭时调用）。
     */
    public void reset() {
        pos = 0;
        limit = 0;
    }

    /**
     * 前移已消费的字节，避免缓冲无限增长。
     */
    private void compact() {
        if (pos == 0) {
            return;
        }
        if (pos == limit) {
            pos = 0;
            limit = 0;
            return;
        }
        // 消费掉一半以上才做一次搬移，避免每帧都 memmove
        if (pos < limit - pos) {
            return;
        }
        System.arraycopy(buf, pos, buf, 0, limit - pos);
        limit -= pos;
        pos = 0;
    }

    private void ensureCapacity(int need) {
        if (need <= buf.length) {
            return;
        }
        // 先前移，再按 2 倍几何增长
        int live = limit - pos;
        int newCap = buf.length;
        while (newCap < need) {
            newCap = newCap << 1;
        }
        byte[] nb = new byte[newCap];
        System.arraycopy(buf, pos, nb, 0, live);
        buf = nb;
        limit = live;
        pos = 0;
    }

    @Override
    public String toString() {
        return "ErpcFrameCodec{buffered=" + buffered() + ", capacity=" + buf.length + "}";
    }
}
