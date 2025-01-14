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

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.test.message.v3.OneBytes;
import io.edap.protobuf.test.message.v3.OneBytesNoAccess;
import io.edap.protobuf.test.message.v3.OneBytesOuterClass;
import io.edap.util.ClazzUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class TestOneBytes {

    @ParameterizedTest
    @ValueSource(strings = {
            "abcdefghijklmn",
            "中文内容"
    })
    void testEncode(String v) throws UnsupportedEncodingException, EncodeException {
        byte[] value = v.getBytes("utf-8");
        OneBytesOuterClass.OneBytes.Builder builder = OneBytesOuterClass.OneBytes.newBuilder();
        builder.setValue(ByteString.copyFrom(value));
        OneBytesOuterClass.OneBytes od = builder.build();
        byte[] pb = od.toByteArray();

        OneBytes oneBytes = new OneBytes();
        oneBytes.setValue(value);
        byte[] epb = ProtoBuf.toByteArray(oneBytes);

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "abcdefghijklmn",
            "中文内容"
    })
    void testDecode(String v) throws InvalidProtocolBufferException, ProtoException, UnsupportedEncodingException {

        byte[] value = v.getBytes("utf-8");
        OneBytesOuterClass.OneBytes.Builder builder = OneBytesOuterClass.OneBytes.newBuilder();
        builder.setValue(ByteString.copyFrom(value));
        OneBytesOuterClass.OneBytes od = builder.build();
        byte[] pb = od.toByteArray();


        OneBytesOuterClass.OneBytes pbOd = OneBytesOuterClass.OneBytes.parseFrom(pb);

        OneBytes oneBytes = ProtoBuf.toObject(pb, OneBytes.class);


        assertArrayEquals(pbOd.getValue().toByteArray(), oneBytes.getValue());

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "abcdefghijklmn",
            "中文内容"
    })
    void testEncodeNoAccess(String v) throws UnsupportedEncodingException, EncodeException, NoSuchFieldException, IllegalAccessException {
        byte[] value = v.getBytes("utf-8");
        OneBytesOuterClass.OneBytes.Builder builder = OneBytesOuterClass.OneBytes.newBuilder();
        builder.setValue(ByteString.copyFrom(value));
        OneBytesOuterClass.OneBytes od = builder.build();
        byte[] pb = od.toByteArray();

        Field field1F = ClazzUtil.getDeclaredField(OneBytesNoAccess.class, "value");
        field1F.setAccessible(true);

        OneBytesNoAccess oneBytes = new OneBytesNoAccess();
        field1F.set(oneBytes, value);
        byte[] epb = ProtoBuf.toByteArray(oneBytes);

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "abcdefghijklmn",
            "中文内容"
    })
    void testDecodeNoAccess(String v) throws InvalidProtocolBufferException, ProtoException, UnsupportedEncodingException, NoSuchFieldException, IllegalAccessException {

        byte[] value = v.getBytes("utf-8");
        OneBytesOuterClass.OneBytes.Builder builder = OneBytesOuterClass.OneBytes.newBuilder();
        builder.setValue(ByteString.copyFrom(value));
        OneBytesOuterClass.OneBytes od = builder.build();
        byte[] pb = od.toByteArray();


        OneBytesOuterClass.OneBytes pbOd = OneBytesOuterClass.OneBytes.parseFrom(pb);

        OneBytesNoAccess oneBytes = ProtoBuf.toObject(pb, OneBytesNoAccess.class);

        Field fieldF = ClazzUtil.getDeclaredField(OneBytesNoAccess.class, "value");
        fieldF.setAccessible(true);

        System.out.println(conver2HexStr(pbOd.getValue().toByteArray()));
        System.out.println(conver2HexStr((byte[])fieldF.get(oneBytes)));

        assertArrayEquals(pbOd.getValue().toByteArray(), (byte[])fieldF.get(oneBytes));

    }
}
