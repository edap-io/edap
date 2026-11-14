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
import io.edap.protobuf.annotation.ProtoField;
import io.edap.protobuf.wire.Field;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class GrpcCodecTest {

    public static class SimpleMessage {
        @ProtoField(tag = 1, type = Field.Type.STRING)
        public String name;

        @ProtoField(tag = 2, type = Field.Type.INT32)
        public int value;
    }

    public static class NestedMessage {
        @ProtoField(tag = 1, type = Field.Type.MESSAGE)
        public SimpleMessage simple;
    }

    public static class ObjectMessage {
        @ProtoField(tag = 1, type = Field.Type.OBJECT)
        public Object data;
    }

    public static class AutoObjectMessage {
        public Object data;
    }

    public static class MapObjectMessage {
        @ProtoField(tag = 1, type = Field.Type.MAP)
        public Map<String, Object> values;
    }

    public static class NestedObjectMessage {
        @ProtoField(tag = 1, type = Field.Type.MESSAGE)
        public ObjectMessage nested;
    }

    @Test
    public void testRoundTrip() {
        SimpleMessage message = new SimpleMessage();
        message.name = "edap";
        message.value = 42;

        byte[] data = GrpcCodec.encode(SimpleMessage.class, message);
        assertNotNull(data);

        SimpleMessage decoded = GrpcCodec.decode(SimpleMessage.class, data, 0, data.length);
        assertEquals("edap", decoded.name);
        assertEquals(42, decoded.value);
    }

    @Test
    public void testRoundTripNested() {
        SimpleMessage simple = new SimpleMessage();
        simple.name = "grpc";
        simple.value = 7;
        NestedMessage message = new NestedMessage();
        message.simple = simple;

        byte[] data = GrpcCodec.encode(NestedMessage.class, message);
        NestedMessage decoded = GrpcCodec.decode(NestedMessage.class, data, 0, data.length);
        assertEquals("grpc", decoded.simple.name);
        assertEquals(7, decoded.simple.value);
    }

    @Test
    public void testDecodeWithOffset() {
        SimpleMessage message = new SimpleMessage();
        message.name = "offset";
        byte[] data = GrpcCodec.encode(SimpleMessage.class, message);

        byte[] buffer = new byte[data.length + 3];
        System.arraycopy(data, 0, buffer, 3, data.length);
        SimpleMessage decoded = GrpcCodec.decode(SimpleMessage.class, buffer, 3, data.length);
        assertEquals("offset", decoded.name);
    }

    /**
     * 编码结果必须是标准的 protobuf 字节：字段 1 是 string，tag 为 0x0A。
     */
    @Test
    public void testWireFormatIsStandard() {
        SimpleMessage message = new SimpleMessage();
        message.name = "a";
        byte[] data = GrpcCodec.encode(SimpleMessage.class, message);
        assertEquals(0x0A, data[0] & 0xFF);
        assertEquals(1, data[1] & 0xFF);
        assertEquals('a', data[2] & 0xFF);
    }

    @Test
    public void testRejectObjectField() {
        assertThrows(GrpcException.class, () -> GrpcCodec.encode(ObjectMessage.class, new ObjectMessage()));
    }

    @Test
    public void testRejectAutoObjectField() {
        assertThrows(GrpcException.class, () -> GrpcCodec.encode(AutoObjectMessage.class, new AutoObjectMessage()));
    }

    @Test
    public void testRejectMapWithObjectValue() {
        assertThrows(GrpcException.class, () -> GrpcCodec.encode(MapObjectMessage.class, new MapObjectMessage()));
    }

    @Test
    public void testRejectNestedObjectField() {
        assertThrows(GrpcException.class, () -> GrpcCodec.encode(NestedObjectMessage.class, new NestedObjectMessage()));
    }

    @Test
    public void testEncodeNullMessage() {
        assertThrows(GrpcException.class, () -> GrpcCodec.encode(SimpleMessage.class, null));
    }

    @Test
    public void testDecodeInvalidData() {
        byte[] data = new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        assertThrows(GrpcException.class, () -> GrpcCodec.decode(SimpleMessage.class, data, 0, data.length));
    }

    @Test
    public void testValidateIsIdempotent() {
        GrpcCodec.validate(SimpleMessage.class);
        GrpcCodec.validate(SimpleMessage.class);
    }
}
