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

import com.google.protobuf.InvalidProtocolBufferException;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.test.message.v3.AnnotatedRepeatedBool;
import io.edap.protobuf.test.message.v3.AnnotatedRepeatedDouble;
import io.edap.protobuf.test.message.v3.AnnotatedRepeatedInt32;
import io.edap.protobuf.test.message.v3.AnnotatedRepeatedInt32Array;
import io.edap.protobuf.test.message.v3.AnnotatedRepeatedInt64;
import io.edap.protobuf.test.message.v3.ListBoolOuterClass;
import io.edap.protobuf.test.message.v3.ListDoubleOuterClass;
import io.edap.protobuf.test.message.v3.ListInt32OuterClass;
import io.edap.protobuf.test.message.v3.ListInt64OuterClass;
import io.edap.protobuf.test.message.v3.ListSfixed32OuterClass;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * <b>repeated packable 标量的 wire type 必须是 LENGTH_DELIMITED（packed）</b> —— 跨框架守门。
 *
 * <p><b>为什么专门测这个</b>：proto3 规定 repeated 的标量字段必须用 packed 编码
 * （wire type = 2），而单值标量用 varint（wire type = 0）。edap 原来按注解里的
 * {@code cardinality} 决定 wire type，而 {@code JavaBuilder} 生成的
 * {@code @ProtoField} <b>从不输出 {@code cardinality}</b>
 * ⇒ 运行时拿到注解默认值 {@code OPTIONAL} ⇒ 算出 varint tag（0x08），
 * 但载荷写的又是 packed（长度前缀 + 内容）⇒ <b>tag 与载荷不一致</b>。</p>
 *
 * <p>为什么 edap 自己往返不炸、跨框架才炸：解码侧的 tag 表也用同一个（错的）cardinality
 * ⇒ 两边都是 0x08；而解码分支只看「类型是否 packable」、不看 cardinality
 * ⇒ 读到 0x08 后按 packed 读，恰好把紧跟其后的长度前缀当成长度 ⇒ <b>歪打正着</b>。
 * protobuf-java 读到 0x08 当作单个 varint，剩下字节当未知字段
 * ⇒ 抛 {@code invalid tag (zero)}。</p>
 *
 * <p>这些用例覆盖「注解里写了 cardinality」的老写法不受影响（{@code ListSfixed32} 那种），
 * 以及「注解没写 cardinality」的新写法必须自动补成 REPEATED。</p>
 */
public class TestRepeatedPackedWireType {

    /**
     * packed 的 tag：field 1 + wire type 2（LENGTH_DELIMITED）= (1 &lt;&lt; 3) | 2 = 0x0A。
     */
    private static final int PACKED_TAG_1 = 0x0A;

    // ---------------------------------------------------------------- int32

    @Test
    void testInt32PackedWireType() {
        List<Integer> values = Arrays.asList(1, 2, 3, 300, -1, Integer.MAX_VALUE);
        AnnotatedRepeatedInt32 edap = new AnnotatedRepeatedInt32();
        edap.value = values;
        byte[] edapBytes = ProtoBuf.toByteArray(edap);

        ListInt32OuterClass.ListInt32 pb = ListInt32OuterClass.ListInt32.newBuilder()
                .addAllValue(values).build();
        byte[] pbBytes = pb.toByteArray();

        assertArrayEquals(pbBytes, edapBytes,
                "repeated int32 的 wire 字节必须与 protobuf-java 逐字节相同（packed）");
        assertEquals(PACKED_TAG_1, edapBytes[0] & 0xFF,
                "repeated int32 的首字节必须是 0x0A（length-delimited），实际是 0x"
                        + Integer.toHexString(edapBytes[0] & 0xFF));
    }

    /**
     * protobuf-java 必须能解析 edap 写出的字节 —— 这是「跨框架可用」的硬证据。
     */
    @Test
    void testInt32ParsableByProtobufJava() {
        List<Integer> values = Arrays.asList(1, 2, 3, 300, -1, Integer.MAX_VALUE);
        AnnotatedRepeatedInt32 edap = new AnnotatedRepeatedInt32();
        edap.value = values;
        byte[] edapBytes = ProtoBuf.toByteArray(edap);

        ListInt32OuterClass.ListInt32 parsed;
        try {
            parsed = ListInt32OuterClass.ListInt32.parseFrom(edapBytes);
        } catch (InvalidProtocolBufferException e) {
            throw new AssertionError("protobuf-java 解析 edap 的字节必须成功，实际抛异常：" + e.getMessage(), e);
        }
        assertEquals(values, parsed.getValueList());
    }

