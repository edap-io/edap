/*
 * Copyright 2026 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.edap.protobuf.test.v3;

import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.test.message.v3.OneString;
import io.edap.protobuf.test.message.v3.OneStringOuterClass;
import io.edap.util.StringUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 字符串字段的 UTF-8 编码正确性 —— edap 与 protobuf-java 的 wire 字节必须逐字节相同。
 *
 * <p><b>为什么专门测这个</b>：protobuf 的 {@code string} 字段的 wire 格式定义的是
 * <b>UTF-8</b>。JDK9+ 的 compact strings 把「全部字符 ≤ 0xFF」的 String 存成
 * <b>Latin1</b>（每个字符 1 字节），这时 String 内部字节数组里的 0x80~0xFF
 * <b>不是</b>合法的 UTF-8 —— 必须先转成 2 字节的 UTF-8 序列才能写出去。</p>
 *
 * <p>edap 的写入路径原来有一条「Latin1 直接裸拷」的快路径，
 * 会把 0x80~0xFF 的字节原样写出去 ⇒ 产生<b>非法 UTF-8</b>
 * （protobuf-java 会把 {@code U+00E9} 写成 {@code 0xC3 0xA9}）。
 * 现有测试全部用中文（{@code U+4E2D} 等 > 0xFF ⇒ String 的 coder 是 UTF16，���不到那条路径），
 * 所以这个洞一直没被测出来。</p>
 */
public class TestStringUtf8 {

