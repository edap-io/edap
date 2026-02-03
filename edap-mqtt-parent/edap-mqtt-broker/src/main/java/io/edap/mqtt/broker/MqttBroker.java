package io.edap.mqtt.broker;

import io.edap.Decoder;
import io.edap.Server;
import io.edap.mqtt.ProtocolLevel;
import io.edap.mqtt.QoSLevel;
import io.edap.mqtt.broker.mqtthandler.MqttHandler_V31;
import io.edap.mqtt.broker.mqtthandler.MqttHandler_V311;
import io.edap.mqtt.broker.mqtthandler.MqttHandler_V5;
import io.edap.mqtt.packet.ControlPacket;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MqttBroker extends Server<ControlPacket, MqttBrokerSession> {

    private static Decoder<ControlPacket, MqttBrokerSession> BASE_DECODER = new MqttBaseDecoder();

    private static Map<ProtocolLevel, MqttHandler> MQTT_HANDLERS = new ConcurrentHashMap<>();

    private QoSLevel qoSLevel;

    @Override
    public void init() {
        super.init();
        setDecoder(BASE_DECODER);
        MQTT_HANDLERS.put(ProtocolLevel.VERSION_3_1,    new MqttHandler_V31());
        MQTT_HANDLERS.put(ProtocolLevel.VERSION_3_1_1, new MqttHandler_V311());
        MQTT_HANDLERS.put(ProtocolLevel.VERSION_5,     new MqttHandler_V5());
        if (qoSLevel == null) {
            qoSLevel = QoSLevel.EXACTLY_ONCE;
        }

    }

    public void setQoSLevel(QoSLevel qoSLevel) {
        this.qoSLevel = qoSLevel;
    }

    @Override
    public MqttBrokerSession createNioSession() {
        MqttBrokerSession session = new MqttBrokerSession();
        session.setServer(this);
        session.setDecoder(BASE_DECODER);
        session.setMqttHandlers(MQTT_HANDLERS);
        session.setQoSLevel(qoSLevel);
        return session;
    }
}
