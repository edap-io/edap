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

/**
 * HPACK 的 Huffman 编码表（RFC 7541 Appendix B）。
 *
 * <p><b>本类只硬编码 257 个「码长」，码字全部由规范构造规则在类初始化时推导。</b>
 * 原因：手抄 257 组「码字 + 码长」几乎必然出错，而手抄 257 个码长仍可能出错 ——
 * 所以这里再加两道机器闸门：</p>
 *
 * <ol>
 *   <li><b>Kraft 等式</b>：{@code Σ 2^(30 - len[i]) == 2^30}。这是码长表完整且自洽的
 *       <b>充要</b>条件（对前缀码成立），任何一处抄错都会让等式不成立。</li>
 *   <li><b>规范构造回代</b>：由码长按 RFC 7541 的规范构造规则还原出的 257 个码字，
 *       与 RFC Appendix B 的「code as hex」列逐个比对（见 {@link #getCode(int)} 与单测）。</li>
 * </ol>
 *
 * <p>码长表来源：RFC 7541 Appendix B（<a href="https://www.rfc-editor.org/rfc/rfc7541">rfc7541</a>），
 * 已用脚本逐行解析该章节的表格并与上述两道闸门核对通过。</p>
 */
public final class HuffmanTable {

    /** EOS 符号 */
    public static final int EOS_SYMBOL = 256;
    /** 符号总数：0..255 + EOS */
    public static final int SYMBOL_COUNT = 257;
    /** 最长码长 */
    public static final int MAX_CODE_LENGTH = 30;
    /** 最短码长 */
    public static final int MIN_CODE_LENGTH = 5;

    /**
     * RFC 7541 Appendix B 的码长表，下标 0..255 是字节值，256 是 EOS。
     * <p>注意：这里只存码长，码字由 {@link #buildCodes()} 推导。</p>
     */
    private static final int[] CODE_LENGTHS = {
            13, 23, 28, 28, 28, 28, 28, 28, 28, 24, 30, 28, 28, 30, 28, 28,
            28, 28, 28, 28, 28, 28, 30, 28, 28, 28, 28, 28, 28, 28, 28, 28,
            6, 10, 10, 12, 13, 6, 8, 11, 10, 10, 8, 11, 8, 6, 6, 6,
            5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 7, 8, 15, 6, 12, 10,
            13, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7, 8, 7, 8, 13, 19, 13, 14, 6,
            15, 5, 6, 5, 6, 5, 6, 6, 6, 5, 7, 7, 6, 6, 6, 5,
            6, 7, 6, 5, 5, 6, 7, 7, 7, 7, 7, 15, 11, 14, 13, 28,
            20, 22, 20, 20, 22, 22, 22, 23, 22, 23, 23, 23, 23, 23, 24, 23,
            24, 24, 22, 23, 24, 23, 23, 23, 23, 21, 22, 23, 22, 23, 23, 24,
            22, 21, 20, 22, 22, 23, 23, 21, 23, 22, 22, 24, 21, 22, 23, 23,
            21, 21, 22, 21, 23, 22, 23, 23, 20, 22, 22, 22, 23, 22, 22, 23,
            26, 26, 20, 19, 22, 23, 22, 25, 26, 26, 26, 27, 27, 26, 24, 25,
            19, 21, 26, 27, 27, 26, 27, 24, 21, 21, 26, 26, 28, 27, 27, 27,
            20, 24, 20, 21, 22, 21, 21, 23, 22, 22, 25, 25, 24, 24, 26, 23,
            26, 27, 26, 26, 27, 27, 27, 27, 27, 28, 27, 27, 27, 27, 27, 26,
            30
    };

    /** 由码长推导出的码字 */
    private static final int[] CODES = new int[SYMBOL_COUNT];

    /** 符号按 (码长, 符号号) 升序排列 */
    private static final int[] ORDER = new int[SYMBOL_COUNT];

