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
import io.edap.util.ClazzUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static org.junit.jupiter.api.Assertions.*;

public class TestOneSint32 {

    @ParameterizedTest
    @ValueSource(ints = {
            0,
            1,
            128,
            -1,
            -129
    })
    void testEncode(int value) throws EncodeException {

        OneSint32OuterClass.OneSint32.Builder builder = OneSint32OuterClass.OneSint32.newBuilder();
        builder.setValue(value);
        OneSint32OuterClass.OneSint32 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneSint32 oneSint32 = new OneSint32();
        oneSint32.setValue(value);
        byte[] epb = ProtoBuf.toByteArray(oneSint32);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(ints = {
            0,
            1,
            128,
            -1,
            -128
    })
    void testDecode(int value) throws InvalidProtocolBufferException, ProtoBufException {

        OneSint32OuterClass.OneSint32.Builder builder = OneSint32OuterClass.OneSint32.newBuilder();
        builder.setValue(value);
        OneSint32OuterClass.OneSint32 oSint32 = builder.build();
        byte[] pb = oSint32.toByteArray();


        OneSint32OuterClass.OneSint32 pbOf = OneSint32OuterClass.OneSint32.parseFrom(pb);

        OneSint32 oneSint32 = ProtoBuf.toObject(pb, OneSint32.class);

        if (value == 0) {
            assertNull(oneSint32.getValue());
        } else {
            assertEquals(pbOf.getValue(), oneSint32.getValue());
        }

    }

    @ParameterizedTest
    @ValueSource(ints = {
            0,
            1,
            128,
            -1,
            -129
    })
    void testEncodeUnboxed(int value) throws EncodeException {

        OneSint32OuterClass.OneSint32.Builder builder = OneSint32OuterClass.OneSint32.newBuilder();
        builder.setValue(value);
        OneSint32OuterClass.OneSint32 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneSint32Unboxed oneSint32 = new OneSint32Unboxed();
        oneSint32.value = value;
        byte[] epb = ProtoBuf.toByteArray(oneSint32);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(ints = {
            0,
            1,
            128,
            -1,
            -128
    })
    void testDecodeUnboxed(int value) throws InvalidProtocolBufferException, ProtoBufException {

        OneSint32OuterClass.OneSint32.Builder builder = OneSint32OuterClass.OneSint32.newBuilder();
        builder.setValue(value);
        OneSint32OuterClass.OneSint32 oSint32 = builder.build();
        byte[] pb = oSint32.toByteArray();


        OneSint32OuterClass.OneSint32 pbOf = OneSint32OuterClass.OneSint32.parseFrom(pb);

        OneSint32Unboxed oneSint32 = ProtoBuf.toObject(pb, OneSint32Unboxed.class);


        assertEquals(pbOf.getValue(), oneSint32.value);

    }

    @ParameterizedTest
    @ValueSource(ints = {
            0,
            1,
            128,
            -1,
            -129
    })
    void testEncodeNoAccess(int value) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        OneSint32OuterClass.OneSint32.Builder builder = OneSint32OuterClass.OneSint32.newBuilder();
        builder.setValue(value);
        OneSint32OuterClass.OneSint32 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");

        Field fieldF = ClazzUtil.getDeclaredField(OneSint32NoAccess.class, "value");
        fieldF.setAccessible(true);

        OneSint32NoAccess oneSint32 = new OneSint32NoAccess();
        fieldF.set(oneSint32, value);
        byte[] epb = ProtoBuf.toByteArray(oneSint32);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(ints = {
            0,
            1,
            128,
            -1,
            -129
    })
    void testEncodeUnboxedNoAccess(int value) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        OneSint32OuterClass.OneSint32.Builder builder = OneSint32OuterClass.OneSint32.newBuilder();
        builder.setValue(value);
        OneSint32OuterClass.OneSint32 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");

        Field fieldF = ClazzUtil.getDeclaredField(OneSint32UnboxedNoAccess.class, "value");
        fieldF.setAccessible(true);

        OneSint32UnboxedNoAccess oneSint32 = new OneSint32UnboxedNoAccess();
        fieldF.set(oneSint32, value);
        byte[] epb = ProtoBuf.toByteArray(oneSint32);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(ints = {
            0,
            1,
            128,
            -1,
            -128
    })
    void testDecodeNoAccess(int value) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        OneSint32OuterClass.OneSint32.Builder builder = OneSint32OuterClass.OneSint32.newBuilder();
        builder.setValue(value);
        OneSint32OuterClass.OneSint32 oSint32 = builder.build();
        byte[] pb = oSint32.toByteArray();


        OneSint32OuterClass.OneSint32 pbOf = OneSint32OuterClass.OneSint32.parseFrom(pb);

        OneSint32NoAccess oneSint32 = ProtoBuf.toObject(pb, OneSint32NoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(OneSint32NoAccess.class, "value");
        fieldF.setAccessible(true);

        if (value == 0) {
            assertNull(fieldF.get(oneSint32));
        } else {
            assertEquals(pbOf.getValue(), (Integer) fieldF.get(oneSint32));
        }

    }

    @ParameterizedTest
    @ValueSource(ints = {
            0,
            1,
            128,
            -1,
            -128
    })
    void testDecodeUnboxedNoAccess(int value) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        OneSint32OuterClass.OneSint32.Builder builder = OneSint32OuterClass.OneSint32.newBuilder();
        builder.setValue(value);
        OneSint32OuterClass.OneSint32 oSint32 = builder.build();
        byte[] pb = oSint32.toByteArray();


        OneSint32OuterClass.OneSint32 pbOf = OneSint32OuterClass.OneSint32.parseFrom(pb);

        OneSint32UnboxedNoAccess oneSint32 = ProtoBuf.toObject(pb, OneSint32UnboxedNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(OneSint32UnboxedNoAccess.class, "value");
        fieldF.setAccessible(true);

        assertEquals(pbOf.getValue(), (Integer)fieldF.get(oneSint32));

    }
}
