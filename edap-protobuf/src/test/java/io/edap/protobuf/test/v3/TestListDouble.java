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
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoBufException;
import io.edap.protobuf.test.message.v3.ArrayDouble;
import io.edap.protobuf.test.message.v3.ArrayDoubleUnboxed;
import io.edap.protobuf.test.message.v3.ListDouble;
import io.edap.protobuf.test.message.v3.ListDoubleOuterClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    void testEncode(String v) {
        List<Double> vs = new ArrayList<>();
        List<Double> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
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
        JSONArray jvs = JSONArray.parseArray(v);
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
    void testEncodeArray(String v) {
        List<Double> pvs = new ArrayList<>();

        JSONArray jvs = JSONArray.parseArray(v);
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
        JSONArray jvs = JSONArray.parseArray(v);
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
    void testEncodeArrayNull() {
        ArrayDouble arrayDouble = new ArrayDouble();
        byte[] epb = ProtoBuf.toByteArray(arrayDouble);

        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeArrayEmpty() {
        ArrayDouble arrayDouble = new ArrayDouble();
        arrayDouble.values = new Double[0];
        byte[] epb = ProtoBuf.toByteArray(arrayDouble);

        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeArrayNullUnboxed() {
        ArrayDoubleUnboxed arrayDouble = new ArrayDoubleUnboxed();
        byte[] epb = ProtoBuf.toByteArray(arrayDouble);

        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeArrayEmptyUnboxed() {
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
    void testEncodeArrayUnboxed(String v) {
        List<Double> pvs = new ArrayList<>();

        JSONArray jvs = JSONArray.parseArray(v);
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
        JSONArray jvs = JSONArray.parseArray(v);
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
}
