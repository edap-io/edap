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

package io.edap.protobuf.test.jtype;

import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.test.message.jtype.OneShort;
import io.edap.protobuf.test.message.v3.OneInt32OuterClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class TestOneShort {

    @ParameterizedTest
    @ValueSource(ints = {
            0,
            1,
            129,
            16385,
            -1,
            -129
    })
    void testEncode(int value) throws EncodeException {

        OneInt32OuterClass.OneInt32.Builder builder = OneInt32OuterClass.OneInt32.newBuilder();
        builder.setValue(value);
        OneInt32OuterClass.OneInt32 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneShort oneShort = new OneShort();
        oneShort.setField1((short)value);
        byte[] epb = ProtoBuf.toByteArray(oneShort);

        assertArrayEquals(pb, epb);
    }
}
