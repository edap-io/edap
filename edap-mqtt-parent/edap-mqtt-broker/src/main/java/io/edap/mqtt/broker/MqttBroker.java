package io.edap.mqtt.broker;

import io.edap.Decoder;
import io.edap.Server;
import io.edap.mqtt.wire.ControlPacket;

public class MqttBroker extends Server<ControlPacket, MqttBrokerSession> {

    private static Decoder<ControlPacket, MqttBrokerSession> BASE_DECODER = new MqttBaseDecoder();

    @Override
    public void init() {
        super.init();
        setDecoder(BASE_DECODER);
    }

    @Override
    public MqttBrokerSession createNioSession() {
        MqttBrokerSession session = new MqttBrokerSession();
        session.setServer(this);
        session.setDecoder(BASE_DECODER);
        return session;
    }
}
