package io.edap.eproto.test;

import io.edap.eproto.reader.ByteArrayReader;
import io.edap.eproto.writer.ByteArrayWriter;
import io.edap.io.BufOut;
import io.edap.io.ByteArrayBufOut;
import io.edap.protobuf.ProtoException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static io.edap.eproto.test.TestUtils.randomLatin1;
import static io.edap.eproto.writer.AbstractWriter.ZIGZAG32_ONE;
import static io.edap.eproto.writer.AbstractWriter.ZIGZAG32_ZERO;
import static io.edap.util.Constants.EMPTY_STRING;
import static org.junit.jupiter.api.Assertions.*;

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

        s = randomLatin1(100);
        writer.reset();
        writer.writeString(s);

        data = writer.toByteArray();
        assertNotNull(data);
        assertEquals(data.length, 203);
        assertEquals(data[0], ZIGZAG32_ONE);
        assertEquals(data[1], -56);
        assertEquals(data[2], 1);
        assertArrayEquals(Arrays.copyOfRange(data, 3, 203), s.getBytes());

        reader = new ByteArrayReader(data);
        s = reader.readString();
        assertNotNull(s);
        assertEquals(s.length(), 0);

    }


}
