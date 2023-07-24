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

package io.edap.protobuf.test.ext;

import io.edap.json.Eson;
import io.edap.json.JsonArray;
import io.edap.protobuf.EncodeException;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoBufException;
import io.edap.protobuf.ProtoBufWriter;
import io.edap.protobuf.model.ProtoBufOption;
import io.edap.protobuf.test.message.ext.FieldObject;
import io.edap.protobuf.test.message.v3.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static io.edap.protobuf.ext.AnyCodec.RANGE_NULL;
import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 属性为Object的单元测试
 */
public class TestFieldObject {

    @ParameterizedTest
    @ValueSource(strings = {
            "a",
            "abcdefgh",
            "中文内容",
            "a😁文c",
            "😁a文c",
            "a文c😁",
            "a😿文c😁",
            "abcdefgh，中文内容，a😁文c，a文c😁，abcdefgh，中文内容，a😁文c，a文c😁:" +
                    "::abcdefgh，中文内容，a😁文c，a文c😁，abcdefgh，中文内容，a😁文c，a文c😁"
    })
    void testStringCodec(String value) throws EncodeException, ProtoBufException {
        FieldObject fo = new FieldObject();
        fo.setObj(value);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        FieldObject nfo = ProtoBuf.toObject(epb, FieldObject.class);
        System.out.println(nfo.getObj());
        assertEquals(nfo.getObj(), value);


        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(fo, option);
        System.out.println(conver2HexStr(epb));

        nfo = ProtoBuf.toObject(epb, FieldObject.class, option);
        System.out.println(nfo.getObj());
        assertEquals(nfo.getObj(), value);
    }

    @ParameterizedTest
    @ValueSource(ints = {
            0,
            1,
            125,
            128,
            -1,
            -129
    })
    void testIntegerCodec(int value) throws EncodeException, ProtoBufException {
        FieldObject fo = new FieldObject();
        fo.setObj(value);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        FieldObject nfo = ProtoBuf.toObject(epb, FieldObject.class);
        System.out.println(((Integer)nfo.getObj()).intValue());
        assertEquals(((Integer)nfo.getObj()).intValue(), value);
    }

    @ParameterizedTest
    @ValueSource(longs = {
            0,
            1,
            128,
            -1,
            -129,
            5671506337319861521L
    })
    void testLongCodec(long value) throws EncodeException, ProtoBufException {
        FieldObject fo = new FieldObject();
        fo.setObj(value);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        FieldObject nfo = ProtoBuf.toObject(epb, FieldObject.class);
        System.out.println(((Long)nfo.getObj()).longValue());
        assertEquals(((Long)nfo.getObj()).longValue(), value);
    }


    @ParameterizedTest
    @ValueSource(booleans = {
            true,
            false
    })
    void testBooleanCodec(boolean value) throws EncodeException, ProtoBufException {
        FieldObject fo = new FieldObject();
        fo.setObj(value);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        FieldObject nfo = ProtoBuf.toObject(epb, FieldObject.class);
        System.out.println(nfo.getObj());
        assertEquals(((Boolean)nfo.getObj()).booleanValue(), value);

    }

    @ParameterizedTest
    @ValueSource(doubles = {
            0,
            1,
            31.415926
    })
    void testDoubleCodec(double value) throws EncodeException, ProtoBufException {
        FieldObject fo = new FieldObject();
        fo.setObj(value);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        FieldObject nfo = ProtoBuf.toObject(epb, FieldObject.class);
        System.out.println(nfo.getObj());
        assertEquals(((Double)nfo.getObj()).doubleValue(), value);

    }

