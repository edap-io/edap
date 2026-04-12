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
import io.edap.protobuf.test.message.v3.Corpus;
import io.edap.protobuf.test.message.v3.OneEnum;
import io.edap.protobuf.test.message.v3.OneEnumNoAccess;
import io.edap.protobuf.test.message.v3.OneEnumOuterClass;
import io.edap.util.ClazzUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestOneEnum {

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

        OneEnum OneEnum = new OneEnum();
        OneEnum.setCorpus(Corpus.valueOf(v));
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

        OneEnum OneEnum = ProtoBuf.toObject(pb, OneEnum.class);


        assertEquals(pbOd.getCorpus().name(), OneEnum.getCorpus() == null ?Corpus.UNIVERSAL.name():OneEnum.getCorpus().name());

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

        Field fieldF = ClazzUtil.getDeclaredField(OneEnumNoAccess.class, "corpus");
        fieldF.setAccessible(true);

        OneEnumNoAccess oneEnum = new OneEnumNoAccess();
        fieldF.set(oneEnum, Corpus.valueOf(v));
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

        OneEnumNoAccess oneEnum = ProtoBuf.toObject(pb, OneEnumNoAccess.class);
        Field fieldF =ClazzUtil.getDeclaredField(OneEnumNoAccess.class, "corpus");
        fieldF.setAccessible(true);
        Corpus corpus = (Corpus)fieldF.get(oneEnum);
        assertEquals(pbOd.getCorpus().name(), corpus == null ?Corpus.UNIVERSAL.name():corpus.name());

    }
}
