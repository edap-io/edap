package io.edap.mqtt.packet.test;

import io.edap.mqtt.ControlPacketType;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;
import io.edap.mqtt.packet.PubRec;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class PubRecTest {

    @Test
    public void testConstructor() {
        PubRec pubRec = new PubRec(59);
        assertEquals(pubRec.getType(), ControlPacketType.PUBREC);
    }

    @Test
    public void testPacketIdentifier() {
        PubRec pubRec = new PubRec(59);
        int packetIdentifier = new Random().nextInt();
        pubRec.setPacketIdentifier(packetIdentifier);
        assertEquals(pubRec.getPacketIdentifier(), packetIdentifier);
    }

    @Test
    public void testProperties() {
        PubRec pubRec = new PubRec(59);
        LinkedHashMap<PropertyType, PacketProperty> props = new LinkedHashMap<>();
        assertNull(pubRec.getProperties());
        pubRec.setProperties(props);
        assertEquals(pubRec.getProperties().size(), 0);

    }
}