    /** 长度 L 的码字段在 {@link #ORDER} 中的起始下标 */
    private static final int[] FIRST_INDEX = new int[MAX_CODE_LENGTH + 1];
    /** 长度 L 的码字段的起始码字 */
    private static final int[] FIRST_CODE = new int[MAX_CODE_LENGTH + 1];
    /** 长度 L 的符号个数 */
    private static final int[] COUNT = new int[MAX_CODE_LENGTH + 1];

    static {
        buildOrder();
        buildCodes();
        buildDecodeTable();
        verifyKraft();
    }

    private HuffmanTable() {
    }

    /**
     * 符号按 (码长, 符号号) 升序排列 —— 规范 Huffman 构造的前提。
     */
    private static void buildOrder() {
        for (int i = 0; i < SYMBOL_COUNT; i++) {
            ORDER[i] = i;
        }
        // 插入排序：257 个元素，一次性初始化，开销可忽略
        for (int i = 1; i < SYMBOL_COUNT; i++) {
            int s = ORDER[i];
            int j = i - 1;
            while (j >= 0 && compare(ORDER[j], s) > 0) {
                ORDER[j + 1] = ORDER[j];
                j--;
            }
            ORDER[j + 1] = s;
        }
    }

    private static int compare(int a, int b) {
        if (CODE_LENGTHS[a] != CODE_LENGTHS[b]) {
            return CODE_LENGTHS[a] - CODE_LENGTHS[b];
        }
        return a - b;
    }

    /**
     * 规范 Huffman 构造：由码长唯一还原码字。
     *
     * <p>规则：符号按 (码长, 符号号) 升序；第一个码字为 0；码长每增加 1 位，
     * 码字 = (上一个码字 + 1) &lt;&lt; 1；码长相同时码字依次 +1。</p>
     */
    private static void buildCodes() {
        int code = -1;
        int prevLen = 0;
        for (int i = 0; i < SYMBOL_COUNT; i++) {
            int sym = ORDER[i];
            int len = CODE_LENGTHS[sym];
            if (len != prevLen) {
                code = (code + 1) << (len - prevLen);
                prevLen = len;
            } else {
                code = code + 1;
            }
            CODES[sym] = code;
        }
    }

    /**
     * 构建按码长分桶的解码表。
     *
     * <p>规范码的性质：同一码长内的码字是连续的整数区间，所以只要「码长 + 起始码字 +
     * 符号个数」就能定位符号，不必逐符号比对。</p>
     */
    private static void buildDecodeTable() {
        int p = 0;
        int len = CODE_LENGTHS[ORDER[0]];
        while (p < SYMBOL_COUNT) {
            int start = p;
            len = CODE_LENGTHS[ORDER[p]];
            while (p < SYMBOL_COUNT && CODE_LENGTHS[ORDER[p]] == len) {
                p++;
            }
            COUNT[len] = p - start;
            FIRST_INDEX[len] = start;
            FIRST_CODE[len] = CODES[ORDER[start]];
        }
    }

    /**
     * Kraft 等式自检：{@code Σ 2^(30 - len[i]) == 2^30}。
     *
     * <p>这是前缀码「长度集合完整且自洽」的充要条件：任何一处码长抄错，
     * 这个等式都不成立 —— 这是防抄写错误最有效的闸门。</p>
     */
    private static void verifyKraft() {
        long sum = 0;
        for (int len : CODE_LENGTHS) {
            sum += 1L << (MAX_CODE_LENGTH - len);
        }
        if (sum != (1L << MAX_CODE_LENGTH)) {
            throw new ExceptionInInitializerError(
                    "Huffman 码长表不满足 Kraft 等式，实际和 = " + sum
                            + "，期望 " + (1L << MAX_CODE_LENGTH) + "（码长表抄错了）");
        }
    }

    // ------------------------------------------------------------------ 查询

    public static int getCodeLength(int symbol) {
        return CODE_LENGTHS[symbol];
    }

    public static int getCode(int symbol) {
        return CODES[symbol];
    }

