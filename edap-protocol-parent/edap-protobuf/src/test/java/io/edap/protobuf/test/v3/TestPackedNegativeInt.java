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
import io.edap.protobuf.test.message.v3.AnnotatedRepeatedInt32;
import io.edap.protobuf.test.message.v3.AnnotatedRepeatedInt32Array;
import io.edap.protobuf.test.message.v3.ListInt32OuterClass;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * <b>packed int32 的负数编码必须与 protobuf-java 逐字节相同</b>。
 *
 * <p>protobuf 的 int32 是 <b>符号扩展成 64 位</b>再写 varint
 * ⇒ 负数固定占 <b>10 字节</b>（0xFF × 9 + 0x01），正数按无符号 32 位 varint。</p>
 *
 * <p>★ 这条曾经踩过一个很隐蔽的坑：{@code writePackedInts(List/Iterable)} 的旧实现
 * 为了「展开前 10 个元素、避免循环的边界检查」，把<b>下标 7 那个元素</b>写成了
 * {@code writeUInt32_0(...)} 而不是 {@code writeInt32_0(...)}
 * ⇒ 该元素若是负数，就会按无符号 32 位写成 <b>5 字节</b>（而不是 10 字节）
 * ⇒ <b>整个字段的字节错位</b>（长度前缀之后的载荷少 5 字节）。
 * ⇒ 只有「负数恰好落在下标 7」才会触发 ⇒ 极难被随机测试撞到。</p>
 *
 * <p>改成「两遍写法（先算长度 → 写长度 → 写内容）」后，所有元素走同一条路径 ⇒ 这个坑消失。</p>
 */
public class TestPackedNegativeInt {

    /**
     * 负数在 packed int32 里占 10 字节（符号扩展的 64 位 varint）。
     */
    private static final int NEG_INT32_BYTES = 10;

    /**
     * 逐字节比对 edap 与 protobuf-java，并额外断言负数确实占 10 字节。
     */
    private void check(List<Integer> values) {
        AnnotatedRepeatedInt32 edap = new AnnotatedRepeatedInt32();
        edap.value = values;
        byte[] edapBytes = ProtoBuf.toByteArray(edap);

        byte[] pbBytes = ListInt32OuterClass.ListInt32.newBuilder()
                .addAllValue(values).build().toByteArray();

        assertArrayEquals(pbBytes, edapBytes,
                "packed int32 的 wire 字节必须与 protobuf-java 逐字节相同，values=" + values);
    }

    /**
     * 负数在<b>下标 7</b> —— 旧实现在这个下标用的是 {@code writeUInt32_0} ⇒ 写 5 字节。
     */
    @Test
    void testNegativeAtIndex7() {
        List<Integer> values = new ArrayList<>(Arrays.asList(
                1, 2, 3, 4, 5, 6, 7, -1, 9, 10));
        check(values);

        // 载荷长度 = 7 个 1 字节 + 1 个 10 字节 + 2 个 1 字节 = 19
        AnnotatedRepeatedInt32 edap = new AnnotatedRepeatedInt32();
        edap.value = values;
        byte[] bytes = ProtoBuf.toByteArray(edap);
        // tag(1) + 长度前缀(1) + 载荷(19)
        assertEquals(1 + 1 + 19, bytes.length,
                "下标 7 的负数必须占 10 字节（符号扩展），整段长度才对得上");
    }

    /**
     * 负数在<b>下标 0</b>与<b>最后一个下标</b>。
     */
    @Test
    void testNegativeAtFirstAndLast() {
        check(Arrays.asList(-1, 2, 3));
        check(Arrays.asList(1, 2, -1));
        check(Arrays.asList(-1));
        check(Arrays.asList(Integer.MIN_VALUE, Integer.MAX_VALUE, -1, 1));
    }

