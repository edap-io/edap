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

import static org.junit.jupiter.api.Assertions.*;

/**
 * 基础类型double的编解码逻辑测试
 */
public class TestOneDouble {

    @ParameterizedTest
    @ValueSource(doubles = {
            0,
            1,
            31.415926
    })
    void testEncode(double value) throws EncodeException {

        OneDoubleOuterClass.OneDouble.Builder builder = OneDoubleOuterClass.OneDouble.newBuilder();
        builder.setD(value);
        OneDoubleOuterClass.OneDouble od = builder.build();
        byte[] pb = od.toByteArray();

        OneDouble oneDouble = new OneDouble();
        oneDouble.setD(value);
        byte[] epb = ProtoBuf.toByteArray(oneDouble);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(doubles = {
            0,
            1,
            31.415926
    })
    void testDecode(double value) throws InvalidProtocolBufferException, ProtoException {

        OneDoubleOuterClass.OneDouble.Builder builder = OneDoubleOuterClass.OneDouble.newBuilder();
        builder.setD(value);
        OneDoubleOuterClass.OneDouble od = builder.build();
        byte[] pb = od.toByteArray();


        OneDoubleOuterClass.OneDouble pbOd = OneDoubleOuterClass.OneDouble.parseFrom(pb);

        OneDouble oneDouble = ProtoBuf.toObject(pb, OneDouble.class);

        if (value == 0) {
            assertNull(oneDouble.getD());
        } else {
            assertEquals(pbOd.getD(), oneDouble.getD());
        }

    }

    @ParameterizedTest
    @ValueSource(doubles = {
            0,
            1,
            31.415926
    })
    void testEncodeUnboxed(double value) throws EncodeException {

        OneDoubleOuterClass.OneDouble.Builder builder = OneDoubleOuterClass.OneDouble.newBuilder();
        builder.setD(value);
        OneDoubleOuterClass.OneDouble od = builder.build();
        byte[] pb = od.toByteArray();

        OneDoubleUnboxed oneDouble = new OneDoubleUnboxed();
        oneDouble.value = value;
        byte[] epb = ProtoBuf.toByteArray(oneDouble);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(doubles = {
            0,
            1,
            31.415926
    })
    void testDecodeUnboxed(double value) throws InvalidProtocolBufferException, ProtoException {

        OneDoubleOuterClass.OneDouble.Builder builder = OneDoubleOuterClass.OneDouble.newBuilder();
        builder.setD(value);
        OneDoubleOuterClass.OneDouble od = builder.build();
        byte[] pb = od.toByteArray();


        OneDoubleOuterClass.OneDouble pbOd = OneDoubleOuterClass.OneDouble.parseFrom(pb);

        OneDoubleUnboxed oneDouble = ProtoBuf.toObject(pb, OneDoubleUnboxed.class);


        assertEquals(pbOd.getD(), oneDouble.value);

    }

    @ParameterizedTest
    @ValueSource(doubles = {
            0,
            1,
            31.415926
    })
    void testEncodeNoAccess(double value) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        OneDoubleOuterClass.OneDouble.Builder builder = OneDoubleOuterClass.OneDouble.newBuilder();
        builder.setD(value);
        OneDoubleOuterClass.OneDouble od = builder.build();
        byte[] pb = od.toByteArray();

        Field fieldF = ClazzUtil.getDeclaredField(OneDoubleNoAccess.class, "d");
        fieldF.setAccessible(true);

        OneDoubleNoAccess oneDouble = new OneDoubleNoAccess();
        fieldF.set(oneDouble, value);
        byte[] epb = ProtoBuf.toByteArray(oneDouble);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(doubles = {
            0,
            1,
            31.415926
    })
    void testEncodeUnboxedNoAccess(double value) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        OneDoubleOuterClass.OneDouble.Builder builder = OneDoubleOuterClass.OneDouble.newBuilder();
        builder.setD(value);
        OneDoubleOuterClass.OneDouble od = builder.build();
        byte[] pb = od.toByteArray();

        Field fieldF = ClazzUtil.getDeclaredField(OneDoubleUnboxedNoAccess.class, "value");
        fieldF.setAccessible(true);

        OneDoubleUnboxedNoAccess oneDouble = new OneDoubleUnboxedNoAccess();
        fieldF.set(oneDouble, value);
        byte[] epb = ProtoBuf.toByteArray(oneDouble);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(doubles = {
            0,
            1,
            31.415926
    })
    void testDecodeNoAccess(double value) throws InvalidProtocolBufferException, ProtoException, NoSuchFieldException, IllegalAccessException {

        OneDoubleOuterClass.OneDouble.Builder builder = OneDoubleOuterClass.OneDouble.newBuilder();
        builder.setD(value);
        OneDoubleOuterClass.OneDouble od = builder.build();
        byte[] pb = od.toByteArray();


        OneDoubleOuterClass.OneDouble pbOd = OneDoubleOuterClass.OneDouble.parseFrom(pb);

        OneDoubleNoAccess oneDouble = ProtoBuf.toObject(pb, OneDoubleNoAccess.class);

        Field fieldF = ClazzUtil.getDeclaredField(OneDoubleNoAccess.class, "d");
        fieldF.setAccessible(true);

        if (value == 0) {
            assertNull(fieldF.get(oneDouble));
        } else {
            assertEquals(pbOd.getD(), (Double) fieldF.get(oneDouble));
        }

    }

    @ParameterizedTest
    @ValueSource(doubles = {
            0,
            1,
            31.415926
    })
    void testDecodeUnboxedNoAccess(double value) throws InvalidProtocolBufferException, ProtoException, NoSuchFieldException, IllegalAccessException {

        OneDoubleOuterClass.OneDouble.Builder builder = OneDoubleOuterClass.OneDouble.newBuilder();
        builder.setD(value);
        OneDoubleOuterClass.OneDouble od = builder.build();
        byte[] pb = od.toByteArray();


        OneDoubleOuterClass.OneDouble pbOd = OneDoubleOuterClass.OneDouble.parseFrom(pb);

        OneDoubleUnboxedNoAccess oneDouble = ProtoBuf.toObject(pb, OneDoubleUnboxedNoAccess.class);

        Field fieldF = ClazzUtil.getDeclaredField(OneDoubleUnboxedNoAccess.class, "value");
        fieldF.setAccessible(true);

        assertEquals(pbOd.getD(), (double) fieldF.get(oneDouble));

    }
}
