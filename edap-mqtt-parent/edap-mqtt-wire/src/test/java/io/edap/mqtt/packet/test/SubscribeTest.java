package io.edap.mqtt.packet.test;

import io.edap.mqtt.ControlPacketType;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;
import io.edap.mqtt.QoSLevel;
import io.edap.mqtt.packet.Subscribe;
import io.edap.mqtt.packet.TopicFilter;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class SubscribeTest {

    @Test
    public void testConstructor() {
        Subscribe subscribe = new Subscribe(62);
        assertEquals(subscribe.getType(), ControlPacketType.SUBSCRIBE);
    }

    @Test
    public void testPacketIdentifier() {
        Subscribe subscribe = new Subscribe(62);
        int packetIdentifier = new Random().nextInt();
        subscribe.setPacketIdentifier(packetIdentifier);
        assertEquals(subscribe.getPacketIdentifier(), packetIdentifier);
    }

    @Test
    public void testTopicFilterList() {
        Subscribe subscribe = new Subscribe(62);
        List<TopicFilter> topicFilterList = new ArrayList<>();
        subscribe.setTopicFilterList(topicFilterList);
        assertEquals(subscribe.getTopicFilterList().size(), topicFilterList.size());

        TopicFilter topicFilter = new TopicFilter();
        topicFilter.setTopicFilter("a/b");
        topicFilter.setSubscriptionOptions(QoSLevel.MOST_ONCE.getValue());
        topicFilterList.add(topicFilter);
        assertEquals(subscribe.getTopicFilterList().size(), 1);
        assertEquals(subscribe.getTopicFilterList().get(0).getTopicFilter(), "a/b");
        assertEquals(subscribe.getTopicFilterList().get(0).getSubscriptionOptions(), QoSLevel.MOST_ONCE.getValue());

    }

    @Test
    public void testProperties() {
        Subscribe subscribe = new Subscribe(62);
        LinkedHashMap<PropertyType, PacketProperty> props = new LinkedHashMap<>();
        assertNull(subscribe.getProperties());
        subscribe.setProperties(props);
        assertEquals(subscribe.getProperties().size(), 0);

    }

}
