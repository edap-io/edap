package io.edap.mqtt.packet.test;

import io.edap.mqtt.ControlPacketType;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;
import io.edap.mqtt.packet.SubAck;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class SubAckTest {
    @Test
    public void testConstructor() {
        SubAck subAck = new SubAck(61);
        assertEquals(subAck.getType(), ControlPacketType.SUBACK);
    }

    @Test
    public void testPacketIdentifier() {
        SubAck subAck = new SubAck(61);
        int packetIdentifier = new Random().nextInt();
        subAck.setPacketIdentifier(packetIdentifier);
        assertEquals(subAck.getPacketIdentifier(), packetIdentifier);
    }

    @Test
    public void testRespCodes() {
        SubAck subAck = new SubAck(61);
        List<Integer> respCodes = new ArrayList<>();
        subAck.setRespCodes(respCodes);
        assertEquals(subAck.getRespCodes().size(), 0);
    }

    @Test
    public void testProperties() {
        SubAck subAck = new SubAck(61);
        LinkedHashMap<PropertyType, PacketProperty> props = new LinkedHashMap<>();
        assertNull(subAck.getProperties());
        subAck.setProperties(props);
        assertEquals(subAck.getProperties().size(), 0);

    }
}
