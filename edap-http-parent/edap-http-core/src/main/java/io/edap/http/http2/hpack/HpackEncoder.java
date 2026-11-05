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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * HPACK 编码器（RFC 7541 §6）。
 *
 * <p><b>v1 策略</b>：</p>
 * <ul>
 *   <li>name + value 与静态表某项完全一致 ⇒ 用 Indexed Header Field（静态表是全连接共享的
 *       不可变表，用它不会污染任何动态表）；</li>
 *   <li>其余一律用 <b>Literal Header Field without Indexing</b>（{@code 0000xxxx}）
 *       —— 不污染对端的动态表，对端解码时也不用维护动态表状态；</li>
 *   <li><b>不做 Huffman 编码</b>：解码器完整支持 Huffman（对端可能用），
 *       但自己发出去用明文字面量，牺牲一点带宽换实现简单和无 bug 风险。</li>
 * </ul>
 *
 * <p>因为从不发「带增量索引的字面量」，所以对端的动态表始终为空，
 * 本类也不需要维护自己的动态表。</p>
 */
public class HpackEncoder {

    public byte[] encode(List<HpackHeader> headers) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        for (HpackHeader header : headers) {
            encode(out, header.getName(), header.getValue());
        }
        return out.toByteArray();
    }

    private void encode(ByteArrayOutputStream out, String name, String value) {
        int exact = HpackStaticTable.find(name, value);
        if (exact > 0) {
            // Indexed Header Field: 1xxxxxxx
            encodeInteger(out, 7, 0x80, exact);
            return;
        }
        // Literal Header Field without Indexing: 0000xxxx
        int nameIndex = HpackStaticTable.findName(name);
        if (nameIndex > 0) {
            encodeInteger(out, 4, 0x00, nameIndex);
        } else {
            // 4-bit 索引字段为 0 ⇒ 名字是字面量
            out.write(0x00);
            writeString(out, name);
        }
        writeString(out, value);
    }

    /**
     * 写字符串字面量（不做 Huffman）。RFC 7541 §5.2。
     */
    private static void writeString(ByteArrayOutputStream out, String value) {
        // HPACK 字符串是 octet 序列；用 ISO-8859-1 做字节↔字符一一映射，
        // 与解码端保持一致，保证 round-trip 字节级无损。
        byte[] bytes = value.getBytes(StandardCharsets.ISO_8859_1);
        encodeInteger(out, 7, 0x00, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    /**
     * 写 HPACK 整数（RFC 7541 §5.1）。
     *
     * @param prefixBits 前缀位数
     * @param prefixValue 前缀里除整数以外要带的其它位（表示类型）
     * @param value      待编码的整数
     */
    static void encodeInteger(ByteArrayOutputStream out, int prefixBits, int prefixValue, int value) {
        int maxPrefix = (1 << prefixBits) - 1;
        if (value < maxPrefix) {
            out.write(prefixValue | value);
            return;
        }
        out.write(prefixValue | maxPrefix);
        int rest = value - maxPrefix;
        while (rest >= 128) {
            out.write((rest & 0x7f) | 0x80);
            rest >>>= 7;
        }
        out.write(rest);
    }
}
