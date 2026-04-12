package io.edap.mqtt.property.test;

import io.edap.mqtt.ByteArrayToLongException;
import io.edap.mqtt.IntegerToLongException;
import io.edap.mqtt.MqttWriter;
import io.edap.mqtt.PropertyType;
import io.edap.mqtt.property.SubscriptionIdentifier;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static io.edap.mqtt.MqttConstant.TWO_BYTE_INT_MAX_VALUE;
import static io.edap.mqtt.PacketProperty.USER_PROPERTY_ID;
import static io.edap.mqtt.packet.test.ConnectTest.randomStr;
import static org.junit.jupiter.api.Assertions.*;

public class SubscriptionIdentifierTest {

    @Test
    public void testName() {
        SubscriptionIdentifier si = new SubscriptionIdentifier();
        assertEquals(si.name(), "Subscription Identifier");
    }

    @Test
    public void testIdentifier() {
        SubscriptionIdentifier si = new SubscriptionIdentifier();
        assertEquals(si.identifier(), PropertyType.SUBSCRIPTION_INDENTIFIER.getType());
    }

    @Test
    public void testValue() {
        SubscriptionIdentifier si = new SubscriptionIdentifier();
        assertNotNull(si.value());
        assertEquals(si.value().intValue(), 0);
        Integer value = new Random().nextInt();
        si.value(value);
        assertEquals(si.value().intValue(), value.intValue());
    }

    @Test
    public void testWriteTo() {
        SubscriptionIdentifier si = new SubscriptionIdentifier();
        MqttWriter writer = new MqttWriter();
        si.writeTo(writer);
        byte[] data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        byte[] expected = new byte[]{0};
        assertArrayEquals(data, expected);

        writer.reset();
        si.value(1);
        si.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{1};
        assertArrayEquals(data, expected);

        writer.reset();
        si.value(127);
        si.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{127};
        assertArrayEquals(data, expected);

        int srcV = 128;
        writer.reset();
        si.value(srcV);
        si.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{-128, 1};
        assertArrayEquals(data, expected);
        int v = decodeInt(data);
        assertEquals(v, srcV);

        srcV = 129;
        writer.reset();
        si.value(srcV);
        si.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{-127, 1};
        assertArrayEquals(data, expected);
        v = decodeInt(data);
        assertEquals(v, srcV);

        srcV = 127 * 127;
        writer.reset();
        si.value(srcV);
        si.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{-127, 126};
        assertArrayEquals(data, expected);
        v = decodeInt(data);
        assertEquals(v, srcV);

        srcV = 127 * 127 + 1;
        writer.reset();
        si.value(srcV);
        si.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{-126, 126};
        assertArrayEquals(data, expected);
        v = decodeInt(data);
        assertEquals(v, srcV);

        srcV = 127 * 127 * 127;
        writer.reset();
        si.value(srcV);
        si.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{-1, -126, 125};
        assertArrayEquals(data, expected);
        v = decodeInt(data);
        assertEquals(v, srcV);

        srcV = 16384;
        writer.reset();
        si.value(srcV);
        si.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{-128, -128, 1};
        assertArrayEquals(data, expected);
        v = decodeInt(data);
        assertEquals(v, srcV);

        srcV = 127 * 127 * 127 + 1;
        writer.reset();
        si.value(srcV);
        si.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{-128, -125, 125};
        assertArrayEquals(data, expected);
        v = decodeInt(data);
        assertEquals(v, srcV);

        srcV = 268435455;
        writer.reset();
        si.value(srcV);
        si.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{-1, -1, -1, 127};
        assertArrayEquals(data, expected);
        v = decodeInt(data);
        assertEquals(v, srcV);

        srcV = 2097151;
        writer.reset();
        si.value(srcV);
        si.writeTo(writer);
        data = new byte[writer.getLength()];
        System.arraycopy(writer.getData(), writer.getStart(), data, 0, writer.getLength());
        expected = new byte[]{-1, -1, 127};
        assertArrayEquals(data, expected);
        v = decodeInt(data);
        assertEquals(v, srcV);


        IntegerToLongException thrown = assertThrows(IntegerToLongException.class,
                () -> {
                    writer.reset();
                    si.value(268435456);
                    si.writeTo(writer);
                });
        assertTrue(thrown.getMessage().contains("Integer 268435456 too big"));

    }

    private int decodeInt(byte[] bs) {
        int rpos = 0;
        int remain;
        byte varFirst = bs[rpos++];
        if (varFirst >= 0) {
            remain = varFirst;
        } else {
            int varTwo = bs[rpos++];
            if (varTwo > 0) {
                remain = (varTwo & 0x7F) << 7 | varFirst & 0x7F;
            } else {
                int varThree = bs[rpos++];
                if (varThree > 0) {
                    remain = (varThree & 0x7F) << 14 | (varTwo & 0x7F) << 7 | (varFirst & 0x7F);
                } else {
                    remain = (bs[rpos++] & 0x7F) << 21 | (varTwo & 0x7F) << 14 | (varThree & 0x7F) << 7 | (varFirst & 0x7F);
                }
            }
        }

        return remain;
    }
}
