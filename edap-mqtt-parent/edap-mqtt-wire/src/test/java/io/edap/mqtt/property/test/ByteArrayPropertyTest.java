package io.edap.mqtt.property.test;

import io.edap.mqtt.ByteArrayToLongException;
import io.edap.mqtt.MqttWriter;
import io.edap.mqtt.property.ByteArrayProperty;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static io.edap.mqtt.MqttConstant.TWO_BYTE_INT_MAX_VALUE;
import static io.edap.mqtt.packet.test.ConnectTest.randomStr;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ByteArrayPropertyTest {

    @Test
    public void testWriteTo() {
        ByteArrayPropertyImpl bapi = new ByteArrayPropertyImpl();
        MqttWriter writer = new MqttWriter();
        bapi.writeTo(writer);
        assertEquals(writer.getLength(), 0);

        byte[] src = randomStr(new Random().nextInt(Short.MAX_VALUE)).getBytes(StandardCharsets.UTF_8);
        writer.reset();
        bapi.value(src);
        bapi.writeTo(writer);
        byte[] data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        byte[] expected = new byte[2 + src.length];
        expected[0] = (byte)(src.length >> 8);
        expected[1] = (byte)(src.length & 0xFF);
        System.arraycopy(src, 0, expected, 2, src.length);
        assertArrayEquals(data, expected);

        src = randomStr(new Random().nextInt(Byte.MAX_VALUE) + 128).getBytes(StandardCharsets.UTF_8);
        writer.reset();
        bapi.value(src);
        bapi.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[2 + src.length];
        expected[0] = (byte)(src.length >> 8);
        expected[1] = (byte)(src.length & 0xFF);
        System.arraycopy(src, 0, expected, 2, src.length);
        assertArrayEquals(data, expected);

        ByteArrayToLongException thrown = assertThrows(ByteArrayToLongException.class,
                () -> {
                    writer.reset();
                    byte[] tmp = randomStr(new Random().nextInt(Byte.MAX_VALUE) + TWO_BYTE_INT_MAX_VALUE)
                            .getBytes(StandardCharsets.UTF_8);
                    bapi.value(tmp);
                    bapi.writeTo(writer);
                });
        assertTrue(thrown.getMessage().contains("byte array data too lang!"));
    }


    public class ByteArrayPropertyImpl extends ByteArrayProperty {

        @Override
        public String name() {
            return ByteArrayPropertyImpl.class.getName();
        }

        @Override
        public int identifier() {
            return 0;
        }
    }
}
