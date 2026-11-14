/*
 * Copyright 2020 The edap Project
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

package io.edap.grpc.test;

import io.edap.grpc.GrpcCodec;
import io.edap.grpc.GrpcException;
import io.edap.grpc.GrpcFrame;
import io.edap.grpc.GrpcFrameEncoder;
import io.edap.protobuf.annotation.ProtoField;
import io.edap.protobuf.wire.Field;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GrpcFrameEncoder}（帧头内联编码）的<b>逐字节等价</b>测试。
 *
 * <p>新路径把 5 字节帧头内联进 protobuf 的编码缓冲，一次编码直接产出「帧头 + 载荷」；
 * 老路径是 {@code GrpcFrame.encode(GrpcCodec.encode(type, message))}（编码两次、拷两次）。
 * 两条路径写出的字节必须<b>逐字节相同</b> —— 否则 wire format 就变了，
 * 与 grpc-java 的互操作会立刻坏掉。</p>
 */
public class GrpcFrameEncoderTest {

    public static class FrameSimpleMessage {
        @ProtoField(tag = 1, type = Field.Type.STRING)
        public String name;

        @ProtoField(tag = 2, type = Field.Type.INT32)
        public int value;
    }

    public static class FrameNestedMessage {
        @ProtoField(tag = 1, type = Field.Type.MESSAGE)
        public FrameSimpleMessage simple;

        @ProtoField(tag = 2, type = Field.Type.BYTES)
        public byte[] blob;
    }

    public static class FrameObjectMessage {
        @ProtoField(tag = 1, type = Field.Type.OBJECT)
        public Object data;
    }

    /**
     * 用老路径（编码两次）产出的帧，作为等价基准。
     */
    private static byte[] legacy(Class<?> type, Object message) {
        return GrpcFrame.encode(GrpcCodec.encode(type, message));
    }

    /**
     * 用新路径（帧头内联）产出的帧。
     */
    private static byte[] encode(Class<?> type, Object message) throws IOException {
        byte[][] holder = new byte[1][];
        GrpcFrameEncoder.encode(type, message, (buf, offset, length) -> {
            // sink 拿到的数组是借用的 —— 必须当场拷走
            holder[0] = Arrays.copyOfRange(buf, offset, offset + length);
        });
        return holder[0];
    }

    /**
     * 断言新路径与老路径写出的字节逐字节相同，且能解码回来。
     */
    private static void assertSameWire(Class<?> type, Object message) throws IOException {
        byte[] expected = legacy(type, message);
        byte[] actual = encode(type, message);
        assertNotNull(actual);
        assertEquals(expected.length, actual.length,
                "帧长度不一致：期望 " + expected.length + "，实际 " + actual.length);
        assertArrayEquals(expected, actual, "帧字节不一致");
    }

    @Test
    public void testEmptyMessage() throws IOException {
        FrameSimpleMessage message = new FrameSimpleMessage();
        message.name = "";
        message.value = 0;
        assertSameWire(FrameSimpleMessage.class, message);
    }

    @Test
    public void testAllFieldsDefault() throws IOException {
        assertSameWire(FrameSimpleMessage.class, new FrameSimpleMessage());
    }

    @Test
    public void testSimpleMessage() throws IOException {
        FrameSimpleMessage message = new FrameSimpleMessage();
        message.name = "edap";
        message.value = 42;
        assertSameWire(FrameSimpleMessage.class, message);
    }

    /**
     * 嵌套消息：长度前缀的 varint 是多字节（内容 &gt; 127 字节），
     * 会走 {@code writeLenMoveBytes} 的多字节 memmove 路径。
     */
    @Test
    public void testNestedMessageOver128Bytes() throws IOException {
        FrameSimpleMessage inner = new FrameSimpleMessage();
        inner.name = repeat('x', 300);
        inner.value = 7;
        FrameNestedMessage message = new FrameNestedMessage();
        message.simple = inner;
        message.blob = new byte[]{1, 2, 3, 4, 5};
        assertSameWire(FrameNestedMessage.class, message);
    }

    /**
     * 嵌套消息：长度前缀的 varint 是 3 字节（内容 &gt; 16383 字节）。
     */
    @Test
    public void testNestedMessageOver16KB() throws IOException {
        FrameSimpleMessage inner = new FrameSimpleMessage();
        inner.name = repeat('y', 20000);
        inner.value = 9;
        FrameNestedMessage message = new FrameNestedMessage();
        message.simple = inner;
        message.blob = new byte[0];
        assertSameWire(FrameNestedMessage.class, message);
    }

