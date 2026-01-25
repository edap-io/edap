package io.edap.mqtt.test.wire.test;

import io.edap.mqtt.wire.ControlPacketType;
import io.edap.mqtt.wire.UnsubAck;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
