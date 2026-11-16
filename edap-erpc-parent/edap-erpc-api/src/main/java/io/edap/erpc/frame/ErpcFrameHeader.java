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
 * eRPC 帧头 —— 32 字节，固定长度。
 *
 * <pre>{@code
 *   偏移  长度  字段        说明
 *     0     4    magic       'E','R','P','C'
 *     4     1    version     协议版本 = 1
 *     5     1    type        1=REQUEST 2=RESPONSE 3=ERROR 4=PING 5=PONG
 *     6     1    flags       bit0 = 压缩预留（当前恒 0）
 *     7     1    codec       序列化器 id（0=protobuf 1=json）
 *     8     4    length      载荷字节数
 *    12     4    methodId    方法号
 *    16     4    serviceId   服务号
 *    20     4    requestId   请求号
 *    24     8    traceId     链路号
 *   --- 32 字节 ---
 *    32     N    payload     业务 DTO 按 codec 编码的字节
 * }</pre>
 *
 * <p><b>全部字段大端（网络字节序）</b>，与 gRPC 帧头一致。</p>
 *
 * <p><b>为什么路由信息放帧头而不是载荷</b>：服务端<b>不解析载荷就能路由</b>
 * （methodId 在帧头），也<b>不解析载荷就能回填响应头</b>（requestId 直接抄）。
 * 载荷里只剩业务 DTO ⇒ <b>只编一次、只拷一次</b>。</p>
 */
public final class ErpcFrameHeader {

    /** 帧头长度。 */
    public static final int HEADER_LENGTH = 32;

    /** magic：'E','R','P','C'。 */
    public static final int MAGIC = 0x45525043;

    /** 当前协议版本。 */
    public static final byte VERSION = 1;

    public static final int OFF_MAGIC     = 0;
    public static final int OFF_VERSION   = 4;
    public static final int OFF_TYPE      = 5;
    public static final int OFF_FLAGS     = 6;
    public static final int OFF_CODEC     = 7;
    public static final int OFF_LENGTH    = 8;
    public static final int OFF_METHOD_ID = 12;
    public static final int OFF_SERVICE_ID = 16;
    public static final int OFF_REQUEST_ID = 20;
    public static final int OFF_TRACE_ID   = 24;

    private int    type;
    private int    flags;
    private int    codec;
    private int    length;
    private int    methodId;
    private int    serviceId;
    private int    requestId;
    private long   traceId;

    public int getType() {
        return type;
    }

    public ErpcFrameHeader setType(int type) {
        this.type = type;
        return this;
    }

    public ErpcFrameHeader setType(ErpcFrameType type) {
        this.type = type == null ? 0 : type.getValue();
        return this;
    }

    public int getFlags() {
        return flags;
    }

    public ErpcFrameHeader setFlags(int flags) {
        this.flags = flags;
        return this;
    }

    public int getCodec() {
        return codec;
    }

    public ErpcFrameHeader setCodec(int codec) {
        this.codec = codec;
        return this;
    }

    public int getLength() {
        return length;
    }

    public ErpcFrameHeader setLength(int length) {
        this.length = length;
        return this;
    }

    public int getMethodId() {
        return methodId;
    }

    public ErpcFrameHeader setMethodId(int methodId) {
        this.methodId = methodId;
        return this;
    }

    public int getServiceId() {
        return serviceId;
    }

    public ErpcFrameHeader setServiceId(int serviceId) {
        this.serviceId = serviceId;
        return this;
    }

    public int getRequestId() {
        return requestId;
    }

    public ErpcFrameHeader setRequestId(int requestId) {
        this.requestId = requestId;
        return this;
    }

    public long getTraceId() {
        return traceId;
    }

    public ErpcFrameHeader setTraceId(long traceId) {
        this.traceId = traceId;
        return this;
    }

    // ------------------------------------------------------------------
    // 序列化
    // ------------------------------------------------------------------