    /**
     * edap 自己也必须能往返。
     */
    @Test
    void testInt32RoundTrip() {
        List<Integer> values = Arrays.asList(1, 2, 3, 300, -1, Integer.MIN_VALUE, Integer.MAX_VALUE);
        AnnotatedRepeatedInt32 edap = new AnnotatedRepeatedInt32();
        edap.value = values;
        byte[] edapBytes = ProtoBuf.toByteArray(edap);

        AnnotatedRepeatedInt32 decoded = ProtoBuf.toObject(edapBytes, AnnotatedRepeatedInt32.class);
        assertNotNull(decoded.value);
        assertEquals(values, decoded.value);
    }

    /**
     * 空列表不写字段 —— 与 protobuf-java 一致。
     */
    @Test
    void testInt32EmptyList() {
        AnnotatedRepeatedInt32 edap = new AnnotatedRepeatedInt32();
        edap.value = new ArrayList<>();
        byte[] edapBytes = ProtoBuf.toByteArray(edap);

        assertArrayEquals(ListInt32OuterClass.ListInt32.newBuilder().build().toByteArray(), edapBytes);
    }

    // ---------------------------------------------------------------- 数组形态

    @Test
    void testInt32ArrayWireType() {
        int[] values = new int[]{1, 2, 3, 300, -1, Integer.MAX_VALUE};
        AnnotatedRepeatedInt32Array edap = new AnnotatedRepeatedInt32Array();
        edap.value = values;
        byte[] edapBytes = ProtoBuf.toByteArray(edap);

        ListInt32OuterClass.ListInt32.Builder builder = ListInt32OuterClass.ListInt32.newBuilder();
        for (int v : values) {
            builder.addValue(v);
        }
        assertArrayEquals(builder.build().toByteArray(), edapBytes,
                "repeated int32（数组形态）的 wire 字节必须与 protobuf-java 逐字节相同");
        assertEquals(PACKED_TAG_1, edapBytes[0] & 0xFF);
    }

    // ---------------------------------------------------------------- int64

    @Test
    void testInt64PackedWireType() {
        List<Long> values = Arrays.asList(1L, 2L, 300L, -1L, Long.MAX_VALUE, Long.MIN_VALUE);
        AnnotatedRepeatedInt64 edap = new AnnotatedRepeatedInt64();
        edap.value = values;
        byte[] edapBytes = ProtoBuf.toByteArray(edap);

        ListInt64OuterClass.ListInt64 pb = ListInt64OuterClass.ListInt64.newBuilder()
                .addAllValue(values).build();
        assertArrayEquals(pb.toByteArray(), edapBytes,
                "repeated int64 的 wire 字节必须与 protobuf-java 逐字节相同（packed）");
        assertEquals(PACKED_TAG_1, edapBytes[0] & 0xFF);
    }

    @Test
    void testInt64ParsableByProtobufJava() throws Exception {
        List<Long> values = Arrays.asList(1L, 2L, 300L, -1L, Long.MAX_VALUE);
        AnnotatedRepeatedInt64 edap = new AnnotatedRepeatedInt64();
        edap.value = values;
        byte[] edapBytes = ProtoBuf.toByteArray(edap);

        ListInt64OuterClass.ListInt64 parsed = ListInt64OuterClass.ListInt64.parseFrom(edapBytes);
        assertEquals(values, parsed.getValueList());
    }

    // ---------------------------------------------------------------- bool

    @Test
    void testBoolPackedWireType() {
        List<Boolean> values = Arrays.asList(true, false, true, true);
        AnnotatedRepeatedBool edap = new AnnotatedRepeatedBool();
        edap.value = values;
        byte[] edapBytes = ProtoBuf.toByteArray(edap);

        ListBoolOuterClass.ListBool pb = ListBoolOuterClass.ListBool.newBuilder()
                .addAllValue(values).build();
        assertArrayEquals(pb.toByteArray(), edapBytes,
                "repeated bool 的 wire 字节必须与 protobuf-java 逐字节相同（packed）");
        assertEquals(PACKED_TAG_1, edapBytes[0] & 0xFF);
    }

    @Test
    void testBoolParsableByProtobufJava() throws Exception {
        List<Boolean> values = Arrays.asList(true, false, true, true);
        AnnotatedRepeatedBool edap = new AnnotatedRepeatedBool();
        edap.value = values;
        byte[] edapBytes = ProtoBuf.toByteArray(edap);

        ListBoolOuterClass.ListBool parsed = ListBoolOuterClass.ListBool.parseFrom(edapBytes);
        assertEquals(values, parsed.getValueList());
    }

    // ---------------------------------------------------------------- double

