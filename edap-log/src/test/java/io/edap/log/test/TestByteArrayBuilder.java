package io.edap.log.test;

import io.edap.log.helps.ByteArrayBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestByteArrayBuilder {

    @ParameterizedTest
    @ValueSource(strings = {
            "0","5","6","9",
            "10","50","61","99",
            "100","500","601","999",
            "1000","3000",Short.MAX_VALUE + "",

            "-5","-6","-9",
            "-10","-50","-61","-99",
            "-100","-500","-601","-999",
            "-1000","-5000","-6001","-9999",
            "-10000","-20000",Short.MIN_VALUE + "",

            "null"

    })
    public void testAppendShort(String str) {
        ByteArrayBuilder builder = new ByteArrayBuilder();
        if (!"null".equals(str)) {
            short i = Short.parseShort(str);

            builder.reset();
            builder.append(i);
            assertArrayEquals(builder.toByteArray(), String.valueOf(i).getBytes());

            Short o = new Short(i);
            builder.reset();
            builder.append(o);
            assertArrayEquals(builder.toByteArray(), String.valueOf(i).getBytes());
        } else {
            Short o = null;
            builder.reset();
            builder.append(o);
            assertArrayEquals(builder.toByteArray(), "null".getBytes());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0","5","6","9",
            "10","50","61","99",
            "100","500","601","999",
            "1000","5000","6001","9999",
            "10000","50000","60001","99999",
            "100000","500000","600001","999999",
            "1000000","5000000","6000001","9999999",
            "10000000","50000000","60000001","99999999",
            "100000000","500000000","600000001","999999999",
            "1000000000",Integer.MAX_VALUE + "",

            "-5","-6","-9",
            "-10","-50","-61","-99",
            "-100","-500","-601","-999",
            "-1000","-5000","-6001","-9999",
            "-10000","-50000","-60001","-99999",
            "-100000","-500000","-600001","-999999",
            "-1000000","-5000000","-6000001","-9999999",
            "-10000000","-50000000","-60000001","-99999999",
            "-100000000","-500000000","-600000001","-999999999",
            "-1000000000",Integer.MIN_VALUE + "",

            "null"

    })
    public void testAppendInt(String str) {
        ByteArrayBuilder builder = new ByteArrayBuilder();
        if (!"null".equals(str)) {
            int i = Integer.parseInt(str);

            builder.reset();
            builder.append(i);
            assertArrayEquals(builder.toByteArray(), String.valueOf(i).getBytes());

            Integer o = new Integer(i);
            builder.reset();
            builder.append(o);
            assertArrayEquals(builder.toByteArray(), String.valueOf(i).getBytes());
        } else {
            Integer o = null;
            builder.reset();
            builder.append(o);
            assertArrayEquals(builder.toByteArray(), "null".getBytes());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0","5","6","9",
            "10","50","61","99",
            "100","500","601","999",
            "1000","5000","6001","9999",
            "10000","50000","60001","99999",
            "100000","500000","600001","999999",
            "1000000","5000000","6000001","9999999",
            "10000000","50000000","60000001","99999999",
            "100000000","500000000","600000001","999999999",
            "1000000000","5000000000","6000000001","9999999999",
            "10000000000","50000000000","60000000001","99999999999",
            "100000000000","500000000000","600000000001","999999999999",
            "1000000000000","5000000000000","6000000000001","9999999999999",
            "10000000000000","50000000000000","60000000000001","99999999999999",
            "100000000000000","500000000000000","600000000000001","999999999999999",
            "1000000000000000","5000000000000000","6000000000000001","9999999999999999",
            "10000000000000000","50000000000000000","60000000000000001","99999999999999999",
            "100000000000000000","500000000000000000","600000000000000001","999999999999999999",
            "1000000000000000000","5000000000000000000","6000000000000000001", Long.MAX_VALUE + "",

            "-5","-6","-9",
            "-10","-50","-61","-99",
            "-100","-500","-601","-999",
            "-1000","-5000","-6001","-9999",
            "-10000","-50000","-60001","-99999",
            "-100000","-500000","-600001","-999999",
            "-1000000","-5000000","-6000001","-9999999",
            "-10000000","-50000000","-60000001","-99999999",
            "-100000000","-500000000","-600000001","-999999999",
            "-1000000000","-5000000000","-6000000001","-9999999999",
            "-10000000000","-50000000000","-60000000001","-99999999999",
            "-100000000000","-500000000000","-600000000001","-999999999999",
            "-1000000000000","-5000000000000","-6000000000001","-9999999999999",
            "-10000000000000","-50000000000000","-60000000000001","-99999999999999",
            "-100000000000000","-500000000000000","-600000000000001","-999999999999999",
            "-1000000000000000","-5000000000000000","-6000000000000001","-9999999999999999",
            "-10000000000000000","-50000000000000000","-60000000000000001","-99999999999999999",
            "-100000000000000000","-500000000000000000","-600000000000000001","-999999999999999999",
            "-1000000000000000000","-5000000000000000000","-6000000000000000001",Long.MIN_VALUE + "",

            "null"

    })
    public void testAppendLong(String str) {
        ByteArrayBuilder builder = new ByteArrayBuilder();
        if (!"null".equals(str)) {
            long i = Long.parseLong(str);

            builder.reset();
            builder.append(i);
            assertArrayEquals(builder.toByteArray(), String.valueOf(i).getBytes());

            Long o = new Long(i);
            builder.reset();
            builder.append(o);
            assertArrayEquals(builder.toByteArray(), String.valueOf(i).getBytes());
        } else {
            Long o = null;
            builder.reset();
            builder.append(o);
            assertArrayEquals(builder.toByteArray(), "null".getBytes());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "a",
            "abcdefgh",
            "中文内容",
            "az中文",
            "",
            "🐶头",
            "unicode表情🐶符号demo",
            "\u0080",
            "abdc再89754",
            "null"
    })
    public void testAppendString(String str) throws UnsupportedEncodingException {
        if ("null".equals(str)) {
            str = null;
        }
        ByteArrayBuilder builder = new ByteArrayBuilder();
        builder.reset();
        builder.append(str);
        if (str != null) {
            assertArrayEquals(builder.toByteArray(), str.getBytes("utf-8"));
        } else {
            assertArrayEquals(builder.toByteArray(), "null".getBytes());
        }
    }

    @Test
    public void testAppendSubString() throws UnsupportedEncodingException {
        String str = "test中文twe";
        ByteArrayBuilder builder = new ByteArrayBuilder();
        builder.reset();
        builder.append(str, 1, 8);
        assertArrayEquals(builder.toByteArray(), str.substring(1).getBytes("utf-8"));

        str = "at中文tw";
        builder.reset();
        builder.append(str, 1, 5);
        assertArrayEquals(builder.toByteArray(), str.substring(1).getBytes("utf-8"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "a",
            "ab",
            "abc",
    })
    public void testAppendByte(String str) {
        ByteArrayBuilder builder = new ByteArrayBuilder();
        builder.reset();
        byte[] bs = str.getBytes();
        switch (bs.length) {
            case 1:
                builder.append(bs[0]);
                break;
            case 2:
                builder.append(bs[0], bs[1]);
                break;
            case 3:
                builder.append(bs[0], bs[1], bs[2]);
                break;
            default:

        }
        assertArrayEquals(builder.toByteArray(), str.getBytes());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "a",
            "ab",
            "abc",
    })
    public void testAppendBytes(String str) {
        ByteArrayBuilder builder = new ByteArrayBuilder();
        builder.reset();
        byte[] bs = str.getBytes();
        builder.append(bs);
        assertArrayEquals(builder.toByteArray(), str.getBytes());
    }

    @Test
    public void testConstruct() {
        ByteArrayBuilder builder = new ByteArrayBuilder();
        assertEquals(builder.cap(), 128);

        builder = new ByteArrayBuilder(32);
        assertEquals(builder.cap(), 32);
    }

    @Test
    public void testAppendObject() {
        Calendar now = Calendar.getInstance();
        ByteArrayBuilder builder = new ByteArrayBuilder();
        builder.append(now);

        assertArrayEquals(builder.toByteArray(), now.toString().getBytes());

        builder.reset();
        Object o = null;
        builder.append(o);
        assertArrayEquals(builder.toByteArray(), "null".getBytes());

        builder.reset();
        o = 31415926;
        builder.append(o);
        assertArrayEquals(builder.toByteArray(), "31415926".getBytes());

        builder.reset();
        o = Short.parseShort("31");
        builder.append(o);
        assertArrayEquals(builder.toByteArray(), "31".getBytes());

        builder.reset();
        o = 3141.59f;
        builder.append(o);
        assertArrayEquals(builder.toByteArray(), "3141.59".getBytes());

        builder.reset();
        o = 3.1415926d;
        builder.append(o);
        assertArrayEquals(builder.toByteArray(), "3.1415926".getBytes());

        builder.reset();
        o = 31415926L;
        builder.append(o);
        assertArrayEquals(builder.toByteArray(), "31415926".getBytes());

        builder.reset();
        o = true;
        builder.append(o);
        assertArrayEquals(builder.toByteArray(), "true".getBytes());

        builder.reset();
        o = false;
        builder.append(o);
        assertArrayEquals(builder.toByteArray(), "false".getBytes());
    }

    @Test
    public void testEnsureCapacity() {
        ByteArrayBuilder builder = new ByteArrayBuilder(8);
        byte[] bs = new byte[9];
        builder.append(bs);

        assertEquals(builder.cap(), 16);

        builder = new ByteArrayBuilder(1073741824);
        bs = new byte[1073741825];
        builder.append(bs);
        assertEquals(builder.cap(), 2147483639);

    }

    @Test
    public void testHugeCapacity() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Method method = ByteArrayBuilder.class.getDeclaredMethod("hugeCapacity", new Class[]{int.class});
        method.setAccessible(true);
        ByteArrayBuilder builder = new ByteArrayBuilder();
        int size = (int)method.invoke(builder, Integer.MAX_VALUE - 7);
        assertEquals(size, Integer.MAX_VALUE - 7);

        size = (int)method.invoke(builder, 7);
        assertEquals(size, Integer.MAX_VALUE - 8);
    }

    @Test
    public void testAppendDouble() {
        final Random rnd = new Random(0);
        ByteArrayBuilder builder = new ByteArrayBuilder(8);
        for (int i = 0; i < 1000000; i++) {
            builder.reset();
            // serialization
            double d = rnd.nextDouble() * rnd.nextInt();

            builder.append(d);

            String s = new String(builder.toByteArray());


            assertEquals(d, Double.parseDouble(s));

            builder.reset();
            Double d2 = new Double(d);

            builder.append(d2);

            s = new String(builder.toByteArray());


            assertEquals(d, Double.parseDouble(s));
        }
    }

    @Test
    public void testAppendFloat() {
        final Random rnd = new Random(0);
        ByteArrayBuilder builder = new ByteArrayBuilder(8);
        for (int i = 0; i < 1000000; i++) {
            builder.reset();
            // serialization
            float d = rnd.nextFloat() * rnd.nextInt();

            builder.append(d);

            String s = new String(builder.toByteArray());


            assertEquals(d, Float.parseFloat(s));

            builder.reset();
            Float d2 = new Float(d);

            builder.append(d2);

            s = new String(builder.toByteArray());


            assertEquals(d, Float.parseFloat(s));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Infinity",
            "-Infinity",
            "NaN",
            "0",
            "null"
    })
    public void testAppendSpecialDouble(String str) {
        if (!"null".equals(str)) {
            double d = Double.parseDouble(str);
            System.out.println("d=" + d);
            ByteArrayBuilder builder = new ByteArrayBuilder(8);
            builder.append(d);

            double d2 = Double.parseDouble(new String(builder.toByteArray()));

            assertEquals(d, d2);
        } else {
            Double d = null;
            ByteArrayBuilder builder = new ByteArrayBuilder(8);
            builder.append(d);
            assertArrayEquals(builder.toByteArray(), "null".getBytes());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Infinity",
            "-Infinity",
            "NaN",
            "0",
            "null"
    })
    public void testAppendSpecialFloat(String str) {
        if (!"null".equals(str)) {
            double d = Float.parseFloat(str);
            System.out.println("d=" + d);
            ByteArrayBuilder builder = new ByteArrayBuilder(8);
            builder.append(d);

            double d2 = Float.parseFloat(new String(builder.toByteArray()));

            assertEquals(d, d2);
        } else {
            Float d = null;
            ByteArrayBuilder builder = new ByteArrayBuilder(8);
            builder.append(d);
            assertArrayEquals(builder.toByteArray(), "null".getBytes());
        }
    }

    @Test
    public void testRemain() {
        ByteArrayBuilder builder = new ByteArrayBuilder(8);
        builder.append((byte)'a');
        assertEquals(builder.remain(), 7);
    }

    @Test
    public void testUncheckAppend() {
        ByteArrayBuilder builder = new ByteArrayBuilder(8);
        builder.uncheckAppend((byte)'b');
        assertArrayEquals(builder.toByteArray(), new byte[]{'b'});

        builder.reset();
        builder.uncheckAppend((byte)'b', (byte)'c');
        assertArrayEquals(builder.toByteArray(), new byte[]{'b','c'});

        builder.reset();
        builder.uncheckAppend((byte)'b', (byte)'c',(byte)'d');
        assertArrayEquals(builder.toByteArray(), new byte[]{'b','c', 'd'});

        builder.reset();
        builder.uncheckAppend((byte)'b', (byte)'c',(byte)'d', (byte)'e');
        assertArrayEquals(builder.toByteArray(), new byte[]{'b','c', 'd', 'e'});
    }

    @Test
    public void testAppendBoolean() {
        ByteArrayBuilder builder = new ByteArrayBuilder(8);
        builder.append(true);
        assertArrayEquals(builder.toByteArray(), new byte[]{'t','r','u','e'});

        builder.reset();
        builder.append(false);
        assertArrayEquals(builder.toByteArray(), new byte[]{'f','a','l','s','e'});

        builder.reset();
        Boolean result = null;
        builder.append(result);
        assertArrayEquals(builder.toByteArray(), new byte[]{'n','u','l','l'});

        builder.reset();
        builder.append(new Boolean(true));
        assertArrayEquals(builder.toByteArray(), new byte[]{'t','r','u','e'});

        builder.reset();
        builder.append(new Boolean(false));
        assertArrayEquals(builder.toByteArray(), new byte[]{'f','a','l','s','e'});
    }
}
