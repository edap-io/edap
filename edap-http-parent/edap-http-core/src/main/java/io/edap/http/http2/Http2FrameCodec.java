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

package io.edap.http.http2;

import io.edap.buffer.FastBuf;

/**
 * HTTP/2 帧编解码（RFC 9113 §4）。
 *
 * <p><b>★ 帧写出必须是原子的。</b>{@code HttpResponse.write0} 在 socket 满时会中途
 * {@code writeToChannel(buf)}，把一帧劈成两半 —— 对端会因此报 FRAME_SIZE_ERROR。
 * 所以本类 {@link #writeFrame} 的语义是：<b>剩余空间不足时整帧不写</b>（返回 false），
 * 由调用方先把缓冲刷出去再重试，绝不产生半帧。</p>
 *
 * <p>{@code FastBuf.write(byte[], off, len)} 是<b>静默截断</b>的（写不满不会报错），
 * 所以每次写入后都要核对返回长度。</p>
 */
public final class Http2FrameCodec {

    /** 帧头长度：3 字节 Length + 1 字节 Type + 1 字节 Flags + 4 字节 Stream Identifier */
    public static final int FRAME_HEADER_LENGTH = 9;

    /** 帧头中 Length 字段的偏移 */
    private static final int OFFSET_LENGTH   = 0;
    private static final int OFFSET_TYPE     = 3;
    private static final int OFFSET_FLAGS    = 4;
    private static final int OFFSET_STREAMID = 5;

    private Http2FrameCodec() {
    }

    /**
     * 从 buf 的当前读位置解析帧头。数据不足 9 字节时返回 {@code null}（等下次读）。
     *
     * <p>注意 {@link FastBuf#get(long)} 不做边界检查，所以必须先判 {@code remain()}。</p>
     */
    public static Http2FrameHeader readHeader(FastBuf buf) {
        if (buf.remain() < FRAME_HEADER_LENGTH) {
            return null;
        }
        long pos = buf.rpos();
        int length = ((buf.get(pos + OFFSET_LENGTH) & 0xff) << 16)
                | ((buf.get(pos + OFFSET_LENGTH + 1) & 0xff) << 8)
                | (buf.get(pos + OFFSET_LENGTH + 2) & 0xff);
        int type     = buf.get(pos + OFFSET_TYPE) & 0xff;
        int flags    = buf.get(pos + OFFSET_FLAGS) & 0xff;
        int streamId = ((buf.get(pos + OFFSET_STREAMID) & 0x7f) << 24)
                | ((buf.get(pos + OFFSET_STREAMID + 1) & 0xff) << 16)
                | ((buf.get(pos + OFFSET_STREAMID + 2) & 0xff) << 8)
                | (buf.get(pos + OFFSET_STREAMID + 3) & 0xff);
        return new Http2FrameHeader(length, type, flags, streamId);
    }

    /**
     * 从 byte[] 的指定位置解析帧头。
     *
     * <p>HTTP/2 的入站数据必须自建累积缓冲（{@code DisruptorReadDispatcher} 每次 read 前
     * 会 {@code buf.reset()}，跨 read 的字节不保留），所以帧头解析需要一个 byte[] 版本。</p>
     *
     * @param data 帧数据
     * @param pos  帧头起始位置
     */
    public static Http2FrameHeader readHeader(byte[] data, int pos) {
        int length = ((data[pos + OFFSET_LENGTH] & 0xff) << 16)
                | ((data[pos + OFFSET_LENGTH + 1] & 0xff) << 8)
                | (data[pos + OFFSET_LENGTH + 2] & 0xff);
        int type     = data[pos + OFFSET_TYPE] & 0xff;
        int flags    = data[pos + OFFSET_FLAGS] & 0xff;
        int streamId = ((data[pos + OFFSET_STREAMID] & 0x7f) << 24)
                | ((data[pos + OFFSET_STREAMID + 1] & 0xff) << 16)
                | ((data[pos + OFFSET_STREAMID + 2] & 0xff) << 8)
                | (data[pos + OFFSET_STREAMID + 3] & 0xff);
        return new Http2FrameHeader(length, type, flags, streamId);
    }

    /**
     * 跳过帧头，把读位置推进到载荷起始处。
     */
    public static void skipHeader(FastBuf buf) {
        buf.rpos(buf.rpos() + FRAME_HEADER_LENGTH);
    }

    /**
     * 原子地写出一整帧。
     *
     * @param out     目标缓冲
     * @param type    帧类型
     * @param flags   标志位
     * @param streamId 流标识符
     * @param payload 载荷
     * @param offset  载荷起始偏移
     * @param length  载荷长度
     * @return true 表示整帧已写入；false 表示剩余空间不足，<b>此时一个字节都没写</b>
     */
    public static boolean writeFrame(FastBuf out, int type, int flags, int streamId,
                                     byte[] payload, int offset, int length) {
        if (length > 0xffffff) {
            throw new Http2Exception(Http2ErrorCode.INTERNAL_ERROR, "帧载荷超过 24 位长度上限: " + length);
        }
        int need = FRAME_HEADER_LENGTH + length;
        if (out.writeRemain() < need) {
            // 空间不足 —— 整帧不写。调用方负责先刷缓冲再重试。
            return false;
        }
        out.write((byte) ((length >>> 16) & 0xff));
        out.write((byte) ((length >>> 8) & 0xff));
        out.write((byte) (length & 0xff));
        out.write((byte) (type & 0xff));
        out.write((byte) (flags & 0xff));
        out.write((byte) ((streamId >>> 24) & 0x7f));
        out.write((byte) ((streamId >>> 16) & 0xff));
        out.write((byte) ((streamId >>> 8) & 0xff));
        out.write((byte) (streamId & 0xff));
        if (length > 0) {
            int written = out.write(payload, offset, length);
            if (written != length) {
                // writeRemain 已经保证空间足够，写不满说明 FastBuf 记账异常 —— 不能继续，
                // 否则对端拿到的是半帧。
                throw new Http2Exception(Http2ErrorCode.INTERNAL_ERROR,
                        "帧载荷写入被截断，期望 " + length + " 实际 " + written);
            }
        }
        return true;
    }

    public static boolean writeFrame(FastBuf out, int type, int flags, int streamId, byte[] payload) {
        return writeFrame(out, type, flags, streamId, payload, 0, payload.length);
    }

    /**
     * 构造一个只含 4 字节数据的帧载荷（RST_STREAM / WINDOW_UPDATE / PING ACK）。
     */
    public static byte[] intPayload(int value) {
        byte[] p = new byte[4];
        p[0] = (byte) (value >>> 24);
        p[1] = (byte) (value >>> 16);
        p[2] = (byte) (value >>> 8);
        p[3] = (byte) value;
        return p;
    }
}
