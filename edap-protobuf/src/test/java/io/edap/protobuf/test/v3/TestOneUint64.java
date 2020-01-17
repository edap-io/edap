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
import io.edap.protobuf.test.message.v3.OneUint64;
import io.edap.protobuf.test.message.v3.OneUint64OuterClass;
import io.edap.protobuf.test.message.v3.OneUint64Unboxed;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestOneUint64 {

    @ParameterizedTest
    @ValueSource(longs = {
            1,
            128,
            2147483648L
    })
    void testEncode(long value) {

        OneUint64OuterClass.OneUint64.Builder builder = OneUint64OuterClass.OneUint64.newBuilder();
        builder.setValue(value);
        OneUint64OuterClass.OneUint64 oi64 = builder.build();
        byte[] pb = oi64.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneUint64 oneUint64 = new OneUint64();
        oneUint64.setValue(value);
        byte[] epb = ProtoBuf.toByteArray(oneUint64);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(longs = {
            1,
            128,
            2147483648L
    })
    void testDecode(long value) throws InvalidProtocolBufferException, ProtoBufException {

        OneUint64OuterClass.OneUint64.Builder builder = OneUint64OuterClass.OneUint64.newBuilder();
        builder.setValue(value);
        OneUint64OuterClass.OneUint64 oint64 = builder.build();
        byte[] pb = oint64.toByteArray();


        OneUint64OuterClass.OneUint64 pbOf = OneUint64OuterClass.OneUint64.parseFrom(pb);

        OneUint64 oneUint64 = ProtoBuf.toObject(pb, OneUint64.class);


        assertEquals(pbOf.getValue(), oneUint64.getValue());

    }

    @ParameterizedTest
    @ValueSource(longs = {
            1,
            128,
            2147483648L
    })
    void testEncodeUnboxed(long value) {

        OneUint64OuterClass.OneUint64.Builder builder = OneUint64OuterClass.OneUint64.newBuilder();
        builder.setValue(value);
        OneUint64OuterClass.OneUint64 oi64 = builder.build();
        byte[] pb = oi64.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneUint64Unboxed oneUint64 = new OneUint64Unboxed();
        oneUint64.value = value;
        byte[] epb = ProtoBuf.toByteArray(oneUint64);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(longs = {
            1,
            128,
            2147483648L
    })
    void testDecodeUnboxed(long value) throws InvalidProtocolBufferException, ProtoBufException {

        OneUint64OuterClass.OneUint64.Builder builder = OneUint64OuterClass.OneUint64.newBuilder();
        builder.setValue(value);
        OneUint64OuterClass.OneUint64 oint64 = builder.build();
        byte[] pb = oint64.toByteArray();


        OneUint64OuterClass.OneUint64 pbOf = OneUint64OuterClass.OneUint64.parseFrom(pb);

        OneUint64Unboxed oneUint64 = ProtoBuf.toObject(pb, OneUint64Unboxed.class);


        assertEquals(pbOf.getValue(), oneUint64.value);

    }
}
