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
import io.edap.protobuf.test.message.v3.ListUint32;
import io.edap.protobuf.test.message.v3.ListUint32OuterClass;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * <b>packed uint32 的负数编码必须与 protobuf-java 逐字节相同</b>。
 *
 * <p>protobuf 的 uint32 是<b>无符号 32 位</b> ⇒ 无论 Java 侧是不是负数，
 * 都按<b>5 字节以内的无符号 varint</b>写（{@code -1} ⇒ {@code FF FF FF FF 0F}）；
 * <b>不是</b> int32 那种「符号扩展成 64 位（10 字节）」。</p>
 *
 * <p>★ 旧实现把 INT32 与 UINT32 放在同一个分支里，都走 {@code writeInt32_0}
 * ⇒ 负数被符号扩展成 10 字节 ⇒ <b>wire 字节与 protobuf-java 不同</b>。</p>
 */
public class TestPackedUint32Negative {

    private void check(List<Integer> values) {
        ListUint32 edap = new ListUint32();
        edap.list = values;
        byte[] edapBytes = ProtoBuf.toByteArray(edap);

        byte[] pbBytes = ListUint32OuterClass.ListUint32.newBuilder()
                .addAllValue(values).build().toByteArray();

        assertArrayEquals(pbBytes, edapBytes,
                "packed uint32 的 wire 字节必须与 protobuf-java 逐字节相同，values=" + values);
    }

    @Test
    void testNegativeValues() {
        check(Arrays.asList(-1));
        check(Arrays.asList(-1, 2, 3));
        check(Arrays.asList(1, 2, -1));
        check(Arrays.asList(Integer.MIN_VALUE, Integer.MAX_VALUE, -1, 1));
    }

    @Test
    void testPositiveValues() {
        check(Arrays.asList(0, 1, 127, 128, 16383, 16384, -1));
    }

    /**
     * ★ <b>varint 宽度边界</b>：1↔2 字节（127/128）、2↔3 字节（16383/16384）、
     * 3↔4 字节（2097151/2097152）、4↔5 字节（268435455/268435456）。
     *
     * <p>编码侧 1~2 字节直接展开在 packed 循环里（零方法调用），≥3 字节走
     * {@code writeVarintAt3to5}（3 字节 / 4~5 字节两个分支），
     * 边界值必须逐字节对齐 protobuf-java。</p>
     */
    @Test
    void testVarintWidthBoundaries() {
        check(Arrays.asList(0, 1, 126, 127, 128, 129));
        check(Arrays.asList(16382, 16383, 16384, 16385));
        check(Arrays.asList(2097150, 2097151, 2097152, 2097153));
        check(Arrays.asList(268435454, 268435455, 268435456, 268435457));
        check(Arrays.asList(Integer.MAX_VALUE - 1, Integer.MAX_VALUE));
    }
}