    @ParameterizedTest
    @ValueSource(floats = {
            0f,
            1f,
            31.415926f
    })
    void testFloatCodec(float value) throws EncodeException, ProtoBufException {
        FieldObject fo = new FieldObject();
        fo.setObj(value);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        FieldObject nfo = ProtoBuf.toObject(epb, FieldObject.class);
        System.out.println(nfo.getObj());
        assertEquals(((Float)nfo.getObj()).floatValue(), value);

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2020-03-04 13:24:35.678"
    })
    void testDateCodec(String value) throws EncodeException, ProtoBufException, ParseException {
        SimpleDateFormat timeF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        Date date = timeF.parse(value);
        FieldObject fo = new FieldObject();
        fo.setObj(date);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        FieldObject nfo = ProtoBuf.toObject(epb, FieldObject.class);
        System.out.println(timeF.format((Date)nfo.getObj()));
        assertEquals(nfo.getObj(), date);

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2020-03-04",
            "2000-12-23"
    })
    void testLocalDateCodec(String value) throws EncodeException, ProtoBufException, ParseException {
        LocalDate date = LocalDate.parse(value);
        FieldObject fo = new FieldObject();
        fo.setObj(date);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        FieldObject nfo = ProtoBuf.toObject(epb, FieldObject.class);
        System.out.println(nfo.getObj());
        assertEquals(nfo.getObj(), date);

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2020-03-04T13:24:35.678"
    })
    void testLocalDateTimeCodec(String value) throws EncodeException, ProtoBufException, ParseException {
        LocalDateTime date = LocalDateTime.parse(value);
        FieldObject fo = new FieldObject();
        fo.setObj(date);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        FieldObject nfo = ProtoBuf.toObject(epb, FieldObject.class);
        System.out.println(((LocalDateTime)nfo.getObj()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")));
        assertEquals(nfo.getObj(), date);

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "13:24:35.678"
    })
    void testLocalTimeCodec(String value) throws EncodeException, ProtoBufException, ParseException {
        LocalTime date = LocalTime.parse(value);
        FieldObject fo = new FieldObject();
        fo.setObj(date);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        FieldObject nfo = ProtoBuf.toObject(epb, FieldObject.class);
        System.out.println(((LocalTime)nfo.getObj()).format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")));
        assertEquals(nfo.getObj(), date);

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2020-03-04 13:24:35.678"
    })
    void testCalendarCodec(String value) throws EncodeException, ProtoBufException, ParseException {
        SimpleDateFormat timeF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        Calendar date = Calendar.getInstance();
        date.setTime(timeF.parse(value));
        FieldObject fo = new FieldObject();
        fo.setObj(date);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        FieldObject nfo = ProtoBuf.toObject(epb, FieldObject.class);
        System.out.println(nfo.getObj());
        assertEquals(nfo.getObj(), date);

    }

    @ParameterizedTest
    @ValueSource(longs = {
            0,
            1,
            10,
            128,
            -1,
            -129,
            5671506337319861521L
    })
    void testBigInteger(long value) throws EncodeException, ProtoBufException {
        BigInteger bv = BigInteger.valueOf(value);
        if (value == 0) {
            bv = BigInteger.ZERO;
        }
        FieldObject fo = new FieldObject();
        fo.setObj(bv);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        FieldObject nfo = ProtoBuf.toObject(epb, FieldObject.class);
        System.out.println(nfo.getObj());
        assertEquals(nfo.getObj(), bv);

    }

    @ParameterizedTest
    @ValueSource(doubles = {
            0,
            10,
            1.0,
            0.1,
            31.415926
    })
    void testBigDecimalCodec(double v) throws EncodeException, ProtoBufException {
        BigDecimal bv = new BigDecimal(v);
        System.out.println("v=" + v + ",v == 0[" + (v == 0) + "]");
        if (v == 0) {
            bv = BigDecimal.ZERO;
        }
        FieldObject fo = new FieldObject();
        fo.setObj(bv);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        FieldObject nfo = ProtoBuf.toObject(epb, FieldObject.class);
        System.out.println(bv.toPlainString());
        System.out.println(nfo.getObj());
        assertEquals(nfo.getObj(), bv);

        fo = ProtoBuf.toObject(epb, FieldObject.class);
        assertNotNull(fo);
        assertNotNull(fo.getObj());
        assertTrue(fo.getObj() instanceof BigDecimal);
        assertEquals(((BigDecimal)fo.getObj()), bv);

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "a",
            "abcdefgh",
            "中文内容",
            "a😁文c",
            "😁a文c",
            "a文c😁",
            "a😿文c😁"
    })
    void testArrayByteCodec(String value) throws EncodeException, ProtoBufException, UnsupportedEncodingException {
        FieldObject fo = new FieldObject();
        fo.setObj(value.getBytes("utf-8"));
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        fo = ProtoBuf.toObject(epb, FieldObject.class);
        assertNotNull(fo);
        assertNotNull(fo.getObj());
        assertTrue(fo.getObj() instanceof byte[]);
        assertArrayEquals((byte[])fo.getObj(), value.getBytes("utf-8"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "a",
            "abcdefgh",
            "中文内容",
            "a😁文c",
            "😁a文c",
            "a文c😁",
            "a😿文c😁"
    })
    void testArrayCharCodec(String value) throws EncodeException, ProtoBufException {
        FieldObject fo = new FieldObject();
        fo.setObj(value.toCharArray());
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        fo = ProtoBuf.toObject(epb, FieldObject.class);
        assertNotNull(fo);
        assertNotNull(fo.getObj());
        assertTrue(fo.getObj() instanceof char[]);
        assertNotNull(fo);
        assertNotNull(fo.getObj());
        assertTrue(fo.getObj() instanceof char[]);
        assertArrayEquals((char[])fo.getObj(), value.toCharArray());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "[1,128,129]",
            "[-1,1,128,-256]"
    })
    void testArrayIntCodec(String v) throws EncodeException, ProtoBufException {

        JsonArray jvs = JsonArray.parseArray(v);
        int[] vs = new int[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getIntValue(i);
        }
        FieldObject fo = new FieldObject();
        fo.setObj(vs);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        fo = ProtoBuf.toObject(epb, FieldObject.class);
        assertNotNull(fo);
        assertNotNull(fo.getObj());
        assertTrue(fo.getObj() instanceof int[]);
        assertArrayEquals((int[])fo.getObj(), vs);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null",
            "[]",
            "[1,128,129,null]",
            "[-1,1,null,128,-256]"
    })
    void testArrayIntegerCodec(String v) throws EncodeException, ProtoBufException {

        JsonArray jvs = JsonArray.parseArray(v);
        Integer[] vs = null;
        if (jvs != null) {
            vs = new Integer[jvs.size()];
            for (int i = 0; i < jvs.size(); i++) {
                String sv = jvs.getString(i);
                if (null == sv || "null".equals(sv)) {
                    vs[i] = null;
                } else {
                    vs[i] = jvs.getIntValue(i);
                }
            }
        }
        FieldObject fo = new FieldObject();
        fo.setObj(vs);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        fo = ProtoBuf.toObject(epb, FieldObject.class);
        assertNotNull(fo);
        assertArrayEquals((Integer[])fo.getObj(), vs);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null",
            "[]",
            "[0,1,128,2147483648L]",
            "[-1,1,128,2147483648L,0]"
    })
    void testArrayLongCodec(String v) throws EncodeException, ProtoBufException {
        JsonArray jvs = JsonArray.parseArray(v);
        long[] vs = null;
        if (null != jvs) {
            vs = new long[jvs.size()];
            for (int i = 0; i < jvs.size(); i++) {
                vs[i] = jvs.getLongValue(i);
            }
        }
        FieldObject fo = new FieldObject();
        fo.setObj(vs);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        fo = ProtoBuf.toObject(epb, FieldObject.class);
        assertNotNull(fo);
        assertArrayEquals((long[])fo.getObj(), vs);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "[1,null,128,2147483648L]",
            "[null,-1,1,128,2147483648L,null]"
    })
    void testArrayLongObjCodec(String v) throws EncodeException, ProtoBufException {
        JsonArray jvs = JsonArray.parseArray(v);
        Long[] vs = new Long[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            String sv = jvs.getString(i);
            System.out.println("sv=" + sv + ",\"null\".equals(sv)=" + "null".equals(sv) + ",sv==null:" + (sv==null));
            if ("null".equals(sv) || sv == null) {
                vs[i] = null;
            } else {
                vs[i] = jvs.getLongValue(i);
            }
        }
        FieldObject fo = new FieldObject();
        fo.setObj(vs);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        fo = ProtoBuf.toObject(epb, FieldObject.class);
        assertNotNull(fo);
        assertNotNull(fo.getObj());
        assertTrue(fo.getObj() instanceof Long[]);
        assertArrayEquals((Long[])fo.getObj(), vs);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "[1,31.415926]",
            "[31.415926,1]"
    })
    void testArrayFloatCodec(String v) throws EncodeException, ProtoBufException {
        JsonArray jvs = JsonArray.parseArray(v);
        float[] vs = new float[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getFloatValue(i);
        }
        FieldObject fo = new FieldObject();
        fo.setObj(vs);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        fo = ProtoBuf.toObject(epb, FieldObject.class);
        assertNotNull(fo);
        assertNotNull(fo.getObj());
        assertTrue(fo.getObj() instanceof float[]);
        assertArrayEquals((float[])fo.getObj(), vs);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "[null,1,31.415926]",
            "[31.415926,null,1,null]"
    })
    void testArrayFloatObjCodec(String v) throws EncodeException, ProtoBufException {
        JsonArray jvs = JsonArray.parseArray(v);
        Float[] vs = new Float[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            String sv = jvs.getString(i);
            if (sv == null || "null".equals(sv)) {
                vs[i] = null;
            } else {
                vs[i] = jvs.getFloatValue(i);
            }
        }
        FieldObject fo = new FieldObject();
        fo.setObj(vs);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        fo = ProtoBuf.toObject(epb, FieldObject.class);
        assertNotNull(fo);
        assertNotNull(fo.getObj());
        assertTrue(fo.getObj() instanceof Float[]);
        assertArrayEquals((Float[])fo.getObj(), vs);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "[0,1,31.415926]",
            "[31.415926,1,0]"
    })
    void testArrayDoubleCodec(String v) throws EncodeException, ProtoBufException {

        JsonArray jvs = JsonArray.parseArray(v);
        double[] vs = new double[jvs.size()];
        for (int i = 0; i < jvs.size(); i++) {
            vs[i] = jvs.getDoubleValue(i);
        }

        FieldObject fo = new FieldObject();
        fo.setObj(vs);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        fo = ProtoBuf.toObject(epb, FieldObject.class);
        assertNotNull(fo);
        assertNotNull(fo.getObj());
        assertTrue(fo.getObj() instanceof double[]);
        assertArrayEquals((double[])fo.getObj(), vs);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "[0,null,1,31.415926]",
            "[null,31.415926,1,null,0,null]"
    })
    void testArrayDoubleObjCodec(String v) throws EncodeException, ProtoBufException {

        JsonArray jvs = JsonArray.parseArray(v);
        Double[] vs = new Double[jvs.size()];
        for (int i = 0; i < jvs.size(); i++) {
            String sv = jvs.getString(i);
            if (sv == null || "null".equals(sv)) {
                vs[i] = null;
            } else {
                vs[i] = jvs.getDoubleValue(i);
            }
        }

        FieldObject fo = new FieldObject();
        fo.setObj(vs);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        fo = ProtoBuf.toObject(epb, FieldObject.class);
        assertNotNull(fo);
        assertNotNull(fo.getObj());
        assertTrue(fo.getObj() instanceof Double[]);
        assertArrayEquals((Double[])fo.getObj(), vs);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"a\",\"\",null,\"abcdefgh\",\"中文内容\"]",
            "[]"
    })
    void testArrayStringCodec(String value) throws EncodeException, ProtoBufException {
        String[] vs = null;
        JsonArray jl = JsonArray.parseArray(value);
        vs = new String[jl.size()];
        for (int i = 0; i < jl.size(); i++) {
            vs[i] = jl.getString(i);
        }

        FieldObject fo = new FieldObject();
        fo.setObj(vs);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));


        fo = ProtoBuf.toObject(epb, FieldObject.class);
        assertNotNull(fo);
        assertNotNull(fo.getObj());
        assertTrue(fo.getObj() instanceof String[]);
        assertArrayEquals((String[])fo.getObj(), vs);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[true,false,true,true,false]",
            "[true,true,false,true,false]",
            "[]"
    })
    void testArrayBoolCodec(String jlist) throws EncodeException, ProtoBufException {

        JsonArray jl = JsonArray.parseArray(jlist);
        boolean[] vs = new boolean[jl.size()];
        for (int i = 0; i < jl.size(); i++) {
            vs[i] = Boolean.valueOf(jl.getBooleanValue(i));
        }
        FieldObject fo = new FieldObject();
        fo.setObj(vs);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        fo = ProtoBuf.toObject(epb, FieldObject.class);
        assertArrayEquals(vs,(boolean[]) fo.getObj());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[null,true,false,true,true,false]",
            "[true,null,true,false,true,false,null]",
            "[]"
    })
    void testArrayBoolObjCodec(String jlist) throws EncodeException, ProtoBufException {

        JsonArray jl = JsonArray.parseArray(jlist);
        Boolean[] vs = new Boolean[jl.size()];
        for (int i = 0; i < jl.size(); i++) {
            String sv = jl.getString(i);
            if (null == sv || "null".equals(sv)) {
                vs[i] = null;
            } else {
                vs[i] = Boolean.valueOf(jl.getBooleanValue(i));
            }
        }
        FieldObject fo = new FieldObject();
        fo.setObj(vs);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        fo = ProtoBuf.toObject(epb, FieldObject.class);

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "byte",
            "boolean",
            "short",
            "char",
            "int",
            "float",
            "long",
            "double",
            "null",
            "java.util.List",
            "java.lang.Integer",
            "java.time.LocalDateTime"
    })
    void testClassCodec(String value) throws EncodeException, ClassNotFoundException, ProtoBufException {
        Class cls = null;
        if (null != value && !"null".equals(value)) {
            switch (value) {
                case "byte":
                    cls = byte.class;
                    break;
                case "boolean":
                    cls = boolean.class;
                    break;
                case "short":
                    cls = short.class;
                    break;
                case "char":
                    cls = char.class;
                    break;
                case "float":
                    cls = float.class;
                    break;
                case "int":
                    cls = int.class;
                    break;
                case "long":
                    cls = long.class;
                    break;
                case "double":
                    cls = double.class;
                    break;
                default:
                    try {
                        cls = Class.forName(value);
                    } catch (ClassNotFoundException e) {
                        throw new ProtoBufException("Class " + cls  + " not found");
                    }
            }
        }

        FieldObject fo = new FieldObject();
        fo.setObj(cls);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println("class=" + cls);
        System.out.println(conver2HexStr(epb));

        fo = ProtoBuf.toObject(epb, FieldObject.class);
        Class ncls = (Class)fo.getObj();
        assertEquals(ncls, cls);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\":\"louis\"}",
            "{\"name1\":\"louis\",\"name2\":\"louis\",\"name3\":\"louis\",\"name4\":\"louis\","
                    + "\"name5\":\"louis\",\"name6\":\"louis\",\"name7\":\"louis\",\"name8\":\"louis\","
                    + "\"name9\":\"louis\",\"name10\":\"louis\",\"name11\":\"louis\",\"name12\":\"louis\","
                    + "\"name13\":\"louis\",\"name14\":\"louis\",\"name15\":\"louis\",\"name16\":\"louis\"}"
    })
    void testMapCodec(String value) throws EncodeException, ProtoBufException {

        Map<String, Object> map = new HashMap<>();
        Map<String, Object> omap = Eson.parseJsonObject(value);

        map.putAll(omap);
        FieldObject fo = new FieldObject();
        fo.setObj(map);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        FieldObject nfo = ProtoBuf.toObject(epb, FieldObject.class);
        System.out.println(nfo.getObj());
        //assertEquals(nfo.getObj(), map);
        Iterator<String> keys = map.keySet().iterator();
        boolean equal = true;
        Map<String, Object> nmap = (Map<String, Object>)nfo.getObj();
        System.out.println("nmap.size=" + nmap.size() + ",map.size=" + map.size());
        System.out.println("nmap.size=" + nmap + ",map.size=" + map);
        while (keys.hasNext()) {
            String key = keys.next();
            Object obj = nmap.get(key);
            if (obj == null || !obj.equals(map.get(key))) {
                equal = false;
            }
        }
        assertTrue(equal);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\":\"louis\"}",
            "{\"name1\":\"louis\",\"name2\":\"louis\",\"name3\":\"louis\",\"name4\":\"louis\","
                    + "\"name5\":\"louis\",\"name6\":\"louis\",\"name7\":\"louis\",\"name8\":\"louis\","
                    + "\"name9\":\"louis\",\"name10\":\"louis\",\"name11\":\"louis\",\"name12\":\"louis\","
                    + "\"name13\":\"louis\",\"name14\":\"louis\",\"name15\":\"louis\",\"name16\":\"louis\"}"
    })
    void testLinkedHashMapCodec(String value) throws EncodeException, ProtoBufException {

        Map<String, Object> map = new LinkedHashMap<>();
        Map<String, Object> omap = Eson.parseJsonObject(value);

        map.putAll(omap);
        FieldObject fo = new FieldObject();
        fo.setObj(map);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        FieldObject nfo = ProtoBuf.toObject(epb, FieldObject.class);
        System.out.println(nfo.getObj());
        //assertEquals(nfo.getObj(), map);
        Iterator<String> keys = map.keySet().iterator();
        boolean equal = true;
        Map<String, Object> nmap = (Map<String, Object>)nfo.getObj();
        System.out.println("nmap.size=" + nmap.size() + ",map.size=" + map.size());
        System.out.println("nmap.size=" + nmap + ",map.size=" + map);
        while (keys.hasNext()) {
            String key = keys.next();
            Object obj = nmap.get(key);
            if (obj == null || !obj.equals(map.get(key))) {
                equal = false;
            }
        }
        assertTrue(equal);

        fo = new FieldObject();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "byte",
    })
    void testArrayListCodec(String value) {
        List<String> list = new ArrayList<>();
        list.add("1");
        list.add("2");
        list.add("3");
        list.add("4");

        FieldObject fo = new FieldObject();
        fo.setObj(list);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        FieldObject nfo = ProtoBuf.toObject(epb, FieldObject.class);
        System.out.println(Eson.toJsonString(nfo.getObj()));
        assertEquals(nfo.getObj(), list);

        list = new ArrayList<>();

        fo = new FieldObject();
        fo.setObj(list);
        epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        nfo = ProtoBuf.toObject(epb, FieldObject.class);
        System.out.println(Eson.toJsonString(nfo.getObj()));
        assertEquals(nfo.getObj(), list);

        List<Integer> ilist = new ArrayList<>();
        ilist.add(1);
        ilist.add(3);
        ilist.add(128);
        ilist.add(-1);
        ilist.add(-2);
        ilist.add(-3);
        ilist.add(-4);
        ilist.add(-5);
        ilist.add(-6);
        ilist.add(-7);
        ilist.add(128);
        ilist.add(129);
        ilist.add(130);
        ilist.add(131);
        ilist.add(132);
        ilist.add(133);
        ilist.add(134);

        fo = new FieldObject();
        fo.setObj(ilist);
        epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        nfo = ProtoBuf.toObject(epb, FieldObject.class);
        System.out.println(Eson.toJsonString(nfo.getObj()));
        assertEquals(nfo.getObj(), ilist);

        fo = new FieldObject();
        fo.setObj(ilist);
        epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        nfo = ProtoBuf.toObject(epb, FieldObject.class);
        System.out.println(Eson.toJsonString(nfo.getObj()));
        assertEquals(nfo.getObj(), ilist);
    }

    @Test
    void testEncodecNull() throws EncodeException, ProtoBufException {
        byte[] data = new byte[]{(byte)RANGE_NULL};
        byte[] edata = ProtoBuf.ser(null);
        System.out.println("data");
        System.out.println(conver2HexStr(data));
        System.out.println("edata");
        System.out.println(conver2HexStr(edata));
        assertArrayEquals(data, edata);

        Object v = ProtoBuf.der(edata);
        System.out.println("decode value = " + v);
        assertNull(v);
    }

    @Test
    public void testEncodecMessage() throws ProtoBufException, EncodeException {
        Project project = new Project();
        project.setId(1L);
        project.setName("edap");
        project.setRepoPath("https://www.easyea.com/edap/edap.git");

        FieldObject fo = new FieldObject();
        fo.setObj(project);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        byte[] epb = ProtoBuf.ser(fo, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        fo = (FieldObject)ProtoBuf.der(epb, option);
        assertNotNull(fo);
        Project proj = (Project)fo.getObj();
        assertEquals(proj.getId(), project.getId());
        assertEquals(proj.getName(), project.getName());
        assertEquals(proj.getRepoPath(), project.getRepoPath());
    }

    @Test
    void testObjectListCodec() {
        Object[] objs = new Object[]{1, 1.0, "123"};

        FieldObject fo = new FieldObject();
        fo.setObj(objs);
        byte[] epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        FieldObject nfo = ProtoBuf.toObject(epb, FieldObject.class);
        System.out.println(Eson.toJsonString(nfo.getObj()));
        assertArrayEquals((Object[])nfo.getObj(), objs);

        nfo = ProtoBuf.toObject(epb, FieldObject.class);
        System.out.println(Eson.toJsonString(nfo.getObj()));
        assertArrayEquals((Object[])nfo.getObj(), objs);

        objs = new Object[0];

        fo.setObj(objs);
        epb = ProtoBuf.toByteArray(fo);
        System.out.println(conver2HexStr(epb));

        nfo = ProtoBuf.toObject(epb, FieldObject.class);
        System.out.println(Eson.toJsonString(nfo.getObj()));
        assertArrayEquals((Object[])nfo.getObj(), objs);

        nfo = ProtoBuf.toObject(epb, FieldObject.class);
        System.out.println(Eson.toJsonString(nfo.getObj()));
        assertArrayEquals((Object[])nfo.getObj(), objs);
    }
}
