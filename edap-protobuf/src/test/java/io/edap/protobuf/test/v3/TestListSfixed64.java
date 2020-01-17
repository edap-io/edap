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
import io.edap.protobuf.test.message.v3.ArraySfixed64;
import io.edap.protobuf.test.message.v3.ArraySfixed64Unboxed;
import io.edap.protobuf.test.message.v3.ListSfixed64;
import io.edap.protobuf.test.message.v3.ListSfixed64OuterClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;


public class TestListSfixed64 {

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testEncode(String v) {
        List<Long> vs = new ArrayList<>();
        List<Long> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListSfixed64OuterClass.ListSfixed64.Builder builder = ListSfixed64OuterClass.ListSfixed64.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed64OuterClass.ListSfixed64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ListSfixed64 listSfixed64 = new ListSfixed64();
        listSfixed64.list = vs;
        byte[] epb = ProtoBuf.toByteArray(listSfixed64);
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

        ListSfixed64OuterClass.ListSfixed64.Builder builder = ListSfixed64OuterClass.ListSfixed64.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed64OuterClass.ListSfixed64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListSfixed64OuterClass.ListSfixed64 pbOd = ListSfixed64OuterClass.ListSfixed64.parseFrom(pb);

        ListSfixed64 ListSfixed64 = ProtoBuf.toObject(pb, ListSfixed64.class);


        assertEquals(pbOd.getValueList().size(), ListSfixed64.list.size());
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), ListSfixed64.list.get(i));
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testEncodeArray(String v) {

        List<Long> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        Long[] vs = new Long[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getLongValue(i);
            pvs.add(jvs.getLongValue(i));
        }

        ListSfixed64OuterClass.ListSfixed64.Builder builder = ListSfixed64OuterClass.ListSfixed64.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed64OuterClass.ListSfixed64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArraySfixed64 arraySfixed64 = new ArraySfixed64();
        arraySfixed64.list = vs;
        byte[] epb = ProtoBuf.toByteArray(arraySfixed64);
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

        ListSfixed64OuterClass.ListSfixed64.Builder builder = ListSfixed64OuterClass.ListSfixed64.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed64OuterClass.ListSfixed64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListSfixed64OuterClass.ListSfixed64 pbOd = ListSfixed64OuterClass.ListSfixed64.parseFrom(pb);

        ArraySfixed64 arraySfixed64 = ProtoBuf.toObject(pb, ArraySfixed64.class);


        assertEquals(pbOd.getValueList().size(), arraySfixed64.list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), arraySfixed64.list[i].longValue());
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testEncodeArrayUnboxed(String v) {

        List<Long> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        long[] vs = new long[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getLongValue(i);
            pvs.add(jvs.getLongValue(i));
        }

        ListSfixed64OuterClass.ListSfixed64.Builder builder = ListSfixed64OuterClass.ListSfixed64.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed64OuterClass.ListSfixed64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArraySfixed64Unboxed arraySfixed64 = new ArraySfixed64Unboxed();
        arraySfixed64.list = vs;
        byte[] epb = ProtoBuf.toByteArray(arraySfixed64);
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

        ListSfixed64OuterClass.ListSfixed64.Builder builder = ListSfixed64OuterClass.ListSfixed64.newBuilder();
        builder.addAllValue(pvs);
        ListSfixed64OuterClass.ListSfixed64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListSfixed64OuterClass.ListSfixed64 pbOd = ListSfixed64OuterClass.ListSfixed64.parseFrom(pb);

        ArraySfixed64Unboxed arraySfixed64 = ProtoBuf.toObject(pb, ArraySfixed64Unboxed.class);


        assertEquals(pbOd.getValueList().size(), arraySfixed64.list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), arraySfixed64.list[i]);
        }

    }
}
