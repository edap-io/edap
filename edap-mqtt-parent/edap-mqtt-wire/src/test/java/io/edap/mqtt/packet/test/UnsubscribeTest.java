package io.edap.mqtt.packet.test;

import io.edap.mqtt.ControlPacketType;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;
import io.edap.mqtt.packet.Unsubscribe;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    @Test
    public void testProperties() {
        Unsubscribe unsubscribe = new Unsubscribe(64);
        LinkedHashMap<PropertyType, PacketProperty> props = new LinkedHashMap<>();
        assertNull(unsubscribe.getProperties());
        unsubscribe.setProperties(props);
        assertEquals(unsubscribe.getProperties().size(), 0);

    }

}
