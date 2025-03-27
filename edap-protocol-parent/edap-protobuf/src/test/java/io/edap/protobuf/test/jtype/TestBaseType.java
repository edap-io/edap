/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.protobuf.test.jtype;

import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.test.message.jtype.*;
import io.edap.protobuf.test.message.v3.OneSint32OuterClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static org.junit.jupiter.api.Assertions.*;

public class TestBaseType {

    @ParameterizedTest
    @ValueSource(ints = {
            0,
            1,
            129,
            16385,
            -1,
            -129
    })
    void testEncodeShort(int value) throws EncodeException {
        OneSint32OuterClass.OneSint32.Builder builder = OneSint32OuterClass.OneSint32.newBuilder();
        builder.setValue(value);
        OneSint32OuterClass.OneSint32 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneShort oneShort = new OneShort();
        oneShort.setField1((short)value);
        byte[] epb = ProtoBuf.toByteArray(oneShort);

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(ints = {
            0,
            1,
            129,
            16385,
            -1,
            -129
    })
    void testEncodeShortObj(int value) throws EncodeException {
        OneSint32OuterClass.OneSint32.Builder builder = OneSint32OuterClass.OneSint32.newBuilder();
        builder.setValue(value);
        OneSint32OuterClass.OneSint32 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneShort oneShort = new OneShort();
        oneShort.setField1(Short.valueOf((short)value));
        byte[] epb = ProtoBuf.toByteArray(oneShort);

        assertArrayEquals(pb, epb);
    }

    @Test
    public void testShortObjDecodeShort() {
        OneShortObj oneShortObj = new OneShortObj();
        oneShortObj.setValue(null);
        byte[] epb = ProtoBuf.toByteArray(oneShortObj);
        assertTrue(epb.length == 0);

        oneShortObj = ProtoBuf.toObject(epb, OneShortObj.class);
        assertNotNull(oneShortObj);
        assertNull(oneShortObj.getValue());

        oneShortObj.setValue((Short.valueOf((short)0)));
        epb = ProtoBuf.toByteArray(oneShortObj);
        assertTrue(epb.length == 2);
        oneShortObj = ProtoBuf.toObject(epb, OneShortObj.class);
        assertNotNull(oneShortObj);
        assertEquals(oneShortObj.getValue(), (short)0);

        Random random = new Random();
        for (int i=0;i<20;i++) {
            Short s = (short)(random.nextInt(Short.MAX_VALUE) - Short.MAX_VALUE);
            oneShortObj.setValue(s);
            epb = ProtoBuf.toByteArray(oneShortObj);
            OneShortObj nShortObj = ProtoBuf.toObject(epb, OneShortObj.class);
            assertEquals(nShortObj.getValue(), s);

        }

        for (int i=0;i<20;i++) {
            Short s = (short)(random.nextInt(Short.MAX_VALUE));
            oneShortObj.setValue(s);
            epb = ProtoBuf.toByteArray(oneShortObj);
            OneShortObj nShortObj = ProtoBuf.toObject(epb, OneShortObj.class);
            assertEquals(nShortObj.getValue(), s);

        }
    }

    @Test
    public void testDecodeShort() {
        OneShort oneShort = new OneShort();
        oneShort.setField1((short)0);
        byte[] epb = ProtoBuf.toByteArray(oneShort);
        assertTrue(epb.length == 0);

        oneShort = ProtoBuf.toObject(epb, OneShort.class);
        assertNotNull(oneShort);
        assertTrue(oneShort.getField1() == 0);

        oneShort.setField1((short)0);
        epb = ProtoBuf.toByteArray(oneShort);
        assertTrue(epb.length == 0);
        oneShort = ProtoBuf.toObject(epb, OneShort.class);
        assertNotNull(oneShort);
        assertEquals(oneShort.getField1(), (short)0);

        Random random = new Random();
        for (int i=0;i<20;i++) {
            Short s = (short)(random.nextInt(Short.MAX_VALUE) - Short.MAX_VALUE);
            oneShort.setField1(s);
            epb = ProtoBuf.toByteArray(oneShort);
            OneShort nShort = ProtoBuf.toObject(epb, OneShort.class);
            assertEquals(nShort.getField1(), s);

        }

        for (int i=0;i<20;i++) {
            Short s = (short)(random.nextInt(Short.MAX_VALUE));
            oneShort.setField1(s);
            epb = ProtoBuf.toByteArray(oneShort);
            OneShort nShort = ProtoBuf.toObject(epb, OneShort.class);
            assertEquals(nShort.getField1(), s);

        }
    }


    @ParameterizedTest
    @ValueSource(ints = {
            0,
            1,
            88,
            127,
            -1,
            -88,
            -128
    })
    void testEncodeByte(int value) throws EncodeException {
        OneSint32OuterClass.OneSint32.Builder builder = OneSint32OuterClass.OneSint32.newBuilder();
        builder.setValue(value);
        OneSint32OuterClass.OneSint32 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneByte oneByte = new OneByte();
        oneByte.setValue((byte)value);
        byte[] epb = ProtoBuf.toByteArray(oneByte);

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(ints = {
            0,
            1,
            88,
            127,
            -1,
            -88,
            -128
    })
    void testEncodeByteObj(int value) throws EncodeException {
        OneSint32OuterClass.OneSint32.Builder builder = OneSint32OuterClass.OneSint32.newBuilder();
        builder.setValue(value);
        OneSint32OuterClass.OneSint32 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneByteObj oneByteObj = new OneByteObj();
        oneByteObj.setValue(Byte.valueOf((byte)value));
        byte[] epb = ProtoBuf.toByteArray(oneByteObj);

        if (value == 0) {
            assertArrayEquals(new byte[]{8, 0}, epb);
        } else {
            assertArrayEquals(pb, epb);
        }
    }

    @Test
    public void testDecodeByte() {

        OneByteObj oneByteObj = new OneByteObj();
        oneByteObj.setValue(null);
        byte[] epb = ProtoBuf.toByteArray(oneByteObj);
        assertTrue(epb.length == 0);

        oneByteObj.setValue((byte)0);
        epb = ProtoBuf.toByteArray(oneByteObj);
        assertTrue(epb.length == 2);

        OneByte oneByte = new OneByte();
        oneByte.setValue((byte)0);
        epb = ProtoBuf.toByteArray(oneByte);
        assertTrue(epb.length == 0);

        oneByte = ProtoBuf.toObject(epb, OneByte.class);
        assertNotNull(oneByte);
        assertTrue(oneByte.getValue() == 0);

        oneByte.setValue((byte)0);
        epb = ProtoBuf.toByteArray(oneByte);
        assertTrue(epb.length == 0);
        oneByte = ProtoBuf.toObject(epb, OneByte.class);
        assertNotNull(oneByte);
        assertEquals(oneByte.getValue(), (short)0);

        Random random = new Random();
        for (int i=0;i<20;i++) {
            byte v = (byte)(random.nextInt(Byte.MAX_VALUE) - Byte.MAX_VALUE);
            oneByte.setValue(v);
            epb = ProtoBuf.toByteArray(oneByte);
            OneByte nOneByte = ProtoBuf.toObject(epb, OneByte.class);
            assertEquals(nOneByte.getValue(), v);

        }

        for (int i=0;i<20;i++) {
            byte v = (byte)(random.nextInt(Byte.MAX_VALUE));
            oneByte.setValue(v);
            epb = ProtoBuf.toByteArray(oneByte);
            OneByte nOneByte = ProtoBuf.toObject(epb, OneByte.class);
            assertEquals(nOneByte.getValue(), v);

        }
    }

    @ParameterizedTest
    @ValueSource(ints = {
            0,
            1,
            88,
            127,
            128,
            129,
            65535
    })
    void testEncodeChar(int value) throws EncodeException {
        OneSint32OuterClass.OneSint32.Builder builder = OneSint32OuterClass.OneSint32.newBuilder();
        builder.setValue(value);
        OneSint32OuterClass.OneSint32 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneChar oneChar = new OneChar();
        oneChar.setValue((char)value);
        byte[] epb = ProtoBuf.toByteArray(oneChar);

        assertArrayEquals(pb, epb);
    }

    @ParameterizedTest
    @ValueSource(ints = {
            0,
            1,
            88,
            127,
            128,
            129,
            65535
    })
    void testEncodeCharObj(int value) throws EncodeException {
        OneSint32OuterClass.OneSint32.Builder builder = OneSint32OuterClass.OneSint32.newBuilder();
        builder.setValue(value);
        OneSint32OuterClass.OneSint32 oi32 = builder.build();
        byte[] pb = oi32.toByteArray();

        System.out.println("+--------------------+");
        System.out.println(conver2HexStr(pb));
        System.out.println("+--------------------+");
        OneCharObj oneCharObj = new OneCharObj();
        oneCharObj.setValue(Character.valueOf((char)value));
        byte[] epb = ProtoBuf.toByteArray(oneCharObj);

        if (value == 0) {
            assertArrayEquals(new byte[]{8, 0}, epb);
        } else {
            assertArrayEquals(pb, epb);
        }
    }

    @Test
    public void testDecodeChar() {

        OneCharObj oneCharObj = new OneCharObj();
        oneCharObj.setValue(null);
        byte[] epb = ProtoBuf.toByteArray(oneCharObj);
        assertTrue(epb.length == 0);

        oneCharObj.setValue((char)0);
        epb = ProtoBuf.toByteArray(oneCharObj);
        assertTrue(epb.length == 2);

        OneChar oneChar = new OneChar();
        oneChar.setValue((char)0);
        epb = ProtoBuf.toByteArray(oneChar);
        assertTrue(epb.length == 0);

        oneChar = ProtoBuf.toObject(epb, OneChar.class);
        assertNotNull(oneChar);
        assertTrue(oneChar.getValue() == 0);

        oneChar.setValue((char)0);
        epb = ProtoBuf.toByteArray(oneChar);
        assertTrue(epb.length == 0);
        oneChar = ProtoBuf.toObject(epb, OneChar.class);
        assertNotNull(oneChar);
        assertEquals(oneChar.getValue(), (char)0);

        Random random = new Random();
        for (int i=0;i<20;i++) {
            char v = (char)(random.nextInt(Character.MAX_VALUE) - Character.MAX_VALUE);
            oneChar.setValue(v);
            epb = ProtoBuf.toByteArray(oneChar);
            OneChar nOneByte = ProtoBuf.toObject(epb, OneChar.class);
            assertEquals(nOneByte.getValue(), v);

        }

        for (int i=0;i<20;i++) {
            char v = (char)(random.nextInt(Character.MAX_VALUE));
            oneChar.setValue(v);
            epb = ProtoBuf.toByteArray(oneChar);
            OneChar nOneChar = ProtoBuf.toObject(epb, OneChar.class);
            assertEquals(nOneChar.getValue(), v);

        }
    }
}
