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


public class TestListFloat {
    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testEncode(String v) throws EncodeException {
        List<Float> vs = new ArrayList<>();
        List<Float> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getFloat(i));
            pvs.add(jvs.getFloat(i));
        }

        ListFloatOuterClass.ListFloat.Builder builder = ListFloatOuterClass.ListFloat.newBuilder();
        builder.addAllF(pvs);
        ListFloatOuterClass.ListFloat od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ListFloat ListFloat = new ListFloat();
        ListFloat.list = vs;
        byte[] epb = ProtoBuf.toByteArray(ListFloat);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testDecode(String v) throws InvalidProtocolBufferException, ProtoBufException {

        List<Float> vs = new ArrayList<>();
        List<Float> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getFloat(i));
            pvs.add(jvs.getFloat(i));
        }

        ListFloatOuterClass.ListFloat.Builder builder = ListFloatOuterClass.ListFloat.newBuilder();
        builder.addAllF(pvs);
        ListFloatOuterClass.ListFloat od = builder.build();
        byte[] pb = od.toByteArray();


        ListFloatOuterClass.ListFloat pbOd = ListFloatOuterClass.ListFloat.parseFrom(pb);

        ListFloat listFloat = ProtoBuf.toObject(pb, ListFloat.class);


        assertEquals(pbOd.getFList().size(), listFloat.list.size());
        for (int i=0;i<pbOd.getFList().size();i++) {
            assertEquals(pbOd.getFList().get(i), listFloat.list.get(i));
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testEncodeArray(String v) throws EncodeException {

        List<Float> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        Float[] vs = new Float[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getFloat(i);
            pvs.add(jvs.getFloat(i));
        }

        ListFloatOuterClass.ListFloat.Builder builder = ListFloatOuterClass.ListFloat.newBuilder();
        builder.addAllF(pvs);
        ListFloatOuterClass.ListFloat od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArrayFloat arrayFloat = new ArrayFloat();
        arrayFloat.list = vs;
        byte[] epb = ProtoBuf.toByteArray(arrayFloat);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @Test
    void testEncodeArrayNull() throws EncodeException {
        ArrayFloat arrayFloat = new ArrayFloat();

        byte[] epb = ProtoBuf.toByteArray(arrayFloat);

        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeArrayEmpty() throws EncodeException {
        ArrayFloat arrayFloat = new ArrayFloat();
        arrayFloat.list = new Float[0];
        byte[] epb = ProtoBuf.toByteArray(arrayFloat);

        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeArrayNullUnboxed() throws EncodeException {
        ArrayFloatUnboxed arrayFloat = new ArrayFloatUnboxed();

        byte[] epb = ProtoBuf.toByteArray(arrayFloat);

        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeArrayEmptyUnboxed() throws EncodeException {
        ArrayFloatUnboxed arrayFloat = new ArrayFloatUnboxed();
        arrayFloat.list = new float[0];
        byte[] epb = ProtoBuf.toByteArray(arrayFloat);

        assertArrayEquals(new byte[0], epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testDecodeArray(String v) throws InvalidProtocolBufferException, ProtoBufException {
        List<Float> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            pvs.add(jvs.getFloat(i));
        }

        ListFloatOuterClass.ListFloat.Builder builder = ListFloatOuterClass.ListFloat.newBuilder();
        builder.addAllF(pvs);
        ListFloatOuterClass.ListFloat od = builder.build();
        byte[] pb = od.toByteArray();


        ListFloatOuterClass.ListFloat pbOd = ListFloatOuterClass.ListFloat.parseFrom(pb);

        ArrayFloat listFloat = ProtoBuf.toObject(pb, ArrayFloat.class);


        assertEquals(pbOd.getFList().size(), listFloat.list.length);
        for (int i=0;i<pbOd.getFList().size();i++) {
            assertEquals(pbOd.getFList().get(i).floatValue(), listFloat.list[i].floatValue());
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testEncodeArrayUnboxed(String v) throws EncodeException {

        List<Float> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        float[] vs = new float[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getFloat(i);
            pvs.add(jvs.getFloat(i));
        }

        ListFloatOuterClass.ListFloat.Builder builder = ListFloatOuterClass.ListFloat.newBuilder();
        builder.addAllF(pvs);
        ListFloatOuterClass.ListFloat od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArrayFloatUnboxed arrayFloat = new ArrayFloatUnboxed();
        arrayFloat.list = vs;
        byte[] epb = ProtoBuf.toByteArray(arrayFloat);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testDecodeArrayUnboxed(String v) throws InvalidProtocolBufferException, ProtoBufException {
        List<Float> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            pvs.add(jvs.getFloat(i));
        }

        ListFloatOuterClass.ListFloat.Builder builder = ListFloatOuterClass.ListFloat.newBuilder();
        builder.addAllF(pvs);
        ListFloatOuterClass.ListFloat od = builder.build();
        byte[] pb = od.toByteArray();


        ListFloatOuterClass.ListFloat pbOd = ListFloatOuterClass.ListFloat.parseFrom(pb);

        ArrayFloatUnboxed listFloat = ProtoBuf.toObject(pb, ArrayFloatUnboxed.class);


        assertEquals(pbOd.getFList().size(), listFloat.list.length);
        for (int i=0;i<pbOd.getFList().size();i++) {
            assertEquals(pbOd.getFList().get(i).floatValue(), listFloat.list[i]);
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testEncodeNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {
        List<Float> vs = new ArrayList<>();
        List<Float> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getFloat(i));
            pvs.add(jvs.getFloat(i));
        }

        ListFloatOuterClass.ListFloat.Builder builder = ListFloatOuterClass.ListFloat.newBuilder();
        builder.addAllF(pvs);
        ListFloatOuterClass.ListFloat od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ListFloatNoAccess.class, "list");
        field1F.setAccessible(true);

        ListFloatNoAccess ListFloat = new ListFloatNoAccess();
        field1F.set(ListFloat, vs);
        byte[] epb = ProtoBuf.toByteArray(ListFloat);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testEncodeArrayNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        List<Float> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        Float[] vs = new Float[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getFloat(i);
            pvs.add(jvs.getFloat(i));
        }

        ListFloatOuterClass.ListFloat.Builder builder = ListFloatOuterClass.ListFloat.newBuilder();
        builder.addAllF(pvs);
        ListFloatOuterClass.ListFloat od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ArrayFloatNoAccess.class, "list");
        field1F.setAccessible(true);

        ArrayFloatNoAccess arrayFloat = new ArrayFloatNoAccess();
        field1F.set(arrayFloat, vs);
        byte[] epb = ProtoBuf.toByteArray(arrayFloat);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testEncodeArrayUnboxedNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        List<Float> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        float[] vs = new float[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getFloat(i);
            pvs.add(jvs.getFloat(i));
        }

        ListFloatOuterClass.ListFloat.Builder builder = ListFloatOuterClass.ListFloat.newBuilder();
        builder.addAllF(pvs);
        ListFloatOuterClass.ListFloat od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ArrayFloatUnboxedNoAccess.class, "list");
        field1F.setAccessible(true);

        ArrayFloatUnboxedNoAccess arrayFloat = new ArrayFloatUnboxedNoAccess();
        field1F.set(arrayFloat, vs);
        byte[] epb = ProtoBuf.toByteArray(arrayFloat);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testDecodeNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        List<Float> vs = new ArrayList<>();
        List<Float> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getFloat(i));
            pvs.add(jvs.getFloat(i));
        }

        ListFloatOuterClass.ListFloat.Builder builder = ListFloatOuterClass.ListFloat.newBuilder();
        builder.addAllF(pvs);
        ListFloatOuterClass.ListFloat od = builder.build();
        byte[] pb = od.toByteArray();


        ListFloatOuterClass.ListFloat pbOd = ListFloatOuterClass.ListFloat.parseFrom(pb);

        ListFloatNoAccess listFloat = ProtoBuf.toObject(pb, ListFloatNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ListFloatNoAccess.class, "list");
        fieldF.setAccessible(true);

        List<Float> list = (List<Float>)fieldF.get(listFloat);
        assertEquals(pbOd.getFList().size(), list.size());
        for (int i=0;i<pbOd.getFList().size();i++) {
            assertEquals(pbOd.getFList().get(i), list.get(i));
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testDecodeArrayNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {
        List<Float> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            pvs.add(jvs.getFloat(i));
        }

        ListFloatOuterClass.ListFloat.Builder builder = ListFloatOuterClass.ListFloat.newBuilder();
        builder.addAllF(pvs);
        ListFloatOuterClass.ListFloat od = builder.build();
        byte[] pb = od.toByteArray();


        ListFloatOuterClass.ListFloat pbOd = ListFloatOuterClass.ListFloat.parseFrom(pb);

        ArrayFloatNoAccess listFloat = ProtoBuf.toObject(pb, ArrayFloatNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ArrayFloatNoAccess.class, "list");
        fieldF.setAccessible(true);

        Float[] list = (Float[])fieldF.get(listFloat);
        assertEquals(pbOd.getFList().size(), list.length);
        for (int i=0;i<pbOd.getFList().size();i++) {
            assertEquals(pbOd.getFList().get(i).floatValue(), list[i].floatValue());
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testDecodeArrayUnboxedNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {
        List<Float> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            pvs.add(jvs.getFloat(i));
        }

        ListFloatOuterClass.ListFloat.Builder builder = ListFloatOuterClass.ListFloat.newBuilder();
        builder.addAllF(pvs);
        ListFloatOuterClass.ListFloat od = builder.build();
        byte[] pb = od.toByteArray();


        ListFloatOuterClass.ListFloat pbOd = ListFloatOuterClass.ListFloat.parseFrom(pb);

        ArrayFloatUnboxedNoAccess listFloat = ProtoBuf.toObject(pb, ArrayFloatUnboxedNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ArrayFloatUnboxedNoAccess.class, "list");
        fieldF.setAccessible(true);

        float[] list = (float[])fieldF.get(listFloat);
        assertEquals(pbOd.getFList().size(), list.length);
        for (int i=0;i<pbOd.getFList().size();i++) {
            assertEquals(pbOd.getFList().get(i).floatValue(), list[i]);
        }

    }
}
