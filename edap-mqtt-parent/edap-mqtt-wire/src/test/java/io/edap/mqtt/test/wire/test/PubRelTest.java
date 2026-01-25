package io.edap.mqtt.test.wire.test;

import io.edap.mqtt.wire.ControlPacketType;
import io.edap.mqtt.wire.PubRel;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
