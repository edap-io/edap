package io.edap.mqtt.property.test;

import io.edap.mqtt.IntegerToLongException;
import io.edap.mqtt.MqttConstant;
import io.edap.mqtt.MqttWriter;
import io.edap.mqtt.StringToLongException;
import io.edap.mqtt.property.StringProperty;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static io.edap.mqtt.packet.test.ConnectTest.randomStr;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StringPropertyTest {

    @Test
    public void testWriteTo() {
        StringPropertyImpl spi = new StringPropertyImpl();
        MqttWriter writer = new MqttWriter();
        spi.writeTo(writer);
        assertEquals(writer.getLength(), 0);
        byte[] data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        byte[] expected = new byte[]{};
        assertArrayEquals(data, expected);

        String value = randomStr(new Random().nextInt(Byte.MAX_VALUE));
        spi.value(value);
        writer.reset();
        spi.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        byte[] bs = value.getBytes(StandardCharsets.UTF_8);
        int len = bs.length;
        expected = new byte[2 + len];
        expected[0] = (byte)(len >> 8);
        expected[1] = (byte)(len & 0xFF);
        System.arraycopy(bs, 0, expected, 2, bs.length);
        assertArrayEquals(data, expected);

        StringToLongException thrown = assertThrows(StringToLongException.class,
                () -> {
                    writer.reset();
                    String val = randomStr((1 << 16) + new Random().nextInt(100));
                    spi.value(val);
                    spi.writeTo(writer);
                });
        assertTrue(thrown.getMessage().contains("String to long!"));
    }


    public class StringPropertyImpl extends StringProperty {

        @Override
        public String name() {
            return StringPropertyImpl.class.getName();
        }

        @Override
        public int identifier() {
            return 0;
        }
    }
}
