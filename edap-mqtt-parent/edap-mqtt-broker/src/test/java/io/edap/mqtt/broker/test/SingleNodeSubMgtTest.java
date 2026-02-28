package io.edap.mqtt.broker.test;

import io.edap.mqtt.QoSLevel;
import io.edap.mqtt.broker.MqttBroker;
import io.edap.mqtt.broker.MqttBrokerSession;
import io.edap.mqtt.broker.SubscribeManager;
import io.edap.mqtt.broker.submgt.MemorySubMgt;
import io.edap.mqtt.packet.SubAck;
import io.edap.mqtt.packet.Subscribe;
import io.edap.mqtt.packet.TopicFilter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static io.edap.mqtt.ControlPacketType.SUBSCRIBE_VALUE;
import static io.edap.mqtt.broker.test.TestUtil.randomStr;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class SingleNodeSubMgtTest {

    @Test
    public void testCheckAndAddTopic() throws NoSuchFieldException, IllegalAccessException {
        SubscribeManager subMgm = new MemorySubMgt();
        Field topicField = subMgm.getClass().getDeclaredField("TOPICS");
        topicField.setAccessible(true);
        assertEquals(((Set)topicField.get(subMgm)).size(), 0);
        subMgm.checkAndAddTopic("sensor/1/temperature");
        Set<String> allTopic = ((Set)topicField.get(subMgm));
        assertEquals(allTopic.size(), 1);
        assertEquals(allTopic.contains("sensor/1/temperature"), true);
        assertEquals(allTopic.contains("sensor/1/temperature2"), false);
    }

    @Test
    public void testSubscribe() {
        SubscribeManager subMgm = new MemorySubMgt() {};
        Subscribe subscribe = new Subscribe(SUBSCRIBE_VALUE << 4);
        int packetIdentifier = Short.MAX_VALUE + new Random().nextInt(Short.MAX_VALUE);
        String clientId = randomStr(20);
        List<TopicFilter> topicFilters = new ArrayList<>();
        subscribe.setPacketIdentifier(packetIdentifier);
        TopicFilter tf1 = new TopicFilter();
        tf1.setSubscriptionOptions(QoSLevel.RESERVED.getValue());
        topicFilters.add(tf1);
        subscribe.setTopicFilterList(topicFilters);
        MqttBrokerSession session = new MqttBrokerSession();
        session.setClientId(clientId);
        MqttBroker broker = new MqttBroker();
        session.setServer(broker);
        broker.init();
        SubAck subAck = subMgm.subscribe(subscribe, session);
        assertEquals(subAck.getPacketIdentifier(), subscribe.getPacketIdentifier());

        tf1.setSubscriptionOptions(QoSLevel.MOST_ONCE.getValue());
        subAck = subMgm.subscribe(subscribe, session);
        assertEquals(subAck.getPacketIdentifier(), subscribe.getPacketIdentifier());

        tf1.setTopicFilter("a/b");
        subAck = subMgm.subscribe(subscribe, session);
        assertEquals(subAck.getPacketIdentifier(), subscribe.getPacketIdentifier());

        TopicFilter tf2 = new TopicFilter();
        tf2.setSubscriptionOptions(QoSLevel.MOST_ONCE.getValue());
        tf2.setTopicFilter("a/+");
        topicFilters.add(tf2);
        subAck = subMgm.subscribe(subscribe, session);
        assertEquals(subAck.getPacketIdentifier(), subscribe.getPacketIdentifier());

        clientId = randomStr(20);
        session.setClientId(clientId);
        subAck = subMgm.subscribe(subscribe, session);
        assertEquals(subAck.getPacketIdentifier(), subscribe.getPacketIdentifier());

    }
}
