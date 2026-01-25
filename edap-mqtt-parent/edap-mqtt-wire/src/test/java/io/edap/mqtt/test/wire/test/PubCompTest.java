package io.edap.mqtt.test.wire.test;

import io.edap.mqtt.wire.ControlPacketType;
import io.edap.mqtt.wire.PubComp;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
