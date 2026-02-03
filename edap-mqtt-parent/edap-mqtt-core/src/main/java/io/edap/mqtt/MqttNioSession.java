package io.edap.mqtt;

public interface MqttNioSession {
    ProtocolLevel getProtocolLevel();
    QoSLevel getQoSLevel();
    void close();
    void setQoSLevel(QoSLevel qoSLevel);
    void setProtocolLevel(ProtocolLevel protocolLevel);
    void setConnected(boolean isConnected);
    boolean isConnected();
    MqttEncoder getMqttEncoder();
}
