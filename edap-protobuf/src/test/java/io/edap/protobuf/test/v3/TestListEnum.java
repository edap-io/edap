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

import com.alibaba.fastjson.JSONArray;
import com.google.protobuf.InvalidProtocolBufferException;
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoBufException;
import io.edap.protobuf.test.message.v3.*;
import io.edap.util.ClazzUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;


public class TestListEnum {

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"UNIVERSAL\",\"WEB\",\"IMAGES\",\"LOCAL\",\"NEWS\",\"PRODUCTS\",\"VIDEO\"]",
            "[\"UNIVERSAL\",\"VIDEO\",\"NEWS\"]"
    })
    void testEncode(String v) throws EncodeException {
        List<Corpus> vs = new ArrayList<>();
        List<OneEnumOuterClass.Corpus> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(Corpus.valueOf(jvs.getString(i)));
            pvs.add(OneEnumOuterClass.Corpus.valueOf(jvs.getString(i)));
        }

        ListEnumOuterClass.ListEnum.Builder builder = ListEnumOuterClass.ListEnum.newBuilder();
        builder.addAllCorpus(pvs);
        ListEnumOuterClass.ListEnum od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ListEnum ListEnum = new ListEnum();
        ListEnum.list = vs;
        byte[] epb = ProtoBuf.toByteArray(ListEnum);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"UNIVERSAL\",\"WEB\",\"IMAGES\",\"LOCAL\",\"NEWS\",\"PRODUCTS\",\"VIDEO\"]",
            "[\"UNIVERSAL\",\"VIDEO\",\"NEWS\"]"
    })
    void testDecode(String v) throws InvalidProtocolBufferException, ProtoBufException {

        List<Corpus> vs = new ArrayList<>();
        List<OneEnumOuterClass.Corpus> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(Corpus.valueOf(jvs.getString(i)));
            pvs.add(OneEnumOuterClass.Corpus.valueOf(jvs.getString(i)));
        }

        ListEnumOuterClass.ListEnum.Builder builder = ListEnumOuterClass.ListEnum.newBuilder();
        builder.addAllCorpus(pvs);
        ListEnumOuterClass.ListEnum od = builder.build();
        byte[] pb = od.toByteArray();


        ListEnumOuterClass.ListEnum pbOd = ListEnumOuterClass.ListEnum.parseFrom(pb);

        ListEnum listEnum = ProtoBuf.toObject(pb, ListEnum.class);


        assertEquals(pbOd.getCorpusList().size(), listEnum.list.size());
        for (int i=0;i<pbOd.getCorpusList().size();i++) {
            assertEquals(pbOd.getCorpusList().get(i).name(), listEnum.list.get(i).name());
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"UNIVERSAL\",\"WEB\",\"IMAGES\",\"LOCAL\",\"NEWS\",\"PRODUCTS\",\"VIDEO\"]",
            "[\"UNIVERSAL\",\"VIDEO\",\"NEWS\"]"
    })
    void testEncodeArray(String v) throws EncodeException {

        List<OneEnumOuterClass.Corpus> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        Corpus[] vs = new Corpus[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = Corpus.valueOf(jvs.getString(i));
            pvs.add(OneEnumOuterClass.Corpus.valueOf(jvs.getString(i)));
        }

        ListEnumOuterClass.ListEnum.Builder builder = ListEnumOuterClass.ListEnum.newBuilder();
        builder.addAllCorpus(pvs);
        ListEnumOuterClass.ListEnum od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArrayEnum arrayEnum = new ArrayEnum();
        arrayEnum.values = vs;
        byte[] epb = ProtoBuf.toByteArray(arrayEnum);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @Test
    void testEncodeArrayNull() throws EncodeException {
        ArrayEnum arrayEnum = new ArrayEnum();
        byte[] epb = ProtoBuf.toByteArray(arrayEnum);

        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeArrayEmpty() throws EncodeException {
        ArrayEnum arrayEnum = new ArrayEnum();
        arrayEnum.values = new Corpus[0];
        byte[] epb = ProtoBuf.toByteArray(arrayEnum);

        assertArrayEquals(new byte[0], epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"UNIVERSAL\",\"WEB\",\"IMAGES\",\"LOCAL\",\"NEWS\",\"PRODUCTS\",\"VIDEO\"]",
            "[\"UNIVERSAL\",\"VIDEO\",\"NEWS\"]"
    })
    void testDecodeArray(String v) throws InvalidProtocolBufferException, ProtoBufException {

        List<Corpus> vs = new ArrayList<>();
        List<OneEnumOuterClass.Corpus> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(Corpus.valueOf(jvs.getString(i)));
            pvs.add(OneEnumOuterClass.Corpus.valueOf(jvs.getString(i)));
        }

        ListEnumOuterClass.ListEnum.Builder builder = ListEnumOuterClass.ListEnum.newBuilder();
        builder.addAllCorpus(pvs);
        ListEnumOuterClass.ListEnum od = builder.build();
        byte[] pb = od.toByteArray();


        ListEnumOuterClass.ListEnum pbOd = ListEnumOuterClass.ListEnum.parseFrom(pb);

        ArrayEnum listEnum = ProtoBuf.toObject(pb, ArrayEnum.class);


        assertEquals(pbOd.getCorpusList().size(), listEnum.values.length);
        for (int i=0;i<pbOd.getCorpusList().size();i++) {
            assertEquals(pbOd.getCorpusList().get(i).name(), listEnum.values[i].name());
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"UNIVERSAL\",\"WEB\",\"IMAGES\",\"LOCAL\",\"NEWS\",\"PRODUCTS\",\"VIDEO\"]",
            "[\"UNIVERSAL\",\"VIDEO\",\"NEWS\"]"
    })
    void testEncodeNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {
        List<Corpus> vs = new ArrayList<>();
        List<OneEnumOuterClass.Corpus> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(Corpus.valueOf(jvs.getString(i)));
            pvs.add(OneEnumOuterClass.Corpus.valueOf(jvs.getString(i)));
        }

        ListEnumOuterClass.ListEnum.Builder builder = ListEnumOuterClass.ListEnum.newBuilder();
        builder.addAllCorpus(pvs);
        ListEnumOuterClass.ListEnum od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ListEnumNoAccess.class, "list");
        field1F.setAccessible(true);

        ListEnumNoAccess ListEnum = new ListEnumNoAccess();
        field1F.set(ListEnum, vs);
        byte[] epb = ProtoBuf.toByteArray(ListEnum);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"UNIVERSAL\",\"WEB\",\"IMAGES\",\"LOCAL\",\"NEWS\",\"PRODUCTS\",\"VIDEO\"]",
            "[\"UNIVERSAL\",\"VIDEO\",\"NEWS\"]"
    })
    void testEncodeArrayNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        List<OneEnumOuterClass.Corpus> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        Corpus[] vs = new Corpus[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = Corpus.valueOf(jvs.getString(i));
            pvs.add(OneEnumOuterClass.Corpus.valueOf(jvs.getString(i)));
        }

        ListEnumOuterClass.ListEnum.Builder builder = ListEnumOuterClass.ListEnum.newBuilder();
        builder.addAllCorpus(pvs);
        ListEnumOuterClass.ListEnum od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ArrayEnumNoAccess.class, "values");
        field1F.setAccessible(true);

        ArrayEnumNoAccess arrayEnum = new ArrayEnumNoAccess();
        field1F.set(arrayEnum, vs);
        byte[] epb = ProtoBuf.toByteArray(arrayEnum);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"UNIVERSAL\",\"WEB\",\"IMAGES\",\"LOCAL\",\"NEWS\",\"PRODUCTS\",\"VIDEO\"]",
            "[\"UNIVERSAL\",\"VIDEO\",\"NEWS\"]"
    })
    void testDecodeNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        List<Corpus> vs = new ArrayList<>();
        List<OneEnumOuterClass.Corpus> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(Corpus.valueOf(jvs.getString(i)));
            pvs.add(OneEnumOuterClass.Corpus.valueOf(jvs.getString(i)));
        }

        ListEnumOuterClass.ListEnum.Builder builder = ListEnumOuterClass.ListEnum.newBuilder();
        builder.addAllCorpus(pvs);
        ListEnumOuterClass.ListEnum od = builder.build();
        byte[] pb = od.toByteArray();


        ListEnumOuterClass.ListEnum pbOd = ListEnumOuterClass.ListEnum.parseFrom(pb);

        ListEnumNoAccess listEnum = ProtoBuf.toObject(pb, ListEnumNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ListEnumNoAccess.class, "list");
        fieldF.setAccessible(true);

        List<Corpus> list = (List<Corpus>)fieldF.get(listEnum);
        assertEquals(pbOd.getCorpusList().size(), list.size());
        for (int i=0;i<pbOd.getCorpusList().size();i++) {
            assertEquals(pbOd.getCorpusList().get(i).name(), list.get(i).name());
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"UNIVERSAL\",\"WEB\",\"IMAGES\",\"LOCAL\",\"NEWS\",\"PRODUCTS\",\"VIDEO\"]",
            "[\"UNIVERSAL\",\"VIDEO\",\"NEWS\"]"
    })
    void testDecodeArrayNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        List<Corpus> vs = new ArrayList<>();
        List<OneEnumOuterClass.Corpus> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(Corpus.valueOf(jvs.getString(i)));
            pvs.add(OneEnumOuterClass.Corpus.valueOf(jvs.getString(i)));
        }

        ListEnumOuterClass.ListEnum.Builder builder = ListEnumOuterClass.ListEnum.newBuilder();
        builder.addAllCorpus(pvs);
        ListEnumOuterClass.ListEnum od = builder.build();
        byte[] pb = od.toByteArray();


        ListEnumOuterClass.ListEnum pbOd = ListEnumOuterClass.ListEnum.parseFrom(pb);

        ArrayEnumNoAccess listEnum = ProtoBuf.toObject(pb, ArrayEnumNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ArrayEnumNoAccess.class, "values");
        fieldF.setAccessible(true);

        Corpus[] values = (Corpus[])fieldF.get(listEnum);
        assertEquals(pbOd.getCorpusList().size(), values.length);
        for (int i=0;i<pbOd.getCorpusList().size();i++) {
            assertEquals(pbOd.getCorpusList().get(i).name(), values[i].name());
        }

    }
}