    /**
     * 多个负数混合，跨越下标 7。
     */
    @Test
    void testMixedNegatives() {
        check(Arrays.asList(-1, -2, -3, -4, -5, -6, -7, -8, -9, -10, -11));
        check(Arrays.asList(0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        check(Arrays.asList(0, -1, 0, -1, 0, -1, 0, -1, 0, -1));
    }

    /**
     * 数组形态（{@code int[]}）也必须一致。
     */
    @Test
    void testArrayForm() {
        int[] values = new int[]{1, 2, 3, 4, 5, 6, 7, -1, 9, 10};
        AnnotatedRepeatedInt32Array edap = new AnnotatedRepeatedInt32Array();
        edap.value = values;
        byte[] edapBytes = ProtoBuf.toByteArray(edap);

        byte[] pbBytes = ListInt32OuterClass.ListInt32.newBuilder()
                .addAllValue(Arrays.stream(values).boxed().toList()).build().toByteArray();

        assertArrayEquals(pbBytes, edapBytes, "packed int32（数组形态）必须与 protobuf-java 逐字节相同");
    }

    /**
     * protobuf-java 必须能解 edap 写出的字节，且值完全一致。
     */
    @Test
    void testParsableByProtobufJava() throws Exception {
        List<Integer> values = Arrays.asList(1, 2, 3, 4, 5, 6, 7, -1, 9, 10);
        AnnotatedRepeatedInt32 edap = new AnnotatedRepeatedInt32();
        edap.value = values;
        byte[] edapBytes = ProtoBuf.toByteArray(edap);

        ListInt32OuterClass.ListInt32 parsed = ListInt32OuterClass.ListInt32.parseFrom(edapBytes);
        assertEquals(values, parsed.getValueList());
    }

    /**
     * edap 自身往返。
     */
    @Test
    void testRoundTrip() {
        List<Integer> values = Arrays.asList(1, 2, 3, 4, 5, 6, 7, -1, 9, 10);
        AnnotatedRepeatedInt32 edap = new AnnotatedRepeatedInt32();
        edap.value = values;
        byte[] edapBytes = ProtoBuf.toByteArray(edap);

        AnnotatedRepeatedInt32 decoded = ProtoBuf.toObject(edapBytes, AnnotatedRepeatedInt32.class);
        assertEquals(values, decoded.value);
    }

    /**
     * 负数占 10 字节的字节级断言（不依赖 protobuf-java）。
     */
    @Test
    void testNegativeIsTenBytes() {
        AnnotatedRepeatedInt32 edap = new AnnotatedRepeatedInt32();
        edap.value = Arrays.asList(-1);
        byte[] bytes = ProtoBuf.toByteArray(edap);

        // tag(1) + 长度前缀(1) + 10 字节 varint
        assertEquals(1 + 1 + NEG_INT32_BYTES, bytes.length,
                "int32 负数必须符号扩展成 64 位 varint（10 字节）");
        assertEquals(0xFF, bytes[2] & 0xFF);
        assertEquals(0x01, bytes[2 + NEG_INT32_BYTES - 1] & 0xFF);
    }

    /**
     * ★ <b>大量负数</b> ⇒ 旧实现会「缓冲越界」。
     *
     * <p>旧实现按 {@code MAX_VARINT_SIZE}（5 字节/元素）预留空间，
     * 而 int32 负数要符号扩展成 <b>10 字节</b> ⇒ 元素足够多时写穿缓冲
     * ⇒ 实测 {@code ArrayIndexOutOfBoundsException: Index 16384 out of bounds for length 16384}
     * （被 {@code ProtoBuf.toByteArray} 吞掉后返回 {@code null}）。</p>
     */
    @Test
    void testManyNegativesNoOverflow() {
        List<Integer> values = new ArrayList<>();
        for (int i = 1; i <= 2000; i++) {
            values.add(-i);
        }
        check(values);
    }

    /**
     * 负数 + 正数<b>交替</b>：每个负数占 10 字节，正数占 1~2 字节 ⇒ 长度前缀会跨档。
     */
    @Test
    void testAlternatingNegatives() {
        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            values.add(-i - 1);
            values.add(i);
        }
        check(values);
    }
}
