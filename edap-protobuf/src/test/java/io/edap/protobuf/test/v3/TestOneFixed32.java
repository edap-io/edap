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

package io.edap.protobuf.test.v3;

import com.google.protobuf.InvalidProtocolBufferException;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoBufException;
import io.edap.protobuf.test.message.v3.OneFixed32;
import io.edap.protobuf.test.message.v3.OneFixed32OuterClass;
import io.edap.protobuf.test.message.v3.OneFixed32Unboxed;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestOneFixed32 {
    @ParameterizedTest
    @ValueSource(ints = {
            1,
            128,
            -1,
            -129
    })
    void testEncode(int value) {

        OneFixed32OuterClass.OneFixed32.Builder builder = OneFixed32OuterClass.OneFixed32.newBuilder();
        builder.setValue(value);
        OneFixed32OuterClass.OneFixed32 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneFixed32 oneFixed32 = new OneFixed32();
        oneFixed32.setValue(value);
        byte[] epb = ProtoBuf.toByteArray(oneFixed32);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(ints = {
            1,
            128,
            -1,
            -128
    })
    void testDecode(int value) throws InvalidProtocolBufferException, ProtoBufException {

        OneFixed32OuterClass.OneFixed32.Builder builder = OneFixed32OuterClass.OneFixed32.newBuilder();
        builder.setValue(value);
        OneFixed32OuterClass.OneFixed32 oFixed32 = builder.build();
        byte[] pb = oFixed32.toByteArray();


        OneFixed32OuterClass.OneFixed32 pbOf = OneFixed32OuterClass.OneFixed32.parseFrom(pb);

        OneFixed32 oneFixed32 = ProtoBuf.toObject(pb, OneFixed32.class);


        assertEquals(pbOf.getValue(), oneFixed32.getValue());

    }

    @ParameterizedTest
    @ValueSource(ints = {
            1,
            128,
            -1,
            -129
    })
    void testEncodeUnboxed(int value) {

        OneFixed32OuterClass.OneFixed32.Builder builder = OneFixed32OuterClass.OneFixed32.newBuilder();
        builder.setValue(value);
        OneFixed32OuterClass.OneFixed32 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneFixed32Unboxed oneFixed32 = new OneFixed32Unboxed();
        oneFixed32.value = value;
        byte[] epb = ProtoBuf.toByteArray(oneFixed32);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(ints = {
            1,
            128,
            -1,
            -128
    })
    void testDecodeUnboxed(int value) throws InvalidProtocolBufferException, ProtoBufException {

        OneFixed32OuterClass.OneFixed32.Builder builder = OneFixed32OuterClass.OneFixed32.newBuilder();
        builder.setValue(value);
        OneFixed32OuterClass.OneFixed32 oFixed32 = builder.build();
        byte[] pb = oFixed32.toByteArray();


        OneFixed32OuterClass.OneFixed32 pbOf = OneFixed32OuterClass.OneFixed32.parseFrom(pb);

        OneFixed32Unboxed oneFixed32 = ProtoBuf.toObject(pb, OneFixed32Unboxed.class);


        assertEquals(pbOf.getValue(), oneFixed32.value);

    }
}
