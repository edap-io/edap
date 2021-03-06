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
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoBufException;
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


public class TestListString {

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"a\",\"abcdefgh\",\"中文内容\"]"
    })
    void testEncode(String value) throws EncodeException {
        JSONArray jl = JSONArray.parseArray(value);
        List<String> vs = new ArrayList<>();
        for (int i=0;i<jl.size();i++) {
            vs.add(jl.getString(i));
        }
        ListStringOuterClass.ListString.Builder builder = ListStringOuterClass.ListString.newBuilder();
        builder.addAllValue(vs);
        ListStringOuterClass.ListString oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        ListString listString = new ListString();
        listString.list = vs;
        byte[] epb = ProtoBuf.toByteArray(listString);


        assertArrayEquals(pb, epb);
    }

    @Test
    void testEncodeStringNull() throws EncodeException {
        List<String> vs = new ArrayList<>();
        vs.add(null);
        ListString listString = new ListString();
        listString.list = vs;
        byte[] epb = ProtoBuf.toByteArray(listString);


        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeStringEmpty() throws EncodeException {
        List<String> vs = new ArrayList<>();
        vs.add("");
        ListString listString = new ListString();
        listString.list = vs;
        byte[] epb = ProtoBuf.toByteArray(listString);


        assertArrayEquals(new byte[0], epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"a\",\"abcdefgh\",\"中文内容\"]"
    })
    void testDecode(String value) throws InvalidProtocolBufferException, ProtoBufException {

        JSONArray jl = JSONArray.parseArray(value);
        List<String> vs = new ArrayList<>();
        for (int i=0;i<jl.size();i++) {
            vs.add(jl.getString(i));
        }
        ListStringOuterClass.ListString.Builder builder = ListStringOuterClass.ListString.newBuilder();
        builder.addAllValue(vs);
        ListStringOuterClass.ListString oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        ListStringOuterClass.ListString pbOf = ListStringOuterClass.ListString.parseFrom(pb);

        ListString listString = ProtoBuf.toObject(pb, ListString.class);


        assertEquals(pbOf.getValueCount(), listString.list.size());
        for (int i=0;i<pbOf.getValueCount();i++) {

            assertEquals(pbOf.getValue(i), listString.list.get(i));
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"a\",\"abcdefgh\",\"中文内容\"]"
    })
    void testEncodeArray(String value) throws EncodeException {
        JSONArray jl = JSONArray.parseArray(value);
        String[] vs = new String[jl.size()];
        List<String> pvs= new ArrayList<>();
        for (int i=0;i<jl.size();i++) {
            vs[i] = jl.getString(i);
            pvs.add(jl.getString(i));
        }
        ListStringOuterClass.ListString.Builder builder = ListStringOuterClass.ListString.newBuilder();
        builder.addAllValue(pvs);
        ListStringOuterClass.ListString oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        ArrayString listString = new ArrayString();
        listString.list = vs;
        byte[] epb = ProtoBuf.toByteArray(listString);


        assertArrayEquals(pb, epb);
    }

    @Test
    void testEncodeArrayNull() throws EncodeException {
        ArrayString listString = new ArrayString();
        byte[] epb = ProtoBuf.toByteArray(listString);


        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeArrayEmpty() throws EncodeException {
        ArrayString listString = new ArrayString();
        listString.list = new String[0];
        byte[] epb = ProtoBuf.toByteArray(listString);


        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeArrayStringNull() throws EncodeException {
        ArrayString listString = new ArrayString();
        listString.list = new String[]{null};
        byte[] epb = ProtoBuf.toByteArray(listString);


        assertArrayEquals(new byte[0], epb);
    }

    @Test
    void testEncodeArrayStringEmpty() throws EncodeException {
        ArrayString listString = new ArrayString();
        listString.list = new String[]{""};
        byte[] epb = ProtoBuf.toByteArray(listString);


        assertArrayEquals(new byte[0], epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"a\",\"abcdefgh\",\"中文内容\"]"
    })
    void testDecodArray(String value) throws InvalidProtocolBufferException, ProtoBufException {

        JSONArray jl = JSONArray.parseArray(value);
        List<String> vs = new ArrayList<>();
        for (int i=0;i<jl.size();i++) {
            vs.add(jl.getString(i));
        }
        ListStringOuterClass.ListString.Builder builder = ListStringOuterClass.ListString.newBuilder();
        builder.addAllValue(vs);
        ListStringOuterClass.ListString oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        ListStringOuterClass.ListString pbOf = ListStringOuterClass.ListString.parseFrom(pb);

        ArrayString listString = ProtoBuf.toObject(pb, ArrayString.class);


        assertEquals(pbOf.getValueCount(), listString.list.length);
        for (int i=0;i<pbOf.getValueCount();i++) {

            assertEquals(pbOf.getValue(i), listString.list[i]);
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"a\",\"abcdefgh\",\"中文内容\"]"
    })
    void testEncodeNoAccess(String value) throws EncodeException, NoSuchFieldException, IllegalAccessException {
        JSONArray jl = JSONArray.parseArray(value);
        List<String> vs = new ArrayList<>();
        for (int i=0;i<jl.size();i++) {
            vs.add(jl.getString(i));
        }
        ListStringOuterClass.ListString.Builder builder = ListStringOuterClass.ListString.newBuilder();
        builder.addAllValue(vs);
        ListStringOuterClass.ListString oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");

        Field field1F = ClazzUtil.getDeclaredField(ListStringNoAccess.class, "list");
        field1F.setAccessible(true);

        ListStringNoAccess listString = new ListStringNoAccess();
        field1F.set(listString, vs);
        byte[] epb = ProtoBuf.toByteArray(listString);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"a\",\"abcdefgh\",\"中文内容\"]"
    })
    void testEncodeArrayNoAccess(String value) throws EncodeException, NoSuchFieldException, IllegalAccessException {
        JSONArray jl = JSONArray.parseArray(value);
        String[] vs = new String[jl.size()];
        List<String> pvs= new ArrayList<>();
        for (int i=0;i<jl.size();i++) {
            vs[i] = jl.getString(i);
            pvs.add(jl.getString(i));
        }
        ListStringOuterClass.ListString.Builder builder = ListStringOuterClass.ListString.newBuilder();
        builder.addAllValue(pvs);
        ListStringOuterClass.ListString oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");

        Field field1F = ClazzUtil.getDeclaredField(ArrayStringNoAccess.class, "list");
        field1F.setAccessible(true);

        ArrayStringNoAccess listString = new ArrayStringNoAccess();
        field1F.set(listString, vs);
        byte[] epb = ProtoBuf.toByteArray(listString);


        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"a\",\"abcdefgh\",\"中文内容\"]"
    })
    void testDecodeNoAccess(String value) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        JSONArray jl = JSONArray.parseArray(value);
        List<String> vs = new ArrayList<>();
        for (int i=0;i<jl.size();i++) {
            vs.add(jl.getString(i));
        }
        ListStringOuterClass.ListString.Builder builder = ListStringOuterClass.ListString.newBuilder();
        builder.addAllValue(vs);
        ListStringOuterClass.ListString oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        ListStringOuterClass.ListString pbOf = ListStringOuterClass.ListString.parseFrom(pb);

        ListStringNoAccess listString = ProtoBuf.toObject(pb, ListStringNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ListStringNoAccess.class, "list");
        fieldF.setAccessible(true);

        List<String> list = (List<String>)fieldF.get(listString);
        assertEquals(pbOf.getValueCount(), list.size());
        for (int i=0;i<pbOf.getValueCount();i++) {

            assertEquals(pbOf.getValue(i), list.get(i));
        }

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"a\",\"abcdefgh\",\"中文内容\"]"
    })
    void testDecodeArrayNoAccess(String value) throws InvalidProtocolBufferException, ProtoBufException, NoSuchFieldException, IllegalAccessException {

        JSONArray jl = JSONArray.parseArray(value);
        List<String> vs = new ArrayList<>();
        for (int i=0;i<jl.size();i++) {
            vs.add(jl.getString(i));
        }
        ListStringOuterClass.ListString.Builder builder = ListStringOuterClass.ListString.newBuilder();
        builder.addAllValue(vs);
        ListStringOuterClass.ListString oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        ListStringOuterClass.ListString pbOf = ListStringOuterClass.ListString.parseFrom(pb);

        ArrayStringNoAccess listString = ProtoBuf.toObject(pb, ArrayStringNoAccess.class);
        Field fieldF = ClazzUtil.getDeclaredField(ArrayStringNoAccess.class, "list");
        fieldF.setAccessible(true);

        String[] list = (String[])fieldF.get(listString);
        assertEquals(pbOf.getValueCount(), list.length);
        for (int i=0;i<pbOf.getValueCount();i++) {

            assertEquals(pbOf.getValue(i), list[i]);
        }

    }
}
