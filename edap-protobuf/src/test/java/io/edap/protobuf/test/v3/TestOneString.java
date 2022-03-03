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
import io.edap.protobuf.test.message.v3.OneString;
import io.edap.protobuf.test.message.v3.OneStringNoAccess;
import io.edap.protobuf.test.message.v3.OneStringOuterClass;
import io.edap.util.ClazzUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestOneString {

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "a",
            "abcdefgh",
            "中文内容",
            "🐶头",
            "\u0080"
    })
    void testEncode(String value) throws EncodeException {

        OneStringOuterClass.OneString.Builder builder = OneStringOuterClass.OneString.newBuilder();
        builder.setValue(value);
        OneStringOuterClass.OneString oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneString oneString = new OneString();
        oneString.setValue(value);
        byte[] epb = ProtoBuf.toByteArray(oneString);


        assertArrayEquals(pb, epb);
    }

    @Test
    void testEncodeNull() throws EncodeException {
        OneString oneString = new OneString();
        byte[] epb = ProtoBuf.toByteArray(oneString);


        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeEmpty() throws EncodeException {
        OneString oneString = new OneString();
        oneString.setValue("");
        byte[] epb = ProtoBuf.toByteArray(oneString);


        assertArrayEquals(new byte[0], epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "a",
            "abcdefgh",
            "中文内容"
    })
    void testDecode(String value) throws InvalidProtocolBufferException, ProtoBufException {

        OneStringOuterClass.OneString.Builder builder = OneStringOuterClass.OneString.newBuilder();
        builder.setValue(value);
        OneStringOuterClass.OneString oint32 = builder.build();
        byte[] pb = oint32.toByteArray();


        OneStringOuterClass.OneString pbOf = OneStringOuterClass.OneString.parseFrom(pb);

        OneString oneString = ProtoBuf.toObject(pb, OneString.class);


        assertEquals(pbOf.getValue(), oneString.getValue());

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "a",
            "abcdefgh",
            "中文内容"
    })
    void testEncodeNoAccess(String value) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        OneStringOuterClass.OneString.Builder builder = OneStringOuterClass.OneString.newBuilder();
        builder.setValue(value);
        OneStringOuterClass.OneString oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");

        Field fieldF = ClazzUtil.getDeclaredField(OneStringNoAccess.class, "value");
        fieldF.setAccessible(true);

        OneStringNoAccess oneString = new OneStringNoAccess();
        fieldF.set(oneString, value);
        byte[] epb = ProtoBuf.toByteArray(oneString);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "a",
            "abcdefgh",
            "中文内容"
    })
    void testDecodeNoAccess(String value) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        OneStringOuterClass.OneString.Builder builder = OneStringOuterClass.OneString.newBuilder();
        builder.setValue(value);
        OneStringOuterClass.OneString oint32 = builder.build();
        byte[] pb = oint32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");

        OneStringOuterClass.OneString pbOf = OneStringOuterClass.OneString.parseFrom(pb);

        OneStringNoAccess oneString = ProtoBuf.toObject(pb, OneStringNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(OneStringNoAccess.class, "value");
        fieldF.setAccessible(true);

        assertEquals(pbOf.getValue(), (String)fieldF.get(oneString));

    }
}
