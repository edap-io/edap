package io.edap.mqtt.broker;

import io.edap.NioServerSession;
import io.edap.mqtt.*;
import io.edap.mqtt.encoder.V311Encoder;
import io.edap.mqtt.encoder.V31Encoder;
import io.edap.mqtt.encoder.V5Encoder;
import io.edap.mqtt.packet.*;

import java.util.HashMap;
import java.util.Map;

import static io.edap.mqtt.ControlPacketType.*;

public class MqttBrokerSession extends NioServerSession<ControlPacket> implements MqttNioSession {

    /**
     * 该客户端是否已经执行了connect的操作，如果未执行过connect操作，第一个解析的数据包必须为connect的包
     */
    private boolean connected;
    /**
     * 客户端连接使用的协议版本
     */
    private ProtocolLevel protocolLevel;

    private QoSLevel qosLevel;

    private Map<ProtocolLevel, MqttBrokerHandler> mqttHandlers;

    private MqttEncoder encoder;

    private static final Map<ProtocolLevel, MqttEncoder> MQTT_ENCODERS;

    static {
        MQTT_ENCODERS = new HashMap<>();
        MQTT_ENCODERS.put(ProtocolLevel.VERSION_3_1, new V31Encoder());
        MQTT_ENCODERS.put(ProtocolLevel.VERSION_3_1_1, new V311Encoder());
        MQTT_ENCODERS.put(ProtocolLevel.VERSION_5, new V5Encoder());
    }

    @Override
    public void handle(ControlPacket message) {
        try {
            if (!connected) {
                if (message instanceof Connect) {
                    Connect connect = (Connect) message;
                    MqttBrokerHandler handler = mqttHandlers.get(connect.getProtocolLevel());
                    handler.handleConnect(this, connect);
                }
                return;
            }
            int packetType = message.getType().getValue();
            MqttBrokerHandler handler = mqttHandlers.get(getProtocolLevel());
            switch (packetType) {
                case DISCONNECT_VALUE:
                    handler.handleDisconnect(this, (Disconnect)message);
                    break;
                case PUBLISH_VALUE:
                    handler.handlePublish(this, (Publish)message);
                    break;
                case PUBACK_VALUE:
                    handler.handlePubAck(this, (PubAck)message);
                    break;
                case PUBREC_VALUE:
                    handler.handlePubRec(this, (PubRec)message);
                    break;
                case PUBREL_VALUE:
                    handler.handlePubRel(this, (PubRel)message);
                    break;
                case PUBCOMP_VALUE:
                    handler.handlePubComp(this, (PubComp)message);
                    break;
                case SUBSCRIBE_VALUE:
                    handler.handleSubscribe(this, (Subscribe)message);
                    break;
                case UNSUBSCRIBE_VALUE:
                    handler.handleUnsubscribe(this, (Unsubscribe)message);
                    break;
                case PINGREQ_VALUE:
                    handler.handlePing(this, (PingReq)message);
                    break;
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 该客户端是否已经执行了connect的操作，如果未执行过connect操作，第一个解析的数据包必须为connect的包
     */
    public boolean isConnected() {
        return connected;
    }

    @Override
    public MqttEncoder getMqttEncoder() {
        return encoder;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    /**
     * 客户端连接使用的协议版本
     */
    public ProtocolLevel getProtocolLevel() {
        return protocolLevel;
    }

    @Override
    public QoSLevel getQoSLevel() {
        return qosLevel;
    }

    @Override
    public void setQoSLevel(QoSLevel qoSLevel) {
        this.qosLevel = qoSLevel;
    }

    public void setProtocolLevel(ProtocolLevel protocolLevel) {
        this.protocolLevel = protocolLevel;
        this.encoder = MQTT_ENCODERS.get(protocolLevel);
    }

    public Map<ProtocolLevel, MqttBrokerHandler> getMqttHandlers() {
        return mqttHandlers;
    }

    public void setMqttHandlers(Map<ProtocolLevel, MqttBrokerHandler> mqttHandlers) {
        this.mqttHandlers = mqttHandlers;
    }
}
