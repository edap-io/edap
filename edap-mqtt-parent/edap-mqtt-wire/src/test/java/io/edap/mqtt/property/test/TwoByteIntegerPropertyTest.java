package io.edap.mqtt.property.test;

import io.edap.mqtt.MqttWriter;
import io.edap.mqtt.property.TwoByteIntegerProperty;
import org.junit.jupiter.api.Test;

import static io.edap.mqtt.MqttConstant.TWO_BYTE_INT_MAX_VALUE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class TwoByteIntegerPropertyTest {

    @Test
    public void testWriteTo() {
        TwoByteIntegerPropertyImpl tbpi = new TwoByteIntegerPropertyImpl();
        MqttWriter writer = new MqttWriter();
        tbpi.writeTo(writer);
        byte[] data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        byte[] expected = new byte[]{0, 0};
        assertArrayEquals(data, expected);

        tbpi = new TwoByteIntegerPropertyImpl();
        tbpi.value(0);
        writer.reset();
        tbpi.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{0, 0};
        assertArrayEquals(data, expected);

        tbpi = new TwoByteIntegerPropertyImpl();
        tbpi.value(1);
        writer.reset();
        tbpi.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{0, 1};
        assertArrayEquals(data, expected);

        tbpi = new TwoByteIntegerPropertyImpl();
        tbpi.value(255);
        writer.reset();
        tbpi.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{0, (byte)255};
        assertArrayEquals(data, expected);

        tbpi = new TwoByteIntegerPropertyImpl();
        tbpi.value(256);
        writer.reset();
        tbpi.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{1, 0};
        assertArrayEquals(data, expected);

        tbpi = new TwoByteIntegerPropertyImpl();
        tbpi.value(257);
        writer.reset();
        tbpi.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{1, 1};
        assertArrayEquals(data, expected);

        tbpi = new TwoByteIntegerPropertyImpl();
        tbpi.value(TWO_BYTE_INT_MAX_VALUE - 1);
        writer.reset();
        tbpi.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{(byte)255, (byte)254};
        assertArrayEquals(data, expected);

        tbpi = new TwoByteIntegerPropertyImpl();
        tbpi.value(TWO_BYTE_INT_MAX_VALUE);
        writer.reset();
        tbpi.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{(byte)255, (byte)255};
        assertArrayEquals(data, expected);
    }


    public class TwoByteIntegerPropertyImpl extends TwoByteIntegerProperty {

        @Override
        public String name() {
            return TwoByteIntegerPropertyImpl.class.getName();
        }

        @Override
        public int identifier() {
            return 0;
        }
    }
}
