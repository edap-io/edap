package io.edap.mqtt.broker.po;

import io.edap.mqtt.QoSLevel;
import io.edap.mqtt.broker.MqttBrokerSession;

public class SubscribeInfo {
    private String            topicFilter;
    private QoSLevel          qoSLevel;
    private MqttBrokerSession session;
    private String            clientId;

    public String getTopicFilter() {
        return topicFilter;
    }

    public void setTopicFilter(String topicFilter) {
        this.topicFilter = topicFilter;
    }

    public QoSLevel getQoSLevel() {
        return qoSLevel;
    }

    public void setQoSLevel(QoSLevel qoSLevel) {
        this.qoSLevel = qoSLevel;
    }

    public MqttBrokerSession getSession() {
        return session;
    }

    public void setSession(MqttBrokerSession session) {
        this.session = session;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }
}
