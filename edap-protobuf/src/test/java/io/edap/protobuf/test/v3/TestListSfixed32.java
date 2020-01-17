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
import io.edap.protobuf.test.message.v3.ArraySfixed32;
import io.edap.protobuf.test.message.v3.ArraySfixed32Unboxed;
import io.edap.protobuf.test.message.v3.ListSfixed32;
import io.edap.protobuf.test.message.v3.ListSfixed32OuterClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    void testEncode(String v) {
        List<Integer> vs = new ArrayList<>();
        List<Integer> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
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
        JSONArray jvs = JSONArray.parseArray(v);
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
    void testEncodeArray(String v) {

        List<Integer> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
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
        JSONArray jvs = JSONArray.parseArray(v);
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
    void testEncodeArrayUnboxed(String v) {

        List<Integer> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
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
        JSONArray jvs = JSONArray.parseArray(v);
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
}
