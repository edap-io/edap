package io.edap.mqtt.packet.test;

import io.edap.mqtt.ControlPacketType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ControlPacketTypeTest {

    @Test
    public void testConstructor() {
        ControlPacketType type = ControlPacketType.RESERVED;
        assertEquals(type.getValue(), 0);
        type = ControlPacketType.CONNECT;
        assertEquals(type.getValue(), 1);
        type = ControlPacketType.CONNACK;
        assertEquals(type.getValue(), 2);
        type = ControlPacketType.PUBLISH;
        assertEquals(type.getValue(), 3);
        type = ControlPacketType.PUBACK;
        assertEquals(type.getValue(), 4);
        type = ControlPacketType.PUBREC;
        assertEquals(type.getValue(), 5);
        type = ControlPacketType.PUBREL;
        assertEquals(type.getValue(), 6);
        type = ControlPacketType.PUBCOMP;
        assertEquals(type.getValue(), 7);
        type = ControlPacketType.SUBSCRIBE;
        assertEquals(type.getValue(), 8);
        type = ControlPacketType.SUBACK;
        assertEquals(type.getValue(), 9);
        type = ControlPacketType.UNSUBSCRIBE;
        assertEquals(type.getValue(), 10);
        type = ControlPacketType.UNSUBACK;
        assertEquals(type.getValue(), 11);
        type = ControlPacketType.PINGREQ;
        assertEquals(type.getValue(), 12);
        type = ControlPacketType.PINGRESP;
        assertEquals(type.getValue(), 13);
        type = ControlPacketType.DISCONNECT;
        assertEquals(type.getValue(), 14);
        type = ControlPacketType.AUTH;
        assertEquals(type.getValue(), 15);
    }

    @Test
    public void testFromValue() {
        ControlPacketType type = ControlPacketType.fromValue(0);
        assertEquals(type, ControlPacketType.RESERVED);
        type = ControlPacketType.fromValue(1);
        assertEquals(type, ControlPacketType.CONNECT);
        type = ControlPacketType.fromValue(2);
        assertEquals(type, ControlPacketType.CONNACK);
        type = ControlPacketType.fromValue(3);
        assertEquals(type, ControlPacketType.PUBLISH);
        type = ControlPacketType.fromValue(4);
        assertEquals(type, ControlPacketType.PUBACK);
        type = ControlPacketType.fromValue(5);
        assertEquals(type, ControlPacketType.PUBREC);
        type = ControlPacketType.fromValue(6);
        assertEquals(type, ControlPacketType.PUBREL);
        type = ControlPacketType.fromValue(7);
        assertEquals(type, ControlPacketType.PUBCOMP);
        type = ControlPacketType.fromValue(8);
        assertEquals(type, ControlPacketType.SUBSCRIBE);
        type = ControlPacketType.fromValue(9);
        assertEquals(type, ControlPacketType.SUBACK);
        type = ControlPacketType.fromValue(10);
        assertEquals(type, ControlPacketType.UNSUBSCRIBE);
        type = ControlPacketType.fromValue(11);
        assertEquals(type, ControlPacketType.UNSUBACK);
        type = ControlPacketType.fromValue(12);
        assertEquals(type, ControlPacketType.PINGREQ);
        type = ControlPacketType.fromValue(13);
        assertEquals(type, ControlPacketType.PINGRESP);
        type = ControlPacketType.fromValue(14);
        assertEquals(type, ControlPacketType.DISCONNECT);
        type = ControlPacketType.fromValue(15);
        assertEquals(type, ControlPacketType.AUTH);

        type = ControlPacketType.fromValue(-1);
        assertEquals(type, ControlPacketType.RESERVED);
        type = ControlPacketType.fromValue(16);
        assertEquals(type, ControlPacketType.RESERVED);
    }
}
