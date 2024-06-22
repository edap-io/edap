package io.edap.eproto.test;

import io.edap.eproto.reader.ByteArrayReader;
import io.edap.eproto.writer.ByteArrayWriter;
import io.edap.io.BufOut;
import io.edap.io.ByteArrayBufOut;
import io.edap.protobuf.ProtoException;
import io.edap.protobuf.wire.Field;
import io.edap.protobuf.wire.Proto;
import io.edap.protobuf.wire.exceptions.ProtoParseException;
import io.edap.protobuf.wire.parser.ProtoParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static io.edap.eproto.EprotoWriter.encodeZigZag32;
import static io.edap.eproto.test.TestUtils.*;
import static io.edap.eproto.writer.AbstractWriter.*;
import static io.edap.util.Constants.EMPTY_STRING;
import static io.edap.util.StringUtil.IS_BYTE_ARRAY;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.condition.JRE.*;

public class TestWriterAndReader {

    @Test
    public void testCodecBoolean() throws ProtoException {
        BufOut out = new ByteArrayBufOut();
        ByteArrayWriter writer = new ByteArrayWriter(out);
        writer.writeBool(null);

        byte[] data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], 1);

        ByteArrayReader reader = new ByteArrayReader(data);
        boolean bool = reader.readBool();
        assertFalse(bool);

        writer.reset();
        writer.writeBool(false);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], 0);

        reader = new ByteArrayReader(data);
        bool = reader.readBool();
        assertFalse(bool);

        writer.reset();
        writer.writeBool(Boolean.valueOf(true));

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], 2);

        reader = new ByteArrayReader(data);
        bool = reader.readBool();
        assertTrue(bool);

        writer.reset();
        writer.writeBool(Boolean.valueOf(false));

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], 0);

        reader = new ByteArrayReader(data);
        bool = reader.readBool();
        assertFalse(bool);

        writer.reset();
        writer.writeBool(true);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], 2);

        reader = new ByteArrayReader(data);
        bool = reader.readBool();
        assertTrue(bool);
    }

    @Test
    public void testCodecFloat() throws ProtoException {
        BufOut out = new ByteArrayBufOut();
        ByteArrayWriter writer = new ByteArrayWriter(out);
        writer.writeFloat(null);

        byte[] data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 4);
        assertArrayEquals(data, new byte[]{0, 0, 0, 0});

        ByteArrayReader reader = new ByteArrayReader(data);
        float f = reader.readFloat();
        assertEquals(f, 0);

        writer.reset();
        writer.writeFloat(Float.valueOf(0));

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 4);
        assertArrayEquals(data, new byte[]{0, 0, 0, 0});

        reader = new ByteArrayReader(data);
        f = reader.readFloat();
        assertEquals(f, 0);

        writer.reset();
        writer.writeFloat(Float.valueOf(3.1415f));

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 4);
        assertArrayEquals(data, new byte[]{86, 14, 73, 64});

        reader = new ByteArrayReader(data);
        f = reader.readFloat();
        assertEquals(f, 3.1415f);

        writer.reset();
        writer.writeFloat(0);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 4);
        assertArrayEquals(data, new byte[]{0, 0, 0, 0});

        reader = new ByteArrayReader(data);
        f = reader.readFloat();
        assertEquals(f, 0);

        writer.reset();
        writer.writeFloat(3.1415f);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 4);
        assertArrayEquals(data, new byte[]{86, 14, 73, 64});

        reader = new ByteArrayReader(data);
        f = reader.readFloat();
        assertEquals(f, 3.1415f);
    }

    @Test
    public void testCodecString() throws ProtoException {
        BufOut out = new ByteArrayBufOut();
        ByteArrayWriter writer = new ByteArrayWriter(out);
        writer.writeString(null);

        byte[] data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], 1);

        ByteArrayReader reader = new ByteArrayReader(data);
        String s = reader.readString();
        assertNull(s);

        writer.reset();
        writer.writeString(EMPTY_STRING);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], 0);

        reader = new ByteArrayReader(data);
        s = reader.readString();
        assertNotNull(s);
        assertEquals(s.length(), 0);

        // 测试latin1编码的字符串编解码逻辑
        s = randomLatin1(100);
        writer.reset();
        writer.writeString(s);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 102);
        assertEquals(data[0], ZIGZAG32_ONE);
        assertEquals(data[1], 100);
        assertArrayEquals(Arrays.copyOfRange(data, 2, 102), s.getBytes());

        reader = new ByteArrayReader(data);
        String result = reader.readString();
        assertNotNull(result);
        assertEquals(result.length(), 100);
        assertEquals(s, result);

        // 测试utf8编码字符串的编解码逻辑
        if (IS_BYTE_ARRAY) {
            s = randomUtf8(100);
            writer.reset();
            writer.writeString(s);

            data = writer.toByteArray();
            assertNotNull(data);
            assertEquals(data.length, 203);
            assertEquals(data[0], ZIGZAG32_TWO);
            assertEquals(data[1], -56);
            assertEquals(data[2], 1);
            assertArrayEquals(Arrays.copyOfRange(data, 3, 203), s.getBytes(StandardCharsets.UTF_16LE));

            reader = new ByteArrayReader(data);
            result = reader.readString();
            assertNotNull(result);
            assertEquals(result.length(), 100);
        }
    }

    @Test
    public void testCodecBytes() throws ProtoException {
        BufOut out = new ByteArrayBufOut();
        ByteArrayWriter writer = new ByteArrayWriter(out);
        byte[] bs = null;
        writer.writeBytes(bs);

        byte[] data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], 1);

        ByteArrayReader reader = new ByteArrayReader(data);
        byte[] result = reader.readBytes();
        assertNull(result);


        writer.reset();
        bs = new byte[0];
        writer.writeBytes(bs);
        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], 0);

        reader = new ByteArrayReader(data);
        result = reader.readBytes();
        assertNotNull(result);
        assertEquals(result.length, 0);


        writer.reset();
        bs = randomUtf8(new Random().nextInt(30,200)).getBytes(StandardCharsets.UTF_8);
        writer.writeBytes(bs);
        data = writer.toByteArray();
        assertNotNull(data);
        int len = lenCount(encodeZigZag32(bs.length));
        assertEquals(data.length, bs.length + len);

        reader = new ByteArrayReader(data);
        result = reader.readBytes();
        assertNotNull(result);
        assertEquals(result.length, bs.length);
        assertArrayEquals(result, bs);

        int l = data.length;
        byte[] ndata = new byte[l-10];
        System.arraycopy(data, 0, ndata, 0, ndata.length);
        ProtoException thrown = assertThrows(ProtoException.class,
                () -> {
                    ByteArrayReader reader2 = new ByteArrayReader(ndata);
                    reader2.readBytes();
                });

        assertTrue(thrown.getMessage().contains("CodedInputStream encountered a malformed varint."));


        writer.reset();
        bs = randomUtf8(new Random().nextInt(20)).getBytes(StandardCharsets.UTF_8);
        writer.writeBytes(bs);
        data = writer.toByteArray();
        assertNotNull(data);
        len = lenCount(encodeZigZag32(bs.length));
        assertEquals(data.length, bs.length + len);

        reader = new ByteArrayReader(data);
        result = reader.readBytes();
        assertNotNull(result);
        assertEquals(result.length, bs.length);
        assertArrayEquals(result, bs);

    }

    @Test
    public void testCodecPackedBoolList() throws ProtoException {
        BufOut out = new ByteArrayBufOut();
        ByteArrayWriter writer = new ByteArrayWriter(out);
        List<Boolean> bs = null;
        writer.writePackedBools(bs);

        byte[] data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], 1);

        ByteArrayReader reader = new ByteArrayReader(data);
        List<Boolean> result = reader.readPackedBool();
        assertNull(result);


        writer.reset();
        bs = new ArrayList<>();
        writer.writePackedBools(bs);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], 0);

        reader = new ByteArrayReader(data);
        result = reader.readPackedBool();
        assertNotNull(result);
        assertTrue(result.isEmpty());


        writer.reset();
        bs = Arrays.asList(true, null, false, true, true);
        writer.writePackedBools(bs);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, bs.size() + 1);
        assertArrayEquals(data, new byte[]{10, 2, 1, 0, 2, 2});

        reader = new ByteArrayReader(data);
        result = reader.readPackedBool();
        assertNotNull(result);
        Iterator<Boolean> itr = result.iterator();
        for (Boolean b : bs) {
            Boolean d = itr.next();
            if (b == null) {
                assertNull(d);
            } else {
                assertEquals(b, d);
            }
        }
    }

    @Test
    public void testCodecPackedBoolIterator() throws ProtoException {
        BufOut out = new ByteArrayBufOut();
        ByteArrayWriter writer = new ByteArrayWriter(out);
        Iterable<Boolean> bs = null;
        writer.writePackedBools(bs);

        byte[] data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], 1);

        ByteArrayReader reader = new ByteArrayReader(data);
        List<Boolean> result = reader.readPackedBool();
        assertNull(result);


        writer.reset();
        bs = new ArrayList<>();
        writer.writePackedBools(bs);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], 0);

        reader = new ByteArrayReader(data);
        result = reader.readPackedBool();
        assertNotNull(result);
        assertTrue(result.isEmpty());


        writer.reset();
        bs = Arrays.asList(true, null, false, true, true);
        writer.writePackedBools(bs);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 6);
        assertArrayEquals(data, new byte[]{10, 2, 1, 0, 2, 2});

        reader = new ByteArrayReader(data);
        result = reader.readPackedBool();
        assertNotNull(result);
        Iterator<Boolean> itr = result.iterator();
        for (Boolean b : bs) {
            Boolean d = itr.next();
            if (b == null) {
                assertNull(d);
            } else {
                assertEquals(b, d);
            }
        }
    }

    @Test
    public void testCodecPackedBoolArray() throws ProtoException {
        BufOut out = new ByteArrayBufOut();
        ByteArrayWriter writer = new ByteArrayWriter(out);
        boolean[] bs = null;
        writer.writePackedBooleans(bs);

        byte[] data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], 1);

        ByteArrayReader reader = new ByteArrayReader(data);
        boolean[] result = reader.readPackedBoolValues();
        assertNull(result);


        writer.reset();
        bs = new boolean[0];
        writer.writePackedBooleans(bs);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], 0);

        reader = new ByteArrayReader(data);
        result = reader.readPackedBoolValues();
        assertNotNull(result);
        assertTrue(result.length == 0);


        writer.reset();
        bs = new boolean[]{true, false, false, true, true};
        writer.writePackedBooleans(bs);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, bs.length + 1);
        assertArrayEquals(data, new byte[]{10, 2, 0, 0, 2, 2});

        reader = new ByteArrayReader(data);
        result = reader.readPackedBoolValues();
        assertNotNull(result);
        assertArrayEquals(result, bs);
    }

    @Test
    public void testCodecPackedBoolObjArray() throws ProtoException {
        BufOut out = new ByteArrayBufOut();
        ByteArrayWriter writer = new ByteArrayWriter(out);
        Boolean[] bs = null;
        writer.writePackedBooleans(bs);

        byte[] data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], 1);

        ByteArrayReader reader = new ByteArrayReader(data);
        Boolean[] result = reader.readPackedBools();
        assertNull(result);


        writer.reset();
        bs = new Boolean[0];
        writer.writePackedBooleans(bs);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], 0);

        reader = new ByteArrayReader(data);
        result = reader.readPackedBools();
        assertNotNull(result);
        assertTrue(result.length == 0);


        writer.reset();
        bs = new Boolean[]{true, null, false, true, true};
        writer.writePackedBooleans(bs);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, bs.length + 1);
        assertArrayEquals(data, new byte[]{10, 2, 1, 0, 2, 2});

        reader = new ByteArrayReader(data);
        result = reader.readPackedBools();
        assertNotNull(result);
        int i = 0;
        for (Boolean b : bs) {
            Boolean d = result[i];
            if (b == null) {
                assertNull(d);
            } else {
                assertEquals(b, d);
            }
            i++;
        }
    }

    @Test
    public void testCodecInt32Obj() throws ProtoException {
        BufOut out = new ByteArrayBufOut();
        ByteArrayWriter writer = new ByteArrayWriter(out);
        Integer value = null;
        writer.writeInt32(value);

        byte[] data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], 0);

        ByteArrayReader reader = new ByteArrayReader(data);
        int result = reader.readInt32();
        assertEquals(result, 0);


        writer.reset();
        value = Integer.valueOf(0);
        writer.writeInt32(value);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], 0);

        reader = new ByteArrayReader(data);
        result = reader.readInt32();
        assertNotNull(result);
        assertTrue(result == 0);


        writer.reset();
        value = new Random().nextInt(Integer.MIN_VALUE, Integer.MAX_VALUE);
        int orignalValue = value;
        writer.writeInt32(value);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, lenCount(value));
        int i = 0;
        while (true) {
            if ((value & ~0x7FL) == 0) {
                assertEquals(value, data[i]);
                break;
            } else {
                assertEquals((byte)(((int) value & 0x7F) | 0x80), data[i]);
                value >>>= 7;
            }
            i++;
        }

        reader = new ByteArrayReader(data);
        result = reader.readInt32();
        assertNotNull(result);
        assertTrue(result == orignalValue);
    }

    @Test
    public void testCodecInt32() throws ProtoException {
        BufOut out = new ByteArrayBufOut();
        ByteArrayWriter writer = new ByteArrayWriter(out);
        int value;

        writer.reset();
        value = Integer.valueOf(0);
        writer.writeInt32(value);

        byte[] data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], 0);

        ByteArrayReader reader = new ByteArrayReader(data);
        int result = reader.readInt32();
        assertNotNull(result);
        assertTrue(result == 0);


        writer.reset();
        value = new Random().nextInt(Integer.MIN_VALUE, Integer.MAX_VALUE);
        int orignalValue = value;
        writer.writeInt32(value);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, lenCount(value));
        int i = 0;
        while (true) {
            if ((value & ~0x7FL) == 0) {
                assertEquals(value, data[i]);
                break;
            } else {
                assertEquals((byte)(((int) value & 0x7F) | 0x80), data[i]);
                value >>>= 7;
            }
            i++;
        }

        reader = new ByteArrayReader(data);
        result = reader.readInt32();
        assertNotNull(result);
        assertTrue(result == orignalValue);
    }

    @Test
    public void testCodecUint32Obj() throws ProtoException {
        BufOut out = new ByteArrayBufOut();
        ByteArrayWriter writer = new ByteArrayWriter(out);
        Integer value = null;
        writer.writeUInt32(value);

        byte[] data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], 0);

        ByteArrayReader reader = new ByteArrayReader(data);
        int result = reader.readUInt32();
        assertEquals(result, 0);


        writer.reset();
        value = Integer.valueOf(0);
        writer.writeUInt32(value);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], 0);

        reader = new ByteArrayReader(data);
        result = reader.readUInt32();
        assertNotNull(result);
        assertTrue(result == 0);


        writer.reset();
        value = new Random().nextInt(Integer.MIN_VALUE, Integer.MAX_VALUE);
        int orignalValue = value;
        writer.writeUInt32(value);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, lenCount(value));
        int i = 0;
        while (true) {
            if ((value & ~0x7FL) == 0) {
                assertEquals(value, data[i]);
                break;
            } else {
                assertEquals((byte)(((int) value & 0x7F) | 0x80), data[i]);
                value >>>= 7;
            }
            i++;
        }

        reader = new ByteArrayReader(data);
        result = reader.readUInt32();
        assertNotNull(result);
        assertTrue(result == orignalValue);
    }

    @Test
    public void testCodecUint32() throws ProtoException {
        BufOut out = new ByteArrayBufOut();
        ByteArrayWriter writer = new ByteArrayWriter(out);

        writer.reset();
        int value = 0;
        writer.writeUInt32(value);

        byte[] data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], 0);

        ByteArrayReader reader = new ByteArrayReader(data);
        int result = reader.readUInt32();
        assertNotNull(result);
        assertTrue(result == 0);


        writer.reset();
        value = new Random().nextInt(Integer.MIN_VALUE, Integer.MAX_VALUE);
        int orignalValue = value;
        writer.writeUInt32(value);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, lenCount(value));
        int i = 0;
        while (true) {
            if ((value & ~0x7FL) == 0) {
                assertEquals(value, data[i]);
                break;
            } else {
                assertEquals((byte)(((int) value & 0x7F) | 0x80), data[i]);
                value >>>= 7;
            }
            i++;
        }

        reader = new ByteArrayReader(data);
        result = reader.readUInt32();
        assertNotNull(result);
        assertTrue(result == orignalValue);
    }

    @Test
    public void testCodecSint32Obj() throws ProtoException {
        BufOut out = new ByteArrayBufOut();
        ByteArrayWriter writer = new ByteArrayWriter(out);
        Integer value = null;
        writer.writeSInt32(value);

        byte[] data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], 0);

        ByteArrayReader reader = new ByteArrayReader(data);
        int result = reader.readSInt32();
        assertEquals(result, 0);


        writer.reset();
        value = Integer.valueOf(0);
        writer.writeSInt32(value);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], 0);

        reader = new ByteArrayReader(data);
        result = reader.readSInt32();
        assertNotNull(result);
        assertTrue(result == 0);


        writer.reset();
        value = new Random().nextInt(Integer.MIN_VALUE, Integer.MAX_VALUE);
        int orignalValue = value;
        writer.writeSInt32(value);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, lenCount(value));
        int i = 0;
        value = encodeZigZag32(value);
        while (true) {
            if ((value & ~0x7FL) == 0) {
                assertEquals(value, data[i]);
                break;
            } else {
                assertEquals((byte)(((int) value & 0x7F) | 0x80), data[i]);
                value >>>= 7;
            }
            i++;
        }

        reader = new ByteArrayReader(data);
        result = reader.readSInt32();
        assertNotNull(result);
        assertTrue(result == orignalValue);
    }

    @Test
    public void testCodecSint32() throws ProtoException {
        BufOut out = new ByteArrayBufOut();
        ByteArrayWriter writer = new ByteArrayWriter(out);

        writer.reset();
        int value = 0;
        writer.writeSInt32(value);

        byte[] data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], 0);

        ByteArrayReader reader = new ByteArrayReader(data);
        int result = reader.readSInt32();
        assertNotNull(result);
        assertTrue(result == 0);


        writer.reset();
        value = new Random().nextInt(Integer.MIN_VALUE, Integer.MAX_VALUE);
        int orignalValue = value;
        writer.writeSInt32(value);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, lenCount(value));
        int i = 0;
        value = encodeZigZag32(value);
        while (true) {
            if ((value & ~0x7FL) == 0) {
                assertEquals(value, data[i]);
                break;
            } else {
                assertEquals((byte)(((int) value & 0x7F) | 0x80), data[i]);
                value >>>= 7;
            }
            i++;
        }

        reader = new ByteArrayReader(data);
        result = reader.readSInt32();
        assertNotNull(result);
        assertTrue(result == orignalValue);
    }

    @Test
    public void testCodecFixed32Obj() throws ProtoException {
        BufOut out = new ByteArrayBufOut();
        ByteArrayWriter writer = new ByteArrayWriter(out);
        Integer value = null;
        writer.writeFixed32(value);

        byte[] data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 4);
        assertArrayEquals(data, new byte[]{0, 0, 0, 0});

        ByteArrayReader reader = new ByteArrayReader(data);
        int result = reader.readFixed32();
        assertEquals(result, 0);


        writer.reset();
        value = Integer.valueOf(0);
        writer.writeFixed32(value);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 4);
        assertArrayEquals(data, new byte[]{0, 0, 0, 0});

        reader = new ByteArrayReader(data);
        result = reader.readFixed32();
        assertNotNull(result);
        assertTrue(result == 0);


        writer.reset();
        value = new Random().nextInt(Integer.MIN_VALUE, Integer.MAX_VALUE);
        int orignalValue = value;
        writer.writeFixed32(value);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 4);
        int i = 0;
        data[i++] = (byte) ((value      ) & 0xFF);
        data[i++] = (byte) ((value >>  8) & 0xFF);
        data[i++] = (byte) ((value >> 16) & 0xFF);
        data[i++] = (byte) ((value >> 24) & 0xFF);

        reader = new ByteArrayReader(data);
        result = reader.readFixed32();
        assertNotNull(result);
        assertTrue(result == orignalValue);
    }

    @Test
    public void testCodecFixed32() throws ProtoException {
        BufOut out = new ByteArrayBufOut();
        ByteArrayWriter writer = new ByteArrayWriter(out);


        writer.reset();
        int value = 0;
        writer.writeFixed32(value);

        byte[] data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 4);
        assertArrayEquals(data, new byte[]{0, 0, 0, 0});

        ByteArrayReader reader = new ByteArrayReader(data);
        int result = reader.readFixed32();
        assertNotNull(result);
        assertTrue(result == 0);


        writer.reset();
        value = new Random().nextInt(Integer.MIN_VALUE, Integer.MAX_VALUE);
        int orignalValue = value;
        writer.writeFixed32(value);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 4);
        int i = 0;
        data[i++] = (byte) ((value      ) & 0xFF);
        data[i++] = (byte) ((value >>  8) & 0xFF);
        data[i++] = (byte) ((value >> 16) & 0xFF);
        data[i++] = (byte) ((value >> 24) & 0xFF);

        reader = new ByteArrayReader(data);
        result = reader.readFixed32();
        assertNotNull(result);
        assertTrue(result == orignalValue);
    }

    @Test
    public void testCodecSFixed32Obj() throws ProtoException {
        BufOut out = new ByteArrayBufOut();
        ByteArrayWriter writer = new ByteArrayWriter(out);
        Integer value = null;
        writer.writeSFixed32(value);

        byte[] data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 4);
        assertArrayEquals(data, new byte[]{0, 0, 0, 0});

        ByteArrayReader reader = new ByteArrayReader(data);
        int result = reader.readSFixed32();
        assertEquals(result, 0);


        writer.reset();
        value = Integer.valueOf(0);
        writer.writeSFixed32(value);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 4);
        assertArrayEquals(data, new byte[]{0, 0, 0, 0});

        reader = new ByteArrayReader(data);
        result = reader.readSFixed32();
        assertNotNull(result);
        assertTrue(result == 0);


        writer.reset();
        value = new Random().nextInt(Integer.MIN_VALUE, Integer.MAX_VALUE);
        int orignalValue = value;
        writer.writeSFixed32(value);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 4);
        int i = 0;
        data[i++] = (byte) ((value      ) & 0xFF);
        data[i++] = (byte) ((value >>  8) & 0xFF);
        data[i++] = (byte) ((value >> 16) & 0xFF);
        data[i++] = (byte) ((value >> 24) & 0xFF);

        reader = new ByteArrayReader(data);
        result = reader.readSFixed32();
        assertNotNull(result);
        assertTrue(result == orignalValue);
    }

    @Test
    public void testCodecSFixed32() throws ProtoException {
        BufOut out = new ByteArrayBufOut();
        ByteArrayWriter writer = new ByteArrayWriter(out);


        writer.reset();
        int value = 0;
        writer.writeSFixed32(value);

        byte[] data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 4);
        assertArrayEquals(data, new byte[]{0, 0, 0, 0});

        ByteArrayReader reader = new ByteArrayReader(data);
        int result = reader.readSFixed32();
        assertNotNull(result);
        assertTrue(result == 0);


        writer.reset();
        value = new Random().nextInt(Integer.MIN_VALUE, Integer.MAX_VALUE);
        int orignalValue = value;
        writer.writeSFixed32(value);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 4);
        int i = 0;
        data[i++] = (byte) ((value      ) & 0xFF);
        data[i++] = (byte) ((value >>  8) & 0xFF);
        data[i++] = (byte) ((value >> 16) & 0xFF);
        data[i++] = (byte) ((value >> 24) & 0xFF);

        reader = new ByteArrayReader(data);
        result = reader.readSFixed32();
        assertNotNull(result);
        assertTrue(result == orignalValue);
    }

    @Test
    public void testCodecPackedInts() {
        BufOut out = new ByteArrayBufOut();
        ByteArrayWriter writer = new ByteArrayWriter(out);
        int[] values = null;
        writer.writePackedInts(values, Field.Type.INT32);

        byte[] data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 1);
        assertEquals(data[0], ZIGZAG32_NEGATIVE_ONE);
    }
}
