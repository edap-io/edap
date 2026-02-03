package io.edap.mqtt.property.test;

import io.edap.mqtt.MqttWriter;
import io.edap.mqtt.property.IntegerProperty;
import org.junit.jupiter.api.Test;

import static io.edap.mqtt.MqttConstant.TWO_BYTE_INT_MAX_VALUE;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class IntegerPropertyTest {

    @Test
    public void testWriteTo() {
        IntegerPropertyImpl ipi = new IntegerPropertyImpl();
        MqttWriter writer = new MqttWriter();
        ipi.writeTo(writer);
        byte[] data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        byte[] expected = new byte[]{0,0,0,0};
        assertArrayEquals(data, expected);

        writer.reset();
        ipi.value(1);
        ipi.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{0, 0, 0, 1};
        assertArrayEquals(data, expected);

        writer.reset();
        ipi.value(127);
        ipi.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{0, 0, 0, 127};
        assertArrayEquals(data, expected);

        writer.reset();
        ipi.value(128);
        ipi.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{0, 0, 0, (byte)128};
        assertArrayEquals(data, expected);

        writer.reset();
        ipi.value(255);
        ipi.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{0, 0, 0, (byte)255};
        assertArrayEquals(data, expected);

        writer.reset();
        ipi.value(256);
        ipi.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{0, 0, 1, (byte)0};
        assertArrayEquals(data, expected);

        writer.reset();
        ipi.value(TWO_BYTE_INT_MAX_VALUE);
        ipi.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{0, 0, (byte)255, (byte)255};
        assertArrayEquals(data, expected);
    }

    public class IntegerPropertyImpl extends IntegerProperty {

        @Override
        public String name() {
            return IntegerPropertyImpl.class.getName();
        }

        @Override
        public int identifier() {
            return 0;
        }
    }
}
