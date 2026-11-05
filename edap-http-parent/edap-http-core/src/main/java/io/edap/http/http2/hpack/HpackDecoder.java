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

package io.edap.http.http2.hpack;

import io.edap.http.http2.Http2ErrorCode;
import io.edap.http.http2.Http2Exception;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * HPACK 解码器（RFC 7541 §6）。
 *
 * <p>支持全部五种头字段表示（RFC 7541 §6.2）：</p>
 * <ul>
 *   <li>Indexed Header Field（{@code 1xxxxxxx}）</li>
 *   <li>Literal with Incremental Indexing（{@code 01xxxxxx}）</li>
 *   <li>Dynamic Table Size Update（{@code 001xxxxx}）</li>
 *   <li>Literal without Indexing（{@code 0000xxxx}）</li>
 *   <li>Literal Never Indexed（{@code 0001xxxx}）</li>
 *
 * <p>字符串字面量支持 Huffman 编码（{@code H} 标志位）。</p>
 *
 * <p>解码错误一律抛 {@link Http2Exception}，错误码为 {@code COMPRESSION_ERROR}
 * （RFC 7541 §4.1 —— HPACK 解码失败是连接级错误）。</p>
 */
public class HpackDecoder {

    private final HpackDynamicTable dynamicTable = new HpackDynamicTable();

    /**
     * 本端通告的 SETTINGS_HEADER_TABLE_SIZE，也是允许对端设置的动态表上限。
     */
    private int maxTableSize = HpackDynamicTable.DEFAULT_MAX_SIZE;

    /**
     * 设置本端通告的 SETTINGS_HEADER_TABLE_SIZE。
     *
     * <p>这同时把动态表当前上限设为该值（RFC 7541 §4.2：动态表初始大小就是
     * SETTINGS_HEADER_TABLE_SIZE），之后只能被对端的 Dynamic Table Size Update 调整，
     * 且新值不得超过本值。</p>
     *
     * <p>必须在开始解码前调用。</p>
     */
    public void setMaxTableSize(int maxTableSize) {
        this.maxTableSize = maxTableSize;
        dynamicTable.setMaxSize(maxTableSize);
    }

    public HpackDynamicTable getDynamicTable() {
        return dynamicTable;
    }

    /**
     * 解码一个 header block。
     *
     * @param block  header block 字节
     * @param offset 起始偏移
     * @param length 长度
     * @return 头字段列表（保持出现顺序）
     * @throws Http2Exception HPACK 编码非法（COMPRESSION_ERROR）
     */
    public List<HpackHeader> decode(byte[] block, int offset, int length) {
        if (length < 0 || offset < 0 || offset + length > block.length) {
            throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR,
                    "HPACK header block 越界: offset=" + offset + ", length=" + length);
        }
        List<HpackHeader> result = new ArrayList<>();
        int[] cursor = new int[]{offset};
        int end = offset + length;
        while (cursor[0] < end) {
            int b = block[cursor[0]] & 0xff;
            if ((b & 0x80) != 0) {
                // Indexed Header Field
                int index = decodeInt(block, cursor, end, 7);
                if (index == 0) {
                    throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR,
                            "Indexed Header Field 的索引不能为 0");
                }
                result.add(resolve(index));
            } else if ((b & 0xc0) == 0x40) {
                // Literal Header Field with Incremental Indexing
                String name = readName(block, cursor, end, 6);
                String value = readString(block, cursor, end);
                dynamicTable.add(name, value);
                result.add(new HpackHeader(name, value));
            } else if ((b & 0xe0) == 0x20) {
                // Dynamic Table Size Update
                int size = decodeInt(block, cursor, end, 5);
                if (size > maxTableSize) {
                    throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR,
                            "Dynamic Table Size Update 超过上限: " + size + " > " + maxTableSize);
                }
                dynamicTable.setMaxSize(size);
            } else {
                // Literal without Indexing (0000) / Literal Never Indexed (0001)
                String name = readName(block, cursor, end, 4);
                String value = readString(block, cursor, end);
                result.add(new HpackHeader(name, value));
            }
        }
        return result;
    }

    public List<HpackHeader> decode(byte[] block) {
        return decode(block, 0, block.length);
    }

    /**
     * 按 HPACK 索引取头字段：1..61 静态表，62 起动态表。
     */
    private HpackHeader resolve(int index) {
        if (index <= HpackStaticTable.SIZE) {
            return new HpackHeader(HpackStaticTable.getName(index), HpackStaticTable.getValue(index));
        }
        // 动态表的 HPACK 索引从 62 开始（62 = 最新加入的），即 d = index - 61
        HpackDynamicTable.Entry e = dynamicTable.get(index - HpackStaticTable.SIZE);
        if (e == null) {
            throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR,
                    "HPACK 索引越界: " + index);
        }
        return new HpackHeader(e.getName(), e.getValue());
    }

    /**
     * 读头字段名：索引为 0 时是字面量，否则从表里取。
     */
    private String readName(byte[] block, int[] cursor, int end, int prefixBits) {
        int index = decodeInt(block, cursor, end, prefixBits);
        if (index == 0) {
            return readString(block, cursor, end);
        }
        return resolve(index).getName();
    }

    /**
     * 读字符串字面量（RFC 7541 §5.2）。
     */
    private String readString(byte[] block, int[] cursor, int end) {
        int pos = cursor[0];
        if (pos >= end) {
            throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "HPACK 字符串字面量被截断");
        }
        boolean huffman = (block[pos] & 0x80) != 0;
        int len = decodeInt(block, cursor, end, 7);
        if (len < 0 || cursor[0] + len > end) {
            throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR,
                    "HPACK 字符串字面量被截断: 需要 " + len + " 字节");
        }
        byte[] raw = new byte[len];
        System.arraycopy(block, cursor[0], raw, 0, len);
        cursor[0] += len;
        if (huffman) {
            raw = HuffmanTable.decode(raw, 0, len);
        }
        // HPACK 字符串是 octet 序列，用 ISO-8859-1 做字节↔字符一一映射，保证字节级无损
        return new String(raw, StandardCharsets.ISO_8859_1);
    }

    /**
     * 解码 HPACK 整数（RFC 7541 §5.1）。
     *
     * @param prefixBits 前缀位数
     * @return 整数值；{@code cursor} 被推进到整数之后
     */
    static int decodeInt(byte[] data, int[] cursor, int end, int prefixBits) {
        int pos = cursor[0];
        if (pos >= end) {
            throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "HPACK 整数被截断");
        }
        int mask = (1 << prefixBits) - 1;
        int b = data[pos++] & 0xff;
        long value = b & mask;
        if (value < mask) {
            cursor[0] = pos;
            return (int) value;
        }
        int shift = 0;
        while (true) {
            if (pos >= end) {
                throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "HPACK 整数被截断");
            }
            b = data[pos++] & 0xff;
            value += ((long) (b & 0x7f)) << shift;
            if (value > Integer.MAX_VALUE) {
                throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "HPACK 整数溢出: " + value);
            }
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
            if (shift > 28) {
                throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "HPACK 整数过长");
            }
        }
        cursor[0] = pos;
        return (int) value;
    }
}
