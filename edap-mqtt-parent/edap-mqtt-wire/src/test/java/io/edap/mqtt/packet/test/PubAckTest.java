package io.edap.mqtt.packet.test;

import io.edap.mqtt.ControlPacketType;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;
import io.edap.mqtt.packet.PubAck;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class PubAckTest {

    @Test
    public void testConstructor() {
        PubAck pubAck = new PubAck(56);
        assertEquals(pubAck.getType(), ControlPacketType.PUBACK);
    }

    @Test
    public void testPacketIdentifier() {
        PubAck pubAck = new PubAck(56);
        int packetIdentifier = new Random().nextInt();
        pubAck.setPacketIdentifier(packetIdentifier);
        assertEquals(pubAck.getPacketIdentifier(), packetIdentifier);
    }

    @Test
    public void testProperties() {
        PubAck pubAck = new PubAck(56);
        LinkedHashMap<PropertyType, PacketProperty> props = new LinkedHashMap<>();
        assertNull(pubAck.getProperties());
        pubAck.setProperties(props);
        assertEquals(pubAck.getProperties().size(), 0);

    }

    @Test
    public void testReasonCode() {
        PubAck pubAck = new PubAck(56);
        int reasonCode = new Random().nextInt(Byte.MAX_VALUE);
        pubAck.setReasonCode(reasonCode);
        assertEquals(pubAck.getReasonCode(), reasonCode);
    }

}
