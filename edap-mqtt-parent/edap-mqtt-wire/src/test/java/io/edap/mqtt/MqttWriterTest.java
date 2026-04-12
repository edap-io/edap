package io.edap.mqtt;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static io.edap.mqtt.packet.test.ConnectTest.randomStr;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MqttWriterTest {

    @Test
    public void testInit() {
        MqttWriter writer = new MqttWriter(32);
        assertEquals(writer.getLength(), 0);
    }

    @Test
    public void testGetPos() {
        MqttWriter writer = new MqttWriter(32);
        assertEquals(writer.getPos(), 0);
        writer.setPos(12);
        assertEquals(writer.getPos(), 12);
    }

    @Test
    public void testSetPos() {
        MqttWriter writer = new MqttWriter(32);
        String str = randomStr(new Random().nextInt(10));
        byte[] bs = str.getBytes(StandardCharsets.UTF_8);
        writer.writeString(str);
        writer.setPos(35);
        byte[] data = writer.getData();
        assertEquals(data.length, 64);

        writer = new MqttWriter(32);
        writer.writeString(str);
        writer.setPos(80);
        data = writer.getData();
        assertEquals(data.length, 80);
    }

    @Test
    public void testWriteBytesWithPos() {
        MqttWriter writer = new MqttWriter(32);
        writer.writeBytes(3, (byte)1, (byte)2);
        byte[] data = new byte[32];
        data[3] = 1;
        data[4] = 2;
        assertArrayEquals(data, writer.getData());

        writer = new MqttWriter(32);
        writer.writeBytes(31, (byte)1, (byte)2);
        data = new byte[64];
        data[31] = 1;
        data[32] = 2;
        assertArrayEquals(data, writer.getData());
    }

    @Test
    public void testWriteBytesThreeByte() {
        MqttWriter writer = new MqttWriter(32);
        writer.writeBytes((byte)3, (byte)1, (byte)2);
        byte[] data = writer.getData();
        assertEquals(data[0], 3);
        assertEquals(data[1], 1);
        assertEquals(data[2], 2);
    }

    @Test
    public void testWriteBytesFiveByte() {
        MqttWriter writer = new MqttWriter(32);
        writer.writeBytes((byte)3, (byte)1, (byte)2, (byte)5, (byte)9);
        byte[] data = writer.getData();
        assertEquals(data[0], 3);
        assertEquals(data[1], 1);
        assertEquals(data[2], 2);
        assertEquals(data[3], 5);
        assertEquals(data[4], 9);
    }

    @Test
    public void testRemain() {
        MqttWriter writer = new MqttWriter(32);
        assertEquals(writer.remain(), 32);
        writer.setPos(3);
        assertEquals(writer.remain(), 29);
    }

    @Test
    public void testSetStart() {
        MqttWriter writer = new MqttWriter(32);
        writer.setStart(0);
        assertEquals(writer.getStart(), 0);

        writer.setStart(-1);
        assertEquals(writer.getStart(), 0);

        writer.setStart(8);
        assertEquals(writer.getStart(), 8);

        writer.setStart(6);
        assertEquals(writer.getStart(), 6);

        writer.setStart(5);
        writer.setPos(12);
        assertEquals(writer.getStart(), 5);
        assertEquals(writer.getPos(), 12);


        writer.setPos(12);
        writer.setStart(15);
        assertEquals(writer.getStart(), 15);
        assertEquals(writer.getPos(), 15);

        writer.setStart(8);
        writer.setPos(12);
        writer.setStart(10);
        assertEquals(writer.getStart(), 10);
        assertEquals(writer.getPos(), 12);
    }

    @Test
    public void testWriteString() {
        MqttWriter writer = new MqttWriter(12);
        String str = "中文";
        writer.writeString(str);
        byte[] data = new byte[]{0, 6, -28, -72, -83, -26, -106, -121, 0, 0, 0, 0};
        assertArrayEquals(data, writer.getData());

        writer.reset();
        int c = new Random().nextInt(128);
        str += (char)c;
        writer.writeString(str);
        data = new byte[]{0, 7, -28, -72, -83, -26, -106, -121, (byte)c, 0, 0, 0};
        assertArrayEquals(data, writer.getData());

        writer.reset();
        int c2 = 129 + new Random().nextInt(0x800 - 129);
        str += (char)c2;
        writer.writeString(str);
        data = new byte[]{0, 9, -28, -72, -83, -26, -106, -121, (byte)c,
                (byte) ((0xF << 6) | (c2 >>> 6)),
                (byte) (0x80       | (0x3F & c2)), 0,0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        assertArrayEquals(data, writer.getData());

        writer.reset();
        String u = "🐶";
        str += u;
        int codePoint = Character.toCodePoint((char) u.charAt(0), (char) u.charAt(1));
        byte b1 = (byte) (0xF0 | ((codePoint >> 18) & 0x07));
        byte b2 = (byte) (0x80 | ((codePoint >> 12) & 0x3F));
        byte b3 = (byte) (0x80 | ((codePoint >> 6) & 0x3F));
        byte b4 = (byte) (0x80 | (codePoint & 0x3F));
        writer.writeString(str);
        data = new byte[]{0, 13, -28, -72, -83, -26, -106, -121, (byte)c,
                (byte) ((0xF << 6) | (c2 >>> 6)),
                (byte) (0x80       | (0x3F & c2)), b1, b2, b3, b4, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        assertArrayEquals(data, writer.getData());

        StringToLongException thrown = assertThrows(StringToLongException.class,
                () -> {
                    writer.reset();
                    String val = "中" + randomStr((1 << 16) + new Random().nextInt(100));
                    writer.writeString(val);
                });
        assertTrue(thrown.getMessage().contains("String to long!"));
    }

    @Test
    public void testWriteVarInt() {
        MqttWriter writer = new MqttWriter(40);
        writer.setStart(12);
        writer.writeVarInt(1);
        byte[] data = writer.getData();
        assertEquals(data[12], 1);

        writer.reset();
        writer.setStart(12);
        writer.writeVarInt(127);
        data = writer.getData();
        assertEquals(data[12], 127);

        writer.reset();
        writer.setStart(12);
        writer.writeVarInt(128);
        data = writer.getData();
        assertEquals(data[12], -128);
        assertEquals(data[13], 1);


        writer.reset();
        writer.setStart(12);
        writer.writeVarInt(16383);
        data = writer.getData();
        assertEquals(data[12], -1);
        assertEquals(data[13], 127);

        writer.reset();
        writer.setStart(12);
        writer.writeVarInt(16384);
        data = writer.getData();
        assertEquals(data[12], -128);
        assertEquals(data[13], -128);
        assertEquals(data[14], 1);

        writer.reset();
        writer.setStart(12);
        writer.writeVarInt(2097151);
        data = writer.getData();
        assertEquals(data[12], -1);
        assertEquals(data[13], -1);
        assertEquals(data[14], 127);

        writer.reset();
        writer.setStart(12);
        writer.writeVarInt(2097152);
        data = writer.getData();
        assertEquals(data[12], -128);
        assertEquals(data[13], -128);
        assertEquals(data[14], -128);
        assertEquals(data[15], 1);

        writer.reset();
        writer.setStart(12);
        writer.writeVarInt(268435455);
        data = writer.getData();
        assertEquals(data[12], -1);
        assertEquals(data[13], -1);
        assertEquals(data[14], -1);
        assertEquals(data[15], 127);


        IntegerToLongException thrown = assertThrows(IntegerToLongException.class,
                () -> {
                    writer.reset();
                    writer.setStart(12);
                    writer.writeVarInt(268435458);
                });
        assertTrue(thrown.getMessage().contains("Integer 268435458 too big"));
    }

    @Test
    public void testWriteLength() {
        MqttWriter writer = new MqttWriter(40);
        writer.setStart(12);
        writer.writeLength(0, 1);
        byte[] data = writer.getData();
        assertEquals(data[11], 1);

        writer.reset();
        writer.setStart(12);
        writer.writeLength(0, 127);
        data = writer.getData();
        assertEquals(data[11], 127);

        writer.reset();
        writer.setStart(12);
        writer.writeLength(0, 128);
        data = writer.getData();
        assertEquals(data[10], -128);
        assertEquals(data[11], 1);


        writer.reset();
        writer.setStart(12);
        writer.writeLength(0, 16383);
        data = writer.getData();
        assertEquals(data[10], -1);
        assertEquals(data[11], 127);

        writer.reset();
        writer.setStart(12);
        writer.writeLength(0, 16384);
        data = writer.getData();
        assertEquals(data[9], -128);
        assertEquals(data[10], -128);
        assertEquals(data[11], 1);

        writer.reset();
        writer.setStart(12);
        writer.writeLength(0, 2097151);
        data = writer.getData();
        assertEquals(data[9], -1);
        assertEquals(data[10], -1);
        assertEquals(data[11], 127);

        writer.reset();
        writer.setStart(12);
        writer.writeLength(0, 2097152);
        data = writer.getData();
        assertEquals(data[8], -128);
        assertEquals(data[9], -128);
        assertEquals(data[10], -128);
        assertEquals(data[11], 1);

        writer.reset();
        writer.setStart(12);
        writer.writeLength(0, 268435455);
        data = writer.getData();
        assertEquals(data[8], -1);
        assertEquals(data[9], -1);
        assertEquals(data[10], -1);
        assertEquals(data[11], 127);


        IntegerToLongException thrown = assertThrows(IntegerToLongException.class,
                () -> {
                    writer.reset();
                    writer.setStart(12);
                    writer.writeLength(0, 268435458);
                });
        assertTrue(thrown.getMessage().contains("Integer 268435458 too big"));
    }
}
