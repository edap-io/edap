package io.edap.mqtt.packet.test;

import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;
import io.edap.mqtt.packet.ConnAck;
import io.edap.mqtt.ControlPacketType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ConnAckTest {

    @Test
    public void testConstructor() {
        ConnAck connAck = new ConnAck(51);
        assertEquals(connAck.getType(), ControlPacketType.CONNACK);
    }

    @Test
    public void testConnAckFlag() {
        ConnAck connAck = new ConnAck(51);
        int ackFlag = new Random().nextInt();
        connAck.setConnAckFlag(ackFlag);
        assertEquals(connAck.getConnAckFlag(), ackFlag);
    }

    @Test
    public void testConnAckCode() {
        ConnAck connAck = new ConnAck(51);
        int ackCode = new Random().nextInt();
        connAck.setConnAckCode(ackCode);
        assertEquals(connAck.getConnAckCode(), ackCode);
    }

    @Test
    public void testProperties() {
        ConnAck connAck = new ConnAck(51);
        LinkedHashMap<PropertyType, PacketProperty> props = new LinkedHashMap<>();
        assertNull(connAck.getProperties());
        connAck.setProperties(props);
        assertEquals(connAck.getProperties().size(), 0);

    }

}
