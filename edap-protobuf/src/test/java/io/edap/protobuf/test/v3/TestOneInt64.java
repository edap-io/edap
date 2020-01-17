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
import io.edap.protobuf.test.message.v3.OneInt64;
import io.edap.protobuf.test.message.v3.OneInt64OuterClass;
import io.edap.protobuf.test.message.v3.OneInt64Unboxed;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestOneInt64 {

    @ParameterizedTest
    @ValueSource(longs = {
            1,
            128,
            -1,
            -129,
            5671506337319861521L
    })
    void testEncode(long value) {

        OneInt64OuterClass.OneInt64.Builder builder = OneInt64OuterClass.OneInt64.newBuilder();
        builder.setValue(value);
        OneInt64OuterClass.OneInt64 oi64 = builder.build();
        byte[] pb = oi64.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneInt64 oneInt64 = new OneInt64();
        oneInt64.setValue(value);
        byte[] epb = ProtoBuf.toByteArray(oneInt64);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(longs = {
            1,
            128,
            -1,
            -128,
            5671506337319861521L
    })
    void testDecode(long value) throws InvalidProtocolBufferException, ProtoBufException {

        OneInt64OuterClass.OneInt64.Builder builder = OneInt64OuterClass.OneInt64.newBuilder();
        builder.setValue(value);
        OneInt64OuterClass.OneInt64 oint64 = builder.build();
        byte[] pb = oint64.toByteArray();


        OneInt64OuterClass.OneInt64 pbOf = OneInt64OuterClass.OneInt64.parseFrom(pb);

        OneInt64 oneInt64 = ProtoBuf.toObject(pb, OneInt64.class);


        assertEquals(pbOf.getValue(), oneInt64.getValue());

    }

    @ParameterizedTest
    @ValueSource(longs = {
            1,
            128,
            -1,
            -129,
            5671506337319861521L
    })
    void testEncodeUnboxed(long value) {

        OneInt64OuterClass.OneInt64.Builder builder = OneInt64OuterClass.OneInt64.newBuilder();
        builder.setValue(value);
        OneInt64OuterClass.OneInt64 oi64 = builder.build();
        byte[] pb = oi64.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneInt64Unboxed oneInt64 = new OneInt64Unboxed();
        oneInt64.value = value;
        byte[] epb = ProtoBuf.toByteArray(oneInt64);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(longs = {
            1,
            128,
            -1,
            -128,
            5671506337319861521L
    })
    void testDecodeUnboxed(long value) throws InvalidProtocolBufferException, ProtoBufException {

        OneInt64OuterClass.OneInt64.Builder builder = OneInt64OuterClass.OneInt64.newBuilder();
        builder.setValue(value);
        OneInt64OuterClass.OneInt64 oint64 = builder.build();
        byte[] pb = oint64.toByteArray();


        OneInt64OuterClass.OneInt64 pbOf = OneInt64OuterClass.OneInt64.parseFrom(pb);

        OneInt64Unboxed oneInt64 = ProtoBuf.toObject(pb, OneInt64Unboxed.class);


        assertEquals(pbOf.getValue(), oneInt64.value);

    }
}
