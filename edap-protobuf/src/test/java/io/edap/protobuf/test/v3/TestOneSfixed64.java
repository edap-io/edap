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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestOneSfixed64 {

    @ParameterizedTest
    @ValueSource(longs = {
            1,
            128,
            -1,
            -129
    })
    void testEncode(long value) throws EncodeException {

        OneSfixed64OuterClass.OneSfixed64.Builder builder = OneSfixed64OuterClass.OneSfixed64.newBuilder();
        builder.setValue(value);
        OneSfixed64OuterClass.OneSfixed64 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneSfixed64 oneSfixed64 = new OneSfixed64();
        oneSfixed64.setValue(value);
        byte[] epb = ProtoBuf.toByteArray(oneSfixed64);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(longs = {
            1,
            128,
            -1,
            -128
    })
    void testDecode(long value) throws InvalidProtocolBufferException, ProtoBufException {

        OneSfixed64OuterClass.OneSfixed64.Builder builder = OneSfixed64OuterClass.OneSfixed64.newBuilder();
        builder.setValue(value);
        OneSfixed64OuterClass.OneSfixed64 oSfixed64 = builder.build();
        byte[] pb = oSfixed64.toByteArray();


        OneSfixed64OuterClass.OneSfixed64 pbOf = OneSfixed64OuterClass.OneSfixed64.parseFrom(pb);

        OneSfixed64 oneSfixed64 = ProtoBuf.toObject(pb, OneSfixed64.class);


        assertEquals(pbOf.getValue(), oneSfixed64.getValue());

    }

    @ParameterizedTest
    @ValueSource(longs = {
            1,
            128,
            -1,
            -129
    })
    void testEncodeUnboxed(long value) throws EncodeException {

        OneSfixed64OuterClass.OneSfixed64.Builder builder = OneSfixed64OuterClass.OneSfixed64.newBuilder();
        builder.setValue(value);
        OneSfixed64OuterClass.OneSfixed64 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneSfixed64Unboxed oneSfixed64 = new OneSfixed64Unboxed();
        oneSfixed64.value = value;
        byte[] epb = ProtoBuf.toByteArray(oneSfixed64);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(longs = {
            1,
            128,
            -1,
            -128
    })
    void testDecodeUnboxed(long value) throws InvalidProtocolBufferException, ProtoBufException {

        OneSfixed64OuterClass.OneSfixed64.Builder builder = OneSfixed64OuterClass.OneSfixed64.newBuilder();
        builder.setValue(value);
        OneSfixed64OuterClass.OneSfixed64 oSfixed64 = builder.build();
        byte[] pb = oSfixed64.toByteArray();


        OneSfixed64OuterClass.OneSfixed64 pbOf = OneSfixed64OuterClass.OneSfixed64.parseFrom(pb);

        OneSfixed64Unboxed oneSfixed64 = ProtoBuf.toObject(pb, OneSfixed64Unboxed.class);

        assertEquals(pbOf.getValue(), oneSfixed64.value);

    }

    @ParameterizedTest
    @ValueSource(longs = {
            1,
            128,
            -1,
            -129
    })
    void testEncodeNoAccess(long value) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        OneSfixed64OuterClass.OneSfixed64.Builder builder = OneSfixed64OuterClass.OneSfixed64.newBuilder();
        builder.setValue(value);
        OneSfixed64OuterClass.OneSfixed64 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");

        Field fieldF = ClazzUtil.getDeclaredField(OneSfixed64NoAccess.class, "value");
        fieldF.setAccessible(true);

        OneSfixed64NoAccess oneSfixed64 = new OneSfixed64NoAccess();
        fieldF.set(oneSfixed64, value);
        byte[] epb = ProtoBuf.toByteArray(oneSfixed64);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(longs = {
            1,
            128,
            -1,
            -129
    })
    void testEncodeUnboxedNoAccess(long value) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        OneSfixed64OuterClass.OneSfixed64.Builder builder = OneSfixed64OuterClass.OneSfixed64.newBuilder();
        builder.setValue(value);
        OneSfixed64OuterClass.OneSfixed64 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");

        Field fieldF = ClazzUtil.getDeclaredField(OneSfixed64UnboxedNoAccess.class, "value");
        fieldF.setAccessible(true);

        OneSfixed64UnboxedNoAccess oneSfixed64 = new OneSfixed64UnboxedNoAccess();
        fieldF.set(oneSfixed64, value);
        byte[] epb = ProtoBuf.toByteArray(oneSfixed64);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(longs = {
            1,
            128,
            -1,
            -128
    })
    void testDecodeNoAccess(long value) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        OneSfixed64OuterClass.OneSfixed64.Builder builder = OneSfixed64OuterClass.OneSfixed64.newBuilder();
        builder.setValue(value);
        OneSfixed64OuterClass.OneSfixed64 oSfixed64 = builder.build();
        byte[] pb = oSfixed64.toByteArray();


        OneSfixed64OuterClass.OneSfixed64 pbOf = OneSfixed64OuterClass.OneSfixed64.parseFrom(pb);

        OneSfixed64NoAccess oneSfixed64 = ProtoBuf.toObject(pb, OneSfixed64NoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(OneSfixed64NoAccess.class, "value");
        fieldF.setAccessible(true);

        assertEquals(pbOf.getValue(), (Long)fieldF.get(oneSfixed64));

    }

    @ParameterizedTest
    @ValueSource(longs = {
            1,
            128,
            -1,
            -128
    })
    void testDecodeUnboxedNoAccess(long value) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        OneSfixed64OuterClass.OneSfixed64.Builder builder = OneSfixed64OuterClass.OneSfixed64.newBuilder();
        builder.setValue(value);
        OneSfixed64OuterClass.OneSfixed64 oSfixed64 = builder.build();
        byte[] pb = oSfixed64.toByteArray();


        OneSfixed64OuterClass.OneSfixed64 pbOf = OneSfixed64OuterClass.OneSfixed64.parseFrom(pb);

        OneSfixed64UnboxedNoAccess oneSfixed64 = ProtoBuf.toObject(pb, OneSfixed64UnboxedNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(OneSfixed64UnboxedNoAccess.class, "value");
        fieldF.setAccessible(true);

        assertEquals(pbOf.getValue(), (long)fieldF.get(oneSfixed64));

    }
}
