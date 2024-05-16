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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestOneProtoEnum {

    @ParameterizedTest
    @ValueSource(strings = {
            "UNIVERSAL",
            "WEB",
            "IMAGES",
            "LOCAL",
            "NEWS",
            "PRODUCTS",
            "VIDEO"
    })
    void testEncode(String v) throws EncodeException {
        OneEnumOuterClass.OneEnum.Builder builder = OneEnumOuterClass.OneEnum.newBuilder();
        builder.setCorpus(OneEnumOuterClass.Corpus.valueOf(v));
        OneEnumOuterClass.OneEnum od = builder.build();
        byte[] pb = od.toByteArray();

        OneProtoEnum OneEnum = new OneProtoEnum();
        OneEnum.setCorpus(ProtoEnumCorpus.valueOf(v));
        byte[] epb = ProtoBuf.toByteArray(OneEnum);
        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UNIVERSAL",
            "WEB",
            "IMAGES",
            "LOCAL",
            "NEWS",
            "PRODUCTS",
            "VIDEO"
    })
    void testDecode(String v) throws InvalidProtocolBufferException, ProtoException {

        OneEnumOuterClass.OneEnum.Builder builder = OneEnumOuterClass.OneEnum.newBuilder();
        builder.setCorpus(OneEnumOuterClass.Corpus.valueOf(v));
        OneEnumOuterClass.OneEnum od = builder.build();
        byte[] pb = od.toByteArray();


        OneEnumOuterClass.OneEnum pbOd = OneEnumOuterClass.OneEnum.parseFrom(pb);

        OneProtoEnum OneEnum = ProtoBuf.toObject(pb, OneProtoEnum.class);


        assertEquals(pbOd.getCorpus().name(), OneEnum.getCorpus() == null ? Corpus.UNIVERSAL.name():OneEnum.getCorpus().name());

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UNIVERSAL",
            "WEB",
            "IMAGES",
            "LOCAL",
            "NEWS",
            "PRODUCTS",
            "VIDEO"
    })
    void testEncodeNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {
        OneEnumOuterClass.OneEnum.Builder builder = OneEnumOuterClass.OneEnum.newBuilder();
        builder.setCorpus(OneEnumOuterClass.Corpus.valueOf(v));
        OneEnumOuterClass.OneEnum od = builder.build();
        byte[] pb = od.toByteArray();

        Field fieldF = ClazzUtil.getDeclaredField(OneProtoEnumNoAccess.class, "corpus");
        fieldF.setAccessible(true);

        OneProtoEnumNoAccess oneEnum = new OneProtoEnumNoAccess();
        fieldF.set(oneEnum, ProtoEnumCorpus.valueOf(v));
        byte[] epb = ProtoBuf.toByteArray(oneEnum);
        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UNIVERSAL",
            "WEB",
            "IMAGES",
            "LOCAL",
            "NEWS",
            "PRODUCTS",
            "VIDEO"
    })
    void testDecodeNoAccess(String v) throws InvalidProtocolBufferException, ProtoException, NoSuchFieldException, IllegalAccessException {

        OneEnumOuterClass.OneEnum.Builder builder = OneEnumOuterClass.OneEnum.newBuilder();
        builder.setCorpus(OneEnumOuterClass.Corpus.valueOf(v));
        OneEnumOuterClass.OneEnum od = builder.build();
        byte[] pb = od.toByteArray();


        OneEnumOuterClass.OneEnum pbOd = OneEnumOuterClass.OneEnum.parseFrom(pb);

        OneProtoEnumNoAccess oneEnum = ProtoBuf.toObject(pb, OneProtoEnumNoAccess.class);
        Field fieldF =ClazzUtil.getDeclaredField(OneProtoEnumNoAccess.class, "corpus");
        fieldF.setAccessible(true);
        ProtoEnumCorpus corpus = (ProtoEnumCorpus)fieldF.get(oneEnum);
        assertEquals(pbOd.getCorpus().name(), corpus == null ?ProtoEnumCorpus.UNIVERSAL.name():corpus.name());

    }
}
