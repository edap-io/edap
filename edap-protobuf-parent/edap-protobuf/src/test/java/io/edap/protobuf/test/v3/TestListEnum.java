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
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.model.ProtoBufOption;
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
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(Corpus.valueOf(jvs.getString(i)));
            pvs.add(OneEnumOuterClass.Corpus.valueOf(jvs.getString(i)));
        }

        ListEnumOuterClass.ListEnum.Builder builder = ListEnumOuterClass.ListEnum.newBuilder();
        builder.addAllCorpus(pvs);
        ListEnumOuterClass.ListEnum od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ListEnum listEnum = new ListEnum();
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
    void testDecode(String v) throws InvalidProtocolBufferException, ProtoException {

        List<Corpus> vs = new ArrayList<>();
        List<OneEnumOuterClass.Corpus> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
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


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        byte[] epb = ProtoBuf.toByteArray(listEnum, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        listEnum = ProtoBuf.toObject(epb, ListEnum.class, option);
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
        JsonArray jvs = JsonArray.parseArray(v);
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


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(arrayEnum, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        arrayEnum = ProtoBuf.toObject(epb, ArrayEnum.class, option);
        assertEquals(od.getCorpusList().size(), arrayEnum.values.length);
        for (int i=0;i<od.getCorpusList().size();i++) {
            assertEquals(od.getCorpusList().get(i).name(), arrayEnum.values[i].name());
        }
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
    void testDecodeArray(String v) throws InvalidProtocolBufferException, ProtoException {

        List<Corpus> vs = new ArrayList<>();
        List<OneEnumOuterClass.Corpus> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
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


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        byte[] epb = ProtoBuf.toByteArray(listEnum, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        listEnum = ProtoBuf.toObject(epb, ArrayEnum.class, option);
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
        List<Corpus> vs = new ArrayList<>();
        List<OneEnumOuterClass.Corpus> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
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

        ListEnumNoAccess listEnum = new ListEnumNoAccess();
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


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(arrayEnum, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        arrayEnum = ProtoBuf.toObject(epb, ArrayEnumNoAccess.class, option);
        assertEquals(od.getCorpusList().size(), ((Corpus[])field1F.get(arrayEnum)).length);
        for (int i=0;i<od.getCorpusList().size();i++) {
            assertEquals(od.getCorpusList().get(i).name(), ((Corpus[])field1F.get(arrayEnum))[i].name());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"UNIVERSAL\",\"WEB\",\"IMAGES\",\"LOCAL\",\"NEWS\",\"PRODUCTS\",\"VIDEO\"]",
            "[\"UNIVERSAL\",\"VIDEO\",\"NEWS\"]"
    })
    void testDecodeNoAccess(String v) throws InvalidProtocolBufferException, ProtoException, NoSuchFieldException, IllegalAccessException {

        List<Corpus> vs = new ArrayList<>();
        List<OneEnumOuterClass.Corpus> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
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


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        byte[] epb = ProtoBuf.toByteArray(listEnum, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        listEnum = ProtoBuf.toObject(epb, ListEnumNoAccess.class, option);
        assertEquals(od.getCorpusList().size(), ((List)fieldF.get(listEnum)).size());
        for (int i=0;i<od.getCorpusList().size();i++) {
            assertEquals(od.getCorpusList().get(i).name(), ((List<Corpus>)fieldF.get(listEnum)).get(i).name());
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"UNIVERSAL\",\"WEB\",\"IMAGES\",\"LOCAL\",\"NEWS\",\"PRODUCTS\",\"VIDEO\"]",
            "[\"UNIVERSAL\",\"VIDEO\",\"NEWS\"]"
    })
    void testDecodeArrayNoAccess(String v) throws InvalidProtocolBufferException, ProtoException, NoSuchFieldException, IllegalAccessException {

        List<Corpus> vs = new ArrayList<>();
        List<OneEnumOuterClass.Corpus> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
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


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        byte[] epb = ProtoBuf.toByteArray(listEnum, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        listEnum = ProtoBuf.toObject(epb, ArrayEnumNoAccess.class, option);
        assertEquals(od.getCorpusList().size(), ((Corpus[])fieldF.get(listEnum)).length);
        for (int i=0;i<od.getCorpusList().size();i++) {
            assertEquals(od.getCorpusList().get(i).name(), ((Corpus[])fieldF.get(listEnum))[i].name());
        }
    }
}
