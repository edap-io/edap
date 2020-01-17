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
import io.edap.protobuf.test.message.v3.OneFixed64;
import io.edap.protobuf.test.message.v3.OneFixed64OuterClass;
import io.edap.protobuf.test.message.v3.OneFixed64Unboxed;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestOneFixed64 {
    @ParameterizedTest
    @ValueSource(longs = {
            1,
            128,
            2147483648L
    })
    void testEncode(long value) {

        OneFixed64OuterClass.OneFixed64.Builder builder = OneFixed64OuterClass.OneFixed64.newBuilder();
        builder.setValue(value);
        OneFixed64OuterClass.OneFixed64 oi64 = builder.build();
        byte[] pb = oi64.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneFixed64 oneFixed64 = new OneFixed64();
        oneFixed64.setValue(value);
        byte[] epb = ProtoBuf.toByteArray(oneFixed64);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(longs = {
            1,
            128,
            2147483648L
    })
    void testDecode(long value) throws InvalidProtocolBufferException, ProtoBufException {

        OneFixed64OuterClass.OneFixed64.Builder builder = OneFixed64OuterClass.OneFixed64.newBuilder();
        builder.setValue(value);
        OneFixed64OuterClass.OneFixed64 oint64 = builder.build();
        byte[] pb = oint64.toByteArray();


        OneFixed64OuterClass.OneFixed64 pbOf = OneFixed64OuterClass.OneFixed64.parseFrom(pb);

        OneFixed64 oneFixed64 = ProtoBuf.toObject(pb, OneFixed64.class);


        assertEquals(pbOf.getValue(), oneFixed64.getValue());

    }

    @ParameterizedTest
    @ValueSource(longs = {
            1,
            128,
            2147483648L
    })
    void testEncodeUnboxed(long value) {

        OneFixed64OuterClass.OneFixed64.Builder builder = OneFixed64OuterClass.OneFixed64.newBuilder();
        builder.setValue(value);
        OneFixed64OuterClass.OneFixed64 oi64 = builder.build();
        byte[] pb = oi64.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneFixed64Unboxed oneFixed64 = new OneFixed64Unboxed();
        oneFixed64.value = value;
        byte[] epb = ProtoBuf.toByteArray(oneFixed64);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(longs = {
            1,
            128,
            2147483648L
    })
    void testDecodeUnboxed(long value) throws InvalidProtocolBufferException, ProtoBufException {

        OneFixed64OuterClass.OneFixed64.Builder builder = OneFixed64OuterClass.OneFixed64.newBuilder();
        builder.setValue(value);
        OneFixed64OuterClass.OneFixed64 oint64 = builder.build();
        byte[] pb = oint64.toByteArray();


        OneFixed64OuterClass.OneFixed64 pbOf = OneFixed64OuterClass.OneFixed64.parseFrom(pb);

        OneFixed64Unboxed oneFixed64 = ProtoBuf.toObject(pb, OneFixed64Unboxed.class);


        assertEquals(pbOf.getValue(), oneFixed64.value);

    }
}
