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

public class TestOneSfixed32 {

    @ParameterizedTest
    @ValueSource(ints = {
            0,
            1,
            128,
            -1,
            -129
    })
    void testEncode(int value) throws EncodeException {

        OneSfixed32OuterClass.OneSfixed32.Builder builder = OneSfixed32OuterClass.OneSfixed32.newBuilder();
        builder.setValue(value);
        OneSfixed32OuterClass.OneSfixed32 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneSfixed32 oneSfixed32 = new OneSfixed32();
        oneSfixed32.setValue(value);
        byte[] epb = ProtoBuf.toByteArray(oneSfixed32);


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

        OneSfixed32OuterClass.OneSfixed32.Builder builder = OneSfixed32OuterClass.OneSfixed32.newBuilder();
        builder.setValue(value);
        OneSfixed32OuterClass.OneSfixed32 oSfixed32 = builder.build();
        byte[] pb = oSfixed32.toByteArray();


        OneSfixed32OuterClass.OneSfixed32 pbOf = OneSfixed32OuterClass.OneSfixed32.parseFrom(pb);

        OneSfixed32 oneSfixed32 = ProtoBuf.toObject(pb, OneSfixed32.class);

        if (value == 0) {
            assertNull(oneSfixed32.getValue());
        } else {
            assertEquals(pbOf.getValue(), oneSfixed32.getValue());
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

        OneSfixed32OuterClass.OneSfixed32.Builder builder = OneSfixed32OuterClass.OneSfixed32.newBuilder();
        builder.setValue(value);
        OneSfixed32OuterClass.OneSfixed32 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneSfixed32Unboxed oneSfixed32 = new OneSfixed32Unboxed();
        oneSfixed32.value = value;
        byte[] epb = ProtoBuf.toByteArray(oneSfixed32);


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

        OneSfixed32OuterClass.OneSfixed32.Builder builder = OneSfixed32OuterClass.OneSfixed32.newBuilder();
        builder.setValue(value);
        OneSfixed32OuterClass.OneSfixed32 oSfixed32 = builder.build();
        byte[] pb = oSfixed32.toByteArray();


        OneSfixed32OuterClass.OneSfixed32 pbOf = OneSfixed32OuterClass.OneSfixed32.parseFrom(pb);

        OneSfixed32Unboxed oneSfixed32 = ProtoBuf.toObject(pb, OneSfixed32Unboxed.class);


        assertEquals(pbOf.getValue(), oneSfixed32.value);

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

        OneSfixed32OuterClass.OneSfixed32.Builder builder = OneSfixed32OuterClass.OneSfixed32.newBuilder();
        builder.setValue(value);
        OneSfixed32OuterClass.OneSfixed32 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");

        Field fieldF = ClazzUtil.getDeclaredField(OneSfixed32NoAccess.class, "value");
        fieldF.setAccessible(true);

        OneSfixed32NoAccess oneSfixed32 = new OneSfixed32NoAccess();
        fieldF.set(oneSfixed32, value);
        byte[] epb = ProtoBuf.toByteArray(oneSfixed32);


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

        OneSfixed32OuterClass.OneSfixed32.Builder builder = OneSfixed32OuterClass.OneSfixed32.newBuilder();
        builder.setValue(value);
        OneSfixed32OuterClass.OneSfixed32 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");

        Field fieldF = ClazzUtil.getDeclaredField(OneSfixed32UnboxedNoAccess.class, "value");
        fieldF.setAccessible(true);

        OneSfixed32UnboxedNoAccess oneSfixed32 = new OneSfixed32UnboxedNoAccess();
        fieldF.set(oneSfixed32, value);
        byte[] epb = ProtoBuf.toByteArray(oneSfixed32);


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

        OneSfixed32OuterClass.OneSfixed32.Builder builder = OneSfixed32OuterClass.OneSfixed32.newBuilder();
        builder.setValue(value);
        OneSfixed32OuterClass.OneSfixed32 oSfixed32 = builder.build();
        byte[] pb = oSfixed32.toByteArray();


        OneSfixed32OuterClass.OneSfixed32 pbOf = OneSfixed32OuterClass.OneSfixed32.parseFrom(pb);

        OneSfixed32NoAccess oneSfixed32 = ProtoBuf.toObject(pb, OneSfixed32NoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(OneSfixed32NoAccess.class, "value");
        fieldF.setAccessible(true);

        if (value == 0) {
            assertNull(fieldF.get(oneSfixed32));
        } else {
            assertEquals(pbOf.getValue(), (Integer) fieldF.get(oneSfixed32));
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

        OneSfixed32OuterClass.OneSfixed32.Builder builder = OneSfixed32OuterClass.OneSfixed32.newBuilder();
        builder.setValue(value);
        OneSfixed32OuterClass.OneSfixed32 oSfixed32 = builder.build();
        byte[] pb = oSfixed32.toByteArray();


        OneSfixed32OuterClass.OneSfixed32 pbOf = OneSfixed32OuterClass.OneSfixed32.parseFrom(pb);

        OneSfixed32UnboxedNoAccess oneSfixed32 = ProtoBuf.toObject(pb, OneSfixed32UnboxedNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(OneSfixed32UnboxedNoAccess.class, "value");
        fieldF.setAccessible(true);

        assertEquals(pbOf.getValue(), (int)fieldF.get(oneSfixed32));

    }
}
