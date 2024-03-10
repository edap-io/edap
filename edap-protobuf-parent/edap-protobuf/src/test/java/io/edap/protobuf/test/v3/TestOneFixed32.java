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
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.test.message.v3.*;
import io.edap.util.ClazzUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static org.junit.jupiter.api.Assertions.*;

public class TestOneFixed32 {
    @ParameterizedTest
    @ValueSource(ints = {
            0,
            1,
            128,
            -1,
            -129
    })
    void testEncode(int value) throws EncodeException {

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
            0,
            1,
            128,
            -1,
            -128
    })
    void testDecode(int value) throws InvalidProtocolBufferException, ProtoException {

        OneFixed32OuterClass.OneFixed32.Builder builder = OneFixed32OuterClass.OneFixed32.newBuilder();
        builder.setValue(value);
        OneFixed32OuterClass.OneFixed32 oFixed32 = builder.build();
        byte[] pb = oFixed32.toByteArray();


        OneFixed32OuterClass.OneFixed32 pbOf = OneFixed32OuterClass.OneFixed32.parseFrom(pb);

        OneFixed32 oneFixed32 = ProtoBuf.toObject(pb, OneFixed32.class);

        if (value == 0) {
            assertNull(oneFixed32.getValue());
        } else {
            assertEquals(pbOf.getValue(), oneFixed32.getValue());
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
            0,
            1,
            128,
            -1,
            -128
    })
    void testDecodeUnboxed(int value) throws InvalidProtocolBufferException, ProtoException {

        OneFixed32OuterClass.OneFixed32.Builder builder = OneFixed32OuterClass.OneFixed32.newBuilder();
        builder.setValue(value);
        OneFixed32OuterClass.OneFixed32 oFixed32 = builder.build();
        byte[] pb = oFixed32.toByteArray();


        OneFixed32OuterClass.OneFixed32 pbOf = OneFixed32OuterClass.OneFixed32.parseFrom(pb);

        OneFixed32Unboxed oneFixed32 = ProtoBuf.toObject(pb, OneFixed32Unboxed.class);


        assertEquals(pbOf.getValue(), oneFixed32.value);

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

        OneFixed32OuterClass.OneFixed32.Builder builder = OneFixed32OuterClass.OneFixed32.newBuilder();
        builder.setValue(value);
        OneFixed32OuterClass.OneFixed32 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");

        Field fieldF = ClazzUtil.getDeclaredField(OneFixed32NoAccess.class, "value");
        fieldF.setAccessible(true);

        OneFixed32NoAccess oneFixed32 = new OneFixed32NoAccess();
        fieldF.set(oneFixed32, value);
        byte[] epb = ProtoBuf.toByteArray(oneFixed32);


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

        OneFixed32OuterClass.OneFixed32.Builder builder = OneFixed32OuterClass.OneFixed32.newBuilder();
        builder.setValue(value);
        OneFixed32OuterClass.OneFixed32 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");

        Field fieldF = ClazzUtil.getDeclaredField(OneFixed32UnboxedNoAccess.class, "value");
        fieldF.setAccessible(true);

        OneFixed32UnboxedNoAccess oneFixed32 = new OneFixed32UnboxedNoAccess();
        fieldF.set(oneFixed32, value);
        byte[] epb = ProtoBuf.toByteArray(oneFixed32);


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
    void testDecodeNoAccess(int value) throws InvalidProtocolBufferException, ProtoException, NoSuchFieldException, IllegalAccessException {

        OneFixed32OuterClass.OneFixed32.Builder builder = OneFixed32OuterClass.OneFixed32.newBuilder();
        builder.setValue(value);
        OneFixed32OuterClass.OneFixed32 oFixed32 = builder.build();
        byte[] pb = oFixed32.toByteArray();


        OneFixed32OuterClass.OneFixed32 pbOf = OneFixed32OuterClass.OneFixed32.parseFrom(pb);

        OneFixed32NoAccess oneFixed32 = ProtoBuf.toObject(pb, OneFixed32NoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(OneFixed32NoAccess.class, "value");
        fieldF.setAccessible(true);

        if (value == 0) {
            assertNull(fieldF.get(oneFixed32));
        } else {
            assertEquals(pbOf.getValue(), (Integer) fieldF.get(oneFixed32));
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
    void testDecodeUnboxedNoAccess(int value) throws InvalidProtocolBufferException, ProtoException, NoSuchFieldException, IllegalAccessException {

        OneFixed32OuterClass.OneFixed32.Builder builder = OneFixed32OuterClass.OneFixed32.newBuilder();
        builder.setValue(value);
        OneFixed32OuterClass.OneFixed32 oFixed32 = builder.build();
        byte[] pb = oFixed32.toByteArray();


        OneFixed32OuterClass.OneFixed32 pbOf = OneFixed32OuterClass.OneFixed32.parseFrom(pb);

        OneFixed32UnboxedNoAccess oneFixed32 = ProtoBuf.toObject(pb, OneFixed32UnboxedNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(OneFixed32UnboxedNoAccess.class, "value");
        fieldF.setAccessible(true);

        assertEquals(pbOf.getValue(), (int)fieldF.get(oneFixed32));

    }
}
