package io.edap.mqtt.test.wire.test;

import io.edap.mqtt.wire.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        topicFilter.setQos(QoSLevel.MOST_ONCE);
        topicFilterList.add(topicFilter);
        assertEquals(subscribe.getTopicFilterList().size(), 1);
        assertEquals(subscribe.getTopicFilterList().get(0).getTopicFilter(), "a/b");
        assertEquals(subscribe.getTopicFilterList().get(0).getQos(), QoSLevel.MOST_ONCE);

    }
}
