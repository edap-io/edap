package io.edap.mqtt.packet.test;

import io.edap.mqtt.ControlPacketType;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;
import io.edap.mqtt.packet.PubAck;
import io.edap.mqtt.packet.PubComp;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class PubCompTest {

    @Test
    public void testConstructor() {
        PubComp pubComp = new PubComp(57);
        assertEquals(pubComp.getType(), ControlPacketType.PUBCOMP);
    }

    @Test
    public void testPacketIdentifier() {
        PubComp pubComp = new PubComp(57);
        int packetIdentifier = new Random().nextInt();
        pubComp.setPacketIdentifier(packetIdentifier);
        assertEquals(pubComp.getPacketIdentifier(), packetIdentifier);
    }

    @Test
    public void testProperties() {
        PubComp pubComp = new PubComp(57);
        LinkedHashMap<PropertyType, PacketProperty> props = new LinkedHashMap<>();
        assertNull(pubComp.getProperties());
        pubComp.setProperties(props);
        assertEquals(pubComp.getProperties().size(), 0);

    }

    @Test
    public void testReasonCode() {
        PubComp pubComp = new PubComp(57);
        int reasonCode = new Random().nextInt(Byte.MAX_VALUE);
        pubComp.setReasonCode(reasonCode);
        assertEquals(pubComp.getReasonCode(), reasonCode);
    }
}
