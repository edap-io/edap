package io.edap.mqtt.packet.test;

import io.edap.mqtt.packet.Connect;
import io.edap.mqtt.QoSLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ControlPacketTest {

    @Test
    public void testConstructor() {
        int value = Integer.parseInt("0000", 2);
        Connect connect = new Connect(value);
        assertEquals(value, 0);
        assertEquals(connect.getDup(), 0);
        assertEquals(connect.getQos(), QoSLevel.MOST_ONCE);
        assertEquals(connect.getRetain(), 0);

        value = Integer.parseInt("0001", 2);
        connect = new Connect(value);
        assertEquals(value, 1);
        assertEquals(connect.getDup(), 0);
        assertEquals(connect.getQos(), QoSLevel.MOST_ONCE);
        assertEquals(connect.getRetain(), 1);

        value = Integer.parseInt("0010", 2);
        connect = new Connect(value);
        assertEquals(value, 2);
        assertEquals(connect.getDup(), 0);
        assertEquals(connect.getQos(), QoSLevel.LEAST_ONCE);
        assertEquals(connect.getRetain(), 0);

        value = Integer.parseInt("0011", 2);
        connect = new Connect(value);
        assertEquals(value, 3);
        assertEquals(connect.getDup(), 0);
        assertEquals(connect.getQos(), QoSLevel.LEAST_ONCE);
        assertEquals(connect.getRetain(), 1);

        value = Integer.parseInt("0100", 2);
        connect = new Connect(value);
        assertEquals(value, 4);
        assertEquals(connect.getDup(), 0);
        assertEquals(connect.getQos(), QoSLevel.EXACTLY_ONCE);
        assertEquals(connect.getRetain(), 0);

        value = Integer.parseInt("0101", 2);
        connect = new Connect(value);
        assertEquals(value, 5);
        assertEquals(connect.getDup(), 0);
        assertEquals(connect.getQos(), QoSLevel.EXACTLY_ONCE);
        assertEquals(connect.getRetain(), 1);

        value = Integer.parseInt("0110", 2);
        connect = new Connect(value);
        assertEquals(value, 6);
        assertEquals(connect.getDup(), 0);
        assertEquals(connect.getQos(), QoSLevel.RESERVED);
        assertEquals(connect.getRetain(), 0);

        value = Integer.parseInt("0111", 2);
        connect = new Connect(value);
        assertEquals(value, 7);
        assertEquals(connect.getDup(), 0);
        assertEquals(connect.getQos(), QoSLevel.RESERVED);
        assertEquals(connect.getRetain(), 1);

        value = Integer.parseInt("1000", 2);
        connect = new Connect(value);
        assertEquals(value, 8);
        assertEquals(connect.getDup(), 1);
        assertEquals(connect.getQos(), QoSLevel.MOST_ONCE);
        assertEquals(connect.getRetain(), 0);

        value = Integer.parseInt("1001", 2);
        connect = new Connect(value);
        assertEquals(value, 9);
        assertEquals(connect.getDup(), 1);
        assertEquals(connect.getQos(), QoSLevel.MOST_ONCE);
        assertEquals(connect.getRetain(), 1);

        value = Integer.parseInt("1010", 2);
        connect = new Connect(value);
        assertEquals(value, 10);
        assertEquals(connect.getDup(), 1);
        assertEquals(connect.getQos(), QoSLevel.LEAST_ONCE);
        assertEquals(connect.getRetain(), 0);

        value = Integer.parseInt("1011", 2);
        connect = new Connect(value);
        assertEquals(value, 11);
        assertEquals(connect.getDup(), 1);
        assertEquals(connect.getQos(), QoSLevel.LEAST_ONCE);
        assertEquals(connect.getRetain(), 1);

        value = Integer.parseInt("1100", 2);
        connect = new Connect(value);
        assertEquals(value, 12);
        assertEquals(connect.getDup(), 1);
        assertEquals(connect.getQos(), QoSLevel.EXACTLY_ONCE);
        assertEquals(connect.getRetain(), 0);

        value = Integer.parseInt("1101", 2);
        connect = new Connect(value);
        assertEquals(value, 13);
        assertEquals(connect.getDup(), 1);
        assertEquals(connect.getQos(), QoSLevel.EXACTLY_ONCE);
        assertEquals(connect.getRetain(), 1);

        value = Integer.parseInt("1110", 2);
        connect = new Connect(value);
        assertEquals(value, 14);
        assertEquals(connect.getDup(), 1);
        assertEquals(connect.getQos(), QoSLevel.RESERVED);
        assertEquals(connect.getRetain(), 0);

        value = Integer.parseInt("1111", 2);
        connect = new Connect(value);
        assertEquals(value, 15);
        assertEquals(connect.getDup(), 1);
        assertEquals(connect.getQos(), QoSLevel.RESERVED);
        assertEquals(connect.getRetain(), 1);

        assertEquals(connect.getLowFourBits(), 15);
    }
}
