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

public class TestOneSint64 {

    @ParameterizedTest
    @ValueSource(longs = {
            0,
            1,
            128,
            5671506337319861524L
    })
    void testEncode(long value) throws EncodeException {

        OneSint64OuterClass.OneSint64.Builder builder = OneSint64OuterClass.OneSint64.newBuilder();
        builder.setValue(value);
        OneSint64OuterClass.OneSint64 oi64 = builder.build();
        byte[] pb = oi64.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneSint64 oneSint64 = new OneSint64();
        oneSint64.setValue(value);
        byte[] epb = ProtoBuf.toByteArray(oneSint64);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(longs = {
            0,
            1,
            128,
            2147483648L
    })
    void testDecode(long value) throws InvalidProtocolBufferException, ProtoBufException {

        OneSint64OuterClass.OneSint64.Builder builder = OneSint64OuterClass.OneSint64.newBuilder();
        builder.setValue(value);
        OneSint64OuterClass.OneSint64 oint64 = builder.build();
        byte[] pb = oint64.toByteArray();


        OneSint64OuterClass.OneSint64 pbOf = OneSint64OuterClass.OneSint64.parseFrom(pb);

        OneSint64 oneSint64 = ProtoBuf.toObject(pb, OneSint64.class);

        if (value == 0) {
            assertNull(oneSint64.getValue());
        } else {
            assertEquals(pbOf.getValue(), oneSint64.getValue());
        }

    }

    @ParameterizedTest
    @ValueSource(longs = {
            0,
            1,
            128,
            5671506337319861524L
    })
    void testEncodeUnboxed(long value) throws EncodeException {

        OneSint64OuterClass.OneSint64.Builder builder = OneSint64OuterClass.OneSint64.newBuilder();
        builder.setValue(value);
        OneSint64OuterClass.OneSint64 oi64 = builder.build();
        byte[] pb = oi64.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneSint64Unboxed oneSint64 = new OneSint64Unboxed();
        oneSint64.value = value;
        byte[] epb = ProtoBuf.toByteArray(oneSint64);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(longs = {
            0,
            1,
            128,
            2147483648L
    })
    void testDecodeUnboxed(long value) throws InvalidProtocolBufferException, ProtoBufException {

        OneSint64OuterClass.OneSint64.Builder builder = OneSint64OuterClass.OneSint64.newBuilder();
        builder.setValue(value);
        OneSint64OuterClass.OneSint64 oint64 = builder.build();
        byte[] pb = oint64.toByteArray();


        OneSint64OuterClass.OneSint64 pbOf = OneSint64OuterClass.OneSint64.parseFrom(pb);

        OneSint64Unboxed oneSint64 = ProtoBuf.toObject(pb, OneSint64Unboxed.class);


        assertEquals(pbOf.getValue(), oneSint64.value);

    }

    @ParameterizedTest
    @ValueSource(longs = {
            0,
            1,
            128,
            5671506337319861524L
    })
    void testEncodeNoAccess(long value) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        OneSint64OuterClass.OneSint64.Builder builder = OneSint64OuterClass.OneSint64.newBuilder();
        builder.setValue(value);
        OneSint64OuterClass.OneSint64 oi64 = builder.build();
        byte[] pb = oi64.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");

        Field fieldF = ClazzUtil.getDeclaredField(OneSint64NoAccess.class, "value");
        fieldF.setAccessible(true);

        OneSint64NoAccess oneSint64 = new OneSint64NoAccess();
        fieldF.set(oneSint64, value);
        byte[] epb = ProtoBuf.toByteArray(oneSint64);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(longs = {
            0,
            1,
            128,
            5671506337319861524L
    })
    void testEncodeUnboxedNoAccess(long value) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        OneSint64OuterClass.OneSint64.Builder builder = OneSint64OuterClass.OneSint64.newBuilder();
        builder.setValue(value);
        OneSint64OuterClass.OneSint64 oi64 = builder.build();
        byte[] pb = oi64.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");

        Field fieldF = ClazzUtil.getDeclaredField(OneSint64UnboxedNoAccess.class, "value");
        fieldF.setAccessible(true);

        OneSint64UnboxedNoAccess oneSint64 = new OneSint64UnboxedNoAccess();
        fieldF.set(oneSint64, value);
        byte[] epb = ProtoBuf.toByteArray(oneSint64);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(longs = {
            0,
            1,
            128,
            2147483648L
    })
    void testDecodeNoAccess(long value) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        OneSint64OuterClass.OneSint64.Builder builder = OneSint64OuterClass.OneSint64.newBuilder();
        builder.setValue(value);
        OneSint64OuterClass.OneSint64 oint64 = builder.build();
        byte[] pb = oint64.toByteArray();


        OneSint64OuterClass.OneSint64 pbOf = OneSint64OuterClass.OneSint64.parseFrom(pb);

        OneSint64NoAccess oneSint64 = ProtoBuf.toObject(pb, OneSint64NoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(OneSint64NoAccess.class, "value");
        fieldF.setAccessible(true);

        if (value == 0) {
            assertNull(fieldF.get(oneSint64));
        } else {
            assertEquals(pbOf.getValue(), (long) fieldF.get(oneSint64));
        }

    }

    @ParameterizedTest
    @ValueSource(longs = {
            0,
            1,
            128,
            2147483648L
    })
    void testDecodeUnboxedNoAccess(long value) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        OneSint64OuterClass.OneSint64.Builder builder = OneSint64OuterClass.OneSint64.newBuilder();
        builder.setValue(value);
        OneSint64OuterClass.OneSint64 oint64 = builder.build();
        byte[] pb = oint64.toByteArray();


        OneSint64OuterClass.OneSint64 pbOf = OneSint64OuterClass.OneSint64.parseFrom(pb);

        OneSint64UnboxedNoAccess oneSint64 = ProtoBuf.toObject(pb, OneSint64UnboxedNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(OneSint64UnboxedNoAccess.class, "value");
        fieldF.setAccessible(true);

        assertEquals(pbOf.getValue(), (long)fieldF.get(oneSint64));

    }
}
