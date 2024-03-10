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

public class TestOneFixed64 {
    @ParameterizedTest
    @ValueSource(longs = {
            0,
            1,
            128,
            2147483648L
    })
    void testEncode(long value) throws EncodeException {

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
            0,
            1,
            128,
            2147483648L
    })
    void testDecode(long value) throws InvalidProtocolBufferException, ProtoException {

        OneFixed64OuterClass.OneFixed64.Builder builder = OneFixed64OuterClass.OneFixed64.newBuilder();
        builder.setValue(value);
        OneFixed64OuterClass.OneFixed64 oint64 = builder.build();
        byte[] pb = oint64.toByteArray();


        OneFixed64OuterClass.OneFixed64 pbOf = OneFixed64OuterClass.OneFixed64.parseFrom(pb);

        OneFixed64 oneFixed64 = ProtoBuf.toObject(pb, OneFixed64.class);

        if (value == 0) {
            assertNull(oneFixed64.getValue());
        } else {
            assertEquals(pbOf.getValue(), oneFixed64.getValue());
        }

    }

    @ParameterizedTest
    @ValueSource(longs = {
            0,
            1,
            128,
            2147483648L
    })
    void testEncodeUnboxed(long value) throws EncodeException {

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
            0,
            1,
            128,
            2147483648L
    })
    void testDecodeUnboxed(long value) throws InvalidProtocolBufferException, ProtoException {

        OneFixed64OuterClass.OneFixed64.Builder builder = OneFixed64OuterClass.OneFixed64.newBuilder();
        builder.setValue(value);
        OneFixed64OuterClass.OneFixed64 oint64 = builder.build();
        byte[] pb = oint64.toByteArray();


        OneFixed64OuterClass.OneFixed64 pbOf = OneFixed64OuterClass.OneFixed64.parseFrom(pb);

        OneFixed64Unboxed oneFixed64 = ProtoBuf.toObject(pb, OneFixed64Unboxed.class);


        assertEquals(pbOf.getValue(), oneFixed64.value);

    }

    @ParameterizedTest
    @ValueSource(longs = {
            0,
            1,
            128,
            2147483648L
    })
    void testEncodeNoAccess(long value) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        OneFixed64OuterClass.OneFixed64.Builder builder = OneFixed64OuterClass.OneFixed64.newBuilder();
        builder.setValue(value);
        OneFixed64OuterClass.OneFixed64 oi64 = builder.build();
        byte[] pb = oi64.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");

        Field fieldF = ClazzUtil.getDeclaredField(OneFixed64NoAccess.class, "value");
        fieldF.setAccessible(true);

        OneFixed64NoAccess oneFixed64 = new OneFixed64NoAccess();
        fieldF.set(oneFixed64, value);
        byte[] epb = ProtoBuf.toByteArray(oneFixed64);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(longs = {
            0,
            1,
            128,
            2147483648L
    })
    void testEncodeUnboxedNoAccess(long value) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        OneFixed64OuterClass.OneFixed64.Builder builder = OneFixed64OuterClass.OneFixed64.newBuilder();
        builder.setValue(value);
        OneFixed64OuterClass.OneFixed64 oi64 = builder.build();
        byte[] pb = oi64.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");

        Field fieldF = ClazzUtil.getDeclaredField(OneFixed64UnboxedNoAccess.class, "value");
        fieldF.setAccessible(true);

        OneFixed64UnboxedNoAccess oneFixed64 = new OneFixed64UnboxedNoAccess();
        fieldF.set(oneFixed64, value);
        byte[] epb = ProtoBuf.toByteArray(oneFixed64);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(longs = {
            0,
            1,
            128,
            2147483648L
    })
    void testDecodeNoAccess(long value) throws InvalidProtocolBufferException, ProtoException, NoSuchFieldException, IllegalAccessException {

        OneFixed64OuterClass.OneFixed64.Builder builder = OneFixed64OuterClass.OneFixed64.newBuilder();
        builder.setValue(value);
        OneFixed64OuterClass.OneFixed64 oint64 = builder.build();
        byte[] pb = oint64.toByteArray();


        OneFixed64OuterClass.OneFixed64 pbOf = OneFixed64OuterClass.OneFixed64.parseFrom(pb);

        OneFixed64NoAccess oneFixed64 = ProtoBuf.toObject(pb, OneFixed64NoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(OneFixed64NoAccess.class, "value");
        fieldF.setAccessible(true);

        if (value == 0) {
            assertNull(fieldF.get(oneFixed64));
        } else {
            assertEquals(pbOf.getValue(), ((Long) fieldF.get(oneFixed64)));
        }

    }

    @ParameterizedTest
    @ValueSource(longs = {
            0,
            1,
            128,
            2147483648L
    })
    void testDecodeUnboxedNoAccess(long value) throws InvalidProtocolBufferException, ProtoException, NoSuchFieldException, IllegalAccessException {

        OneFixed64OuterClass.OneFixed64.Builder builder = OneFixed64OuterClass.OneFixed64.newBuilder();
        builder.setValue(value);
        OneFixed64OuterClass.OneFixed64 oint64 = builder.build();
        byte[] pb = oint64.toByteArray();


        OneFixed64OuterClass.OneFixed64 pbOf = OneFixed64OuterClass.OneFixed64.parseFrom(pb);

        OneFixed64UnboxedNoAccess oneFixed64 = ProtoBuf.toObject(pb, OneFixed64UnboxedNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(OneFixed64UnboxedNoAccess.class, "value");
        fieldF.setAccessible(true);

        assertEquals(pbOf.getValue(), ((Long)fieldF.get(oneFixed64)).longValue());

    }
}
