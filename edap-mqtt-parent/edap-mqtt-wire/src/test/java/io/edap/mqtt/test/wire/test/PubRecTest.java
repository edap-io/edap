package io.edap.mqtt.test.wire.test;

import io.edap.mqtt.wire.ControlPacketType;
import io.edap.mqtt.wire.PubRec;
import io.edap.mqtt.wire.Publish;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
