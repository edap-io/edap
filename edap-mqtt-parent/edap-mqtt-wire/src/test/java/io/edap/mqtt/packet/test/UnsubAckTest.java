package io.edap.mqtt.packet.test;

import io.edap.mqtt.ControlPacketType;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;
import io.edap.mqtt.packet.UnsubAck;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class UnsubAckTest {

    @Test
    public void testConstructor() {
        UnsubAck unsubAck = new UnsubAck(63);
        assertEquals(unsubAck.getType(), ControlPacketType.UNSUBACK);
    }

    @Test
    public void testPacketIdentifier() {
        UnsubAck unsubAck = new UnsubAck(63);
        int packetIdentifier = new Random().nextInt();
        unsubAck.setPacketIdentifier(packetIdentifier);
        assertEquals(unsubAck.getPacketIdentifier(), packetIdentifier);
    }

    @Test
    public void testReasonCodes() {
        UnsubAck unsubAck = new UnsubAck(63);
        int reasonCode = new Random().nextInt(Byte.MAX_VALUE);
        assertNull(unsubAck.getReasonCodes());

        List<Integer> reasonCodes = new ArrayList<>();
        unsubAck.setReasonCodes(reasonCodes);
        assertNotNull(unsubAck.getReasonCodes());
        assertEquals(unsubAck.getReasonCodes().size(), 0);

        reasonCodes.add(reasonCode);
        assertNotNull(unsubAck.getReasonCodes());
        assertEquals(unsubAck.getReasonCodes().size(), 1);
        assertEquals(unsubAck.getReasonCodes().get(0), reasonCode);
    }

    @Test
    public void testProperties() {
        UnsubAck unsubAck = new UnsubAck(63);
        LinkedHashMap<PropertyType, PacketProperty> props = new LinkedHashMap<>();
        assertNull(unsubAck.getProperties());
        unsubAck.setProperties(props);
        assertEquals(unsubAck.getProperties().size(), 0);

    }
}
