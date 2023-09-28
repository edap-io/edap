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
import io.edap.json.JsonArray;
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoBufException;
import io.edap.protobuf.model.ProtoBufOption;
import io.edap.protobuf.test.message.v3.*;
import io.edap.util.ClazzUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;


public class TestListProtoEnum {

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"UNIVERSAL\",\"WEB\",\"IMAGES\",\"LOCAL\",\"NEWS\",\"PRODUCTS\",\"VIDEO\"]",
            "[\"UNIVERSAL\",\"VIDEO\",\"NEWS\"]"
    })
    void testEncode(String v) throws EncodeException {
        List<ProtoEnumCorpus> vs = new ArrayList<>();
        List<OneEnumOuterClass.Corpus> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(ProtoEnumCorpus.valueOf(jvs.getString(i)));
            pvs.add(OneEnumOuterClass.Corpus.valueOf(jvs.getString(i)));
        }

        ListEnumOuterClass.ListEnum.Builder builder = ListEnumOuterClass.ListEnum.newBuilder();
        builder.addAllCorpus(pvs);
        ListEnumOuterClass.ListEnum od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ListProtoEnum listEnum = new ListProtoEnum();
        listEnum.list = vs;
        byte[] epb = ProtoBuf.toByteArray(listEnum);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(listEnum, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"UNIVERSAL\",\"WEB\",\"IMAGES\",\"LOCAL\",\"NEWS\",\"PRODUCTS\",\"VIDEO\"]",
            "[\"UNIVERSAL\",\"VIDEO\",\"NEWS\"]"
    })
    void testDecode(String v) throws InvalidProtocolBufferException, ProtoBufException {

        List<ProtoEnumCorpus> vs = new ArrayList<>();
        List<OneEnumOuterClass.Corpus> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(ProtoEnumCorpus.valueOf(jvs.getString(i)));
            pvs.add(OneEnumOuterClass.Corpus.valueOf(jvs.getString(i)));
        }

        ListEnumOuterClass.ListEnum.Builder builder = ListEnumOuterClass.ListEnum.newBuilder();
        builder.addAllCorpus(pvs);
        ListEnumOuterClass.ListEnum od = builder.build();
        byte[] pb = od.toByteArray();


        ListEnumOuterClass.ListEnum pbOd = ListEnumOuterClass.ListEnum.parseFrom(pb);

        ListProtoEnum listEnum = ProtoBuf.toObject(pb, ListProtoEnum.class);


        assertEquals(pbOd.getCorpusList().size(), listEnum.list.size());
        for (int i=0;i<pbOd.getCorpusList().size();i++) {
            assertEquals(pbOd.getCorpusList().get(i).name(), listEnum.list.get(i).name());
        }


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        byte[] epb = ProtoBuf.toByteArray(listEnum, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        listEnum = ProtoBuf.toObject(epb, ListProtoEnum.class, option);
        assertEquals(od.getCorpusList().size(), listEnum.list.size());
        for (int i=0;i<od.getCorpusList().size();i++) {
            assertEquals(od.getCorpusList().get(i).name(), listEnum.list.get(i).name());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"UNIVERSAL\",\"WEB\",\"IMAGES\",\"LOCAL\",\"NEWS\",\"PRODUCTS\",\"VIDEO\"]",
            "[\"UNIVERSAL\",\"VIDEO\",\"NEWS\"]"
    })
    void testEncodeArray(String v) throws EncodeException {

        List<OneEnumOuterClass.Corpus> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        ProtoEnumCorpus[] vs = new ProtoEnumCorpus[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = ProtoEnumCorpus.valueOf(jvs.getString(i));
            pvs.add(OneEnumOuterClass.Corpus.valueOf(jvs.getString(i)));
        }

        ListEnumOuterClass.ListEnum.Builder builder = ListEnumOuterClass.ListEnum.newBuilder();
        builder.addAllCorpus(pvs);
        ListEnumOuterClass.ListEnum od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArrayProtoEnum arrayEnum = new ArrayProtoEnum();
        arrayEnum.values = vs;
        byte[] epb = ProtoBuf.toByteArray(arrayEnum);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(arrayEnum, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"UNIVERSAL\",\"WEB\",\"IMAGES\",\"LOCAL\",\"NEWS\",\"PRODUCTS\",\"VIDEO\"]",
            "[\"UNIVERSAL\",\"VIDEO\",\"NEWS\"]"
    })
    void testDecodeArray(String v) throws InvalidProtocolBufferException, ProtoBufException {

        List<ProtoEnumCorpus> vs = new ArrayList<>();
        List<OneEnumOuterClass.Corpus> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(ProtoEnumCorpus.valueOf(jvs.getString(i)));
            pvs.add(OneEnumOuterClass.Corpus.valueOf(jvs.getString(i)));
        }

        ListEnumOuterClass.ListEnum.Builder builder = ListEnumOuterClass.ListEnum.newBuilder();
        builder.addAllCorpus(pvs);
        ListEnumOuterClass.ListEnum od = builder.build();
        byte[] pb = od.toByteArray();


        ListEnumOuterClass.ListEnum pbOd = ListEnumOuterClass.ListEnum.parseFrom(pb);

        ArrayProtoEnum listEnum = ProtoBuf.toObject(pb, ArrayProtoEnum.class);


        assertEquals(pbOd.getCorpusList().size(), listEnum.values.length);
        for (int i=0;i<pbOd.getCorpusList().size();i++) {
            assertEquals(pbOd.getCorpusList().get(i).name(), listEnum.values[i].name());
        }


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        byte[] epb = ProtoBuf.toByteArray(listEnum, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        listEnum = ProtoBuf.toObject(epb, ArrayProtoEnum.class, option);
        assertEquals(od.getCorpusList().size(), listEnum.values.length);
        for (int i=0;i<od.getCorpusList().size();i++) {
            assertEquals(od.getCorpusList().get(i).name(), listEnum.values[i].name());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"UNIVERSAL\",\"WEB\",\"IMAGES\",\"LOCAL\",\"NEWS\",\"PRODUCTS\",\"VIDEO\"]",
            "[\"UNIVERSAL\",\"VIDEO\",\"NEWS\"]"
    })
    void testEncodeNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {
        List<ProtoEnumCorpus> vs = new ArrayList<>();
        List<OneEnumOuterClass.Corpus> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(ProtoEnumCorpus.valueOf(jvs.getString(i)));
            pvs.add(OneEnumOuterClass.Corpus.valueOf(jvs.getString(i)));
        }

        ListEnumOuterClass.ListEnum.Builder builder = ListEnumOuterClass.ListEnum.newBuilder();
        builder.addAllCorpus(pvs);
        ListEnumOuterClass.ListEnum od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ListProtoEnumNoAccess.class, "list");
        field1F.setAccessible(true);

        ListProtoEnumNoAccess listEnum = new ListProtoEnumNoAccess();
        field1F.set(listEnum, vs);
        byte[] epb = ProtoBuf.toByteArray(listEnum);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(listEnum, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"UNIVERSAL\",\"WEB\",\"IMAGES\",\"LOCAL\",\"NEWS\",\"PRODUCTS\",\"VIDEO\"]",
            "[\"UNIVERSAL\",\"VIDEO\",\"NEWS\"]"
    })
    void testEncodeArrayNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        List<OneEnumOuterClass.Corpus> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        ProtoEnumCorpus[] vs = new ProtoEnumCorpus[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = ProtoEnumCorpus.valueOf(jvs.getString(i));
            pvs.add(OneEnumOuterClass.Corpus.valueOf(jvs.getString(i)));
        }

        ListEnumOuterClass.ListEnum.Builder builder = ListEnumOuterClass.ListEnum.newBuilder();
        builder.addAllCorpus(pvs);
        ListEnumOuterClass.ListEnum od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ArrayProtoEnumNoAccess.class, "values");
        field1F.setAccessible(true);

        ArrayProtoEnumNoAccess arrayEnum = new ArrayProtoEnumNoAccess();
        field1F.set(arrayEnum, vs);
        byte[] epb = ProtoBuf.toByteArray(arrayEnum);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(arrayEnum, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"UNIVERSAL\",\"WEB\",\"IMAGES\",\"LOCAL\",\"NEWS\",\"PRODUCTS\",\"VIDEO\"]",
            "[\"UNIVERSAL\",\"VIDEO\",\"NEWS\"]"
    })
    void testDecodeNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        List<ProtoEnumCorpus> vs = new ArrayList<>();
        List<OneEnumOuterClass.Corpus> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(ProtoEnumCorpus.valueOf(jvs.getString(i)));
            pvs.add(OneEnumOuterClass.Corpus.valueOf(jvs.getString(i)));
        }

        ListEnumOuterClass.ListEnum.Builder builder = ListEnumOuterClass.ListEnum.newBuilder();
        builder.addAllCorpus(pvs);
        ListEnumOuterClass.ListEnum od = builder.build();
        byte[] pb = od.toByteArray();


        ListEnumOuterClass.ListEnum pbOd = ListEnumOuterClass.ListEnum.parseFrom(pb);

        ListProtoEnumNoAccess listEnum = ProtoBuf.toObject(pb, ListProtoEnumNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ListProtoEnumNoAccess.class, "list");
        fieldF.setAccessible(true);

        List<ProtoEnumCorpus> list = (List<ProtoEnumCorpus>)fieldF.get(listEnum);
        assertEquals(pbOd.getCorpusList().size(), list.size());
        for (int i=0;i<pbOd.getCorpusList().size();i++) {
            assertEquals(pbOd.getCorpusList().get(i).name(), list.get(i).name());
        }


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        byte[] epb = ProtoBuf.toByteArray(listEnum, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        listEnum = ProtoBuf.toObject(epb, ListProtoEnumNoAccess.class, option);
        assertEquals(od.getCorpusList().size(), ((List<ProtoEnumCorpus>)fieldF.get(listEnum)).size());
        for (int i=0;i<od.getCorpusList().size();i++) {
            assertEquals(od.getCorpusList().get(i).name(), ((List<ProtoEnumCorpus>)fieldF.get(listEnum)).get(i).name());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"UNIVERSAL\",\"WEB\",\"IMAGES\",\"LOCAL\",\"NEWS\",\"PRODUCTS\",\"VIDEO\"]",
            "[\"UNIVERSAL\",\"VIDEO\",\"NEWS\"]"
    })
    void testDecodeArrayNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        List<ProtoEnumCorpus> vs = new ArrayList<>();
        List<OneEnumOuterClass.Corpus> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(ProtoEnumCorpus.valueOf(jvs.getString(i)));
            pvs.add(OneEnumOuterClass.Corpus.valueOf(jvs.getString(i)));
        }

        ListEnumOuterClass.ListEnum.Builder builder = ListEnumOuterClass.ListEnum.newBuilder();
        builder.addAllCorpus(pvs);
        ListEnumOuterClass.ListEnum od = builder.build();
        byte[] pb = od.toByteArray();


        ListEnumOuterClass.ListEnum pbOd = ListEnumOuterClass.ListEnum.parseFrom(pb);

        ArrayProtoEnumNoAccess listEnum = ProtoBuf.toObject(pb, ArrayProtoEnumNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ArrayProtoEnumNoAccess.class, "values");
        fieldF.setAccessible(true);

        ProtoEnumCorpus[] values = (ProtoEnumCorpus[])fieldF.get(listEnum);
        assertEquals(pbOd.getCorpusList().size(), values.length);
        for (int i=0;i<pbOd.getCorpusList().size();i++) {
            assertEquals(pbOd.getCorpusList().get(i).name(), values[i].name());
        }


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        byte[] epb = ProtoBuf.toByteArray(listEnum, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        listEnum = ProtoBuf.toObject(epb, ArrayProtoEnumNoAccess.class, option);
        assertEquals(od.getCorpusList().size(), ((ProtoEnumCorpus[])fieldF.get(listEnum)).length);
        for (int i=0;i<od.getCorpusList().size();i++) {
            assertEquals(od.getCorpusList().get(i).name(), ((ProtoEnumCorpus[])fieldF.get(listEnum))[i].name());
        }
    }
}
