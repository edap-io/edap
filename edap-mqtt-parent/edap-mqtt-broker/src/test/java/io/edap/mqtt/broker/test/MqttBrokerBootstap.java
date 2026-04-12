package io.edap.mqtt.broker.test;

import io.edap.Edap;
import io.edap.mqtt.broker.MqttBrokerBuilder;

import java.io.IOException;

public class MqttBrokerBootstap {

    public static void main(String[] args) throws IOException {
        MqttBrokerBuilder brokerBuilder = new MqttBrokerBuilder();
        brokerBuilder.listen(1883);
        Edap edap = new Edap();
        edap.addServer(brokerBuilder.build());
        edap.run();
    }
}
