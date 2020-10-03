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

import io.edap.protobuf.wire.WireType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试WireType的操作逻辑
 */
public class TestWireType {

    @Test
    void testFromValue() {
        WireType wireType;
        wireType = WireType.fromValue(0);
        assertEquals(WireType.VARINT, wireType);

        wireType = WireType.fromValue(1);
        assertEquals(WireType.FIXED64, wireType);

        wireType = WireType.fromValue(2);
        assertEquals(WireType.LENGTH_DELIMITED, wireType);

        wireType = WireType.fromValue(3);
        assertEquals(WireType.START_GROUP, wireType);

        wireType = WireType.fromValue(4);
        assertEquals(WireType.END_GROUP, wireType);

        wireType = WireType.fromValue(5);
        assertEquals(WireType.FIXED32, wireType);

        wireType = WireType.fromValue(6);
        assertEquals(WireType.OBJECT, wireType);

        WireType varIntType = WireType.VARINT;
        assertEquals(0, varIntType.getValue());
    }

    @ParameterizedTest
    @ValueSource(ints = {7,8,10, 200})
    void testWireTypeWrongValue(int v) {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> {
                    WireType.fromValue(v);
                });
        assertTrue(thrown.getMessage().contains("no enum value WireType"));
    }
}
