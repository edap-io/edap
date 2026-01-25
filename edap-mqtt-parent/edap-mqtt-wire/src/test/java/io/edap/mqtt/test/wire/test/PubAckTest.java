package io.edap.mqtt.test.wire.test;

import io.edap.mqtt.wire.ControlPacketType;
import io.edap.mqtt.wire.PubAck;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