    @Test
    void testDoublePackedWireType() {
        List<Double> values = Arrays.asList(1.5d, -2.25d, 0.0d, Double.MAX_VALUE);
        AnnotatedRepeatedDouble edap = new AnnotatedRepeatedDouble();
        edap.value = values;
        byte[] edapBytes = ProtoBuf.toByteArray(edap);

        ListDoubleOuterClass.ListDouble pb = ListDoubleOuterClass.ListDouble.newBuilder()
                .addAllD(values).build();
        assertArrayEquals(pb.toByteArray(), edapBytes,
                "repeated double 的 wire 字节必须与 protobuf-java 逐字节相同（packed）");
        assertEquals(PACKED_TAG_1, edapBytes[0] & 0xFF);
    }

    @Test
    void testDoubleParsableByProtobufJava() throws Exception {
        List<Double> values = Arrays.asList(1.5d, -2.25d, 0.0d, Double.MAX_VALUE);
        AnnotatedRepeatedDouble edap = new AnnotatedRepeatedDouble();
        edap.value = values;
        byte[] edapBytes = ProtoBuf.toByteArray(edap);

        ListDoubleOuterClass.ListDouble parsed = ListDoubleOuterClass.ListDouble.parseFrom(edapBytes);
        assertEquals(values, parsed.getDList());
    }

    // ------------------------------------------------- 显式写了 cardinality 的老写法不受影响

    /**
     * 显式写 {@code cardinality = REPEATED} 的 POJO 必须保持原样（不能被「自动合成」逻辑改坏）。
     */
    @Test
    void testExplicitCardinalityStillWorks() {
        io.edap.protobuf.test.message.v3.ListSfixed32 edap =
                new io.edap.protobuf.test.message.v3.ListSfixed32();
        edap.list = Arrays.asList(1, 2, 300);
        byte[] edapBytes = ProtoBuf.toByteArray(edap);

        ListSfixed32OuterClass.ListSfixed32 pb = ListSfixed32OuterClass.ListSfixed32.newBuilder()
                .addAllValue(Arrays.asList(1, 2, 300)).build();
        assertArrayEquals(pb.toByteArray(), edapBytes);
    }

    /**
     * 没有注解的 POJO（走 {@code generateProtoField} 推断 cardinality 的路径）必须保持原样。
     */
    @Test
    void testNoAnnotationPathStillWorks() {
        io.edap.protobuf.test.message.v3.ListInt32 edap = new io.edap.protobuf.test.message.v3.ListInt32();
        edap.list = Arrays.asList(1, 2, 300);
        byte[] edapBytes = ProtoBuf.toByteArray(edap);

        ListInt32OuterClass.ListInt32 pb = ListInt32OuterClass.ListInt32.newBuilder()
                .addAllValue(Arrays.asList(1, 2, 300)).build();
        assertArrayEquals(pb.toByteArray(), edapBytes);
    }

    /**
     * 单值（非 repeated）标量必须仍然是 varint —— 自动合成不能误伤。
     */
    @Test
    void testSingularScalarStaysVarint() {
        io.edap.protobuf.test.message.v3.OneInt32 edap = new io.edap.protobuf.test.message.v3.OneInt32();
        edap.setValue(300);
        byte[] edapBytes = ProtoBuf.toByteArray(edap);

        assertEquals(0x08, edapBytes[0] & 0xFF,
                "单值 int32 的 tag 必须仍是 varint（0x08）");
    }

    /**
     * repeated string（本来就��� LENGTH_DELIMITED）不受影响。
     */
    @Test
    void testRepeatedStringUnaffected() {
        io.edap.protobuf.test.message.v3.ListString edap = new io.edap.protobuf.test.message.v3.ListString();
        edap.list = Arrays.asList("a", "bb");
        byte[] edapBytes = ProtoBuf.toByteArray(edap);

        assertEquals(PACKED_TAG_1, edapBytes[0] & 0xFF,
                "repeated string 的 tag 是 length-delimited（0x0A）");
    }

    /**
     * protobuf-java 必须能读 edap 写的 repeated —— 曾经的「invalid tag (zero)」就是这条。
     */
    @Test
    void testProtobufJavaDoesNotThrowOnEdapBytes() throws Exception {
        AnnotatedRepeatedInt32 edap = new AnnotatedRepeatedInt32();
        edap.value = Arrays.asList(1, 2, 3);
        byte[] edapBytes = ProtoBuf.toByteArray(edap);

        assertDoesNotThrowInvalidTag(() -> ListInt32OuterClass.ListInt32.parseFrom(edapBytes));
    }

    private static void assertDoesNotThrowInvalidTag(ThrowingRunnable r) throws Exception {
        try {
            r.run();
        } catch (InvalidProtocolBufferException e) {
            throw new AssertionError("protobuf-java 抛了 invalid tag —— edap 的 wire 格式不兼容：" + e.getMessage(), e);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
