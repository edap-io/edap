package io.edap.mqtt.test.wire.test;

import io.edap.mqtt.wire.ControlPacketType;
import io.edap.mqtt.wire.PubRel;
import io.edap.mqtt.wire.SubAck;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
