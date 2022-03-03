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

package io.edap.protobuf.wire.test;

import io.edap.protobuf.wire.Field;
import io.edap.protobuf.wire.WireFormat;
import io.edap.protobuf.wire.WireType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 测试Field的内部类
 */
public class TestFieldInnerClazz {

    @Test
    void testFieldType() {
        Field.Type boolType = Field.Type.BOOL;
        assertEquals(WireFormat.JavaType.BOOLEAN, boolType.javaType());
        assertEquals(WireType.VARINT, boolType.wireType());
        assertTrue(boolType.packable());

        Field.Type bytesType = Field.Type.BYTES;
        assertEquals(WireFormat.JavaType.BYTES, bytesType.javaType());
        assertEquals(WireType.LENGTH_DELIMITED, bytesType.wireType());
        assertFalse(bytesType.packable());

        Field.Type doubleType = Field.Type.DOUBLE;
        assertEquals(WireFormat.JavaType.DOUBLE, doubleType.javaType());
        assertEquals(WireType.FIXED64, doubleType.wireType());
        assertTrue(doubleType.packable());

        Field.Type enumType = Field.Type.ENUM;
        assertEquals(WireFormat.JavaType.ENUM, enumType.javaType());
        assertEquals(WireType.VARINT, enumType.wireType());
        assertTrue(enumType.packable());

        Field.Type fixed32Type = Field.Type.FIXED32;
        assertEquals(WireFormat.JavaType.INT, fixed32Type.javaType());
        assertEquals(WireType.FIXED32, fixed32Type.wireType());
        assertTrue(fixed32Type.packable());

        Field.Type fixed64Type = Field.Type.FIXED64;
        assertEquals(WireFormat.JavaType.LONG, fixed64Type.javaType());
        assertEquals(WireType.FIXED64, fixed64Type.wireType());
        assertTrue(fixed64Type.packable());

        Field.Type floatType = Field.Type.FLOAT;
        assertEquals(WireFormat.JavaType.FLOAT, floatType.javaType());
        assertEquals(WireType.FIXED32, floatType.wireType());
        assertTrue(floatType.packable());

        Field.Type groupType = Field.Type.GROUP;
        assertEquals(WireFormat.JavaType.MESSAGE, groupType.javaType());
        assertEquals(WireType.START_GROUP, groupType.wireType());
        assertFalse(groupType.packable());

        Field.Type int32Type = Field.Type.INT32;
        assertEquals(WireFormat.JavaType.INT, int32Type.javaType());
        assertEquals(WireType.VARINT, int32Type.wireType());
        assertTrue(int32Type.packable());

        Field.Type int64Type = Field.Type.INT64;
        assertEquals(WireFormat.JavaType.LONG, int64Type.javaType());
        assertEquals(WireType.VARINT, int64Type.wireType());
        assertTrue(int64Type.packable());

        Field.Type mapType = Field.Type.MAP;
        assertEquals(WireFormat.JavaType.MAP, mapType.javaType());
        assertEquals(WireType.LENGTH_DELIMITED, mapType.wireType());
        assertFalse(mapType.packable());

        Field.Type msgType = Field.Type.MESSAGE;
        assertEquals(WireFormat.JavaType.MESSAGE, msgType.javaType());
        assertEquals(WireType.LENGTH_DELIMITED, msgType.wireType());
        assertFalse(msgType.packable());

        Field.Type sfixed32Type = Field.Type.SFIXED32;
        assertEquals(WireFormat.JavaType.INT, sfixed32Type.javaType());
        assertEquals(WireType.FIXED32, sfixed32Type.wireType());
        assertTrue(sfixed32Type.packable());

        Field.Type sfixed64Type = Field.Type.SFIXED64;
        assertEquals(WireFormat.JavaType.LONG, sfixed64Type.javaType());
        assertEquals(WireType.FIXED64, sfixed64Type.wireType());
        assertTrue(sfixed64Type.packable());

        Field.Type sint32Type = Field.Type.SINT32;
        assertEquals(WireFormat.JavaType.INT, sint32Type.javaType());
        assertEquals(WireType.VARINT, sint32Type.wireType());
        assertTrue(sint32Type.packable());

        Field.Type sint64Type = Field.Type.SINT64;
        assertEquals(WireFormat.JavaType.LONG, sint64Type.javaType());
        assertEquals(WireType.VARINT, sint64Type.wireType());
        assertTrue(sint64Type.packable());

        Field.Type stringType = Field.Type.STRING;
        assertEquals(WireFormat.JavaType.STRING, stringType.javaType());
        assertEquals( WireType.LENGTH_DELIMITED, stringType.wireType());
        assertFalse(stringType.packable());

        Field.Type uint32Type = Field.Type.UINT32;
        assertEquals(WireFormat.JavaType.INT, uint32Type.javaType());
        assertEquals(WireType.VARINT, uint32Type.wireType());
        assertTrue(uint32Type.packable());

        Field.Type uint64Type = Field.Type.UINT64;
        assertEquals(WireFormat.JavaType.LONG, uint64Type.javaType());
        assertEquals(WireType.VARINT, uint64Type.wireType());
        assertTrue(uint64Type.packable());

        Field.Type anyType = Field.Type.OBJECT;
        assertEquals(WireFormat.JavaType.OBJECT, anyType.javaType());
        assertEquals(WireType.LENGTH_DELIMITED, anyType.wireType());
        assertFalse(anyType.packable());
    }

    @Test
    void testCardinality() {
        Field.Cardinality optional = Field.Cardinality.OPTIONAL;
        assertEquals("optional", optional.getValue());

        Field.Cardinality repeated = Field.Cardinality.REPEATED;
        assertEquals("repeated", repeated.getValue());

        Field.Cardinality required = Field.Cardinality.REQUIRED;
        assertEquals("required", required.getValue());

        Field.Cardinality unkown = Field.Cardinality.UNKNOWN;
        assertEquals("unknown", unkown.getValue());
    }
}
