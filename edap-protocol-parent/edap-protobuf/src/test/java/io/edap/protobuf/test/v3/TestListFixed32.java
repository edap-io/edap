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


public class TestListFixed32 {

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

        ListFixed32OuterClass.ListFixed32.Builder builder = ListFixed32OuterClass.ListFixed32.newBuilder();
        builder.addAllValue(pvs);
        ListFixed32OuterClass.ListFixed32 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ListFixed32 listFixed32 = new ListFixed32();
        listFixed32.list = vs;
        byte[] epb = ProtoBuf.toByteArray(listFixed32);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testDecode(String v) throws InvalidProtocolBufferException, ProtoException {

        List<Integer> vs = new ArrayList<>();
        List<Integer> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getIntValue(i));
            pvs.add(jvs.getIntValue(i));
        }

        ListFixed32OuterClass.ListFixed32.Builder builder = ListFixed32OuterClass.ListFixed32.newBuilder();
        builder.addAllValue(pvs);
        ListFixed32OuterClass.ListFixed32 od = builder.build();
        byte[] pb = od.toByteArray();


        ListFixed32OuterClass.ListFixed32 pbOd = ListFixed32OuterClass.ListFixed32.parseFrom(pb);

        ListFixed32 ListFixed32 = ProtoBuf.toObject(pb, ListFixed32.class);


        assertEquals(pbOd.getValueList().size(), ListFixed32.list.size());
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), ListFixed32.list.get(i));
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

        ListFixed32OuterClass.ListFixed32.Builder builder = ListFixed32OuterClass.ListFixed32.newBuilder();
        builder.addAllValue(pvs);
        ListFixed32OuterClass.ListFixed32 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArrayFixed32 listFixed32 = new ArrayFixed32();
        listFixed32.values = vs;
        byte[] epb = ProtoBuf.toByteArray(listFixed32);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testDecodeArray(String v) throws InvalidProtocolBufferException, ProtoException {

        List<Integer> vs = new ArrayList<>();
        List<Integer> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getIntValue(i));
            pvs.add(jvs.getIntValue(i));
        }

        ListFixed32OuterClass.ListFixed32.Builder builder = ListFixed32OuterClass.ListFixed32.newBuilder();
        builder.addAllValue(pvs);
        ListFixed32OuterClass.ListFixed32 od = builder.build();
        byte[] pb = od.toByteArray();


        ListFixed32OuterClass.ListFixed32 pbOd = ListFixed32OuterClass.ListFixed32.parseFrom(pb);

        ArrayFixed32 ListFixed32 = ProtoBuf.toObject(pb, ArrayFixed32.class);


        assertEquals(pbOd.getValueList().size(), ListFixed32.values.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), ListFixed32.values[i]);
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

        ListFixed32OuterClass.ListFixed32.Builder builder = ListFixed32OuterClass.ListFixed32.newBuilder();
        builder.addAllValue(pvs);
        ListFixed32OuterClass.ListFixed32 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArrayFixed32Unboxed listFixed32 = new ArrayFixed32Unboxed();
        listFixed32.values = vs;
        byte[] epb = ProtoBuf.toByteArray(listFixed32);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testDecodeArrayUnboxed(String v) throws InvalidProtocolBufferException, ProtoException {

        List<Integer> vs = new ArrayList<>();
        List<Integer> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getIntValue(i));
            pvs.add(jvs.getIntValue(i));
        }

        ListFixed32OuterClass.ListFixed32.Builder builder = ListFixed32OuterClass.ListFixed32.newBuilder();
        builder.addAllValue(pvs);
        ListFixed32OuterClass.ListFixed32 od = builder.build();
        byte[] pb = od.toByteArray();


        ListFixed32OuterClass.ListFixed32 pbOd = ListFixed32OuterClass.ListFixed32.parseFrom(pb);

        ArrayFixed32Unboxed ListFixed32 = ProtoBuf.toObject(pb, ArrayFixed32Unboxed.class);


        assertEquals(pbOd.getValueList().size(), ListFixed32.values.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), ListFixed32.values[i]);
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

        ListFixed32OuterClass.ListFixed32.Builder builder = ListFixed32OuterClass.ListFixed32.newBuilder();
        builder.addAllValue(pvs);
        ListFixed32OuterClass.ListFixed32 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field fied1F = ClazzUtil.getDeclaredField(ListFixed32NoAccess.class, "list");
        fied1F.setAccessible(true);

        ListFixed32NoAccess listFixed32 = new ListFixed32NoAccess();
        fied1F.set(listFixed32, vs);
        byte[] epb = ProtoBuf.toByteArray(listFixed32);
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

        ListFixed32OuterClass.ListFixed32.Builder builder = ListFixed32OuterClass.ListFixed32.newBuilder();
        builder.addAllValue(pvs);
        ListFixed32OuterClass.ListFixed32 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ArrayFixed32NoAccess.class, "values");
        field1F.setAccessible(true);

        ArrayFixed32NoAccess listFixed32 = new ArrayFixed32NoAccess();
        field1F.set(listFixed32, vs);
        byte[] epb = ProtoBuf.toByteArray(listFixed32);
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

        ListFixed32OuterClass.ListFixed32.Builder builder = ListFixed32OuterClass.ListFixed32.newBuilder();
        builder.addAllValue(pvs);
        ListFixed32OuterClass.ListFixed32 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ArrayFixed32UnboxedNoAccess.class, "values");
        field1F.setAccessible(true);

        ArrayFixed32UnboxedNoAccess listFixed32 = new ArrayFixed32UnboxedNoAccess();
        field1F.set(listFixed32, vs);
        byte[] epb = ProtoBuf.toByteArray(listFixed32);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testDecodeNoAccess(String v) throws InvalidProtocolBufferException, ProtoException, NoSuchFieldException, IllegalAccessException {

        List<Integer> vs = new ArrayList<>();
        List<Integer> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getIntValue(i));
            pvs.add(jvs.getIntValue(i));
        }

        ListFixed32OuterClass.ListFixed32.Builder builder = ListFixed32OuterClass.ListFixed32.newBuilder();
        builder.addAllValue(pvs);
        ListFixed32OuterClass.ListFixed32 od = builder.build();
        byte[] pb = od.toByteArray();


        ListFixed32OuterClass.ListFixed32 pbOd = ListFixed32OuterClass.ListFixed32.parseFrom(pb);

        ListFixed32NoAccess ListFixed32 = ProtoBuf.toObject(pb, ListFixed32NoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ListFixed32NoAccess.class, "list");
        fieldF.setAccessible(true);

        List<Integer> list = (List<Integer>)fieldF.get(ListFixed32);
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
    void testDecodeArrayNoAccess(String v) throws InvalidProtocolBufferException, ProtoException, NoSuchFieldException, IllegalAccessException {

        List<Integer> vs = new ArrayList<>();
        List<Integer> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getIntValue(i));
            pvs.add(jvs.getIntValue(i));
        }

        ListFixed32OuterClass.ListFixed32.Builder builder = ListFixed32OuterClass.ListFixed32.newBuilder();
        builder.addAllValue(pvs);
        ListFixed32OuterClass.ListFixed32 od = builder.build();
        byte[] pb = od.toByteArray();


        ListFixed32OuterClass.ListFixed32 pbOd = ListFixed32OuterClass.ListFixed32.parseFrom(pb);

        ArrayFixed32NoAccess ListFixed32 = ProtoBuf.toObject(pb, ArrayFixed32NoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ArrayFixed32NoAccess.class, "values");
        fieldF.setAccessible(true);

        Integer[] values = (Integer[])fieldF.get(ListFixed32);
        assertEquals(pbOd.getValueList().size(), values.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), values[i]);
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testDecodeArrayUnboxedNoAccess(String v) throws InvalidProtocolBufferException, ProtoException, NoSuchFieldException, IllegalAccessException {

        List<Integer> vs = new ArrayList<>();
        List<Integer> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getIntValue(i));
            pvs.add(jvs.getIntValue(i));
        }

        ListFixed32OuterClass.ListFixed32.Builder builder = ListFixed32OuterClass.ListFixed32.newBuilder();
        builder.addAllValue(pvs);
        ListFixed32OuterClass.ListFixed32 od = builder.build();
        byte[] pb = od.toByteArray();


        ListFixed32OuterClass.ListFixed32 pbOd = ListFixed32OuterClass.ListFixed32.parseFrom(pb);

        ArrayFixed32UnboxedNoAccess ListFixed32 = ProtoBuf.toObject(pb, ArrayFixed32UnboxedNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ArrayFixed32UnboxedNoAccess.class, "values");
        fieldF.setAccessible(true);

        int[] values = (int[])fieldF.get(ListFixed32);
        assertEquals(pbOd.getValueList().size(), values.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), values[i]);
        }

    }
}
