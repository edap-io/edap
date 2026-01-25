package io.edap.mqtt.test.wire.test;

import io.edap.mqtt.wire.ControlPacketType;
import io.edap.mqtt.wire.UnsubAck;
import io.edap.mqtt.wire.Unsubscribe;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class UnsubscribeTest {

    @Test
    public void testConstructor() {
        Unsubscribe unsubscribe = new Unsubscribe(64);
        assertEquals(unsubscribe.getType(), ControlPacketType.UNSUBSCRIBE);
    }

    @Test
    public void testPacketIdentifier() {
        Unsubscribe unsubscribe = new Unsubscribe(64);
        int packetIdentifier = new Random().nextInt();
        unsubscribe.setPacketIdentifier(packetIdentifier);
        assertEquals(unsubscribe.getPacketIdentifier(), packetIdentifier);
    }

    @Test
    public void testTopicFilterList() {
        Unsubscribe unsubscribe = new Unsubscribe(64);
        List<String> topicFilterList = new ArrayList<>();
        unsubscribe.setTopicFilterList(topicFilterList);
        assertEquals(unsubscribe.getTopicFilterList().size(), topicFilterList.size());

        topicFilterList.add("a/b");
        assertEquals(unsubscribe.getTopicFilterList().size(), 1);
        assertEquals(unsubscribe.getTopicFilterList().get(0), "a/b");
    }
}