    /**
     * 大负载：&gt; 8192 字节（编码缓冲的初始容量）⇒ 编码途中必然扩容换数组。
     */
    @Test
    public void testLargePayloadForcesBufferGrowth() throws IOException {
        FrameSimpleMessage message = new FrameSimpleMessage();
        message.name = repeat('z', 60000);
        message.value = 1;
        byte[] expected = legacy(FrameSimpleMessage.class, message);
        byte[] actual = encode(FrameSimpleMessage.class, message);
        assertTrue(expected.length > 8192, "这个用例必须超过编码缓冲的初始容量");
        assertArrayEquals(expected, actual, "扩容换数组之后字节不一致");
    }

    /**
     * 字符串 &gt; 3072 字符 ⇒ {@code writeString0} 的长串分支（分段写 + memmove 补长度前缀）。
     */
    @Test
    public void testLongAsciiString() throws IOException {
        FrameSimpleMessage message = new FrameSimpleMessage();
        message.name = repeat('a', 4000);
        assertSameWire(FrameSimpleMessage.class, message);
    }

    /**
     * 字符串 &gt; 3072 字符且非 Latin1（中文）⇒ 走 UTF-16 分支的 memmove 路径。
     */
    @Test
    public void testLongNonLatin1String() throws IOException {
        FrameSimpleMessage message = new FrameSimpleMessage();
        message.name = repeat('中', 4000);
        assertSameWire(FrameSimpleMessage.class, message);
    }

    /**
     * 短的非 Latin1 字符串 ⇒ {@code writeString0} 的短串分支（预留 1 字节长度前缀）。
     */
    @Test
    public void testShortNonLatin1String() throws IOException {
        FrameSimpleMessage message = new FrameSimpleMessage();
        message.name = "中文内容";
        assertSameWire(FrameSimpleMessage.class, message);
    }

    /**
     * Latin1 高位字符（'é' = U+00E9）⇒ 必须编码成合法的 UTF-8（0xC3 0xA9）。
     */
    @Test
    public void testLatin1HighByteString() throws IOException {
        FrameSimpleMessage message = new FrameSimpleMessage();
        message.name = "café";
        assertSameWire(FrameSimpleMessage.class, message);

        byte[] frame = encode(FrameSimpleMessage.class, message);
        byte[] payload = Arrays.copyOfRange(frame, GrpcFrame.HEADER_LENGTH, frame.length);
        // tag(1) + len(1) + 'c' + 'a' + 'f' + 0xC3 + 0xA9
        assertEquals(0x0A, payload[0] & 0xFF);
        assertEquals(5, payload[1] & 0xFF);
        assertEquals((byte) 0xC3, payload[5]);
        assertEquals((byte) 0xA9, payload[6]);
    }

    /**
     * 帧头本身：压缩标志为 0，长度是大端的载荷长度。
     */
    @Test
    public void testFrameHeaderShape() throws IOException {
        FrameSimpleMessage message = new FrameSimpleMessage();
        message.name = "edap";
        byte[] frame = encode(FrameSimpleMessage.class, message);
        assertEquals(0, frame[0], "压缩标志必须是 0（identity）");
        int payloadLength = GrpcFrame.decodeLength(frame, 0);
        assertEquals(frame.length - GrpcFrame.HEADER_LENGTH, payloadLength);
    }

    /**
     * 帧能被解码回去（端到端自洽）。
     */
    @Test
    public void testRoundTrip() throws IOException {
        FrameSimpleMessage message = new FrameSimpleMessage();
        message.name = "edap-grpc";
        message.value = 123;

        byte[] frame = encode(FrameSimpleMessage.class, message);
        byte[] payload = Arrays.copyOfRange(frame, GrpcFrame.HEADER_LENGTH, frame.length);
        FrameSimpleMessage decoded = GrpcCodec.decode(FrameSimpleMessage.class, payload, 0, payload.length);
        assertEquals("edap-grpc", decoded.name);
        assertEquals(123, decoded.value);
    }

    @Test
    public void testNullMessage() {
        assertThrows(GrpcException.class, () -> GrpcFrameEncoder.encode(FrameSimpleMessage.class, null,
                (buf, offset, length) -> { }));
    }

    /**
     * 私有 wire type（OBJECT）必须在编码前就被拒绝。
     */
    @Test
    public void testRejectObjectField() {
        assertThrows(GrpcException.class, () -> GrpcFrameEncoder.encode(FrameObjectMessage.class, new FrameObjectMessage(),
                (buf, offset, length) -> { }));
    }

    /**
     * sink 抛出的 IOException 要原样透传。
     */
    @Test
    public void testSinkIOExceptionPropagates() {
        assertThrows(IOException.class, () -> GrpcFrameEncoder.encode(FrameSimpleMessage.class, new FrameSimpleMessage(),
                (buf, offset, length) -> {
                    throw new IOException("boom");
                }));
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
    }
}
