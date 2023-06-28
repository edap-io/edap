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


public class TestListSfixed32 {

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,-1,-129]",
            "[-1,1,128,-129]"
    })
    void testEncode(String v) throws EncodeException {
        List<Integer> vs = new ArrayList<>();
        List<Integer> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getIntValue(i));
            pvs.add(jvs.getIntValue(i));
        }

        ListSfixed32OuterClass.ListSfixed32.Builder builder = ListSfixed32OuterClass.ListSfixed32.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed32OuterClass.ListSfixed32 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ListSfixed32 ListSfixed32 = new ListSfixed32();
        ListSfixed32.list = vs;
        byte[] epb = ProtoBuf.toByteArray(ListSfixed32);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testDecode(String v) throws InvalidProtocolBufferException, ProtoBufException {

        List<Integer> vs = new ArrayList<>();
        List<Integer> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getIntValue(i));
            pvs.add(jvs.getIntValue(i));
        }

        ListSfixed32OuterClass.ListSfixed32.Builder builder = ListSfixed32OuterClass.ListSfixed32.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed32OuterClass.ListSfixed32 od = builder.build();
        byte[] pb = od.toByteArray();


        ListSfixed32OuterClass.ListSfixed32 pbOd = ListSfixed32OuterClass.ListSfixed32.parseFrom(pb);

        ListSfixed32 ListSfixed32 = ProtoBuf.toObject(pb, ListSfixed32.class);


        assertEquals(pbOd.getValueList().size(), ListSfixed32.list.size());
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), ListSfixed32.list.get(i));
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,-1,-129]",
            "[-1,1,128,-129]"
    })
    void testEncodeArray(String v) throws EncodeException {

        List<Integer> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        Integer[] vs = new Integer[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getIntValue(i);
            pvs.add(jvs.getIntValue(i));
        }

        ListSfixed32OuterClass.ListSfixed32.Builder builder = ListSfixed32OuterClass.ListSfixed32.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed32OuterClass.ListSfixed32 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArraySfixed32 arraySfixed32 = new ArraySfixed32();
        arraySfixed32.list = vs;
        byte[] epb = ProtoBuf.toByteArray(arraySfixed32);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testDecodeArray(String v) throws InvalidProtocolBufferException, ProtoBufException {

        List<Integer> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            pvs.add(jvs.getIntValue(i));
        }

        ListSfixed32OuterClass.ListSfixed32.Builder builder = ListSfixed32OuterClass.ListSfixed32.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed32OuterClass.ListSfixed32 od = builder.build();
        byte[] pb = od.toByteArray();


        ListSfixed32OuterClass.ListSfixed32 pbOd = ListSfixed32OuterClass.ListSfixed32.parseFrom(pb);

        ArraySfixed32 arraySfixed32 = ProtoBuf.toObject(pb, ArraySfixed32.class);


        assertEquals(pbOd.getValueList().size(), arraySfixed32.list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i).intValue(), arraySfixed32.list[i].intValue());
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,-1,-129]",
            "[-1,1,128,-129]"
    })
    void testEncodeArrayUnboxed(String v) throws EncodeException {

        List<Integer> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        int[] vs = new int[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getIntValue(i);
            pvs.add(jvs.getIntValue(i));
        }

        ListSfixed32OuterClass.ListSfixed32.Builder builder = ListSfixed32OuterClass.ListSfixed32.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed32OuterClass.ListSfixed32 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArraySfixed32Unboxed arraySfixed32 = new ArraySfixed32Unboxed();
        arraySfixed32.list = vs;
        byte[] epb = ProtoBuf.toByteArray(arraySfixed32);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testDecodeArrayUnboxed(String v) throws InvalidProtocolBufferException, ProtoBufException {

        List<Integer> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            pvs.add(jvs.getIntValue(i));
        }

        ListSfixed32OuterClass.ListSfixed32.Builder builder = ListSfixed32OuterClass.ListSfixed32.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed32OuterClass.ListSfixed32 od = builder.build();
        byte[] pb = od.toByteArray();


        ListSfixed32OuterClass.ListSfixed32 pbOd = ListSfixed32OuterClass.ListSfixed32.parseFrom(pb);

        ArraySfixed32Unboxed arraySfixed32 = ProtoBuf.toObject(pb, ArraySfixed32Unboxed.class);


        assertEquals(pbOd.getValueList().size(), arraySfixed32.list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i).intValue(), arraySfixed32.list[i]);
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,-1,-129]",
            "[-1,1,128,-129]"
    })
    void testEncodeNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {
        List<Integer> vs = new ArrayList<>();
        List<Integer> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getIntValue(i));
            pvs.add(jvs.getIntValue(i));
        }

        ListSfixed32OuterClass.ListSfixed32.Builder builder = ListSfixed32OuterClass.ListSfixed32.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed32OuterClass.ListSfixed32 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ListSfixed32NoAccess.class, "list");
        field1F.setAccessible(true);

        ListSfixed32NoAccess listSfixed32 = new ListSfixed32NoAccess();
        field1F.set(listSfixed32, vs);
        byte[] epb = ProtoBuf.toByteArray(listSfixed32);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,-1,-129]",
            "[-1,1,128,-129]"
    })
    void testEncodeArrayNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        List<Integer> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        Integer[] vs = new Integer[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getIntValue(i);
            pvs.add(jvs.getIntValue(i));
        }

        ListSfixed32OuterClass.ListSfixed32.Builder builder = ListSfixed32OuterClass.ListSfixed32.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed32OuterClass.ListSfixed32 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ArraySfixed32NoAccess.class, "list");
        field1F.setAccessible(true);

        ArraySfixed32NoAccess arraySfixed32 = new ArraySfixed32NoAccess();
        field1F.set(arraySfixed32, vs);
        byte[] epb = ProtoBuf.toByteArray(arraySfixed32);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,-1,-129]",
            "[-1,1,128,-129]"
    })
    void testEncodeArrayUnboxedNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        List<Integer> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        int[] vs = new int[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getIntValue(i);
            pvs.add(jvs.getIntValue(i));
        }

        ListSfixed32OuterClass.ListSfixed32.Builder builder = ListSfixed32OuterClass.ListSfixed32.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed32OuterClass.ListSfixed32 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ArraySfixed32UnboxedNoAccess.class, "list");
        field1F.setAccessible(true);

        ArraySfixed32UnboxedNoAccess arraySfixed32 = new ArraySfixed32UnboxedNoAccess();
        field1F.set(arraySfixed32, vs);
        byte[] epb = ProtoBuf.toByteArray(arraySfixed32);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testDecodeNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        List<Integer> vs = new ArrayList<>();
        List<Integer> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getIntValue(i));
            pvs.add(jvs.getIntValue(i));
        }

        ListSfixed32OuterClass.ListSfixed32.Builder builder = ListSfixed32OuterClass.ListSfixed32.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed32OuterClass.ListSfixed32 od = builder.build();
        byte[] pb = od.toByteArray();


        ListSfixed32OuterClass.ListSfixed32 pbOd = ListSfixed32OuterClass.ListSfixed32.parseFrom(pb);

        ListSfixed32NoAccess ListSfixed32 = ProtoBuf.toObject(pb, ListSfixed32NoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ListSfixed32NoAccess.class, "list");
        fieldF.setAccessible(true);

        List<Integer> list = (List<Integer>)fieldF.get(ListSfixed32);
        assertEquals(pbOd.getValueList().size(), list.size());
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), list.get(i));
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testDecodeArrayNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        List<Integer> vs = new ArrayList<>();
        List<Integer> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getIntValue(i));
            pvs.add(jvs.getIntValue(i));
        }

        ListSfixed32OuterClass.ListSfixed32.Builder builder = ListSfixed32OuterClass.ListSfixed32.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed32OuterClass.ListSfixed32 od = builder.build();
        byte[] pb = od.toByteArray();


        ListSfixed32OuterClass.ListSfixed32 pbOd = ListSfixed32OuterClass.ListSfixed32.parseFrom(pb);

        ArraySfixed32NoAccess ListSfixed32 = ProtoBuf.toObject(pb, ArraySfixed32NoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ArraySfixed32NoAccess.class, "list");
        fieldF.setAccessible(true);

        Integer[] list = (Integer[])fieldF.get(ListSfixed32);
        assertEquals(pbOd.getValueList().size(), list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), list[i]);
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testDecodeArrayUnboxedNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        List<Integer> vs = new ArrayList<>();
        List<Integer> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getIntValue(i));
            pvs.add(jvs.getIntValue(i));
        }

        ListSfixed32OuterClass.ListSfixed32.Builder builder = ListSfixed32OuterClass.ListSfixed32.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed32OuterClass.ListSfixed32 od = builder.build();
        byte[] pb = od.toByteArray();


        ListSfixed32OuterClass.ListSfixed32 pbOd = ListSfixed32OuterClass.ListSfixed32.parseFrom(pb);

        ArraySfixed32UnboxedNoAccess ListSfixed32 = ProtoBuf.toObject(pb, ArraySfixed32UnboxedNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ArraySfixed32UnboxedNoAccess.class, "list");
        fieldF.setAccessible(true);

        int[] list = (int[])fieldF.get(ListSfixed32);
        assertEquals(pbOd.getValueList().size(), list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), list[i]);
        }

    }
}
