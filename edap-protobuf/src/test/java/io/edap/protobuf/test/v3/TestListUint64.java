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


public class TestListUint64 {
    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[1,127,512]"
    })
    void testEncode(String v) throws EncodeException {
        List<Long> vs = new ArrayList<>();
        List<Long> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListUint64OuterClass.ListUint64.Builder builder = ListUint64OuterClass.ListUint64.newBuilder();
        builder.addAllValue(pvs);
        ListUint64OuterClass.ListUint64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ListUint64 ListUint64 = new ListUint64();
        ListUint64.list = vs;
        byte[] epb = ProtoBuf.toByteArray(ListUint64);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[1,127,256]"
    })
    void testDecode(String v) throws InvalidProtocolBufferException, ProtoBufException {

        List<Long> vs = new ArrayList<>();
        List<Long> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListUint64OuterClass.ListUint64.Builder builder = ListUint64OuterClass.ListUint64.newBuilder();
        builder.addAllValue(pvs);
        ListUint64OuterClass.ListUint64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListUint64OuterClass.ListUint64 pbOd = ListUint64OuterClass.ListUint64.parseFrom(pb);

        ListUint64 listUint64 = ProtoBuf.toObject(pb, ListUint64.class);


        assertEquals(pbOd.getValueList().size(), listUint64.list.size());
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), listUint64.list.get(i));
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[1,127,512]"
    })
    void testEncodeArray(String v) throws EncodeException {

        List<Long> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        Long[] vs = new Long[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getLongValue(i);
            pvs.add(jvs.getLongValue(i));
        }

        ListUint64OuterClass.ListUint64.Builder builder = ListUint64OuterClass.ListUint64.newBuilder();
        builder.addAllValue(pvs);
        ListUint64OuterClass.ListUint64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArrayUint64 arrayUint64 = new ArrayUint64();
        arrayUint64.list = vs;
        byte[] epb = ProtoBuf.toByteArray(arrayUint64);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[1,127,256]"
    })
    void testDecodeArray(String v) throws InvalidProtocolBufferException, ProtoBufException {

        List<Long> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            pvs.add(jvs.getLongValue(i));
        }

        ListUint64OuterClass.ListUint64.Builder builder = ListUint64OuterClass.ListUint64.newBuilder();
        builder.addAllValue(pvs);
        ListUint64OuterClass.ListUint64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListUint64OuterClass.ListUint64 pbOd = ListUint64OuterClass.ListUint64.parseFrom(pb);

        ArrayUint64 listUint64 = ProtoBuf.toObject(pb, ArrayUint64.class);


        assertEquals(pbOd.getValueList().size(), listUint64.list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i).longValue(), listUint64.list[i].longValue());
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[1,127,512]"
    })
    void testEncodeArrayUnboxed(String v) throws EncodeException {

        List<Long> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        long[] vs = new long[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getLongValue(i);
            pvs.add(jvs.getLongValue(i));
        }

        ListUint64OuterClass.ListUint64.Builder builder = ListUint64OuterClass.ListUint64.newBuilder();
        builder.addAllValue(pvs);
        ListUint64OuterClass.ListUint64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArrayUint64Unboxed arrayUint64 = new ArrayUint64Unboxed();
        arrayUint64.list = vs;
        byte[] epb = ProtoBuf.toByteArray(arrayUint64);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[1,127,256]"
    })
    void testDecodeArrayUnboxed(String v) throws InvalidProtocolBufferException, ProtoBufException {

        List<Long> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            pvs.add(jvs.getLongValue(i));
        }

        ListUint64OuterClass.ListUint64.Builder builder = ListUint64OuterClass.ListUint64.newBuilder();
        builder.addAllValue(pvs);
        ListUint64OuterClass.ListUint64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListUint64OuterClass.ListUint64 pbOd = ListUint64OuterClass.ListUint64.parseFrom(pb);

        ArrayUint64Unboxed listUint64 = ProtoBuf.toObject(pb, ArrayUint64Unboxed.class);


        assertEquals(pbOd.getValueList().size(), listUint64.list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i).longValue(), listUint64.list[i]);
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[1,127,512]"
    })
    void testEncodeNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {
        List<Long> vs = new ArrayList<>();
        List<Long> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListUint64OuterClass.ListUint64.Builder builder = ListUint64OuterClass.ListUint64.newBuilder();
        builder.addAllValue(pvs);
        ListUint64OuterClass.ListUint64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ListUint64NoAccess.class, "list");
        field1F.setAccessible(true);

        ListUint64NoAccess listUint64 = new ListUint64NoAccess();
        field1F.set(listUint64, vs);
        byte[] epb = ProtoBuf.toByteArray(listUint64);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[1,127,512]"
    })
    void testEncodeArrayNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        List<Long> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        Long[] vs = new Long[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getLongValue(i);
            pvs.add(jvs.getLongValue(i));
        }

        ListUint64OuterClass.ListUint64.Builder builder = ListUint64OuterClass.ListUint64.newBuilder();
        builder.addAllValue(pvs);
        ListUint64OuterClass.ListUint64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ArrayUint64NoAccess.class, "list");
        field1F.setAccessible(true);

        ArrayUint64NoAccess arrayUint64 = new ArrayUint64NoAccess();
        field1F.set(arrayUint64, vs);
        byte[] epb = ProtoBuf.toByteArray(arrayUint64);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[1,127,512]"
    })
    void testEncodeArrayUnboxedNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        List<Long> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        long[] vs = new long[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getLongValue(i);
            pvs.add(jvs.getLongValue(i));
        }

        ListUint64OuterClass.ListUint64.Builder builder = ListUint64OuterClass.ListUint64.newBuilder();
        builder.addAllValue(pvs);
        ListUint64OuterClass.ListUint64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ArrayUint64UnboxedNoAccess.class, "list");
        field1F.setAccessible(true);

        ArrayUint64UnboxedNoAccess arrayUint64 = new ArrayUint64UnboxedNoAccess();
        field1F.set(arrayUint64, vs);
        byte[] epb = ProtoBuf.toByteArray(arrayUint64);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[1,127,256]"
    })
    void testDecodeNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        List<Long> vs = new ArrayList<>();
        List<Long> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListUint64OuterClass.ListUint64.Builder builder = ListUint64OuterClass.ListUint64.newBuilder();
        builder.addAllValue(pvs);
        ListUint64OuterClass.ListUint64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListUint64OuterClass.ListUint64 pbOd = ListUint64OuterClass.ListUint64.parseFrom(pb);

        ListUint64NoAccess listUint64 = ProtoBuf.toObject(pb, ListUint64NoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ListUint64NoAccess.class, "list");
        fieldF.setAccessible(true);

        List<Long> list = (List<Long>)fieldF.get(listUint64);
        assertEquals(pbOd.getValueList().size(), list.size());
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), list.get(i).longValue());
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[1,127,256]"
    })
    void testDecodeArrayNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        List<Long> vs = new ArrayList<>();
        List<Long> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListUint64OuterClass.ListUint64.Builder builder = ListUint64OuterClass.ListUint64.newBuilder();
        builder.addAllValue(pvs);
        ListUint64OuterClass.ListUint64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListUint64OuterClass.ListUint64 pbOd = ListUint64OuterClass.ListUint64.parseFrom(pb);

        ArrayUint64NoAccess listUint64 = ProtoBuf.toObject(pb, ArrayUint64NoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ArrayUint64NoAccess.class, "list");
        fieldF.setAccessible(true);

        Long[] list = (Long[])fieldF.get(listUint64);
        assertEquals(pbOd.getValueList().size(), list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), list[i].longValue());
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[1,127,256]"
    })
    void testDecodeArrayUnboxedNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        List<Long> vs = new ArrayList<>();
        List<Long> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListUint64OuterClass.ListUint64.Builder builder = ListUint64OuterClass.ListUint64.newBuilder();
        builder.addAllValue(pvs);
        ListUint64OuterClass.ListUint64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListUint64OuterClass.ListUint64 pbOd = ListUint64OuterClass.ListUint64.parseFrom(pb);

        ArrayUint64UnboxedNoAccess listUint64 = ProtoBuf.toObject(pb, ArrayUint64UnboxedNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ArrayUint64UnboxedNoAccess.class, "list");
        fieldF.setAccessible(true);

        long[] list = (long[])fieldF.get(listUint64);
        assertEquals(pbOd.getValueList().size(), list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), list[i]);
        }

    }
}
