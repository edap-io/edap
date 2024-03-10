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
import io.edap.protobuf.model.ProtoBufOption;
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


public class TestListInt64 {

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


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(listInt64, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testDecode(String v) throws InvalidProtocolBufferException, ProtoException {

        List<Long> vs = new ArrayList<>();
        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListInt64OuterClass.ListInt64.Builder builder = ListInt64OuterClass.ListInt64.newBuilder();
        builder.addAllValue(pvs);
        ListInt64OuterClass.ListInt64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListInt64OuterClass.ListInt64 pbOd = ListInt64OuterClass.ListInt64.parseFrom(pb);

        ListInt64 listInt64 = ProtoBuf.toObject(pb, ListInt64.class);


        assertEquals(pbOd.getValueList().size(), listInt64.list.size());
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), listInt64.list.get(i));
        }


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        byte[] epb = ProtoBuf.toByteArray(listInt64, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        listInt64 = ProtoBuf.toObject(epb, ListInt64.class, option);
        assertEquals(od.getValueList().size(), listInt64.list.size());
        for (int i=0;i<od.getValueList().size();i++) {
            assertEquals(od.getValueList().get(i).longValue(), listInt64.list.get(i));
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


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(listInt64, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

    }

    @Test
    void testEncodeArrayNull() throws EncodeException {
        ArrayInt64 listInt64 = new ArrayInt64();
        byte[] epb = ProtoBuf.toByteArray(listInt64);

        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeArrayEmpty() throws EncodeException {
        ArrayInt64 listInt64 = new ArrayInt64();
        listInt64.list = new Long[0];
        byte[] epb = ProtoBuf.toByteArray(listInt64);

        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeArrayNullUnboxed() throws EncodeException {
        ArrayInt64Unboxed listInt64 = new ArrayInt64Unboxed();
        byte[] epb = ProtoBuf.toByteArray(listInt64);

        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeArrayEmptyUnboxed() throws EncodeException {
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
    void testDecodeArray(String v) throws InvalidProtocolBufferException, ProtoException {

        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            pvs.add(jvs.getLongValue(i));
        }

        ListInt64OuterClass.ListInt64.Builder builder = ListInt64OuterClass.ListInt64.newBuilder();
        builder.addAllValue(pvs);
        ListInt64OuterClass.ListInt64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListInt64OuterClass.ListInt64 pbOd = ListInt64OuterClass.ListInt64.parseFrom(pb);

        ArrayInt64 listInt64 = ProtoBuf.toObject(pb, ArrayInt64.class);


        assertEquals(pbOd.getValueList().size(), listInt64.list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i).longValue(), listInt64.list[i].longValue());
        }


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        byte[] epb = ProtoBuf.toByteArray(listInt64, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        listInt64 = ProtoBuf.toObject(epb, ArrayInt64.class, option);
        assertEquals(od.getValueList().size(), listInt64.list.length);
        for (int i=0;i<od.getValueList().size();i++) {
            assertEquals(od.getValueList().get(i).longValue(), listInt64.list[i]);
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


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(listInt64, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testDecodeArrayUnboxed(String v) throws InvalidProtocolBufferException, ProtoException {

        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            pvs.add(jvs.getLongValue(i));
        }

        ListInt64OuterClass.ListInt64.Builder builder = ListInt64OuterClass.ListInt64.newBuilder();
        builder.addAllValue(pvs);
        ListInt64OuterClass.ListInt64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListInt64OuterClass.ListInt64 pbOd = ListInt64OuterClass.ListInt64.parseFrom(pb);

        ArrayInt64Unboxed listInt64 = ProtoBuf.toObject(pb, ArrayInt64Unboxed.class);


        assertEquals(pbOd.getValueList().size(), listInt64.list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i).longValue(), listInt64.list[i]);
        }



        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        byte[] epb = ProtoBuf.toByteArray(listInt64, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        listInt64 = ProtoBuf.toObject(epb, ArrayInt64Unboxed.class, option);
        assertEquals(od.getValueList().size(), listInt64.list.length);
        for (int i=0;i<od.getValueList().size();i++) {
            assertEquals(od.getValueList().get(i).longValue(), listInt64.list[i]);
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

        ListInt64OuterClass.ListInt64.Builder builder = ListInt64OuterClass.ListInt64.newBuilder();
        builder.addAllValue(pvs);
        ListInt64OuterClass.ListInt64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ListInt64NoAccess.class, "list");
        field1F.setAccessible(true);

        ListInt64NoAccess listInt64 = new ListInt64NoAccess();
        field1F.set(listInt64, vs);
        byte[] epb = ProtoBuf.toByteArray(listInt64);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(listInt64, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");
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

        ListInt64OuterClass.ListInt64.Builder builder = ListInt64OuterClass.ListInt64.newBuilder();
        builder.addAllValue(pvs);
        ListInt64OuterClass.ListInt64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ArrayInt64NoAccess.class, "list");
        field1F.setAccessible(true);

        ArrayInt64NoAccess listInt64 = new ArrayInt64NoAccess();
        field1F.set(listInt64, vs);
        byte[] epb = ProtoBuf.toByteArray(listInt64);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(listInt64, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

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

        ListInt64OuterClass.ListInt64.Builder builder = ListInt64OuterClass.ListInt64.newBuilder();
        builder.addAllValue(pvs);
        ListInt64OuterClass.ListInt64 od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ArrayInt64UnboxedNoAccess.class, "list");
        field1F.setAccessible(true);

        ArrayInt64UnboxedNoAccess listInt64 = new ArrayInt64UnboxedNoAccess();
        field1F.set(listInt64, vs);
        byte[] epb = ProtoBuf.toByteArray(listInt64);
        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(listInt64, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testDecodeNoAccess(String v) throws InvalidProtocolBufferException, ProtoException, NoSuchFieldException, IllegalAccessException {

        List<Long> vs = new ArrayList<>();
        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListInt64OuterClass.ListInt64.Builder builder = ListInt64OuterClass.ListInt64.newBuilder();
        builder.addAllValue(pvs);
        ListInt64OuterClass.ListInt64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListInt64OuterClass.ListInt64 pbOd = ListInt64OuterClass.ListInt64.parseFrom(pb);

        ListInt64NoAccess listInt64 = ProtoBuf.toObject(pb, ListInt64NoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ListInt64NoAccess.class, "list");
        fieldF.setAccessible(true);

        List<Long> list = (List<Long>)fieldF.get(listInt64);
        assertEquals(pbOd.getValueList().size(), list.size());
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), list.get(i));
        }


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        byte[] epb = ProtoBuf.toByteArray(listInt64, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        listInt64 = ProtoBuf.toObject(epb, ListInt64NoAccess.class, option);
        assertEquals(od.getValueList().size(), ((List<Long>)fieldF.get(listInt64)).size());
        for (int i=0;i<od.getValueList().size();i++) {
            assertEquals(od.getValueList().get(i).longValue(), ((List<Long>)fieldF.get(listInt64)).get(i));
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testDecodeArrayNoAccess(String v) throws InvalidProtocolBufferException, ProtoException, NoSuchFieldException, IllegalAccessException {

        List<Long> vs = new ArrayList<>();
        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListInt64OuterClass.ListInt64.Builder builder = ListInt64OuterClass.ListInt64.newBuilder();
        builder.addAllValue(pvs);
        ListInt64OuterClass.ListInt64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListInt64OuterClass.ListInt64 pbOd = ListInt64OuterClass.ListInt64.parseFrom(pb);

        ArrayInt64NoAccess listInt64 = ProtoBuf.toObject(pb, ArrayInt64NoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ArrayInt64NoAccess.class, "list");
        fieldF.setAccessible(true);

        Long[] list = (Long[])fieldF.get(listInt64);
        assertEquals(pbOd.getValueList().size(), list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), list[i]);
        }


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        byte[] epb = ProtoBuf.toByteArray(listInt64, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        listInt64 = ProtoBuf.toObject(epb, ArrayInt64NoAccess.class, option);
        assertEquals(od.getValueList().size(), ((Long[])fieldF.get(listInt64)).length);
        for (int i=0;i<od.getValueList().size();i++) {
            assertEquals(od.getValueList().get(i).longValue(), ((Long[])fieldF.get(listInt64))[i]);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1,128,2147483648L]",
            "[-1,1,128,2147483648L]"
    })
    void testDecodeArrayUnboxedNoAccess(String v) throws InvalidProtocolBufferException, ProtoException, NoSuchFieldException, IllegalAccessException {

        List<Long> vs = new ArrayList<>();
        List<Long> pvs = new ArrayList<>();
        JsonArray jvs = JsonArray.parseArray(v);
        for (int i=0;i<jvs.size();i++) {
            vs.add(jvs.getLongValue(i));
            pvs.add(jvs.getLongValue(i));
        }

        ListInt64OuterClass.ListInt64.Builder builder = ListInt64OuterClass.ListInt64.newBuilder();
        builder.addAllValue(pvs);
        ListInt64OuterClass.ListInt64 od = builder.build();
        byte[] pb = od.toByteArray();


        ListInt64OuterClass.ListInt64 pbOd = ListInt64OuterClass.ListInt64.parseFrom(pb);

        ArrayInt64UnboxedNoAccess listInt64 = ProtoBuf.toObject(pb, ArrayInt64UnboxedNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ArrayInt64UnboxedNoAccess.class, "list");
        fieldF.setAccessible(true);

        long[] list = (long[])fieldF.get(listInt64);
        assertEquals(pbOd.getValueList().size(), list.length);
        for (int i=0;i<pbOd.getValueList().size();i++) {
            assertEquals(pbOd.getValueList().get(i), list[i]);
        }


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        byte[] epb = ProtoBuf.toByteArray(listInt64, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        listInt64 = ProtoBuf.toObject(epb, ArrayInt64UnboxedNoAccess.class, option);
        assertEquals(od.getValueList().size(), ((long[])fieldF.get(listInt64)).length);
        for (int i=0;i<od.getValueList().size();i++) {
            assertEquals(od.getValueList().get(i).longValue(), ((long[])fieldF.get(listInt64))[i]);
        }
    }
}
