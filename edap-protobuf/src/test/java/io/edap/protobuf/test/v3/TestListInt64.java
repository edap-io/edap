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
import io.edap.protobuf.test.message.v3.ArrayInt64;
import io.edap.protobuf.test.message.v3.ArrayInt64Unboxed;
import io.edap.protobuf.test.message.v3.ListInt64;
import io.edap.protobuf.test.message.v3.ListInt64OuterClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;


public class TestListInt64 {

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

        ListInt64OuterClass.ListInt64.Builder builder = ListInt64OuterClass.ListInt64.newBuilder();
        builder.addAllValue(pvs);
        ListInt64OuterClass.ListInt64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ListInt64 listInt64 = new ListInt64();
        listInt64.list = vs;
        byte[] epb = ProtoBuf.toByteArray(listInt64);
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

        ListInt64OuterClass.ListInt64.Builder builder = ListInt64OuterClass.ListInt64.newBuilder();
        builder.addAllValue(pvs);
        ListInt64OuterClass.ListInt64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListInt64OuterClass.ListInt64 pbOd = ListInt64OuterClass.ListInt64.parseFrom(pb);

        ListInt64 ListInt64 = ProtoBuf.toObject(pb, ListInt64.class);


        assertEquals(pbOd.getValueList().size(), ListInt64.list.size());
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), ListInt64.list.get(i));
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

        ListInt64OuterClass.ListInt64.Builder builder = ListInt64OuterClass.ListInt64.newBuilder();
        builder.addAllValue(pvs);
        ListInt64OuterClass.ListInt64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArrayInt64 listInt64 = new ArrayInt64();
        listInt64.list = vs;
        byte[] epb = ProtoBuf.toByteArray(listInt64);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @Test
    void testEncodeArrayNull() {
        ArrayInt64 listInt64 = new ArrayInt64();
        byte[] epb = ProtoBuf.toByteArray(listInt64);

        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeArrayEmpty() {
        ArrayInt64 listInt64 = new ArrayInt64();
        listInt64.list = new Long[0];
        byte[] epb = ProtoBuf.toByteArray(listInt64);

        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeArrayNullUnboxed() {
        ArrayInt64Unboxed listInt64 = new ArrayInt64Unboxed();
        byte[] epb = ProtoBuf.toByteArray(listInt64);

        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeArrayEmptyUnboxed() {
        ArrayInt64Unboxed listInt64 = new ArrayInt64Unboxed();
        listInt64.list = new long[0];
        byte[] epb = ProtoBuf.toByteArray(listInt64);

        assertArrayEquals(new byte[0], epb);
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

        ListInt64OuterClass.ListInt64.Builder builder = ListInt64OuterClass.ListInt64.newBuilder();
        builder.addAllValue(pvs);
        ListInt64OuterClass.ListInt64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListInt64OuterClass.ListInt64 pbOd = ListInt64OuterClass.ListInt64.parseFrom(pb);

        ArrayInt64 ListInt64 = ProtoBuf.toObject(pb, ArrayInt64.class);


        assertEquals(pbOd.getValueList().size(), ListInt64.list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i).longValue(), ListInt64.list[i].longValue());
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

        ListInt64OuterClass.ListInt64.Builder builder = ListInt64OuterClass.ListInt64.newBuilder();
        builder.addAllValue(pvs);
        ListInt64OuterClass.ListInt64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArrayInt64Unboxed listInt64 = new ArrayInt64Unboxed();
        listInt64.list = vs;
        byte[] epb = ProtoBuf.toByteArray(listInt64);
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

        ListInt64OuterClass.ListInt64.Builder builder = ListInt64OuterClass.ListInt64.newBuilder();
        builder.addAllValue(pvs);
        ListInt64OuterClass.ListInt64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListInt64OuterClass.ListInt64 pbOd = ListInt64OuterClass.ListInt64.parseFrom(pb);

        ArrayInt64Unboxed ListInt64 = ProtoBuf.toObject(pb, ArrayInt64Unboxed.class);


        assertEquals(pbOd.getValueList().size(), ListInt64.list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i).longValue(), ListInt64.list[i]);
        }

    }
}