    /**
     * 把帧头写到 {@code buf} 的 {@code off} 处（32 字节）。
     */
    public void writeTo(byte[] buf, int off) {
        buf[off]     = (byte) ((MAGIC >>> 24) & 0xff);
        buf[off + 1] = (byte) ((MAGIC >>> 16) & 0xff);
        buf[off + 2] = (byte) ((MAGIC >>> 8) & 0xff);
        buf[off + 3] = (byte) (MAGIC & 0xff);
        buf[off + 4] = VERSION;
        buf[off + 5] = (byte) type;
        buf[off + 6] = (byte) flags;
        buf[off + 7] = (byte) codec;
        writeInt(buf, off + OFF_LENGTH, length);
        writeInt(buf, off + OFF_METHOD_ID, methodId);
        writeInt(buf, off + OFF_SERVICE_ID, serviceId);
        writeInt(buf, off + OFF_REQUEST_ID, requestId);
        writeLong(buf, off + OFF_TRACE_ID, traceId);
    }

    /**
     * 把帧头写到堆外缓冲 {@code buf} 的绝对地址 {@code pos} 处（32 字节）。
     *
     * <p>服务端出站走这条路：帧头与载荷都直接写进出站累积缓冲，中间不落地 byte[]。</p>
     */
    public void writeTo(FastBuf buf, long pos) {
        buf.writeNotCheck(pos,     (byte) ((MAGIC >>> 24) & 0xff));
        buf.writeNotCheck(pos + 1, (byte) ((MAGIC >>> 16) & 0xff));
        buf.writeNotCheck(pos + 2, (byte) ((MAGIC >>> 8) & 0xff));
        buf.writeNotCheck(pos + 3, (byte) (MAGIC & 0xff));
        buf.writeNotCheck(pos + 4, VERSION);
        buf.writeNotCheck(pos + 5, (byte) type);
        buf.writeNotCheck(pos + 6, (byte) flags);
        buf.writeNotCheck(pos + 7, (byte) codec);
        writeInt(buf, pos + OFF_LENGTH, length);
        writeInt(buf, pos + OFF_METHOD_ID, methodId);
        writeInt(buf, pos + OFF_SERVICE_ID, serviceId);
        writeInt(buf, pos + OFF_REQUEST_ID, requestId);
        writeLong(buf, pos + OFF_TRACE_ID, traceId);
    }

    // ------------------------------------------------------------------
    // 反序列化
    // ------------------------------------------------------------------

    /**
     * 从 {@code buf} 的 {@code off} 处解析帧头。
     */
    public static ErpcFrameHeader readFrom(byte[] buf, int off) {
        ErpcFrameHeader h = new ErpcFrameHeader();
        h.type      = buf[off + OFF_TYPE] & 0xff;
        h.flags     = buf[off + OFF_FLAGS] & 0xff;
        h.codec     = buf[off + OFF_CODEC] & 0xff;
        h.length    = readInt(buf, off + OFF_LENGTH);
        h.methodId  = readInt(buf, off + OFF_METHOD_ID);
        h.serviceId = readInt(buf, off + OFF_SERVICE_ID);
        h.requestId = readInt(buf, off + OFF_REQUEST_ID);
        h.traceId   = readLong(buf, off + OFF_TRACE_ID);
        return h;
    }

    /**
     * 从堆外缓冲 {@code buf} 的绝对地址 {@code pos} 处解析帧头。
     */
    public static ErpcFrameHeader readFrom(FastBuf buf, long pos) {
        ErpcFrameHeader h = new ErpcFrameHeader();
        h.type      = buf.get(pos + OFF_TYPE) & 0xff;
        h.flags     = buf.get(pos + OFF_FLAGS) & 0xff;
        h.codec     = buf.get(pos + OFF_CODEC) & 0xff;
        h.length    = readInt(buf, pos + OFF_LENGTH);
        h.methodId  = readInt(buf, pos + OFF_METHOD_ID);
        h.serviceId = readInt(buf, pos + OFF_SERVICE_ID);
        h.requestId = readInt(buf, pos + OFF_REQUEST_ID);
        h.traceId   = readLong(buf, pos + OFF_TRACE_ID);
        return h;
    }

    /**
     * 只读 length 字段（分帧时用它判断「帧是否收全了」，不必解析整个帧头）。
     */
    public static int readLength(byte[] buf, int off) {
        return readInt(buf, off + OFF_LENGTH);
    }

    /**
     * 校验 magic。
     *
     * @return true 表示这段字节确实是 eRPC 帧
     */
    public static boolean checkMagic(byte[] buf, int off) {
        return ((buf[off] & 0xff) == ((MAGIC >>> 24) & 0xff))
            && ((buf[off + 1] & 0xff) == ((MAGIC >>> 16) & 0xff))
            && ((buf[off + 2] & 0xff) == ((MAGIC >>> 8) & 0xff))
            && ((buf[off + 3] & 0xff) == (MAGIC & 0xff));
    }

