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
import io.edap.protobuf.test.message.v3.ArrayInt32;
import io.edap.protobuf.test.message.v3.ArrayInt32Unboxed;
import io.edap.protobuf.test.message.v3.ListInt32;
import io.edap.protobuf.test.message.v3.ListInt32OuterClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;


public class TestListInt32 {

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[-1,1,128,-256]"
    })
    void testEncode(String v) {
        List<Integer> vs = new ArrayList<>();
        List<Integer> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getIntValue(i));
            pvs.add(jvs.getIntValue(i));
        }

        ListInt32OuterClass.ListInt32.Builder builder = ListInt32OuterClass.ListInt32.newBuilder();
        builder.addAllValue(pvs);
        ListInt32OuterClass.ListInt32 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ListInt32 ListInt32 = new ListInt32();
        ListInt32.list = vs;
        byte[] epb = ProtoBuf.toByteArray(ListInt32);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[-1,1,128,-256]"
    })
    void testDecode(String v) throws InvalidProtocolBufferException, ProtoBufException {

        List<Integer> vs = new ArrayList<>();
        List<Integer> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getIntValue(i));
            pvs.add(jvs.getIntValue(i));
        }

        ListInt32OuterClass.ListInt32.Builder builder = ListInt32OuterClass.ListInt32.newBuilder();
        builder.addAllValue(pvs);
        ListInt32OuterClass.ListInt32 od = builder.build();
        byte[] pb = od.toByteArray();


        ListInt32OuterClass.ListInt32 pbOd = ListInt32OuterClass.ListInt32.parseFrom(pb);

        ListInt32 listInt32 = ProtoBuf.toObject(pb, ListInt32.class);


        assertEquals(pbOd.getValueList().size(), listInt32.list.size());
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), listInt32.list.get(i));
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[-1,1,128,-256]"
    })
    void testEncodeArray(String v) {

        List<Integer> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        Integer[] vs = new Integer[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getIntValue(i);
            pvs.add(jvs.getIntValue(i));
        }

        ListInt32OuterClass.ListInt32.Builder builder = ListInt32OuterClass.ListInt32.newBuilder();
        builder.addAllValue(pvs);
        ListInt32OuterClass.ListInt32 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArrayInt32 arrayInt32 = new ArrayInt32();
        arrayInt32.list = vs;
        byte[] epb = ProtoBuf.toByteArray(arrayInt32);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[-1,1,128,-256]"
    })
    void testDecodeArray(String v) throws InvalidProtocolBufferException, ProtoBufException {

        List<Integer> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            pvs.add(jvs.getIntValue(i));
        }

        ListInt32OuterClass.ListInt32.Builder builder = ListInt32OuterClass.ListInt32.newBuilder();
        builder.addAllValue(pvs);
        ListInt32OuterClass.ListInt32 od = builder.build();
        byte[] pb = od.toByteArray();


        ListInt32OuterClass.ListInt32 pbOd = ListInt32OuterClass.ListInt32.parseFrom(pb);

        ArrayInt32 listInt32 = ProtoBuf.toObject(pb, ArrayInt32.class);


        assertEquals(pbOd.getValueList().size(), listInt32.list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i).intValue(), listInt32.list[i].intValue());
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[-1,1,128,-256]"
    })
    void testEncodeArrayUnboxed(String v) {

        List<Integer> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        int[] vs = new int[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getIntValue(i);
            pvs.add(jvs.getIntValue(i));
        }

        ListInt32OuterClass.ListInt32.Builder builder = ListInt32OuterClass.ListInt32.newBuilder();
        builder.addAllValue(pvs);
        ListInt32OuterClass.ListInt32 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArrayInt32Unboxed arrayInt32 = new ArrayInt32Unboxed();
        arrayInt32.list = vs;
        byte[] epb = ProtoBuf.toByteArray(arrayInt32);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,129]",
            "[-1,1,128,-256]"
    })
    void testDecodeArrayUnboxed(String v) throws InvalidProtocolBufferException, ProtoBufException {

        List<Integer> pvs = new ArrayList<>();
        JSONArray jvs = JSONArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            pvs.add(jvs.getIntValue(i));
        }

        ListInt32OuterClass.ListInt32.Builder builder = ListInt32OuterClass.ListInt32.newBuilder();
        builder.addAllValue(pvs);
        ListInt32OuterClass.ListInt32 od = builder.build();
        byte[] pb = od.toByteArray();


        ListInt32OuterClass.ListInt32 pbOd = ListInt32OuterClass.ListInt32.parseFrom(pb);

        ArrayInt32Unboxed listInt32 = ProtoBuf.toObject(pb, ArrayInt32Unboxed.class);


        assertEquals(pbOd.getValueList().size(), listInt32.list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i).intValue(), listInt32.list[i]);
        }

    }
}