    /**
     * 构造一个 <b>coder 一定是 LATIN1</b> 且含有 0x80~0xFF 字符的 String。
     *
     * <p>用 {@code ISO_8859_1} 字节数组构造 ⇒ 每个字节就是一个 char（0~255）
     * ⇒ JDK 一定把它存成 Latin1。</p>
     */
    private static String latin1(int... bytes) {
        byte[] bs = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            bs[i] = (byte) bytes[i];
        }
        return new String(bs, StandardCharsets.ISO_8859_1);
    }

    /**
     * edap 编码出来的字节必须与 protobuf-java 完全一致。
     */
    private static void assertWireSameAsGoogle(String value) {
        OneString oneString = new OneString();
        oneString.setValue(value);
        byte[] edapBytes = ProtoBuf.toByteArray(oneString);

        OneStringOuterClass.OneString pb = OneStringOuterClass.OneString.newBuilder()
                .setValue(value).build();
        byte[] pbBytes = pb.toByteArray();

        assertArrayEquals(pbBytes, edapBytes,
                "edap 与 protobuf-java 的 wire 字节必须逐字节相同（value=" + describe(value) + "）");
    }

    /**
     * edap 自己解码自己的字节，必须还原成原串。
     */
    private static void assertRoundTrip(String value) {
        OneString oneString = new OneString();
        oneString.setValue(value);
        byte[] edapBytes = ProtoBuf.toByteArray(oneString);
        OneString decoded = ProtoBuf.toObject(edapBytes, OneString.class);
        assertEquals(value, decoded.getValue(), "edap 的往返解码必须还原原串");
    }

    private static String describe(String value) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(value.length(), 32); i++) {
            sb.append(String.format("\\u%04x", (int) value.charAt(i)));
        }
        return sb.toString();
    }

    /**
     * ★ Latin1 且含 0x80~0xFF 的字符 —— 原来会写出非法 UTF-8。
     */
    @Test
    void testLatin1HighByte() {
        // "café" —— 'é' = U+00E9 = Latin1 字节 0xE9
        String value = latin1('c', 'a', 'f', 0xE9);
        assertWireSameAsGoogle(value);
        assertRoundTrip(value);
    }

    /**
     * Latin1 的高位字符混在 ASCII 里。
     */
    @Test
    void testLatin1MixedWithAscii() {
        String value = latin1('a', 0x80, 'b', 0xFF, 'c');
        assertWireSameAsGoogle(value);
        assertRoundTrip(value);
    }

    /**
     * 纯 ASCII —— 快路径必须保持不变。
     */
    @ParameterizedTest
    @ValueSource(strings = {"", "a", "abcdefgh", "0123456789"})
    void testAscii(String value) {
        assertWireSameAsGoogle(value);
        assertRoundTrip(value);
    }

    /**
     * UTF-16 coder 的串（中文）—— 走的是另一条编码路径。
     */
    @ParameterizedTest
    @ValueSource(strings = {"中文内容", "🐶头", "a😁文c"})
    void testUtf16(String value) {
        assertWireSameAsGoogle(value);
        assertRoundTrip(value);
    }

    /**
     * 长度跨过 varint 长度前缀的边界（1 字节 / 2 字节 / 3 字节），
     * 以及跨过读侧 4096 字节的中间缓冲阈值。
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 127, 128, 16383, 16384, 4095, 4096, 4097, 65535})
    void testLengthBoundary(int len) {
        // ASCII 串
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        String ascii = sb.toString();
        assertWireSameAsGoogle(ascii);
        assertRoundTrip(ascii);

        // 同样的长度但每个字符占 3 个 UTF-8 字节（中文）
        StringBuilder cn = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            cn.append('中');
        }
        String chinese = cn.toString();
        assertWireSameAsGoogle(chinese);
        assertRoundTrip(chinese);
    }

    /**
     * Latin1 高位字符 + 长度跨 varint 边界。
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 127, 128, 4096})
    void testLatin1LengthBoundary(int len) {
        byte[] bs = new byte[len];
        for (int i = 0; i < len; i++) {
            bs[i] = (byte) (0x80 + (i % 0x80));
        }
        String value = new String(bs, StandardCharsets.ISO_8859_1);
        assertWireSameAsGoogle(value);
        assertRoundTrip(value);
    }

    /**
     * ★ 短串分支 / 长串分支的分界线（{@code maxBytes = charLen * 3 <= 3072}）。
     *
     * <p>1024 个中文字符 = 3072 字节 ⇒ 恰好走短串分支；1025 个 = 3075 字节 ⇒ 走长串分支。
     * 两条分支的「长度前缀回填」逻辑不同，必须都覆盖到。</p>
     */
    @ParameterizedTest
    @ValueSource(ints = {1023, 1024, 1025, 1026, 2047, 2048, 2049})
    void testShortLongBranchBoundary(int len) {
        StringBuilder cn = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            cn.append('中');
        }
        assertWireSameAsGoogle(cn.toString());
        assertRoundTrip(cn.toString());
    }

    /**
     * ★ <b>「预留长度前缀 ≠ 实际长度前缀」的回退分支</b>。
     *
     * <p>写入时先按「每字最多 3 字节」预留长度前缀的字节数，编码完再回填真实长度。
     * 当串里绝大多数是 ASCII（每字 1 字节）、只有个别中文字（3 字节）时，
     * 真实长度前缀比预留的<b>短</b> ⇒ 需要把内容左移补齐。
     * 这个分支在旧实现里不存在（旧实现一律右移），必须单独锁住。</p>
     */
    @ParameterizedTest
    @ValueSource(ints = {100, 1000, 6000, 20000})
    void testMixedAsciiChinesePrefixShift(int len) {
        // 绝大多数 ASCII + 最后一个中文字 ⇒ coder 是 UTF16，但 UTF-8 长度远小于 charLen*3
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len - 1; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        sb.append('中');
        String value = sb.toString();
        assertWireSameAsGoogle(value);
        assertRoundTrip(value);
    }

    /**
     * 长中文串（30000 字 ⇒ 90000 字节 ⇒ 长度前缀 3 字节 varint，走长串分支）。
     */
    @Test
    void testLongChinese() {
        StringBuilder sb = new StringBuilder(30000);
        for (int i = 0; i < 30000; i++) {
            sb.append('中');
        }
        String value = sb.toString();
        assertWireSameAsGoogle(value);
        assertRoundTrip(value);
    }

    /**
     * ★★ <b>纯 ASCII 快路径</b>（写入侧零分配直写）。
     *
     * <p>写入侧现在对「纯 ASCII 的 Latin1 串」走一条更快的路：
     * 先用 JDK 的 SIMD 判定（{@code StringCoding.hasNegatives}）确认串里没有高位字节，
     * 于是<b>它的 UTF-8 字节与 String 内部字节完全相同</b> ⇒ 直接把内部字节拷进输出缓冲，
     * 省掉 {@code getBytes(UTF_8)} 的「一次分配 + 一次拷贝」。</p>
     *
     * <p>本测试锁两件事：① 快路径的<b>前提</b>确实成立（否则测的就不是这条路径）；
     * ② 走快路径产出的 wire 字节与 protobuf-java 仍逐字节相同。</p>
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 5, 127, 128, 1024, 16383, 16384, 65536})
    void testAsciiFastPath(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + (i % 26)));
        }
        String value = sb.toString();

        // ① 快路径前提：Latin1 编码 + 没有高位字节 ⇒ UTF-8 字节 == 内部字节
        byte[] internal = StringUtil.getLatin1Bytes(value);
        assertNotNull(internal, "纯 ASCII 串必须是 Latin1 编码");
        assertFalse(StringUtil.hasHighBytes(internal, 0, internal.length),
                "纯 ASCII 串不能有高位字节（否则写出去的是非法 UTF-8）");
        assertArrayEquals(value.getBytes(StandardCharsets.UTF_8), internal,
                "纯 ASCII 串：内部字节必须与 UTF-8 字节相同（这是快路径的前提）");

        // ② 字节必须与 protobuf-java 一致
        assertWireSameAsGoogle(value);
        assertRoundTrip(value);
    }
}
