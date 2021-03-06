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


public class TestListUint32 {

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[1,127,512]"
    })
    void testEncode(String v) throws EncodeException {
        List<Integer> vs = new ArrayList<>();
        List<Integer> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getIntValue(i));
            pvs.add(jvs.getIntValue(i));
        }

        ListUint32OuterClass.ListUint32.Builder builder = ListUint32OuterClass.ListUint32.newBuilder();
        builder.addAllValue(pvs);
        ListUint32OuterClass.ListUint32 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ListUint32 ListUint32 = new ListUint32();
        ListUint32.list = vs;
        byte[] epb = ProtoBuf.toByteArray(ListUint32);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[1,127,256]"
    })
    void testDecode(String v) throws InvalidProtocolBufferException, ProtoBufException {

        List<Integer> vs = new ArrayList<>();
        List<Integer> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getIntValue(i));
            pvs.add(jvs.getIntValue(i));
        }

        ListUint32OuterClass.ListUint32.Builder builder = ListUint32OuterClass.ListUint32.newBuilder();
        builder.addAllValue(pvs);
        ListUint32OuterClass.ListUint32 od = builder.build();
        byte[] pb = od.toByteArray();


        ListUint32OuterClass.ListUint32 pbOd = ListUint32OuterClass.ListUint32.parseFrom(pb);

        ListUint32 listUint32 = ProtoBuf.toObject(pb, ListUint32.class);


        assertEquals(pbOd.getValueList().size(), listUint32.list.size());
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), listUint32.list.get(i));
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[1,127,512]"
    })
    void testEncodeArray(String v) throws EncodeException {

        List<Integer> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        Integer[] vs = new Integer[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getIntValue(i);
            pvs.add(jvs.getIntValue(i));
        }

        ListUint32OuterClass.ListUint32.Builder builder = ListUint32OuterClass.ListUint32.newBuilder();
        builder.addAllValue(pvs);
        ListUint32OuterClass.ListUint32 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArrayUint32 arrayUint32 = new ArrayUint32();
        arrayUint32.list = vs;
        byte[] epb = ProtoBuf.toByteArray(arrayUint32);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[1,127,256]"
    })
    void testDecodeArray(String v) throws InvalidProtocolBufferException, ProtoBufException {

        List<Integer> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            pvs.add(jvs.getIntValue(i));
        }

        ListUint32OuterClass.ListUint32.Builder builder = ListUint32OuterClass.ListUint32.newBuilder();
        builder.addAllValue(pvs);
        ListUint32OuterClass.ListUint32 od = builder.build();
        byte[] pb = od.toByteArray();


        ListUint32OuterClass.ListUint32 pbOd = ListUint32OuterClass.ListUint32.parseFrom(pb);

        ArrayUint32 listUint32 = ProtoBuf.toObject(pb, ArrayUint32.class);


        assertEquals(pbOd.getValueList().size(), listUint32.list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i).intValue(), listUint32.list[i].intValue());
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[1,127,512]"
    })
    void testEncodeArrayUnboxed(String v) throws EncodeException {

        List<Integer> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        int[] vs = new int[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getIntValue(i);
            pvs.add(jvs.getIntValue(i));
        }

        ListUint32OuterClass.ListUint32.Builder builder = ListUint32OuterClass.ListUint32.newBuilder();
        builder.addAllValue(pvs);
        ListUint32OuterClass.ListUint32 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArrayUint32Unboxed arrayUint32 = new ArrayUint32Unboxed();
        arrayUint32.list = vs;
        byte[] epb = ProtoBuf.toByteArray(arrayUint32);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[1,127,256]"
    })
    void testDecodeArrayUnboxed(String v) throws InvalidProtocolBufferException, ProtoBufException {

        List<Integer> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            pvs.add(jvs.getIntValue(i));
        }

        ListUint32OuterClass.ListUint32.Builder builder = ListUint32OuterClass.ListUint32.newBuilder();
        builder.addAllValue(pvs);
        ListUint32OuterClass.ListUint32 od = builder.build();
        byte[] pb = od.toByteArray();


        ListUint32OuterClass.ListUint32 pbOd = ListUint32OuterClass.ListUint32.parseFrom(pb);

        ArrayUint32Unboxed listUint32 = ProtoBuf.toObject(pb, ArrayUint32Unboxed.class);


        assertEquals(pbOd.getValueList().size(), listUint32.list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i).intValue(), listUint32.list[i]);
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[1,127,512]"
    })
    void testEncodeNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {
        List<Integer> vs = new ArrayList<>();
        List<Integer> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getIntValue(i));
            pvs.add(jvs.getIntValue(i));
        }

        ListUint32OuterClass.ListUint32.Builder builder = ListUint32OuterClass.ListUint32.newBuilder();
        builder.addAllValue(pvs);
        ListUint32OuterClass.ListUint32 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ListUint32NoAccess.class, "list");
        field1F.setAccessible(true);

        ListUint32NoAccess listUint32 = new ListUint32NoAccess();
        field1F.set(listUint32, vs);
        byte[] epb = ProtoBuf.toByteArray(listUint32);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[1,127,512]"
    })
    void testEncodeArrayNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        List<Integer> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        Integer[] vs = new Integer[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getIntValue(i);
            pvs.add(jvs.getIntValue(i));
        }

        ListUint32OuterClass.ListUint32.Builder builder = ListUint32OuterClass.ListUint32.newBuilder();
        builder.addAllValue(pvs);
        ListUint32OuterClass.ListUint32 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ArrayUint32NoAccess.class, "list");
        field1F.setAccessible(true);

        ArrayUint32NoAccess arrayUint32 = new ArrayUint32NoAccess();
        field1F.set(arrayUint32, vs);
        byte[] epb = ProtoBuf.toByteArray(arrayUint32);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[1,127,512]"
    })
    void testEncodeArrayUnboxedNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        List<Integer> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        int[] vs = new int[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getIntValue(i);
            pvs.add(jvs.getIntValue(i));
        }

        ListUint32OuterClass.ListUint32.Builder builder = ListUint32OuterClass.ListUint32.newBuilder();
        builder.addAllValue(pvs);
        ListUint32OuterClass.ListUint32 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ArrayUint32UnboxedNoAccess.class, "list");
        field1F.setAccessible(true);

        ArrayUint32UnboxedNoAccess arrayUint32 = new ArrayUint32UnboxedNoAccess();
        field1F.set(arrayUint32, vs);
        byte[] epb = ProtoBuf.toByteArray(arrayUint32);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[1,127,256]"
    })
    void testDecodeNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        List<Integer> vs = new ArrayList<>();
        List<Integer> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getIntValue(i));
            pvs.add(jvs.getIntValue(i));
        }

        ListUint32OuterClass.ListUint32.Builder builder = ListUint32OuterClass.ListUint32.newBuilder();
        builder.addAllValue(pvs);
        ListUint32OuterClass.ListUint32 od = builder.build();
        byte[] pb = od.toByteArray();


        ListUint32OuterClass.ListUint32 pbOd = ListUint32OuterClass.ListUint32.parseFrom(pb);

        ListUint32NoAccess listUint32 = ProtoBuf.toObject(pb, ListUint32NoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ListUint32NoAccess.class, "list");
        fieldF.setAccessible(true);

        List<Integer> list = (List<Integer>)fieldF.get(listUint32);
        assertEquals(pbOd.getValueList().size(), list.size());
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), list.get(i).intValue());
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[1,127,256]"
    })
    void testDecodeArrayNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        List<Integer> vs = new ArrayList<>();
        List<Integer> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getIntValue(i));
            pvs.add(jvs.getIntValue(i));
        }

        ListUint32OuterClass.ListUint32.Builder builder = ListUint32OuterClass.ListUint32.newBuilder();
        builder.addAllValue(pvs);
        ListUint32OuterClass.ListUint32 od = builder.build();
        byte[] pb = od.toByteArray();


        ListUint32OuterClass.ListUint32 pbOd = ListUint32OuterClass.ListUint32.parseFrom(pb);

        ArrayUint32NoAccess listUint32 = ProtoBuf.toObject(pb, ArrayUint32NoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ArrayUint32NoAccess.class, "list");
        fieldF.setAccessible(true);

        Integer[] list = (Integer[])fieldF.get(listUint32);
        assertEquals(pbOd.getValueList().size(), list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), list[i].intValue());
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[1,127,256]"
    })
    void testDecodeArrayUnboxedNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        List<Integer> vs = new ArrayList<>();
        List<Integer> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getIntValue(i));
            pvs.add(jvs.getIntValue(i));
        }

        ListUint32OuterClass.ListUint32.Builder builder = ListUint32OuterClass.ListUint32.newBuilder();
        builder.addAllValue(pvs);
        ListUint32OuterClass.ListUint32 od = builder.build();
        byte[] pb = od.toByteArray();


        ListUint32OuterClass.ListUint32 pbOd = ListUint32OuterClass.ListUint32.parseFrom(pb);

        ArrayUint32UnboxedNoAccess listUint32 = ProtoBuf.toObject(pb, ArrayUint32UnboxedNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ArrayUint32UnboxedNoAccess.class, "list");
        fieldF.setAccessible(true);

        int[] list = (int[])fieldF.get(listUint32);
        assertEquals(pbOd.getValueList().size(), list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), list[i]);
        }

    }
}
