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

import io.edap.protobuf.wire.WireFormat;
import io.edap.protobuf.wire.WireType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 测试WireFormat的逻辑
 */
public class TestWireFormat {

    @Test
    void testJavaType() {

        WireFormat.JavaType iType = WireFormat.JavaType.INT;
        assertEquals("Integer", iType.getTypeString());

        WireFormat.JavaType lType = WireFormat.JavaType.LONG;
        assertEquals("Long", lType.getTypeString());

        WireFormat.JavaType fType = WireFormat.JavaType.FLOAT;
        assertEquals("Float", fType.getTypeString());

        WireFormat.JavaType dType = WireFormat.JavaType.DOUBLE;
        assertEquals("Double", dType.getTypeString());

        WireFormat.JavaType bType = WireFormat.JavaType.BOOLEAN;
        assertEquals("Boolean", bType.getTypeString());

        WireFormat.JavaType sType = WireFormat.JavaType.STRING;
        assertEquals("String", sType.getTypeString());

        WireFormat.JavaType bsType = WireFormat.JavaType.BYTES;
        assertEquals("byte[]", bsType.getTypeString());

        WireFormat.JavaType eType = WireFormat.JavaType.ENUM;
        assertEquals("enum", eType.getTypeString());

        WireFormat.JavaType mType = WireFormat.JavaType.MESSAGE;
        assertEquals("", mType.getTypeString());

        WireFormat.JavaType mapType = WireFormat.JavaType.MAP;
        assertEquals("Map", mapType.getTypeString());
    }

    @Test
    void testTag() {
        int tag = WireFormat.makeTag(1, WireType.VARINT);
        assertEquals(8, tag);

        int fieldNum = WireFormat.getTagFieldNumber(tag);
        assertEquals(1, fieldNum);

        int wireType = WireFormat.getTagWireType(tag);
        assertEquals(WireType.VARINT.getValue(), wireType);
    }
}