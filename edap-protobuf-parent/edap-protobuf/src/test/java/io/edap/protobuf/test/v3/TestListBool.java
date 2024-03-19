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

import io.edap.json.JsonArray;
import io.edap.protobuf.CodecType;
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBuf;
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
import static org.junit.jupiter.api.Assertions.*;


public class TestListBool {

    @ParameterizedTest
    @ValueSource(strings = {
            "[true,false,true,true,false]",
            "[true,true,false,true,false]"
    })
    void testEncode(String jlist) throws EncodeException {
        List<Boolean> vs = new ArrayList<>();
        JsonArray jl = JsonArray.parseArray(jlist);
        for (int i=0;i<jl.size();i++) {
            vs.add(Boolean.valueOf(jl.getBoolean(i)));
        }
        ListBoolOuterClass.ListBool.Builder builder = ListBoolOuterClass.ListBool.newBuilder();
        builder.addAllValue(vs);
        ListBoolOuterClass.ListBool od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ListBool listBool = new ListBool();
        listBool.values = vs;
        byte[] epb = ProtoBuf.toByteArray(listBool);

        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        assertArrayEquals(pb, epb);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(CodecType.FAST);
        epb = ProtoBuf.toByteArray(listBool, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[true,false,true,true,false]",
            "[true,true,false,true,false]"
    })
    void testDecode(String jlist) {

        try {
            List<Boolean> vs = new ArrayList<>();
            JsonArray jl = JsonArray.parseArray(jlist);
            for (int i = 0; i < jl.size(); i++) {
                vs.add(Boolean.valueOf(jl.getBoolean(i)));
            }
            ListBoolOuterClass.ListBool.Builder builder = ListBoolOuterClass.ListBool.newBuilder();
            builder.addAllValue(vs);
            ListBoolOuterClass.ListBool od = builder.build();
            byte[] pb = od.toByteArray();


            ListBoolOuterClass.ListBool pbOd = ListBoolOuterClass.ListBool.parseFrom(pb);

            ListBool listBool = ProtoBuf.toObject(pb, ListBool.class);

            assertEquals(pbOd.getValueList().size(), listBool.values.size());


            ProtoBufOption option = new ProtoBufOption();
            option.setCodecType(CodecType.FAST);
            byte[] epb = ProtoBuf.toByteArray(listBool, option);
            System.out.println("+-epbf[" + epb.length + "]-------------------+");
            System.out.println(conver2HexStr(epb));
            System.out.println("+--------------------+");

            listBool = ProtoBuf.toObject(epb, ListBool.class, option);
            assertEquals(pbOd.getValueList().size(), listBool.values.size());
            for (int i=0;i<pbOd.getValueList().size();i++) {
                assertEquals(pbOd.getValueList().get(i), listBool.values.get(i));
            }

        } catch (Exception e) {
            fail(e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[true,false,true,true,false]",
            "[true,true,false,true,false]"
    })
    void testEncodeArray(String jlist) throws EncodeException {
        List<Boolean> vs = new ArrayList<>();
        JsonArray jl = JsonArray.parseArray(jlist);
        Boolean[] evs = new Boolean[jl.size()];
        for (int i=0;i<jl.size();i++) {
            vs.add(Boolean.valueOf(jl.getBoolean(i)));
            evs[i] = Boolean.valueOf(jl.getBoolean(i));
        }
        ListBoolOuterClass.ListBool.Builder builder = ListBoolOuterClass.ListBool.newBuilder();
        builder.addAllValue(vs);
        ListBoolOuterClass.ListBool od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArrayBool arrayBool = new ArrayBool();
        arrayBool.values = evs;
        byte[] epb = ProtoBuf.toByteArray(arrayBool);

        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(CodecType.FAST);
        epb = ProtoBuf.toByteArray(arrayBool, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

    }

    @Test
    void testEncodeArrayNull() throws EncodeException {
        ArrayBool arrayBool = new ArrayBool();
        byte[] epb = ProtoBuf.toByteArray(arrayBool);

        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeArrayEmpty() throws EncodeException {
        ArrayBool arrayBool = new ArrayBool();
        arrayBool.values = new Boolean[0];
        byte[] epb = ProtoBuf.toByteArray(arrayBool);

        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeArrayNullUnboxed() throws EncodeException {
        ArrayBoolUnboxed arrayBool = new ArrayBoolUnboxed();
        byte[] epb = ProtoBuf.toByteArray(arrayBool);

        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeArrayEmptyUnboxed() throws EncodeException {
        ArrayBoolUnboxed arrayBool = new ArrayBoolUnboxed();
        arrayBool.values = new boolean[0];
        byte[] epb = ProtoBuf.toByteArray(arrayBool);

        assertArrayEquals(new byte[0], epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[true,false,true,true,false]",
            "[true,true,false,true,false]"
    })
    void testDecodeArray(String jlist) {

        try {
            List<Boolean> vs = new ArrayList<>();
            JsonArray jl = JsonArray.parseArray(jlist);
            for (int i = 0; i < jl.size(); i++) {
                vs.add(Boolean.valueOf(jl.getBoolean(i)));
            }
            ListBoolOuterClass.ListBool.Builder builder = ListBoolOuterClass.ListBool.newBuilder();
            builder.addAllValue(vs);
            ListBoolOuterClass.ListBool od = builder.build();
            byte[] pb = od.toByteArray();


            ListBoolOuterClass.ListBool pbOd = ListBoolOuterClass.ListBool.parseFrom(pb);

            ArrayBool arrayBool = ProtoBuf.toObject(pb, ArrayBool.class);


            assertEquals(pbOd.getValueList().size(), arrayBool.values.length);


            ProtoBufOption option = new ProtoBufOption();
            option.setCodecType(CodecType.FAST);
            byte[] epb = ProtoBuf.toByteArray(arrayBool, option);
            System.out.println("+-epbf[" + epb.length + "]-------------------+");
            System.out.println(conver2HexStr(epb));
            System.out.println("+--------------------+");

            arrayBool = ProtoBuf.toObject(epb, ArrayBool.class, option);
            assertEquals(pbOd.getValueList().size(), arrayBool.values.length);
            for (int i=0;i<pbOd.getValueList().size();i++) {
                assertEquals(pbOd.getValueList().get(i), arrayBool.values[i]);
            }
        } catch (Exception e) {
            fail(e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[true,false,true,true,false]",
            "[true,true,false,true,false]"
    })
    void testEncodeArrayUnboxed(String jlist) throws EncodeException {
        List<Boolean> vs = new ArrayList<>();
        JsonArray jl = JsonArray.parseArray(jlist);
        boolean[] evs = new boolean[jl.size()];
        for (int i=0;i<jl.size();i++) {
            vs.add(Boolean.valueOf(jl.getBoolean(i)));
            evs[i] = Boolean.valueOf(jl.getBoolean(i));
        }
        ListBoolOuterClass.ListBool.Builder builder = ListBoolOuterClass.ListBool.newBuilder();
        builder.addAllValue(vs);
        ListBoolOuterClass.ListBool od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArrayBoolUnboxed arrayBool = new ArrayBoolUnboxed();
        arrayBool.values = evs;
        byte[] epb = ProtoBuf.toByteArray(arrayBool);

        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(CodecType.FAST);
        epb = ProtoBuf.toByteArray(arrayBool, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[true,false,true,true,false]",
            "[true,true,false,true,false]"
    })
    void testDecodeArrayUnboxed(String jlist) {

        try {
            List<Boolean> vs = new ArrayList<>();
            JsonArray jl = JsonArray.parseArray(jlist);
            for (int i = 0; i < jl.size(); i++) {
                vs.add(Boolean.valueOf(jl.getBooleanValue(i)));
            }
            ListBoolOuterClass.ListBool.Builder builder = ListBoolOuterClass.ListBool.newBuilder();
            builder.addAllValue(vs);
            ListBoolOuterClass.ListBool od = builder.build();
            byte[] pb = od.toByteArray();


            ListBoolOuterClass.ListBool pbOd = ListBoolOuterClass.ListBool.parseFrom(pb);

            ArrayBoolUnboxed arrayBool = ProtoBuf.toObject(pb, ArrayBoolUnboxed.class);

            assertEquals(pbOd.getValueList().size(), arrayBool.values.length);


            ProtoBufOption option = new ProtoBufOption();
            option.setCodecType(CodecType.FAST);
            byte[] epb = ProtoBuf.toByteArray(arrayBool, option);
            System.out.println("+-epbf[" + epb.length + "]-------------------+");
            System.out.println(conver2HexStr(epb));
            System.out.println("+--------------------+");

            arrayBool = ProtoBuf.toObject(epb, ArrayBoolUnboxed.class, option);
            assertEquals(pbOd.getValueList().size(), arrayBool.values.length);
            for (int i=0;i<pbOd.getValueList().size();i++) {
                assertEquals(pbOd.getValueList().get(i), arrayBool.values[i]);
            }
        } catch (Exception e) {
            fail(e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[true,false,true,true,false]",
            "[true,true,false,true,false]"
    })
    void testEncodeNoAccess(String jlist) throws EncodeException, NoSuchFieldException, IllegalAccessException {
        List<Boolean> vs = new ArrayList<>();
        JsonArray jl = JsonArray.parseArray(jlist);
        for (int i=0;i<jl.size();i++) {
            vs.add(Boolean.valueOf(jl.getBooleanValue(i)));
        }
        ListBoolOuterClass.ListBool.Builder builder = ListBoolOuterClass.ListBool.newBuilder();
        builder.addAllValue(vs);
        ListBoolOuterClass.ListBool od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        Field field1F = ClazzUtil.getDeclaredField(ListBoolNoAccess.class, "values");
        field1F.setAccessible(true);

        ListBoolNoAccess listBool = new ListBoolNoAccess();
        field1F.set(listBool, vs);
        byte[] epb = ProtoBuf.toByteArray(listBool);

        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(CodecType.FAST);
        epb = ProtoBuf.toByteArray(listBool, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[true,false,true,true,false]",
            "[true,true,false,true,false]"
    })
    void testEncodeArrayNoAccess(String jlist) throws EncodeException, NoSuchFieldException, IllegalAccessException {
        List<Boolean> vs = new ArrayList<>();
        JsonArray jl = JsonArray.parseArray(jlist);
        Boolean[] evs = new Boolean[jl.size()];
        for (int i=0;i<jl.size();i++) {
            vs.add(Boolean.valueOf(jl.getBooleanValue(i)));
            evs[i] = Boolean.valueOf(jl.getBooleanValue(i));
        }
        ListBoolOuterClass.ListBool.Builder builder = ListBoolOuterClass.ListBool.newBuilder();
        builder.addAllValue(vs);
        ListBoolOuterClass.ListBool od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArrayBoolNoAccess arrayBool = new ArrayBoolNoAccess();
        Field field1F = ClazzUtil.getDeclaredField(ArrayBoolNoAccess.class, "values");
        field1F.setAccessible(true);
        field1F.set(arrayBool, evs);
        byte[] epb = ProtoBuf.toByteArray(arrayBool);

        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(CodecType.FAST);
        epb = ProtoBuf.toByteArray(arrayBool, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[true,false,true,true,false]",
            "[true,true,false,true,false]"
    })
    void testEncodeArrayUnboxedNoAccess(String jlist) throws EncodeException, NoSuchFieldException, IllegalAccessException {
        List<Boolean> vs = new ArrayList<>();
        JsonArray jl = JsonArray.parseArray(jlist);
        boolean[] evs = new boolean[jl.size()];
        for (int i=0;i<jl.size();i++) {
            vs.add(Boolean.valueOf(jl.getBooleanValue(i)));
            evs[i] = Boolean.valueOf(jl.getBooleanValue(i));
        }
        ListBoolOuterClass.ListBool.Builder builder = ListBoolOuterClass.ListBool.newBuilder();
        builder.addAllValue(vs);
        ListBoolOuterClass.ListBool od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ArrayBoolUnboxedNoAccess arrayBool = new ArrayBoolUnboxedNoAccess();
        Field field1F = ClazzUtil.getDeclaredField(ArrayBoolUnboxedNoAccess.class, "values");
        field1F.setAccessible(true);
        field1F.set(arrayBool, evs);
        byte[] epb = ProtoBuf.toByteArray(arrayBool);

        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(CodecType.FAST);
        epb = ProtoBuf.toByteArray(arrayBool, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[true,false,true,true,false]",
            "[true,true,false,true,false]"
    })
    void testDecodeNoAccess(String jlist) {

        try {
            List<Boolean> vs = new ArrayList<>();
            JsonArray jl = JsonArray.parseArray(jlist);
            for (int i = 0; i < jl.size(); i++) {
                vs.add(Boolean.valueOf(jl.getBoolean(i)));
            }
            ListBoolOuterClass.ListBool.Builder builder = ListBoolOuterClass.ListBool.newBuilder();
            builder.addAllValue(vs);
            ListBoolOuterClass.ListBool od = builder.build();
            byte[] pb = od.toByteArray();


            ListBoolOuterClass.ListBool pbOd = ListBoolOuterClass.ListBool.parseFrom(pb);

            ListBoolNoAccess listBool = ProtoBuf.toObject(pb, ListBoolNoAccess.class);
            Field fieldF = ClazzUtil.getDeclaredField(ListBoolNoAccess.class, "values");
            fieldF.setAccessible(true);

            assertEquals(pbOd.getValueList().size(), ((List)fieldF.get(listBool)).size());


            ProtoBufOption option = new ProtoBufOption();
            option.setCodecType(CodecType.FAST);
            byte[] epb = ProtoBuf.toByteArray(listBool, option);
            System.out.println("+-epbf[" + epb.length + "]-------------------+");
            System.out.println(conver2HexStr(epb));
            System.out.println("+--------------------+");

            listBool = ProtoBuf.toObject(epb, ListBoolNoAccess.class, option);
            assertEquals(pbOd.getValueList().size(), ((List)fieldF.get(listBool)).size());
            for (int i=0;i<pbOd.getValueList().size();i++) {
                assertEquals(pbOd.getValueList().get(i), ((List)fieldF.get(listBool)).get(i));
            }
        } catch (Exception e) {
            fail(e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[true,false,true,true,false]",
            "[true,true,false,true,false]"
    })
    void testDecodeArrayNoAccess(String jlist) {

        try {
            List<Boolean> vs = new ArrayList<>();
            JsonArray jl = JsonArray.parseArray(jlist);
            for (int i = 0; i < jl.size(); i++) {
                vs.add(Boolean.valueOf(jl.getBoolean(i)));
            }
            ListBoolOuterClass.ListBool.Builder builder = ListBoolOuterClass.ListBool.newBuilder();
            builder.addAllValue(vs);
            ListBoolOuterClass.ListBool od = builder.build();
            byte[] pb = od.toByteArray();


            ListBoolOuterClass.ListBool pbOd = ListBoolOuterClass.ListBool.parseFrom(pb);

            ArrayBoolNoAccess arrayBool = ProtoBuf.toObject(pb, ArrayBoolNoAccess.class);
            Field fieldF = ClazzUtil.getDeclaredField(ArrayBoolNoAccess.class, "values");
            fieldF.setAccessible(true);

            assertEquals(pbOd.getValueList().size(), ((Boolean[])fieldF.get(arrayBool)).length);


            ProtoBufOption option = new ProtoBufOption();
            option.setCodecType(CodecType.FAST);
            byte[] epb = ProtoBuf.toByteArray(arrayBool, option);
            System.out.println("+-epbf[" + epb.length + "]-------------------+");
            System.out.println(conver2HexStr(epb));
            System.out.println("+--------------------+");

            arrayBool = ProtoBuf.toObject(epb, ArrayBoolNoAccess.class, option);
            assertEquals(pbOd.getValueList().size(), ((Boolean[])fieldF.get(arrayBool)).length);
            for (int i=0;i<pbOd.getValueList().size();i++) {
                assertEquals(pbOd.getValueList().get(i), (((Boolean[])fieldF.get(arrayBool))[i]));
            }
        } catch (Exception e) {
            fail(e);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[true,false,true,true,false]",
            "[true,true,false,true,false]"
    })
    void testDecodeArrayUnboxedNoAccess(String jlist) {

        try {
            List<Boolean> vs = new ArrayList<>();
            JsonArray jl = JsonArray.parseArray(jlist);
            for (int i = 0; i < jl.size(); i++) {
                vs.add(Boolean.valueOf(jl.getBoolean(i)));
            }
            ListBoolOuterClass.ListBool.Builder builder = ListBoolOuterClass.ListBool.newBuilder();
            builder.addAllValue(vs);
            ListBoolOuterClass.ListBool od = builder.build();
            byte[] pb = od.toByteArray();


            ListBoolOuterClass.ListBool pbOd = ListBoolOuterClass.ListBool.parseFrom(pb);

            ArrayBoolUnboxedNoAccess arrayBool = ProtoBuf.toObject(pb, ArrayBoolUnboxedNoAccess.class);
            Field fieldF = ClazzUtil.getDeclaredField(ArrayBoolUnboxedNoAccess.class, "values");
            fieldF.setAccessible(true);

            assertEquals(pbOd.getValueList().size(), ((boolean[])fieldF.get(arrayBool)).length);


            ProtoBufOption option = new ProtoBufOption();
            option.setCodecType(CodecType.FAST);
            byte[] epb = ProtoBuf.toByteArray(arrayBool, option);
            System.out.println("+-epbf[" + epb.length + "]-------------------+");
            System.out.println(conver2HexStr(epb));
            System.out.println("+--------------------+");

            arrayBool = ProtoBuf.toObject(epb, ArrayBoolUnboxedNoAccess.class, option);
            assertEquals(pbOd.getValueList().size(), ((boolean[])fieldF.get(arrayBool)).length);
            for (int i=0;i<pbOd.getValueList().size();i++) {
                assertEquals(pbOd.getValueList().get(i), (((boolean[])fieldF.get(arrayBool))[i]));
            }
        } catch (Exception e) {
            fail(e);
        }
    }
}
