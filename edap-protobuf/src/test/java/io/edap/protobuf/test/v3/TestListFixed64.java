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


public class TestListFixed64 {
    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testEncode(String v) throws EncodeException {
        List<Long> vs = new ArrayList<>();
        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListFixed64OuterClass.ListFixed64.Builder builder = ListFixed64OuterClass.ListFixed64.newBuilder();
        builder.addAllValue(pvs);
        ListFixed64OuterClass.ListFixed64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ListFixed64 listFixed64 = new ListFixed64();
        listFixed64.list = vs;
        byte[] epb = ProtoBuf.toByteArray(listFixed64);
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
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListFixed64OuterClass.ListFixed64.Builder builder = ListFixed64OuterClass.ListFixed64.newBuilder();
        builder.addAllValue(pvs);
        ListFixed64OuterClass.ListFixed64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListFixed64OuterClass.ListFixed64 pbOd = ListFixed64OuterClass.ListFixed64.parseFrom(pb);

        ListFixed64 ListFixed64 = ProtoBuf.toObject(pb, ListFixed64.class);


        assertEquals(pbOd.getValueList().size(), ListFixed64.list.size());
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), ListFixed64.list.get(i));
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testEncodeArray(String v) throws EncodeException {

        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        Long[] vs = new Long[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getLongValue(i);
            pvs.add(jvs.getLongValue(i));
        }

        ListFixed64OuterClass.ListFixed64.Builder builder = ListFixed64OuterClass.ListFixed64.newBuilder();
        builder.addAllValue(pvs);
        ListFixed64OuterClass.ListFixed64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArrayFixed64 listFixed64 = new ArrayFixed64();
        listFixed64.list = vs;
        byte[] epb = ProtoBuf.toByteArray(listFixed64);
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
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            pvs.add(jvs.getLongValue(i));
        }

        ListFixed64OuterClass.ListFixed64.Builder builder = ListFixed64OuterClass.ListFixed64.newBuilder();
        builder.addAllValue(pvs);
        ListFixed64OuterClass.ListFixed64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListFixed64OuterClass.ListFixed64 pbOd = ListFixed64OuterClass.ListFixed64.parseFrom(pb);

        ArrayFixed64 ListFixed64 = ProtoBuf.toObject(pb, ArrayFixed64.class);


        assertEquals(pbOd.getValueList().size(), ListFixed64.list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i).longValue(), ListFixed64.list[i].longValue());
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testEncodeArrayUnboxed(String v) throws EncodeException {

        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        long[] vs = new long[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getLongValue(i);
            pvs.add(jvs.getLongValue(i));
        }

        ListFixed64OuterClass.ListFixed64.Builder builder = ListFixed64OuterClass.ListFixed64.newBuilder();
        builder.addAllValue(pvs);
        ListFixed64OuterClass.ListFixed64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArrayFixed64Unboxed listFixed64 = new ArrayFixed64Unboxed();
        listFixed64.list = vs;
        byte[] epb = ProtoBuf.toByteArray(listFixed64);
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
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            pvs.add(jvs.getLongValue(i));
        }

        ListFixed64OuterClass.ListFixed64.Builder builder = ListFixed64OuterClass.ListFixed64.newBuilder();
        builder.addAllValue(pvs);
        ListFixed64OuterClass.ListFixed64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListFixed64OuterClass.ListFixed64 pbOd = ListFixed64OuterClass.ListFixed64.parseFrom(pb);

        ArrayFixed64Unboxed ListFixed64 = ProtoBuf.toObject(pb, ArrayFixed64Unboxed.class);


        assertEquals(pbOd.getValueList().size(), ListFixed64.list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i).longValue(), ListFixed64.list[i]);
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testEncodeNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {
        List<Long> vs = new ArrayList<>();
        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListFixed64OuterClass.ListFixed64.Builder builder = ListFixed64OuterClass.ListFixed64.newBuilder();
        builder.addAllValue(pvs);
        ListFixed64OuterClass.ListFixed64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ListFixed64NoAccess.class, "list");
        field1F.setAccessible(true);

        ListFixed64NoAccess listFixed64 = new ListFixed64NoAccess();
        field1F.set(listFixed64, vs);
        byte[] epb = ProtoBuf.toByteArray(listFixed64);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testEncodeArrayNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        Long[] vs = new Long[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getLongValue(i);
            pvs.add(jvs.getLongValue(i));
        }

        ListFixed64OuterClass.ListFixed64.Builder builder = ListFixed64OuterClass.ListFixed64.newBuilder();
        builder.addAllValue(pvs);
        ListFixed64OuterClass.ListFixed64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ArrayFixed64NoAccess.class, "list");
        field1F.setAccessible(true);

        ArrayFixed64NoAccess listFixed64 = new ArrayFixed64NoAccess();
        field1F.set(listFixed64, vs);
        byte[] epb = ProtoBuf.toByteArray(listFixed64);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testEncodeArrayUnboxedNoAccess(String v) throws EncodeException, NoSuchFieldException, IllegalAccessException {

        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        long[] vs = new long[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getLongValue(i);
            pvs.add(jvs.getLongValue(i));
        }

        ListFixed64OuterClass.ListFixed64.Builder builder = ListFixed64OuterClass.ListFixed64.newBuilder();
        builder.addAllValue(pvs);
        ListFixed64OuterClass.ListFixed64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ArrayFixed64UnboxedNoAccess.class, "list");
        field1F.setAccessible(true);

        ArrayFixed64UnboxedNoAccess listFixed64 = new ArrayFixed64UnboxedNoAccess();
        field1F.set(listFixed64, vs);
        byte[] epb = ProtoBuf.toByteArray(listFixed64);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testDecodeNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        List<Long> vs = new ArrayList<>();
        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListFixed64OuterClass.ListFixed64.Builder builder = ListFixed64OuterClass.ListFixed64.newBuilder();
        builder.addAllValue(pvs);
        ListFixed64OuterClass.ListFixed64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListFixed64OuterClass.ListFixed64 pbOd = ListFixed64OuterClass.ListFixed64.parseFrom(pb);

        ListFixed64NoAccess ListFixed64 = ProtoBuf.toObject(pb, ListFixed64NoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ListFixed64NoAccess.class, "list");
        fieldF.setAccessible(true);

        List<Long> list = (List<Long>)fieldF.get(ListFixed64);
        assertEquals(pbOd.getValueList().size(), list.size());
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), list.get(i));
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testDecodeArraNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            pvs.add(jvs.getLongValue(i));
        }

        ListFixed64OuterClass.ListFixed64.Builder builder = ListFixed64OuterClass.ListFixed64.newBuilder();
        builder.addAllValue(pvs);
        ListFixed64OuterClass.ListFixed64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListFixed64OuterClass.ListFixed64 pbOd = ListFixed64OuterClass.ListFixed64.parseFrom(pb);

        ArrayFixed64NoAccess ListFixed64 = ProtoBuf.toObject(pb, ArrayFixed64NoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ArrayFixed64NoAccess.class, "list");
        fieldF.setAccessible(true);

        Long[] list = (Long[])fieldF.get(ListFixed64);
        assertEquals(pbOd.getValueList().size(), list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i).longValue(), list[i].longValue());
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testDecodeArraUnboxedNoAccess(String v) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            pvs.add(jvs.getLongValue(i));
        }

        ListFixed64OuterClass.ListFixed64.Builder builder = ListFixed64OuterClass.ListFixed64.newBuilder();
        builder.addAllValue(pvs);
        ListFixed64OuterClass.ListFixed64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListFixed64OuterClass.ListFixed64 pbOd = ListFixed64OuterClass.ListFixed64.parseFrom(pb);

        ArrayFixed64UnboxedNoAccess ListFixed64 = ProtoBuf.toObject(pb, ArrayFixed64UnboxedNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ArrayFixed64UnboxedNoAccess.class, "list");
        fieldF.setAccessible(true);

        long[] list = (long[])fieldF.get(ListFixed64);
        assertEquals(pbOd.getValueList().size(), list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i).longValue(), list[i]);
        }

    }
}
