/*
 * Copyright 2023 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.edap.erpc.codec;

import io.edap.erpc.ErpcProtocolException;
import io.edap.rpc.RpcMetadata;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * eRPC 的 <b>RPC 上下文段</b>编解码 —— 帧内容区里与业务 DTO <b>分开</b>的那一段。
 *
 * <p><b>帧内容区的两种形态</b>（由帧头 {@code type} 字节的 bit7 决定，
 * 见 {@link io.edap.erpc.frame.ErpcFrameType#HAS_CONTEXT}）：</p>
 * <pre>{@code
 *   不带上下文：[ 业务 DTO ]
 *   带上下文  ：[ RPC 上下文段 ][ 业务 DTO ]
 * }</pre>
 *
 * <p><b>上下文段的格式</b>（自描述、大端、<b>不依赖业务序列化器</b>）：</p>
 * <pre>{@code
 *   metadataLen(4B)   ← 上下文块体的字节数（不含本 4 字节）
 *   entryCount(4B)
 *   [ keyLen(2B) key(UTF-8) valLen(4B) val(UTF-8) ]*
 * }</pre>
 *
 * <p>★ <b>为什么不复用业务序列化器</b>：上下文是<b>框架级</b>的东西（txId、灰度标签），
 * 业务 DTO 用什么编码器跟它无关 —— 用独立的固定格式，任何 codec 下都能解析，
 * 也方便别的语言实现。</p>
 *
 * <p>★ <b>值一律字符串</b>（UTF-8）：与 gRPC 的 metadata 规矩一致。</p>
 */
public final class ErpcMetadataCodec {

    /** 上下文段开头的长度字段（4 字节）。 */
    public static final int LENGTH_PREFIX = 4;

    private ErpcMetadataCodec() {
    }

    /**
     * 编码成<b>完整的上下文段</b>（含开头的 4 字节长度）。
     *
     * <p>客户端把它整段写进帧内容区的最前面 ⇒ 对端一读就知道上下文段有多长。</p>
     *
     * @param metadata 元数据；{@code null} 或空 ⇒ 只返回一个 4 字节的长度 0
     * @return 上下文段的字节
     */
    public static byte[] encode(RpcMetadata metadata) {
        Map<String, String> entries = metadata == null
            ? java.util.Collections.emptyMap() : metadata.asMap();
        if (entries.isEmpty()) {
            byte[] out = new byte[LENGTH_PREFIX];
            writeInt(out, 0, 0);
            return out;
        }
        // ★ 两遍法：先算块体大小（一次 UTF-8 编码），再写。
        //   编码结果缓存在临时数组里，避免第二遍重复编码。
        byte[][] keys = new byte[entries.size()][];
        byte[][] values = new byte[entries.size()][];
        int i = 0;
        int blockLen = 4;                                   // entryCount
        for (Map.Entry<String, String> e : entries.entrySet()) {
            byte[] k = e.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] v = e.getValue().getBytes(StandardCharsets.UTF_8);
            keys[i] = k;
            values[i] = v;
            blockLen += 2 + k.length + 4 + v.length;
            i++;
        }
        byte[] out = new byte[LENGTH_PREFIX + blockLen];
        writeInt(out, 0, blockLen);
        writeInt(out, LENGTH_PREFIX, entries.size());
        int p = LENGTH_PREFIX + 4;
        for (int j = 0; j < keys.length; j++) {
            byte[] k = keys[j];
            byte[] v = values[j];
            writeShort(out, p, k.length);
            p += 2;
            System.arraycopy(k, 0, out, p, k.length);
            p += k.length;
            writeInt(out, p, v.length);
            p += 4;
            System.arraycopy(v, 0, out, p, v.length);
            p += v.length;
        }
        return out;
    }

    /**
     * 读上下文块的字节数（{@link #encode} 写进去的那个长度字段）。
     *
     * @param buf 缓冲
     * @param off 长度字段的偏移
     * @return 块体的字节数
     */
    public static int blockLength(byte[] buf, int off) {
        if (buf == null || off < 0 || off + LENGTH_PREFIX > buf.length) {
            throw new ErpcProtocolException("RPC 上下文段的长度字段不完整");
        }
        return readInt(buf, off);
    }

    /**
     * 解析上下文块体。
     *
     * @param buf 缓冲
     * @param off 块体起始偏移
     * @param len 块体字节数（{@link #blockLength} 给的那个）
     * @return 解析出来的元数据
     * @throws ErpcProtocolException 结构不对（长度越界、键非法……）
     */
    public static RpcMetadata decode(byte[] buf, int off, int len) {
        if (len < 4) {
            throw new ErpcProtocolException("RPC 上下文段太短: " + len + " 字节");
        }
        if (off < 0 || off + len > buf.length) {
            throw new ErpcProtocolException("RPC 上下文段越界: off=" + off + ", len=" + len
                + ", 缓冲=" + buf.length);
        }
        int count = readInt(buf, off);
        int p = off + 4;
        int end = off + len;
        RpcMetadata metadata = RpcMetadata.create();
        for (int i = 0; i < count; i++) {
            if (p + 2 > end) {
                throw new ErpcProtocolException("RPC 上下文段的键长度越界");
            }
            int keyLen = readShort(buf, p);
            p += 2;
            // ★ 用减法比较而不是 p + keyLen > end：线上的长度字段是攻击面，
            //   p + keyLen 可能溢出成负数 ⇒ 越界检查被绕过 ⇒ 后面 new String 抛
            //   StringIndexOutOfBoundsException（不是协议错误，对端看不懂）。
            if (keyLen < 0 || keyLen > end - p) {
                throw new ErpcProtocolException("RPC 上下文段的键越界: 长度 " + keyLen);
            }
            String key = new String(buf, p, keyLen, StandardCharsets.UTF_8);
            p += keyLen;
            if (p + 4 > end) {
                throw new ErpcProtocolException("RPC 上下文段的值长度越界");
            }
            int valLen = readInt(buf, p);
            p += 4;
            if (valLen < 0 || valLen > end - p) {
                throw new ErpcProtocolException("RPC 上下文段的值越界: 长度 " + valLen);
            }
            String value = new String(buf, p, valLen, StandardCharsets.UTF_8);
            p += valLen;
            try {
                metadata.put(key, value);
            } catch (IllegalArgumentException e) {
                // 线上的键不合法 ⇒ 协议错误（不是业务错误）⇒ 让对端知道连错了
                throw new ErpcProtocolException("RPC 上下文段的键非法: " + key, e);
            }
        }
        return metadata;
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

    private static void writeShort(byte[] buf, int off, int v) {
        buf[off]     = (byte) ((v >>> 8) & 0xff);
        buf[off + 1] = (byte) (v & 0xff);
    }

    private static int readInt(byte[] buf, int off) {
        return ((buf[off] & 0xff) << 24)
             | ((buf[off + 1] & 0xff) << 16)
             | ((buf[off + 2] & 0xff) << 8)
             | (buf[off + 3] & 0xff);
    }

    private static int readShort(byte[] buf, int off) {
        return ((buf[off] & 0xff) << 8) | (buf[off + 1] & 0xff);
    }
}
