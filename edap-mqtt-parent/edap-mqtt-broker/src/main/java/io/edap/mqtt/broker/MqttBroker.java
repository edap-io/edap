package io.edap.mqtt.broker;

import io.edap.Decoder;
import io.edap.Server;
import io.edap.mqtt.ProtocolLevel;
import io.edap.mqtt.QoSLevel;
import io.edap.mqtt.broker.mqtthandler.MqttBrokerHandler_V31;
import io.edap.mqtt.broker.mqtthandler.MqttBrokerHandler_V311;
import io.edap.mqtt.broker.mqtthandler.MqttBrokerHandler_V5;
import io.edap.mqtt.ControlPacket;
import io.edap.mqtt.broker.submgt.MemorySubMgt;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MqttBroker extends Server<ControlPacket, MqttBrokerSession> {

    private static Decoder<ControlPacket, MqttBrokerSession> BASE_DECODER = new MqttBrokerBaseDecoder();

    private static Map<ProtocolLevel, MqttBrokerHandler> MQTT_HANDLERS = new ConcurrentHashMap<>();

    private LockPool lockPool;

    private QoSLevel qoSLevel;

    private SubscribeManager subscribeManager;

    @Override
    public void init() {
        super.init();
        setDecoder(BASE_DECODER);
        MQTT_HANDLERS.put(ProtocolLevel.VERSION_3_1,   new MqttBrokerHandler_V31());
        MQTT_HANDLERS.put(ProtocolLevel.VERSION_3_1_1, new MqttBrokerHandler_V311());
        MQTT_HANDLERS.put(ProtocolLevel.VERSION_5,     new MqttBrokerHandler_V5());
        if (qoSLevel == null) {
            qoSLevel = QoSLevel.EXACTLY_ONCE;
        }
        lockPool = new LockPool();
        subscribeManager = new MemorySubMgt();
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
        session.setSubscribeManager(subscribeManager);
        return session;
    }

    public LockPool getLockPool() {
        return lockPool;
    }

    public SubscribeManager getSubscribeManager() {
        return subscribeManager;
    }
}
