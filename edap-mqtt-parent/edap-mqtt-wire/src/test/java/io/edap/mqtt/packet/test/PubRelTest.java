package io.edap.mqtt.packet.test;

import io.edap.mqtt.ControlPacketType;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;
import io.edap.mqtt.packet.PubRel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class PubRelTest {

    @Test
    public void testConstructor() {
        PubRel pubRel = new PubRel(60);
        assertEquals(pubRel.getType(), ControlPacketType.PUBREL);
    }

    @Test
    public void testPacketIdentifier() {
        PubRel pubRel = new PubRel(60);
        int packetIdentifier = new Random().nextInt();
        pubRel.setPacketIdentifier(packetIdentifier);
        assertEquals(pubRel.getPacketIdentifier(), packetIdentifier);
    }

    @Test
    public void testProperties() {
        PubRel pubRel = new PubRel(60);
        LinkedHashMap<PropertyType, PacketProperty> props = new LinkedHashMap<>();
        assertNull(pubRel.getProperties());
        pubRel.setProperties(props);
        assertEquals(pubRel.getProperties().size(), 0);

    }

    @Test
    public void testReasonCode() {
        PubRel pubRel = new PubRel(60);
        int reasonCode = new Random().nextInt(Byte.MAX_VALUE);
        pubRel.setReasonCode(reasonCode);
        assertEquals(reasonCode, pubRel.getReasonCode());
    }
}