    // ------------------------------------------------------------------
    // 大端读写
    // ------------------------------------------------------------------

    private static void writeInt(byte[] buf, int off, int v) {
        buf[off]     = (byte) ((v >>> 24) & 0xff);
        buf[off + 1] = (byte) ((v >>> 16) & 0xff);
        buf[off + 2] = (byte) ((v >>> 8) & 0xff);
        buf[off + 3] = (byte) (v & 0xff);
    }

    private static void writeLong(byte[] buf, int off, long v) {
        buf[off]     = (byte) ((v >>> 56) & 0xff);
        buf[off + 1] = (byte) ((v >>> 48) & 0xff);
        buf[off + 2] = (byte) ((v >>> 40) & 0xff);
        buf[off + 3] = (byte) ((v >>> 32) & 0xff);
        buf[off + 4] = (byte) ((v >>> 24) & 0xff);
        buf[off + 5] = (byte) ((v >>> 16) & 0xff);
        buf[off + 6] = (byte) ((v >>> 8) & 0xff);
        buf[off + 7] = (byte) (v & 0xff);
    }

    private static int readInt(byte[] buf, int off) {
        return ((buf[off] & 0xff) << 24)
             | ((buf[off + 1] & 0xff) << 16)
             | ((buf[off + 2] & 0xff) << 8)
             | (buf[off + 3] & 0xff);
    }

    private static long readLong(byte[] buf, int off) {
        return ((long) (buf[off] & 0xff) << 56)
             | ((long) (buf[off + 1] & 0xff) << 48)
             | ((long) (buf[off + 2] & 0xff) << 40)
             | ((long) (buf[off + 3] & 0xff) << 32)
             | ((long) (buf[off + 4] & 0xff) << 24)
             | ((long) (buf[off + 5] & 0xff) << 16)
             | ((long) (buf[off + 6] & 0xff) << 8)
             | (buf[off + 7] & 0xff);
    }

    private static void writeInt(FastBuf buf, long pos, int v) {
        buf.writeNotCheck(pos,     (byte) ((v >>> 24) & 0xff));
        buf.writeNotCheck(pos + 1, (byte) ((v >>> 16) & 0xff));
        buf.writeNotCheck(pos + 2, (byte) ((v >>> 8) & 0xff));
        buf.writeNotCheck(pos + 3, (byte) (v & 0xff));
    }

    private static void writeLong(FastBuf buf, long pos, long v) {
        buf.writeNotCheck(pos,     (byte) ((v >>> 56) & 0xff));
        buf.writeNotCheck(pos + 1, (byte) ((v >>> 48) & 0xff));
        buf.writeNotCheck(pos + 2, (byte) ((v >>> 40) & 0xff));
        buf.writeNotCheck(pos + 3, (byte) ((v >>> 32) & 0xff));
        buf.writeNotCheck(pos + 4, (byte) ((v >>> 24) & 0xff));
        buf.writeNotCheck(pos + 5, (byte) ((v >>> 16) & 0xff));
        buf.writeNotCheck(pos + 6, (byte) ((v >>> 8) & 0xff));
        buf.writeNotCheck(pos + 7, (byte) (v & 0xff));
    }

    private static int readInt(FastBuf buf, long pos) {
        return ((buf.get(pos) & 0xff) << 24)
             | ((buf.get(pos + 1) & 0xff) << 16)
             | ((buf.get(pos + 2) & 0xff) << 8)
             | (buf.get(pos + 3) & 0xff);
    }

    private static long readLong(FastBuf buf, long pos) {
        return ((long) (buf.get(pos) & 0xff) << 56)
             | ((long) (buf.get(pos + 1) & 0xff) << 48)
             | ((long) (buf.get(pos + 2) & 0xff) << 40)
             | ((long) (buf.get(pos + 3) & 0xff) << 32)
             | ((long) (buf.get(pos + 4) & 0xff) << 24)
             | ((long) (buf.get(pos + 5) & 0xff) << 16)
             | ((long) (buf.get(pos + 6) & 0xff) << 8)
             | (buf.get(pos + 7) & 0xff);
    }
}
