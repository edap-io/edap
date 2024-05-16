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
import io.edap.protobuf.CodecType;
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoException;
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


public class TestListSfixed64 {

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testEncode(String v) throws EncodeException {
        List<Long> vs = new ArrayList<>();
        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListSfixed64OuterClass.ListSfixed64.Builder builder = ListSfixed64OuterClass.ListSfixed64.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed64OuterClass.ListSfixed64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ListSfixed64 listSfixed64 = new ListSfixed64();
        listSfixed64.list = vs;
        byte[] epb = ProtoBuf.toByteArray(listSfixed64);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testDecode(String v) throws InvalidProtocolBufferException, ProtoException {

        List<Long> vs = new ArrayList<>();
        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListSfixed64OuterClass.ListSfixed64.Builder builder = ListSfixed64OuterClass.ListSfixed64.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed64OuterClass.ListSfixed64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListSfixed64OuterClass.ListSfixed64 pbOd = ListSfixed64OuterClass.ListSfixed64.parseFrom(pb);

        ListSfixed64 listSfixed64 = ProtoBuf.toObject(pb, ListSfixed64.class);


        assertEquals(pbOd.getValueList().size(), listSfixed64.list.size());
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), listSfixed64.list.get(i));
        }


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(CodecType.FAST);
        byte[] epb = ProtoBuf.toByteArray(listSfixed64, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        listSfixed64 = ProtoBuf.toObject(epb, ListSfixed64.class, option);
        assertEquals(od.getValueList().size(), listSfixed64.list.size());
        for (int i=0;i<od.getValueList().size();i++) {
            assertEquals(od.getValueList().get(i), listSfixed64.list.get(i));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testEncodeArray(String v) throws EncodeException {

        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        Long[] vs = new Long[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getLongValue(i);
            pvs.add(jvs.getLongValue(i));
        }

        ListSfixed64OuterClass.ListSfixed64.Builder builder = ListSfixed64OuterClass.ListSfixed64.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed64OuterClass.ListSfixed64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArraySfixed64 arraySfixed64 = new ArraySfixed64();
        arraySfixed64.list = vs;
        byte[] epb = ProtoBuf.toByteArray(arraySfixed64);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testDecodeArray(String v) throws InvalidProtocolBufferException, ProtoException {

        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            pvs.add(jvs.getLongValue(i));
        }

        ListSfixed64OuterClass.ListSfixed64.Builder builder = ListSfixed64OuterClass.ListSfixed64.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed64OuterClass.ListSfixed64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListSfixed64OuterClass.ListSfixed64 pbOd = ListSfixed64OuterClass.ListSfixed64.parseFrom(pb);

        ArraySfixed64 arraySfixed64 = ProtoBuf.toObject(pb, ArraySfixed64.class);


        assertEquals(pbOd.getValueList().size(), arraySfixed64.list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), arraySfixed64.list[i].longValue());
        }


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(CodecType.FAST);
        byte[] epb = ProtoBuf.toByteArray(arraySfixed64, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        arraySfixed64 = ProtoBuf.toObject(epb, ArraySfixed64.class, option);
        assertEquals(od.getValueList().size(), arraySfixed64.list.length);
        for (int i=0;i<od.getValueList().size();i++) {
            assertEquals(od.getValueList().get(i), arraySfixed64.list[i]);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testEncodeArrayUnboxed(String v) throws EncodeException {

        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        long[] vs = new long[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getLongValue(i);
            pvs.add(jvs.getLongValue(i));
        }

        ListSfixed64OuterClass.ListSfixed64.Builder builder = ListSfixed64OuterClass.ListSfixed64.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed64OuterClass.ListSfixed64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArraySfixed64Unboxed arraySfixed64 = new ArraySfixed64Unboxed();
        arraySfixed64.list = vs;
        byte[] epb = ProtoBuf.toByteArray(arraySfixed64);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testDecodeArrayUnboxed(String v) throws InvalidProtocolBufferException, ProtoException {

        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            pvs.add(jvs.getLongValue(i));
        }

        ListSfixed64OuterClass.ListSfixed64.Builder builder = ListSfixed64OuterClass.ListSfixed64.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed64OuterClass.ListSfixed64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListSfixed64OuterClass.ListSfixed64 pbOd = ListSfixed64OuterClass.ListSfixed64.parseFrom(pb);

        ArraySfixed64Unboxed arraySfixed64 = ProtoBuf.toObject(pb, ArraySfixed64Unboxed.class);


        assertEquals(pbOd.getValueList().size(), arraySfixed64.list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), arraySfixed64.list[i]);
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testEncodeNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {
        List<Long> vs = new ArrayList<>();
        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListSfixed64OuterClass.ListSfixed64.Builder builder = ListSfixed64OuterClass.ListSfixed64.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed64OuterClass.ListSfixed64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ListSfixed64NoAccess.class, "list");
        field1F.setAccessible(true);

        ListSfixed64NoAccess listSfixed64 = new ListSfixed64NoAccess();
        field1F.set(listSfixed64, vs);
        byte[] epb = ProtoBuf.toByteArray(listSfixed64);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testEncodeArrayNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        Long[] vs = new Long[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getLongValue(i);
            pvs.add(jvs.getLongValue(i));
        }

        ListSfixed64OuterClass.ListSfixed64.Builder builder = ListSfixed64OuterClass.ListSfixed64.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed64OuterClass.ListSfixed64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ArraySfixed64NoAccess.class, "list");
        field1F.setAccessible(true);

        ArraySfixed64NoAccess arraySfixed64 = new ArraySfixed64NoAccess();
        field1F.set(arraySfixed64, vs);
        byte[] epb = ProtoBuf.toByteArray(arraySfixed64);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testEncodeArrayUnboxedNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        long[] vs = new long[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getLongValue(i);
            pvs.add(jvs.getLongValue(i));
        }

        ListSfixed64OuterClass.ListSfixed64.Builder builder = ListSfixed64OuterClass.ListSfixed64.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed64OuterClass.ListSfixed64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ArraySfixed64UnboxedNoAccess.class, "list");
        field1F.setAccessible(true);

        ArraySfixed64UnboxedNoAccess arraySfixed64 = new ArraySfixed64UnboxedNoAccess();
        field1F.set(arraySfixed64, vs);
        byte[] epb = ProtoBuf.toByteArray(arraySfixed64);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testDecodeNoAccess(String v) throws InvalidProtocolBufferException, ProtoException, NoSuchFieldException, IllegalAccessException {

        List<Long> vs = new ArrayList<>();
        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListSfixed64OuterClass.ListSfixed64.Builder builder = ListSfixed64OuterClass.ListSfixed64.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed64OuterClass.ListSfixed64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListSfixed64OuterClass.ListSfixed64 pbOd = ListSfixed64OuterClass.ListSfixed64.parseFrom(pb);

        ListSfixed64NoAccess ListSfixed64 = ProtoBuf.toObject(pb, ListSfixed64NoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ListSfixed64NoAccess.class, "list");
        fieldF.setAccessible(true);

        List<Long> list = (List<Long>)fieldF.get(ListSfixed64);
        assertEquals(pbOd.getValueList().size(), list.size());
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), list.get(i));
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testDecodeArrayNoAccess(String v) throws InvalidProtocolBufferException, ProtoException, NoSuchFieldException, IllegalAccessException {

        List<Long> vs = new ArrayList<>();
        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListSfixed64OuterClass.ListSfixed64.Builder builder = ListSfixed64OuterClass.ListSfixed64.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed64OuterClass.ListSfixed64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListSfixed64OuterClass.ListSfixed64 pbOd = ListSfixed64OuterClass.ListSfixed64.parseFrom(pb);

        ArraySfixed64NoAccess ListSfixed64 = ProtoBuf.toObject(pb, ArraySfixed64NoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ArraySfixed64NoAccess.class, "list");
        fieldF.setAccessible(true);

        Long[] list = (Long[])fieldF.get(ListSfixed64);
        assertEquals(pbOd.getValueList().size(), list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), list[i].longValue());
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testDecodeArrayUnboxedNoAccess(String v) throws InvalidProtocolBufferException, ProtoException, NoSuchFieldException, IllegalAccessException {

        List<Long> vs = new ArrayList<>();
        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListSfixed64OuterClass.ListSfixed64.Builder builder = ListSfixed64OuterClass.ListSfixed64.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed64OuterClass.ListSfixed64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListSfixed64OuterClass.ListSfixed64 pbOd = ListSfixed64OuterClass.ListSfixed64.parseFrom(pb);

        ArraySfixed64UnboxedNoAccess ListSfixed64 = ProtoBuf.toObject(pb, ArraySfixed64UnboxedNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ArraySfixed64UnboxedNoAccess.class, "list");
        fieldF.setAccessible(true);

        long[] list = (long[])fieldF.get(ListSfixed64);
        assertEquals(pbOd.getValueList().size(), list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), list[i]);
        }

    }
}
