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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestListDouble {
    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testEncode(String v) throws EncodeException {
        List<Double> vs = new ArrayList<>();
        List<Double> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getDouble(i));
            pvs.add(jvs.getDouble(i));
        }

        ListDoubleOuterClass.ListDouble.Builder builder = ListDoubleOuterClass.ListDouble.newBuilder();
        builder.addAllD(pvs);
        ListDoubleOuterClass.ListDouble od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ListDouble ListDouble = new ListDouble();
        ListDouble.list = vs;
        byte[] epb = ProtoBuf.toByteArray(ListDouble);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testDecode(String v) throws InvalidProtocolBufferException, ProtoBufException {

        List<Double> vs = new ArrayList<>();
        List<Double> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getDouble(i));
            pvs.add(jvs.getDouble(i));
        }

        ListDoubleOuterClass.ListDouble.Builder builder = ListDoubleOuterClass.ListDouble.newBuilder();
        builder.addAllD(pvs);
        ListDoubleOuterClass.ListDouble od = builder.build();
        byte[] pb = od.toByteArray();


        ListDoubleOuterClass.ListDouble pbOd = ListDoubleOuterClass.ListDouble.parseFrom(pb);

        ListDouble listDouble = ProtoBuf.toObject(pb, ListDouble.class);


        assertEquals(pbOd.getDList().size(), listDouble.list.size());
        for (int i=0;i<pbOd.getDList().size();i++) {
            assertEquals(pbOd.getDList().get(i), listDouble.list.get(i));
        }

    }


    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testEncodeArray(String v) throws EncodeException {
        List<Double> pvs = new ArrayList<>();

        JsonArray jvs = JsonArray.parseArray(v);
        Double[] vs = new Double[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            pvs.add(jvs.getDouble(i));
            vs[i] = jvs.getDouble(i);
        }

        ListDoubleOuterClass.ListDouble.Builder builder = ListDoubleOuterClass.ListDouble.newBuilder();
        builder.addAllD(pvs);
        ListDoubleOuterClass.ListDouble od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArrayDouble arrayDouble = new ArrayDouble();
        arrayDouble.values = vs;
        byte[] epb = ProtoBuf.toByteArray(arrayDouble);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testDecodeArray(String v) throws InvalidProtocolBufferException, ProtoBufException {

        List<Double> vs = new ArrayList<>();
        List<Double> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getDouble(i));
            pvs.add(jvs.getDouble(i));
        }

        ListDoubleOuterClass.ListDouble.Builder builder = ListDoubleOuterClass.ListDouble.newBuilder();
        builder.addAllD(pvs);
        ListDoubleOuterClass.ListDouble od = builder.build();
        byte[] pb = od.toByteArray();


        ListDoubleOuterClass.ListDouble pbOd = ListDoubleOuterClass.ListDouble.parseFrom(pb);

        ArrayDouble listDouble = ProtoBuf.toObject(pb, ArrayDouble.class);


        assertEquals(pbOd.getDList().size(), listDouble.values.length);
        for (int i=0;i<pbOd.getDList().size();i++) {
            assertEquals(pbOd.getDList().get(i), listDouble.values[i]);
        }

    }

    @Test
    void testEncodeArrayNull() throws EncodeException {
        ArrayDouble arrayDouble = new ArrayDouble();
        byte[] epb = ProtoBuf.toByteArray(arrayDouble);

        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeArrayEmpty() throws EncodeException {
        ArrayDouble arrayDouble = new ArrayDouble();
        arrayDouble.values = new Double[0];
        byte[] epb = ProtoBuf.toByteArray(arrayDouble);

        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeArrayNullUnboxed() throws EncodeException {
        ArrayDoubleUnboxed arrayDouble = new ArrayDoubleUnboxed();
        byte[] epb = ProtoBuf.toByteArray(arrayDouble);

        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeArrayEmptyUnboxed() throws EncodeException {
        ArrayDoubleUnboxed arrayDouble = new ArrayDoubleUnboxed();
        arrayDouble.values = new double[0];
        byte[] epb = ProtoBuf.toByteArray(arrayDouble);

        assertArrayEquals(new byte[0], epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testEncodeArrayUnboxed(String v) throws EncodeException {
        List<Double> pvs = new ArrayList<>();

        JsonArray jvs = JsonArray.parseArray(v);
        double[] vs = new double[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            pvs.add(jvs.getDouble(i));
            vs[i] = jvs.getDouble(i);
        }

        ListDoubleOuterClass.ListDouble.Builder builder = ListDoubleOuterClass.ListDouble.newBuilder();
        builder.addAllD(pvs);
        ListDoubleOuterClass.ListDouble od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArrayDoubleUnboxed arrayDouble = new ArrayDoubleUnboxed();
        arrayDouble.values = vs;
        byte[] epb = ProtoBuf.toByteArray(arrayDouble);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testDecodeArrayUnboxed(String v) throws InvalidProtocolBufferException, ProtoBufException {

        List<Double> vs = new ArrayList<>();
        List<Double> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getDouble(i));
            pvs.add(jvs.getDouble(i));
        }

        ListDoubleOuterClass.ListDouble.Builder builder = ListDoubleOuterClass.ListDouble.newBuilder();
        builder.addAllD(pvs);
        ListDoubleOuterClass.ListDouble od = builder.build();
        byte[] pb = od.toByteArray();


        ListDoubleOuterClass.ListDouble pbOd = ListDoubleOuterClass.ListDouble.parseFrom(pb);

        ArrayDoubleUnboxed listDouble = ProtoBuf.toObject(pb, ArrayDoubleUnboxed.class);


        assertEquals(pbOd.getDList().size(), listDouble.values.length);
        for (int i=0;i<pbOd.getDList().size();i++) {
            assertEquals(pbOd.getDList().get(i), listDouble.values[i]);
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testEncodeNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {
        List<Double> vs = new ArrayList<>();
        List<Double> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getDouble(i));
            pvs.add(jvs.getDouble(i));
        }

        ListDoubleOuterClass.ListDouble.Builder builder = ListDoubleOuterClass.ListDouble.newBuilder();
        builder.addAllD(pvs);
        ListDoubleOuterClass.ListDouble od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ListDoubleNoAccess.class, "list");
        field1F.setAccessible(true);

        ListDoubleNoAccess ListDouble = new ListDoubleNoAccess();
        field1F.set(ListDouble, vs);
        byte[] epb = ProtoBuf.toByteArray(ListDouble);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testEncodeArrayNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {
        List<Double> pvs = new ArrayList<>();

        JsonArray jvs = JsonArray.parseArray(v);
        Double[] vs = new Double[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            pvs.add(jvs.getDouble(i));
            vs[i] = jvs.getDouble(i);
        }

        ListDoubleOuterClass.ListDouble.Builder builder = ListDoubleOuterClass.ListDouble.newBuilder();
        builder.addAllD(pvs);
        ListDoubleOuterClass.ListDouble od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ArrayDoubleNoAccess.class, "values");
        field1F.setAccessible(true);

        ArrayDoubleNoAccess arrayDouble = new ArrayDoubleNoAccess();
        field1F.set(arrayDouble, vs);
        byte[] epb = ProtoBuf.toByteArray(arrayDouble);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testEncodeArrayUnboxedNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {
        List<Double> pvs = new ArrayList<>();

        JsonArray jvs = JsonArray.parseArray(v);
        double[] vs = new double[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            pvs.add(jvs.getDouble(i));
            vs[i] = jvs.getDouble(i);
        }

        ListDoubleOuterClass.ListDouble.Builder builder = ListDoubleOuterClass.ListDouble.newBuilder();
        builder.addAllD(pvs);
        ListDoubleOuterClass.ListDouble od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ArrayDoubleUnboxedNoAccess.class, "values");
        field1F.setAccessible(true);

        ArrayDoubleUnboxedNoAccess arrayDouble = new ArrayDoubleUnboxedNoAccess();
        field1F.set(arrayDouble, vs);
        byte[] epb = ProtoBuf.toByteArray(arrayDouble);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testDecodeNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        List<Double> vs = new ArrayList<>();
        List<Double> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getDouble(i));
            pvs.add(jvs.getDouble(i));
        }

        ListDoubleOuterClass.ListDouble.Builder builder = ListDoubleOuterClass.ListDouble.newBuilder();
        builder.addAllD(pvs);
        ListDoubleOuterClass.ListDouble od = builder.build();
        byte[] pb = od.toByteArray();


        ListDoubleOuterClass.ListDouble pbOd = ListDoubleOuterClass.ListDouble.parseFrom(pb);

        ListDoubleNoAccess listDouble = ProtoBuf.toObject(pb, ListDoubleNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ListDoubleNoAccess.class, "list");
        fieldF.setAccessible(true);

        List<Double> list = (List)fieldF.get(listDouble);
        assertEquals(pbOd.getDList().size(), list.size());
        for (int i=0;i<pbOd.getDList().size();i++) {
            assertEquals(pbOd.getDList().get(i), list.get(i));
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testDecodeArrayNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        List<Double> vs = new ArrayList<>();
        List<Double> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getDouble(i));
            pvs.add(jvs.getDouble(i));
        }

        ListDoubleOuterClass.ListDouble.Builder builder = ListDoubleOuterClass.ListDouble.newBuilder();
        builder.addAllD(pvs);
        ListDoubleOuterClass.ListDouble od = builder.build();
        byte[] pb = od.toByteArray();


        ListDoubleOuterClass.ListDouble pbOd = ListDoubleOuterClass.ListDouble.parseFrom(pb);

        ArrayDoubleNoAccess listDouble = ProtoBuf.toObject(pb, ArrayDoubleNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ArrayDoubleNoAccess.class, "values");
        fieldF.setAccessible(true);

        Double[] values = (Double[])fieldF.get(listDouble);
        assertEquals(pbOd.getDList().size(), values.length);
        for (int i=0;i<pbOd.getDList().size();i++) {
            assertEquals(pbOd.getDList().get(i), values[i]);
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testDecodeArrayUnboxedNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        List<Double> vs = new ArrayList<>();
        List<Double> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getDouble(i));
            pvs.add(jvs.getDouble(i));
        }

        ListDoubleOuterClass.ListDouble.Builder builder = ListDoubleOuterClass.ListDouble.newBuilder();
        builder.addAllD(pvs);
        ListDoubleOuterClass.ListDouble od = builder.build();
        byte[] pb = od.toByteArray();


        ListDoubleOuterClass.ListDouble pbOd = ListDoubleOuterClass.ListDouble.parseFrom(pb);

        ArrayDoubleUnboxedNoAccess listDouble = ProtoBuf.toObject(pb, ArrayDoubleUnboxedNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ArrayDoubleUnboxedNoAccess.class, "values");
        fieldF.setAccessible(true);

        double[] values = (double[])fieldF.get(listDouble);
        assertEquals(pbOd.getDList().size(), values.length);
        for (int i=0;i<pbOd.getDList().size();i++) {
            assertEquals(pbOd.getDList().get(i), values[i]);
        }

    }
}
