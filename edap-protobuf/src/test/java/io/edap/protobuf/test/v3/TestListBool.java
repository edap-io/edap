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
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBuf;
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
        JSONArray jl = JSONArray.parseArray(jlist);
        for (int i=0;i<jl.size();i++) {
            vs.add(Boolean.valueOf(jl.getBoolean(i)));
        }
        ListBoolOuterClass.ListBool.Builder builder = ListBoolOuterClass.ListBool.newBuilder();
        builder.addAllValue(vs);
        ListBoolOuterClass.ListBool od = builder.build();
        byte[] pb = od.toByteArray();

        System.out.println(conver2HexStr(pb));

        ListBool ListBool = new ListBool();
        ListBool.values = vs;
        byte[] epb = ProtoBuf.toByteArray(ListBool);

        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[true,false,true,true,false]",
            "[true,true,false,true,false]"
    })
    void testDecode(String jlist) {

        try {
            List<Boolean> vs = new ArrayList<>();
            JSONArray jl = JSONArray.parseArray(jlist);
            for (int i = 0; i < jl.size(); i++) {
                vs.add(Boolean.valueOf(jl.getBoolean(i)));
            }
            ListBoolOuterClass.ListBool.Builder builder = ListBoolOuterClass.ListBool.newBuilder();
            builder.addAllValue(vs);
            ListBoolOuterClass.ListBool od = builder.build();
            byte[] pb = od.toByteArray();


            ListBoolOuterClass.ListBool pbOd = ListBoolOuterClass.ListBool.parseFrom(pb);

            ListBool ListBool = ProtoBuf.toObject(pb, ListBool.class);


            assertEquals(pbOd.getValueList().size(), ListBool.values.size());
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
        JSONArray jl = JSONArray.parseArray(jlist);
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
            JSONArray jl = JSONArray.parseArray(jlist);
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
        JSONArray jl = JSONArray.parseArray(jlist);
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
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[true,false,true,true,false]",
            "[true,true,false,true,false]"
    })
    void testDecodeArrayUnboxed(String jlist) {

        try {
            List<Boolean> vs = new ArrayList<>();
            JSONArray jl = JSONArray.parseArray(jlist);
            for (int i = 0; i < jl.size(); i++) {
                vs.add(Boolean.valueOf(jl.getBoolean(i)));
            }
            ListBoolOuterClass.ListBool.Builder builder = ListBoolOuterClass.ListBool.newBuilder();
            builder.addAllValue(vs);
            ListBoolOuterClass.ListBool od = builder.build();
            byte[] pb = od.toByteArray();


            ListBoolOuterClass.ListBool pbOd = ListBoolOuterClass.ListBool.parseFrom(pb);

            ArrayBoolUnboxed arrayBool = ProtoBuf.toObject(pb, ArrayBoolUnboxed.class);


            assertEquals(pbOd.getValueList().size(), arrayBool.values.length);
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
        JSONArray jl = JSONArray.parseArray(jlist);
        for (int i=0;i<jl.size();i++) {
            vs.add(Boolean.valueOf(jl.getBoolean(i)));
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
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[true,false,true,true,false]",
            "[true,true,false,true,false]"
    })
    void testEncodeArrayNoAccess(String jlist) throws EncodeException, NoSuchFieldException, IllegalAccessException {
        List<Boolean> vs = new ArrayList<>();
        JSONArray jl = JSONArray.parseArray(jlist);
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

        ArrayBoolNoAccess arrayBool = new ArrayBoolNoAccess();
        Field field1F = ClazzUtil.getDeclaredField(ArrayBoolNoAccess.class, "values");
        field1F.setAccessible(true);
        field1F.set(arrayBool, evs);
        byte[] epb = ProtoBuf.toByteArray(arrayBool);

        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[true,false,true,true,false]",
            "[true,true,false,true,false]"
    })
    void testEncodeArrayUnboxedNoAccess(String jlist) throws EncodeException, NoSuchFieldException, IllegalAccessException {
        List<Boolean> vs = new ArrayList<>();
        JSONArray jl = JSONArray.parseArray(jlist);
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

        ArrayBoolUnboxedNoAccess arrayBool = new ArrayBoolUnboxedNoAccess();
        Field field1F = ClazzUtil.getDeclaredField(ArrayBoolUnboxedNoAccess.class, "values");
        field1F.setAccessible(true);
        field1F.set(arrayBool, evs);
        byte[] epb = ProtoBuf.toByteArray(arrayBool);

        System.out.println(conver2HexStr(epb));

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[true,false,true,true,false]",
            "[true,true,false,true,false]"
    })
    void testDecodeNoAccess(String jlist) {

        try {
            List<Boolean> vs = new ArrayList<>();
            JSONArray jl = JSONArray.parseArray(jlist);
            for (int i = 0; i < jl.size(); i++) {
                vs.add(Boolean.valueOf(jl.getBoolean(i)));
            }
            ListBoolOuterClass.ListBool.Builder builder = ListBoolOuterClass.ListBool.newBuilder();
            builder.addAllValue(vs);
            ListBoolOuterClass.ListBool od = builder.build();
            byte[] pb = od.toByteArray();


            ListBoolOuterClass.ListBool pbOd = ListBoolOuterClass.ListBool.parseFrom(pb);

            ListBoolNoAccess ListBool = ProtoBuf.toObject(pb, ListBoolNoAccess.class);
            Field fieldF = ClazzUtil.getDeclaredField(ListBoolNoAccess.class, "values");
            fieldF.setAccessible(true);

            assertEquals(pbOd.getValueList().size(), ((List)fieldF.get(ListBool)).size());
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
            JSONArray jl = JSONArray.parseArray(jlist);
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
            JSONArray jl = JSONArray.parseArray(jlist);
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
        } catch (Exception e) {
            fail(e);
        }
    }
}
