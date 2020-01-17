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
import io.edap.protobuf.test.message.v3.ArrayFloat;
import io.edap.protobuf.test.message.v3.ArrayFloatUnboxed;
import io.edap.protobuf.test.message.v3.ListFloat;
import io.edap.protobuf.test.message.v3.ListFloatOuterClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    void testEncode(String v) {
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
    void testEncodeArray(String v) {

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
    void testEncodeArrayNull() {
        ArrayFloat arrayFloat = new ArrayFloat();

        byte[] epb = ProtoBuf.toByteArray(arrayFloat);

        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeArrayEmpty() {
        ArrayFloat arrayFloat = new ArrayFloat();
        arrayFloat.list = new Float[0];
        byte[] epb = ProtoBuf.toByteArray(arrayFloat);

        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeArrayNullUnboxed() {
        ArrayFloatUnboxed arrayFloat = new ArrayFloatUnboxed();

        byte[] epb = ProtoBuf.toByteArray(arrayFloat);

        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeArrayEmptyUnboxed() {
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
    void testEncodeArrayUnboxed(String v) {

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
}
