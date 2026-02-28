package io.edap.mqtt.broker;

import io.edap.mqtt.packet.SubAck;
import io.edap.mqtt.packet.Subscribe;

public interface SubscribeManager {

    void checkAndAddTopic(String topic);

    SubAck subscribe(Subscribe subscribe, MqttBrokerSession session);
}
