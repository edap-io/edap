package io.edap.mqtt.property.test;

import io.edap.mqtt.MqttWriter;
import io.edap.mqtt.property.ByteProperty;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class BytePropertyTest {

    @Test
    public void testWriteTo() {
        BytePropertyImpl bpi = new BytePropertyImpl();
        MqttWriter writer = new MqttWriter();
        bpi.writeTo(writer);
        byte[] data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        byte[] expected = new byte[]{0};
        assertArrayEquals(data, expected);

        writer.reset();
        bpi.value((byte)1);
        bpi.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{1};
        assertArrayEquals(data, expected);

        writer.reset();
        bpi.value((byte)2);
        bpi.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{2};
        assertArrayEquals(data, expected);

        writer.reset();
        bpi.value((byte)127);
        bpi.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{127};
        assertArrayEquals(data, expected);

        writer.reset();
        bpi.value((byte)128);
        bpi.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{(byte)128};
        assertArrayEquals(data, expected);

        writer.reset();
        bpi.value((byte)255);
        bpi.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{(byte)255};
        assertArrayEquals(data, expected);
    }


    public class BytePropertyImpl extends ByteProperty {

        @Override
        public String name() {
            return BytePropertyImpl.class.getName();
        }

        @Override
        public int identifier() {
            return 0;
        }
    }
}
