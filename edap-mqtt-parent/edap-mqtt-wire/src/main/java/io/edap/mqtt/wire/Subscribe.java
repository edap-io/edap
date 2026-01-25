package io.edap.mqtt.wire;

import java.util.List;
import java.util.Map;

public class Subscribe extends ControlPacket {

    private int packetIdentifier;
    private List<TopicFilter> topicFilterList;

    private int subscriptionIdentifier;
    private Map<String, String> userProperty;


    public Subscribe(int fixedHeaderByte) {
        super(ControlPacketType.SUBSCRIBE, fixedHeaderByte);
    }

    public int getPacketIdentifier() {
        return packetIdentifier;
    }

    public void setPacketIdentifier(int packetIdentifier) {
        this.packetIdentifier = packetIdentifier;
    }

    public List<TopicFilter> getTopicFilterList() {
        return topicFilterList;
    }

    public void setTopicFilterList(List<TopicFilter> topicFilterList) {
        this.topicFilterList = topicFilterList;
    }

    public int getSubscriptionIdentifier() {
        return subscriptionIdentifier;
    }

    public void setSubscriptionIdentifier(int subscriptionIdentifier) {
        this.subscriptionIdentifier = subscriptionIdentifier;
    }

    public Map<String, String> getUserProperty() {
        return userProperty;
    }

    public void setUserProperty(Map<String, String> userProperty) {
        this.userProperty = userProperty;
    }
}
