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

package io.edap.erpc.codec;

import io.edap.erpc.codec.json.JsonErpcCodec;
import io.edap.erpc.codec.protobuf.ProtobufErpcCodec;
import io.edap.erpc.frame.ErpcFrameHeader;
import io.edap.erpc.frame.ErpcFrameType;
import io.edap.json.Eson;
import io.edap.protobuf.ProtoBuf;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 帧编码器的测试。
 *
 * <p>★ 关键不变量：</p>
 * <ol>
 *   <li><b>载荷字节不变</b>：帧编码器交出的载荷必须与「直接用底层框架编码」的字节
 *       逐字节相同 ⇒ 线上载荷格式不因重构而变；</li>
 *   <li><b>载荷从偏移 0 开始</b>：不再有 32 字节的帧头占位（旧实现里那 32 字节
 *       从头到尾没被写入任何内容，纯空转）；</li>
 *   <li><b>帧头字段与载荷长度一致</b>：{@code header.length == 载荷长度}。</li>
 * </ol>
 */
public class ErpcFrameEncoderTest {

    /** 测试用的业务 DTO（不带协议注解）。 */
    public static class Dto {
        private String name;
        private int    value;

        public Dto() {
        }

        public Dto(String name, int value) {
            this.name = name;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getValue() {
            return value;
        }

        public void setValue(int value) {
            this.value = value;
        }
    }

    /** 收到的帧。 */
    private static final class Captured {
        byte[] payload;
        int    length;
        ErpcFrameHeader header;
    }

    private Captured capture(ErpcCodec codec, Object message) {
        Captured c = new Captured();
        ErpcFrameEncoder.encode(codec, ErpcFrameType.RESPONSE.getValue(), codec.id(),
            11, 22, 33, 44L, message, (header, payload, length) -> {
                c.header = header;
                c.payload = payload;
                c.length = length;
            });
        return c;
    }

    @Test
    public void protobufPayloadMatchesProtoBufToByteArray() {
        ProtobufErpcCodec codec = new ProtobufErpcCodec();
        Dto dto = new Dto("hello", 21);
        byte[] expected = ProtoBuf.toByteArray(dto);

        Captured c = capture(codec, dto);

        assertEquals(expected.length, c.length, "载荷长度必须与底层框架一致");
        byte[] actual = Arrays.copyOf(c.payload, c.length);
        assertArrayEquals(expected, actual, "载荷字节必须与 ProtoBuf.toByteArray 逐字节相同");
    }

    @Test
    public void jsonPayloadMatchesEsonToJsonBytes() {
        JsonErpcCodec codec = new JsonErpcCodec();
        Dto dto = new Dto("hello", 21);
        byte[] expected = Eson.toJsonBytes(dto);

        Captured c = capture(codec, dto);

        assertEquals(expected.length, c.length, "载荷长度必须与底层框架一致");
        byte[] actual = Arrays.copyOf(c.payload, c.length);
        assertArrayEquals(expected, actual, "载荷字节必须与 Eson.toJsonBytes 逐字节相同");
    }

    @Test
    public void payloadStartsAtOffsetZero() {
        // ★ 旧实现里载荷前面有 32 字节的帧头占位（空转）⇒ 现在必须从 0 开始
        ProtobufErpcCodec codec = new ProtobufErpcCodec();
        Captured c = capture(codec, new Dto("x", 1));

        assertEquals(c.length, c.header.getLength(), "帧头里的长度必须等于载荷长度");
        assertTrue(c.length > 0, "这个用例的载荷不该是空的");
    }

    @Test
    public void headerFieldsAreFilledIn() {
        ProtobufErpcCodec codec = new ProtobufErpcCodec();
        Captured c = capture(codec, new Dto("x", 1));

        assertEquals(ErpcFrameType.RESPONSE.getValue(), c.header.getType());
        assertEquals(codec.id(), c.header.getCodec());
        assertEquals(11, c.header.getMethodId());
        assertEquals(22, c.header.getServiceId());
        assertEquals(33, c.header.getRequestId());
        assertEquals(44L, c.header.getTraceId());
        assertEquals(c.length, c.header.getLength(), "帧头里的长度必须等于载荷长度");
    }

    @Test
    public void nullMessageProducesAnEmptyPayload() {
        ProtobufErpcCodec codec = new ProtobufErpcCodec();
        Captured c = capture(codec, null);

        assertEquals(0, c.length, "null 消息没有载荷");
        assertEquals(0, c.header.getLength());
    }

    @Test
    public void borrowedBufferIsReadableInsideTheSink() {
        // 载荷缓冲是「借来的」：必须在 sink 返回前拷走，但拷走之前是可读的
        ProtobufErpcCodec codec = new ProtobufErpcCodec();
        Dto dto = new Dto("borrowed", 7);
        byte[] expected = ProtoBuf.toByteArray(dto);

        final byte[][] seen = new byte[1][];
        ErpcFrameEncoder.encode(codec, ErpcFrameType.REQUEST.getValue(), codec.id(),
            1, 2, 3, 4L, dto, (header, payload, length) -> {
                seen[0] = Arrays.copyOf(payload, length);
            });
        assertArrayEquals(expected, seen[0]);
    }

    @Test
    public void flagsAreWrittenIntoTheHeader() {
        // ★ 超时代码要真的进到帧头里（bit1~bit7）
        ProtobufErpcCodec codec = new ProtobufErpcCodec();
        final int[] flagsSeen = new int[1];
        ErpcFrameEncoder.encode(codec, ErpcFrameType.REQUEST.getValue(), codec.id(),
            1, 2, 3, 4L, 0x7E, new Dto("x", 1), (header, payload, length) -> {
                flagsSeen[0] = header.getFlags();
            });
        assertEquals(0x7E, flagsSeen[0]);
    }

    @Test
    public void legacyOverloadWritesZeroFlags() {
        // 老签名（没有 flags 参数）必须写 0 —— 否则上一帧的超时码会漏到下一帧
        ProtobufErpcCodec codec = new ProtobufErpcCodec();
        final int[] flagsSeen = new int[1];
        ErpcFrameEncoder.encode(codec, ErpcFrameType.REQUEST.getValue(), codec.id(),
            1, 2, 3, 4L, new Dto("x", 1), (header, payload, length) -> {
                flagsSeen[0] = header.getFlags();
            });
        assertEquals(0, flagsSeen[0]);
    }
}
