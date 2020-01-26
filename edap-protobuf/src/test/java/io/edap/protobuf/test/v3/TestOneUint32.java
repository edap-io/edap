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
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoBufException;
import io.edap.protobuf.test.message.v3.*;
import io.edap.protobuf.wire.Proto;
import io.edap.util.ClazzUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestOneUint32 {

    @ParameterizedTest
    @ValueSource(ints = {
            1,
            131,
            -1
    })
    void testEncode(int value) throws EncodeException {

        OneUint32OuterClass.OneUint32.Builder builder = OneUint32OuterClass.OneUint32.newBuilder();
        builder.setValue(value);
        OneUint32OuterClass.OneUint32 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneUint32 oneUint32 = new OneUint32();
        oneUint32.setValue(value);
        byte[] epb = ProtoBuf.toByteArray(oneUint32);
        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(ints = {
            1,
            128,
    })
    void testDecode(int value) throws InvalidProtocolBufferException, ProtoBufException {

        OneUint32OuterClass.OneUint32.Builder builder = OneUint32OuterClass.OneUint32.newBuilder();
        builder.setValue(value);
        OneUint32OuterClass.OneUint32 oint32 = builder.build();
        byte[] pb = oint32.toByteArray();


        OneUint32OuterClass.OneUint32 pbOf = OneUint32OuterClass.OneUint32.parseFrom(pb);

        OneUint32 oneUint32 = ProtoBuf.toObject(pb, OneUint32.class);


        assertEquals(pbOf.getValue(), oneUint32.getValue());

    }

    @ParameterizedTest
    @ValueSource(ints = {
            1,
            131,
    })
    void testEncodeUnboxed(int value) throws EncodeException {

        OneUint32OuterClass.OneUint32.Builder builder = OneUint32OuterClass.OneUint32.newBuilder();
        builder.setValue(value);
        OneUint32OuterClass.OneUint32 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneUint32Unboxed oneUint32 = new OneUint32Unboxed();
        oneUint32.value = value;
        byte[] epb = ProtoBuf.toByteArray(oneUint32);
        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(ints = {
            1,
            128,
    })
    void testDecodeUnboxed(int value) throws InvalidProtocolBufferException, ProtoBufException {

        OneUint32OuterClass.OneUint32.Builder builder = OneUint32OuterClass.OneUint32.newBuilder();
        builder.setValue(value);
        OneUint32OuterClass.OneUint32 oint32 = builder.build();
        byte[] pb = oint32.toByteArray();


        OneUint32OuterClass.OneUint32 pbOf = OneUint32OuterClass.OneUint32.parseFrom(pb);

        OneUint32Unboxed oneUint32 = ProtoBuf.toObject(pb, OneUint32Unboxed.class);


        assertEquals(pbOf.getValue(), oneUint32.value);

    }

    @ParameterizedTest
    @ValueSource(ints = {
            1,
            131,
    })
    void testEncodeNoAccess(int value) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        OneUint32OuterClass.OneUint32.Builder builder = OneUint32OuterClass.OneUint32.newBuilder();
        builder.setValue(value);
        OneUint32OuterClass.OneUint32 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");

        Field fieldF = ClazzUtil.getDeclaredField(OneUint32NoAccess.class, "value");
        fieldF.setAccessible(true);

        OneUint32NoAccess oneUint32 = new OneUint32NoAccess();
        fieldF.set(oneUint32, value);
        byte[] epb = ProtoBuf.toByteArray(oneUint32);
        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(ints = {
            1,
            131,
    })
    void testEncodeUnboxedNoAccess(int value) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        OneUint32OuterClass.OneUint32.Builder builder = OneUint32OuterClass.OneUint32.newBuilder();
        builder.setValue(value);
        OneUint32OuterClass.OneUint32 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");

        Field fieldF = ClazzUtil.getDeclaredField(OneUint32UnboxedNoAccess.class, "value");
        fieldF.setAccessible(true);

        OneUint32UnboxedNoAccess oneUint32 = new OneUint32UnboxedNoAccess();
        fieldF.set(oneUint32, value);
        byte[] epb = ProtoBuf.toByteArray(oneUint32);
        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(ints = {
            1,
            128,
    })
    void testDecodeNoAccess(int value) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        OneUint32OuterClass.OneUint32.Builder builder = OneUint32OuterClass.OneUint32.newBuilder();
        builder.setValue(value);
        OneUint32OuterClass.OneUint32 oint32 = builder.build();
        byte[] pb = oint32.toByteArray();


        OneUint32OuterClass.OneUint32 pbOf = OneUint32OuterClass.OneUint32.parseFrom(pb);

        OneUint32NoAccess oneUint32 = ProtoBuf.toObject(pb, OneUint32NoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(OneUint32NoAccess.class, "value");
        fieldF.setAccessible(true);

        assertEquals(pbOf.getValue(), (Integer)fieldF.get(oneUint32));

    }

    @ParameterizedTest
    @ValueSource(ints = {
            1,
            128,
    })
    void testDecodeUnboxedNoAccess(int value) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        OneUint32OuterClass.OneUint32.Builder builder = OneUint32OuterClass.OneUint32.newBuilder();
        builder.setValue(value);
        OneUint32OuterClass.OneUint32 oint32 = builder.build();
        byte[] pb = oint32.toByteArray();


        OneUint32OuterClass.OneUint32 pbOf = OneUint32OuterClass.OneUint32.parseFrom(pb);

        OneUint32UnboxedNoAccess oneUint32 = ProtoBuf.toObject(pb, OneUint32UnboxedNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(OneUint32UnboxedNoAccess.class, "value");
        fieldF.setAccessible(true);

        assertEquals(pbOf.getValue(), (int)fieldF.get(oneUint32));

    }

}
