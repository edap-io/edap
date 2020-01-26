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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;


public class TestListSint64 {

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testEncode(String v) throws EncodeException {
        List<Long> vs = new ArrayList<>();
        List<Long> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListSint64OuterClass.ListSint64.Builder builder = ListSint64OuterClass.ListSint64.newBuilder();
        builder.addAllValue(pvs);
        ListSint64OuterClass.ListSint64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ListSint64 listSint64 = new ListSint64();
        listSint64.list = vs;
        byte[] epb = ProtoBuf.toByteArray(listSint64);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testDecode(String v) throws InvalidProtocolBufferException, ProtoBufException {

        List<Long> vs = new ArrayList<>();
        List<Long> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListSint64OuterClass.ListSint64.Builder builder = ListSint64OuterClass.ListSint64.newBuilder();
        builder.addAllValue(pvs);
        ListSint64OuterClass.ListSint64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListSint64OuterClass.ListSint64 pbOd = ListSint64OuterClass.ListSint64.parseFrom(pb);

        ListSint64 ListSint64 = ProtoBuf.toObject(pb, ListSint64.class);


        assertEquals(pbOd.getValueList().size(), ListSint64.list.size());
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), ListSint64.list.get(i));
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testEncodeArray(String v) throws EncodeException {

        List<Long> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        Long[] vs = new Long[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getLongValue(i);
            pvs.add(jvs.getLongValue(i));
        }

        ListSint64OuterClass.ListSint64.Builder builder = ListSint64OuterClass.ListSint64.newBuilder();
        builder.addAllValue(pvs);
        ListSint64OuterClass.ListSint64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArraySint64 listSint64 = new ArraySint64();
        listSint64.list = vs;
        byte[] epb = ProtoBuf.toByteArray(listSint64);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testDecodeArray(String v) throws InvalidProtocolBufferException, ProtoBufException {

        List<Long> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            pvs.add(jvs.getLongValue(i));
        }

        ListSint64OuterClass.ListSint64.Builder builder = ListSint64OuterClass.ListSint64.newBuilder();
        builder.addAllValue(pvs);
        ListSint64OuterClass.ListSint64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListSint64OuterClass.ListSint64 pbOd = ListSint64OuterClass.ListSint64.parseFrom(pb);

        ArraySint64 arraySint64 = ProtoBuf.toObject(pb, ArraySint64.class);


        assertEquals(pbOd.getValueList().size(), arraySint64.list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), arraySint64.list[i].longValue());
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testEncodeArrayUnboxed(String v) throws EncodeException {

        List<Long> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        long[] vs = new long[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getLongValue(i);
            pvs.add(jvs.getLongValue(i));
        }

        ListSint64OuterClass.ListSint64.Builder builder = ListSint64OuterClass.ListSint64.newBuilder();
        builder.addAllValue(pvs);
        ListSint64OuterClass.ListSint64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArraySint64Unboxed listSint64 = new ArraySint64Unboxed();
        listSint64.list = vs;
        byte[] epb = ProtoBuf.toByteArray(listSint64);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testDecodeArrayUnboxed(String v) throws InvalidProtocolBufferException, ProtoBufException {

        List<Long> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            pvs.add(jvs.getLongValue(i));
        }

        ListSint64OuterClass.ListSint64.Builder builder = ListSint64OuterClass.ListSint64.newBuilder();
        builder.addAllValue(pvs);
        ListSint64OuterClass.ListSint64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListSint64OuterClass.ListSint64 pbOd = ListSint64OuterClass.ListSint64.parseFrom(pb);

        ArraySint64Unboxed arraySint64 = ProtoBuf.toObject(pb, ArraySint64Unboxed.class);


        assertEquals(pbOd.getValueList().size(), arraySint64.list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), arraySint64.list[i]);
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
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListSint64OuterClass.ListSint64.Builder builder = ListSint64OuterClass.ListSint64.newBuilder();
        builder.addAllValue(pvs);
        ListSint64OuterClass.ListSint64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ListSint64NoAccess.class, "list");
        field1F.setAccessible(true);

        ListSint64NoAccess listSint64 = new ListSint64NoAccess();
        field1F.set(listSint64, vs);
        byte[] epb = ProtoBuf.toByteArray(listSint64);
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
        JSONArray jvs = JSONArray.parseArray(v);
        Long[] vs = new Long[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getLongValue(i);
            pvs.add(jvs.getLongValue(i));
        }

        ListSint64OuterClass.ListSint64.Builder builder = ListSint64OuterClass.ListSint64.newBuilder();
        builder.addAllValue(pvs);
        ListSint64OuterClass.ListSint64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ArraySint64NoAccess.class, "list");
        field1F.setAccessible(true);

        ArraySint64NoAccess listSint64 = new ArraySint64NoAccess();
        field1F.set(listSint64, vs);
        byte[] epb = ProtoBuf.toByteArray(listSint64);
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
        JSONArray jvs = JSONArray.parseArray(v);
        long[] vs = new long[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getLongValue(i);
            pvs.add(jvs.getLongValue(i));
        }

        ListSint64OuterClass.ListSint64.Builder builder = ListSint64OuterClass.ListSint64.newBuilder();
        builder.addAllValue(pvs);
        ListSint64OuterClass.ListSint64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ArraySint64UnboxedNoAccess.class, "list");
        field1F.setAccessible(true);

        ArraySint64UnboxedNoAccess listSint64 = new ArraySint64UnboxedNoAccess();
        field1F.set(listSint64, vs);
        byte[] epb = ProtoBuf.toByteArray(listSint64);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testDecodeNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        List<Long> vs = new ArrayList<>();
        List<Long> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListSint64OuterClass.ListSint64.Builder builder = ListSint64OuterClass.ListSint64.newBuilder();
        builder.addAllValue(pvs);
        ListSint64OuterClass.ListSint64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListSint64OuterClass.ListSint64 pbOd = ListSint64OuterClass.ListSint64.parseFrom(pb);

        ListSint64NoAccess ListSint64 = ProtoBuf.toObject(pb, ListSint64NoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ListSint64NoAccess.class, "list");
        fieldF.setAccessible(true);

        List<Long> list = (List<Long>)fieldF.get(ListSint64);
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
    void testDecodeArrayNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        List<Long> vs = new ArrayList<>();
        List<Long> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListSint64OuterClass.ListSint64.Builder builder = ListSint64OuterClass.ListSint64.newBuilder();
        builder.addAllValue(pvs);
        ListSint64OuterClass.ListSint64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListSint64OuterClass.ListSint64 pbOd = ListSint64OuterClass.ListSint64.parseFrom(pb);

        ArraySint64NoAccess ListSint64 = ProtoBuf.toObject(pb, ArraySint64NoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ArraySint64NoAccess.class, "list");
        fieldF.setAccessible(true);

        Long[] list = (Long[])fieldF.get(ListSint64);
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
    void testDecodeArrayUnboxedNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        List<Long> vs = new ArrayList<>();
        List<Long> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListSint64OuterClass.ListSint64.Builder builder = ListSint64OuterClass.ListSint64.newBuilder();
        builder.addAllValue(pvs);
        ListSint64OuterClass.ListSint64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListSint64OuterClass.ListSint64 pbOd = ListSint64OuterClass.ListSint64.parseFrom(pb);

        ArraySint64UnboxedNoAccess ListSint64 = ProtoBuf.toObject(pb, ArraySint64UnboxedNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ArraySint64UnboxedNoAccess.class, "list");
        fieldF.setAccessible(true);

        long[] list = (long[])fieldF.get(ListSint64);
        assertEquals(pbOd.getValueList().size(), list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), list[i]);
        }

    }
}
