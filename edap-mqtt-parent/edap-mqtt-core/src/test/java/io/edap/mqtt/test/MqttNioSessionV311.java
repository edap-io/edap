package io.edap.mqtt.test;

import io.edap.mqtt.MqttEncoder;
import io.edap.mqtt.MqttNioSession;
import io.edap.mqtt.ProtocolLevel;
import io.edap.mqtt.QoSLevel;

public class MqttNioSessionV311 implements MqttNioSession {
    @Override
    public ProtocolLevel getProtocolLevel() {
        return ProtocolLevel.VERSION_3_1_1;
    }

    @Override
    public QoSLevel getQoSLevel() {
        return QoSLevel.EXACTLY_ONCE;
    }

    @Override
    public void close() {

    }

    @Override
    public void setQoSLevel(QoSLevel qoSLevel) {

    }

    @Override
    public void setProtocolLevel(ProtocolLevel protocolLevel) {

    }

    @Override
    public void setConnected(boolean isConnected) {

    }

    @Override
    public boolean isConnected() {
        return false;
    }

    @Override
    public MqttEncoder getMqttEncoder() {
        return null;
    }
}