    public static int[] getCodeLengths() {
        return CODE_LENGTHS.clone();
    }

    // ------------------------------------------------------------------ 解码

    /**
     * 解码一段 Huffman 编码的字符串。
     *
     * @param input  输入字节
     * @param offset 起始偏移
     * @param length 长度
     * @return 解码后的字节
     * @throws Http2Exception Huffman 编码非法（对应 HPACK 的 COMPRESSION_ERROR）
     */
    public static byte[] decode(byte[] input, int offset, int length) {
        // 最短码长 5 bit ⇒ 一个字节最多解出 1.6 字节，2 倍容量足够；不够时再扩容
        byte[] out = new byte[length * 2];
        int outLen = 0;

        int code = 0;
        int codeLen = 0;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            int b = input[i] & 0xff;
            for (int bit = 7; bit >= 0; bit--) {
                code = (code << 1) | ((b >> bit) & 1);
                codeLen++;
                if (codeLen > MAX_CODE_LENGTH) {
                    // 没有任何符号的码长超过 30 位，走到 31 位说明是非法编码
                    throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR,
                            "Huffman 编码超过最大码长 " + MAX_CODE_LENGTH);
                }
                if (codeLen < MIN_CODE_LENGTH) {
                    continue;
                }
                int cnt = COUNT[codeLen];
                if (cnt == 0) {
                    continue;
                }
                int first = FIRST_CODE[codeLen];
                if (code < first || code >= first + cnt) {
                    continue;
                }
                int sym = ORDER[FIRST_INDEX[codeLen] + (code - first)];
                if (sym == EOS_SYMBOL) {
                    // RFC 7541 §5.2：字面量里出现 EOS 必须当解码错误
                    throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR,
                            "Huffman 编码中出现 EOS 符号");
                }
                if (outLen == out.length) {
                    byte[] n = new byte[out.length * 2];
                    System.arraycopy(out, 0, n, 0, outLen);
                    out = n;
                }
                out[outLen++] = (byte) sym;
                code = 0;
                codeLen = 0;
            }
        }
        // 尾部 padding：必须是 EOS 的前缀（全 1），且不超过 7 位（RFC 7541 §5.2）
        if (codeLen > 7) {
            throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR,
                    "Huffman 编码尾部残留 " + codeLen + " 位，超过 7 位 padding 上限");
        }
        if (codeLen > 0 && code != ((1 << codeLen) - 1)) {
            throw new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR,
                    "Huffman 编码尾部 padding 不是 EOS 的前缀");
        }
        byte[] result = new byte[outLen];
        System.arraycopy(out, 0, result, 0, outLen);
        return result;
    }

    // ------------------------------------------------------------------ 编码

    /**
     * Huffman 编码后的字节长度（向上取整到整字节）。
     */
    public static int encodedLength(byte[] data, int offset, int length) {
        int bits = 0;
        for (int i = offset; i < offset + length; i++) {
            bits += CODE_LENGTHS[data[i] & 0xff];
        }
        return (bits + 7) / 8;
    }

    /**
     * Huffman 编码一段字符串。尾部用 EOS 的前缀（全 1）补齐到字节边界。
     */
    public static byte[] encode(byte[] data, int offset, int length) {
        byte[] out = new byte[encodedLength(data, offset, length)];
        int bitPos = 0;
        for (int i = offset; i < offset + length; i++) {
            int sym = data[i] & 0xff;
            int code = CODES[sym];
            int codeLen = CODE_LENGTHS[sym];
            for (int b = codeLen - 1; b >= 0; b--) {
                if (((code >>> b) & 1) != 0) {
                    out[bitPos >>> 3] |= (byte) (1 << (7 - (bitPos & 7)));
                }
                bitPos++;
            }
        }
        // padding：全 1
        while ((bitPos & 7) != 0) {
            out[bitPos >>> 3] |= (byte) (1 << (7 - (bitPos & 7)));
            bitPos++;
        }
        return out;
    }
}
