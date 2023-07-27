package io.edap.protobuf.test;

import io.edap.json.Eson;
import io.edap.json.JsonArray;
import io.edap.protobuf.ProtoBuf;
import io.edap.protobuf.ProtoBufException;
import io.edap.protobuf.model.ProtoBufOption;
import io.edap.protobuf.test.message.ext.FieldObject;
import io.edap.protobuf.test.message.v3.Project;
import io.edap.protobuf.test.message.v3.SkipDest;
import io.edap.protobuf.test.message.v3.SkipSrc;
import io.edap.protobuf.test.message.v3.SkipSrcInner;
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
import java.util.*;

import static io.edap.protobuf.test.TestUtil.conver2HexStr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TestSkipObject {

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
    public void testSkipString(String v) {
        String str = new Random().nextDouble() + "";

        SkipSrc src = buildSkipSrc(str, v);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
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
    public void testSkipInteger(Integer v) {
        String str = new Random().nextDouble() + "";

        SkipSrc src = buildSkipSrc(str, v);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
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
    public void testSkipLong(long v) {
        String str = new Random().nextDouble() + "";

        SkipSrc src = buildSkipSrc(str, v);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @ParameterizedTest
    @ValueSource(booleans = {
            true,
            false
    })
    public void testSkipBoolean(boolean v) {
        String str = new Random().nextDouble() + "";

        SkipSrc src = buildSkipSrc(str, v);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @ParameterizedTest
    @ValueSource(doubles = {
            0,
            1,
            31.415926
    })
    public void testSkipDouble(double v) {
        String str = new Random().nextDouble() + "";

        SkipSrc src = buildSkipSrc(str, v);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @ParameterizedTest
    @ValueSource(floats = {
            0f,
            1f,
            31.415926f
    })
    public void testSkipFloat(float v) {
        String str = new Random().nextDouble() + "";

        SkipSrc src = buildSkipSrc(str, v);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2020-03-04 13:24:35.678"
    })
    public void testSkipDate(String dateStr) throws ParseException {
        String str = new Random().nextDouble() + "";
        SimpleDateFormat timeF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        Date v = timeF.parse(dateStr);

        SkipSrc src = buildSkipSrc(str, v);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2020-03-04",
            "2000-12-23"
    })
    public void testSkipLocalDate(String dateStr) throws ParseException {
        String str = new Random().nextDouble() + "";
        LocalDate v = LocalDate.parse(dateStr);

        SkipSrc src = buildSkipSrc(str, v);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2020-03-04T13:24:35.678"
    })
    public void testSkipLocalDateTime(String dateStr) throws ParseException {
        String str = new Random().nextDouble() + "";
        LocalDateTime v = LocalDateTime.parse(dateStr);

        SkipSrc src = buildSkipSrc(str, v);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "13:24:35.678"
    })
    public void testSkipLocalTime(String dateStr) throws ParseException {
        String str = new Random().nextDouble() + "";
        LocalTime v = LocalTime.parse(dateStr);

        SkipSrc src = buildSkipSrc(str, v);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2020-03-04 13:24:35.678"
    })
    public void testSkipCalendar(String dateStr) throws ParseException {
        String str = new Random().nextDouble() + "";
        SimpleDateFormat timeF = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        Calendar v = Calendar.getInstance();
        v.setTime(timeF.parse(dateStr));

        SkipSrc src = buildSkipSrc(str, v);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
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
    public void testSkipBigInteger(long value) {
        String str = new Random().nextDouble() + "";
        BigInteger v = BigInteger.valueOf(value);
        if (value == 0) {
            v = BigInteger.ZERO;
        }
        SkipSrc src = buildSkipSrc(str, v);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @ParameterizedTest
    @ValueSource(doubles = {
            0,
            10,
            1.0,
            0.1,
            31.415926
    })
    public void testSkipBigDecimal(double value) {
        String str = new Random().nextDouble() + "";
        BigDecimal v = new BigDecimal(value);
        System.out.println("v=" + value + ",v == 0[" + (value == 0) + "]");
        if (value == 0) {
            v = BigDecimal.ZERO;
        }
        SkipSrc src = buildSkipSrc(str, v);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
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
    public void testSkipByteArray(String value) throws UnsupportedEncodingException {
        String str = new Random().nextDouble() + "";
        SkipSrc src = buildSkipSrc(str, value.getBytes("utf-8"));

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
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
    public void testSkipArrayChar(String value) throws UnsupportedEncodingException {
        String str = new Random().nextDouble() + "";
        SkipSrc src = buildSkipSrc(str, value.toCharArray());

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "[1,128,129]",
            "[-1,1,128,-256]"
    })
    public void testSkipArrayInt(String value) throws UnsupportedEncodingException {
        String str = new Random().nextDouble() + "";

        JsonArray jvs = JsonArray.parseArray(value);
        int[] vs = new int[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getIntValue(i);
        }

        SkipSrc src = buildSkipSrc(str, vs);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "[1,128,129]",
            "[-1,1,128,-256]"
    })
    public void testSkipArrayInteger(String value) throws UnsupportedEncodingException {
        String str = new Random().nextDouble() + "";

        JsonArray jvs = JsonArray.parseArray(value);
        Integer[] vs = new Integer[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getIntValue(i);
        }

        SkipSrc src = buildSkipSrc(str, vs);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null",
            "[]",
            "[0,1,128,2147483648L]",
            "[-1,1,128,2147483648L,0]"
    })
    public void testSkipArrayLong(String value) throws UnsupportedEncodingException {
        String str = new Random().nextDouble() + "";

        JsonArray jvs = JsonArray.parseArray(value);
        long[] vs = null;
        if (null != jvs) {
            vs = new long[jvs.size()];
            for (int i = 0; i < jvs.size(); i++) {
                vs[i] = jvs.getLongValue(i);
            }
        }
        SkipSrc src = buildSkipSrc(str, vs);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "null",
            "[]",
            "[0,1,128,2147483648L]",
            "[-1,1,128,2147483648L,0]"
    })
    public void testSkipArrayLongObj(String value) throws UnsupportedEncodingException {
        String str = new Random().nextDouble() + "";

        JsonArray jvs = JsonArray.parseArray(value);
        Long[] vs;
        if (jvs != null) {
            vs = new Long[jvs.size()];
            for (int i = 0; i < jvs.size(); i++) {
                vs[i] = jvs.getLongValue(i);
            }
        } else {
            vs = null;
        }

        SkipSrc src = buildSkipSrc(str, vs);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "[1,31.415926]",
            "[31.415926,1]"
    })
    public void testSkipArrayFloat(String value) throws UnsupportedEncodingException {
        String str = new Random().nextDouble() + "";

        JsonArray jvs = JsonArray.parseArray(value);
        float[] vs = new float[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getFloatValue(i);
        }

        SkipSrc src = buildSkipSrc(str, vs);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "[1,31.415926]",
            "[31.415926,1]"
    })
    public void testSkipArrayFloatObj(String value) throws UnsupportedEncodingException {
        String str = new Random().nextDouble() + "";

        JsonArray jvs = JsonArray.parseArray(value);
        Float[] vs = new Float[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getFloatValue(i);
        }

        SkipSrc src = buildSkipSrc(str, vs);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "[0,1,31.415926]",
            "[31.415926,1,0]"
    })
    public void testSkipArrayDouble(String value) throws UnsupportedEncodingException {
        String str = new Random().nextDouble() + "";

        JsonArray jvs = JsonArray.parseArray(value);
        double[] vs = new double[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getDoubleValue(i);
        }

        SkipSrc src = buildSkipSrc(str, vs);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }


    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "[0,null,1,31.415926]",
            "[null,31.415926,1,null,0,null]"
    })
    public void testSkipArrayDoubleObj(String value) throws UnsupportedEncodingException {
        String str = new Random().nextDouble() + "";

        JsonArray jvs = JsonArray.parseArray(value);
        Double[] vs = new Double[jvs.size()];
        for (int i=0;i<jvs.size();i++) {
            vs[i] = jvs.getDoubleValue(i);
        }

        SkipSrc src = buildSkipSrc(str, vs);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"a\",\"\",null,\"abcdefgh\",\"中文内容\"]",
            "[]"
    })
    public void testSkipArrayString(String value) throws UnsupportedEncodingException {
        String str = new Random().nextDouble() + "";

        String[] vs = null;
        JsonArray jl = JsonArray.parseArray(value);
        vs = new String[jl.size()];
        for (int i = 0; i < jl.size(); i++) {
            vs[i] = jl.getString(i);
        }

        SkipSrc src = buildSkipSrc(str, vs);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[true,false,true,true,false]",
            "[true,true,false,true,false]",
            "[]"
    })
    public void testSkipArrayBool(String value) throws UnsupportedEncodingException {
        String str = new Random().nextDouble() + "";

        JsonArray jl = JsonArray.parseArray(value);
        boolean[] vs = new boolean[jl.size()];
        for (int i = 0; i < jl.size(); i++) {
            vs[i] = Boolean.valueOf(jl.getBooleanValue(i));
        }

        SkipSrc src = buildSkipSrc(str, vs);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[true,false,true,true,false]",
            "[true,true,false,true,false]",
            "[]"
    })
    public void testSkipArrayBoolObj(String value) throws UnsupportedEncodingException {
        String str = new Random().nextDouble() + "";

        JsonArray jl = JsonArray.parseArray(value);
        Boolean[] vs = new Boolean[jl.size()];
        for (int i = 0; i < jl.size(); i++) {
            vs[i] = Boolean.valueOf(jl.getBooleanValue(i));
        }

        SkipSrc src = buildSkipSrc(str, vs);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
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
    public void testSkipClass(String value) throws UnsupportedEncodingException, ProtoBufException {
        String str = new Random().nextDouble() + "";

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

        SkipSrc src = buildSkipSrc(str, cls);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\":\"louis\"}",
            "{\"name1\":\"louis\",\"name2\":\"louis\",\"name3\":\"louis\",\"name4\":\"louis\","
                    + "\"name5\":\"louis\",\"name6\":\"louis\",\"name7\":\"louis\",\"name8\":\"louis\","
                    + "\"name9\":\"louis\",\"name10\":\"louis\",\"name11\":\"louis\",\"name12\":\"louis\","
                    + "\"name13\":\"louis\",\"name14\":\"louis\",\"name15\":\"louis\",\"name16\":\"louis\"}"
    })
    public void testSkipHashMap(String value) throws UnsupportedEncodingException {
        String str = new Random().nextDouble() + "";

        Map<String, Object> map = new HashMap<>();
        Map<String, Object> omap = Eson.parseJsonObject(value);

        map.putAll(omap);

        SkipSrc src = buildSkipSrc(str, map);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"name\":\"louis\"}",
            "{\"name1\":\"louis\",\"name2\":\"louis\",\"name3\":\"louis\",\"name4\":\"louis\","
                    + "\"name5\":\"louis\",\"name6\":\"louis\",\"name7\":\"louis\",\"name8\":\"louis\","
                    + "\"name9\":\"louis\",\"name10\":\"louis\",\"name11\":\"louis\",\"name12\":\"louis\","
                    + "\"name13\":\"louis\",\"name14\":\"louis\",\"name15\":\"louis\",\"name16\":\"louis\"}"
    })
    public void testSkipLinkedHashMap(String value) throws UnsupportedEncodingException {
        String str = new Random().nextDouble() + "";

        Map<String, Object> map = new LinkedHashMap<>();
        Map<String, Object> omap = Eson.parseJsonObject(value);

        map.putAll(omap);

        SkipSrc src = buildSkipSrc(str, map);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "byte",
    })
    public void testSkipList(String value) throws UnsupportedEncodingException {
        String str = new Random().nextDouble() + "";

        List<String> list = new ArrayList<>();
        list.add("1");
        list.add("2");
        list.add("3");
        list.add("4");

        SkipSrc src = buildSkipSrc(str, list);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);


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

        src = buildSkipSrc(str, ilist);

        epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "byte",
    })
    public void testSkipNull(String value) throws UnsupportedEncodingException {
        String str = new Random().nextDouble() + "";

        SkipSrc src = buildSkipSrc(str, null);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    @Test
    public void testSkipArrayObject() throws UnsupportedEncodingException {
        String str = new Random().nextDouble() + "";

        Object[] objs = new Object[]{1, 1.0, "123"};
        SkipSrc src = buildSkipSrc(str, objs);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
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
    public void testSkipInt(int i) {
        String str = new Random().nextDouble() + "";

        SkipSrc src = buildSkipSrc(str, i);

        byte[] epb = ProtoBuf.toByteArray(src);
        System.out.println("+-epb[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        SkipDest dest = ProtoBuf.toObject(epb, SkipDest.class);
        assertNotNull(dest);
        assertEquals(dest.field19, str);

        ProtoBufOption option = new ProtoBufOption();
        option.setCodecType(ProtoBuf.CodecType.FAST);
        epb = ProtoBuf.toByteArray(src, option);
        System.out.println("+-epbf[" + epb.length + "]-------------------+");
        System.out.println(conver2HexStr(epb));
        System.out.println("+--------------------+");

        dest = ProtoBuf.toObject(epb, SkipDest.class, option);
        assertNotNull(dest);
        assertEquals(dest.field19, str);
    }

    private SkipSrc buildSkipSrc(String v, Object skipObj) {
        Project project = new Project();
        project.setName("edap");
        project.setId(1L);
        project.setRepoPath("http://www.easyea.com/edap/edap.git");

        Random random = new Random();
        double d = random.nextDouble();
        float  f = random.nextFloat();
        int   ival = random.nextInt();
        int fixed32 = random.nextInt();
        long fixed64 = random.nextLong();
        int[] ints = new int[5];
        for (int i=0;i<5;i++) {
            ints[i] = random.nextInt();
        }
        SkipSrcInner srcInner = new SkipSrcInner();
        srcInner.setValDouble(d);
        srcInner.setValFixed32(fixed32);
        srcInner.setValFixed64(fixed64);
        srcInner.setValFloat(f);
        srcInner.setValInt(ival);
        srcInner.setValStr(v);
        srcInner.setProject(project);
        srcInner.setValObj(skipObj);
        srcInner.setValIntArray(ints);

        SkipSrc src = new SkipSrc();
        src.setSkipInner(srcInner);
        src.field19 = v;

        return src;
    }
}
