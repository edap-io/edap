package io.edap.mqtt.broker;

import io.edap.NioServerSession;
import io.edap.mqtt.wire.ControlPacket;
import io.edap.mqtt.wire.ProtocolLevel;

public class MqttBrokerSession extends NioServerSession<ControlPacket> {

    /**
     * 该客户端是否已经执行了connect的操作，如果未执行过connect操作，第一个解析的数据包必须为connect的包
     */
    private boolean connected;
    /**
     * 客户端连接使用的协议版本
     */
    private ProtocolLevel protocolLevel;

    @Override
    public void handle(ControlPacket message) {

    }

    /**
     * 该客户端是否已经执行了connect的操作，如果未执行过connect操作，第一个解析的数据包必须为connect的包
     */
    public boolean isConnected() {
        return connected;
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

    public void setProtocolLevel(ProtocolLevel protocolLevel) {
        this.protocolLevel = protocolLevel;
    }
}
