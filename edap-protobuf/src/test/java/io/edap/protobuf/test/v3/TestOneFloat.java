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
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoBufException;
import io.edap.protobuf.test.message.v3.OneFloat;
import io.edap.protobuf.test.message.v3.OneFloatOuterClass;
import io.edap.protobuf.test.message.v3.OneFloatUnboxed;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 基础类型float的编解码逻辑测试
 */
public class TestOneFloat {

    @ParameterizedTest
    @ValueSource(floats = {
            1f,
            31.415926f
    })
    void testEncode(float value) {

        OneFloatOuterClass.OneFloat.Builder builder = OneFloatOuterClass.OneFloat.newBuilder();
        builder.setF(value);
        OneFloatOuterClass.OneFloat of = builder.build();
        byte[] pb = of.toByteArray();

        OneFloat oneFloat = new OneFloat();
        oneFloat.setF(value);
        byte[] epb = ProtoBuf.toByteArray(oneFloat);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(floats = {
            1f,
            31.415926f
    })
    void testDecode(float value) throws InvalidProtocolBufferException, ProtoBufException {

        OneFloatOuterClass.OneFloat.Builder builder = OneFloatOuterClass.OneFloat.newBuilder();
        builder.setF(value);
        OneFloatOuterClass.OneFloat of = builder.build();
        byte[] pb = of.toByteArray();


        OneFloatOuterClass.OneFloat pbOf = OneFloatOuterClass.OneFloat.parseFrom(pb);

        OneFloat oneFloat = ProtoBuf.toObject(pb, OneFloat.class);


        assertEquals(pbOf.getF(), oneFloat.getF());

    }

    @ParameterizedTest
    @ValueSource(floats = {
            1f,
            31.415926f
    })
    void testEncodeUnboxed(float value) {

        OneFloatOuterClass.OneFloat.Builder builder = OneFloatOuterClass.OneFloat.newBuilder();
        builder.setF(value);
        OneFloatOuterClass.OneFloat of = builder.build();
        byte[] pb = of.toByteArray();

        OneFloatUnboxed oneFloat = new OneFloatUnboxed();
        oneFloat.value =  value;
        byte[] epb = ProtoBuf.toByteArray(oneFloat);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(floats = {
            1f,
            31.415926f
    })
    void testDecodeUnboxed(float value) throws InvalidProtocolBufferException, ProtoBufException {

        OneFloatOuterClass.OneFloat.Builder builder = OneFloatOuterClass.OneFloat.newBuilder();
        builder.setF(value);
        OneFloatOuterClass.OneFloat of = builder.build();
        byte[] pb = of.toByteArray();


        OneFloatOuterClass.OneFloat pbOf = OneFloatOuterClass.OneFloat.parseFrom(pb);

        OneFloatUnboxed oneFloat = ProtoBuf.toObject(pb, OneFloatUnboxed.class);


        assertEquals(pbOf.getF(), oneFloat.value);

    }
}
