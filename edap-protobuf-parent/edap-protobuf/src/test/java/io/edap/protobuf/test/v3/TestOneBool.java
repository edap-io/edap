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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestOneBool {

    @ParameterizedTest
    @ValueSource(booleans = {
            true,
            false
    })
    void testEncode(boolean value) throws EncodeException {

        OneBoolOuterClass.OneBool.Builder builder = OneBoolOuterClass.OneBool.newBuilder();
        builder.setValue(value);
        OneBoolOuterClass.OneBool od = builder.build();
        byte[] pb = od.toByteArray();

        OneBool oneBool = new OneBool();
        oneBool.setValue(value);
        byte[] epb = ProtoBuf.toByteArray(oneBool);

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(booleans = {
            true,
            false
    })
    void testDecode(boolean value) throws InvalidProtocolBufferException, ProtoBufException {

        OneBoolOuterClass.OneBool.Builder builder = OneBoolOuterClass.OneBool.newBuilder();
        builder.setValue(value);
        OneBoolOuterClass.OneBool od = builder.build();
        byte[] pb = od.toByteArray();


        OneBoolOuterClass.OneBool pbOd = OneBoolOuterClass.OneBool.parseFrom(pb);

        OneBool oneBool = ProtoBuf.toObject(pb, OneBool.class);


        assertEquals(pbOd.getValue(), oneBool.getValue()==null?false:oneBool.getValue());

    }

    @ParameterizedTest
    @ValueSource(booleans = {
            true,
            false
    })
    void testEncodeUnboxed(boolean value) throws EncodeException {

        OneBoolOuterClass.OneBool.Builder builder = OneBoolOuterClass.OneBool.newBuilder();
        builder.setValue(value);
        OneBoolOuterClass.OneBool od = builder.build();
        byte[] pb = od.toByteArray();

        OneBoolUnboxed oneBool = new OneBoolUnboxed();
        oneBool.value = value;
        byte[] epb = ProtoBuf.toByteArray(oneBool);

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(booleans = {
            true,
            false
    })
    void testDecodeUnboxed(boolean value) throws InvalidProtocolBufferException, ProtoBufException {

        OneBoolOuterClass.OneBool.Builder builder = OneBoolOuterClass.OneBool.newBuilder();
        builder.setValue(value);
        OneBoolOuterClass.OneBool od = builder.build();
        byte[] pb = od.toByteArray();


        OneBoolOuterClass.OneBool pbOd = OneBoolOuterClass.OneBool.parseFrom(pb);

        OneBoolUnboxed oneBool = ProtoBuf.toObject(pb, OneBoolUnboxed.class);


        assertEquals(pbOd.getValue(), oneBool.value);

    }

    @ParameterizedTest
    @ValueSource(booleans = {
            true,
            false
    })
    void testEncodeNoAccess(boolean value) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        OneBoolOuterClass.OneBool.Builder builder = OneBoolOuterClass.OneBool.newBuilder();
        builder.setValue(value);
        OneBoolOuterClass.OneBool od = builder.build();
        byte[] pb = od.toByteArray();

        Field field1F = ClazzUtil.getDeclaredField(OneBoolNoAccess.class, "value");
        field1F.setAccessible(true);

        OneBoolNoAccess oneBool = new OneBoolNoAccess();
        field1F.set(oneBool, value);
        byte[] epb = ProtoBuf.toByteArray(oneBool);

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(booleans = {
            true,
            false
    })
    void testEncodeUnboxedNoAccess(boolean value) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        OneBoolOuterClass.OneBool.Builder builder = OneBoolOuterClass.OneBool.newBuilder();
        builder.setValue(value);
        OneBoolOuterClass.OneBool od = builder.build();
        byte[] pb = od.toByteArray();

        Field field1F = ClazzUtil.getDeclaredField(OneBoolUnboxedNoAccess.class, "value");
        field1F.setAccessible(true);

        OneBoolUnboxedNoAccess oneBool = new OneBoolUnboxedNoAccess();
        field1F.set(oneBool, value);
        byte[] epb = ProtoBuf.toByteArray(oneBool);

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(booleans = {
            true,
            false
    })
    void testDecodeNoAccess(boolean value) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        OneBoolOuterClass.OneBool.Builder builder = OneBoolOuterClass.OneBool.newBuilder();
        builder.setValue(value);
        OneBoolOuterClass.OneBool od = builder.build();
        byte[] pb = od.toByteArray();


        OneBoolOuterClass.OneBool pbOd = OneBoolOuterClass.OneBool.parseFrom(pb);

        OneBoolNoAccess oneBool = ProtoBuf.toObject(pb, OneBoolNoAccess.class);

        Field fieldF = ClazzUtil.getDeclaredField(OneBoolNoAccess.class, "value");
        fieldF.setAccessible(true);

        assertEquals(pbOd.getValue(), fieldF.get(oneBool)==null?false:(boolean)fieldF.get(oneBool));

    }

    @ParameterizedTest
    @ValueSource(booleans = {
            true,
            false
    })
    void testDecodeUnboxedNoAccess(boolean value) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        OneBoolOuterClass.OneBool.Builder builder = OneBoolOuterClass.OneBool.newBuilder();
        builder.setValue(value);
        OneBoolOuterClass.OneBool od = builder.build();
        byte[] pb = od.toByteArray();


        OneBoolOuterClass.OneBool pbOd = OneBoolOuterClass.OneBool.parseFrom(pb);

        OneBoolUnboxedNoAccess oneBool = ProtoBuf.toObject(pb, OneBoolUnboxedNoAccess.class);

        Field fieldF = ClazzUtil.getDeclaredField(OneBoolUnboxedNoAccess.class, "value");
        fieldF.setAccessible(true);

        assertEquals(pbOd.getValue(), fieldF.get(oneBool)==null?false:(boolean)fieldF.get(oneBool));

    }
}
