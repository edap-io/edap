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
import io.edap.protobuf.test.message.v3.OneDouble;
import io.edap.protobuf.test.message.v3.OneDoubleOuterClass;
import io.edap.protobuf.test.message.v3.OneDoubleUnboxed;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 基础类型double的编解码逻辑测试
 */
public class TestOneDouble {

    @ParameterizedTest
    @ValueSource(doubles = {
            1,
            31.415926
    })
    void testEncode(double value) {

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
            1,
            31.415926
    })
    void testDecode(double value) throws InvalidProtocolBufferException, ProtoBufException {

        OneDoubleOuterClass.OneDouble.Builder builder = OneDoubleOuterClass.OneDouble.newBuilder();
        builder.setD(value);
        OneDoubleOuterClass.OneDouble od = builder.build();
        byte[] pb = od.toByteArray();


        OneDoubleOuterClass.OneDouble pbOd = OneDoubleOuterClass.OneDouble.parseFrom(pb);

        OneDouble oneDouble = ProtoBuf.toObject(pb, OneDouble.class);


        assertEquals(pbOd.getD(), oneDouble.getD());

    }

    @ParameterizedTest
    @ValueSource(doubles = {
            1,
            31.415926
    })
    void testEncodeUnboxed(double value) {

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
            1,
            31.415926
    })
    void testDecodeUnboxed(double value) throws InvalidProtocolBufferException, ProtoBufException {

        OneDoubleOuterClass.OneDouble.Builder builder = OneDoubleOuterClass.OneDouble.newBuilder();
        builder.setD(value);
        OneDoubleOuterClass.OneDouble od = builder.build();
        byte[] pb = od.toByteArray();


        OneDoubleOuterClass.OneDouble pbOd = OneDoubleOuterClass.OneDouble.parseFrom(pb);

        OneDoubleUnboxed oneDouble = ProtoBuf.toObject(pb, OneDoubleUnboxed.class);


        assertEquals(pbOd.getD(), oneDouble.value);

    }
}
