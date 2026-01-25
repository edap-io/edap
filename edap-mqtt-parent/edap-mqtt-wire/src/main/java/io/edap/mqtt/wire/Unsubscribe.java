package io.edap.mqtt.wire;

import java.util.List;
import java.util.Map;

public class Unsubscribe extends ControlPacket {

    private int packetIdentifier;
    private List<String> topicFilterList;

    private Map<String, String> userProperty;

    public Unsubscribe(int fixedHeaderByte) {
        super(ControlPacketType.UNSUBSCRIBE, fixedHeaderByte);
    }

    public int getPacketIdentifier() {
        return packetIdentifier;
    }

    public void setPacketIdentifier(int packetIdentifier) {
        this.packetIdentifier = packetIdentifier;
    }

    public List<String> getTopicFilterList() {
        return topicFilterList;
    }

    public void setTopicFilterList(List<String> topicFilterList) {
        this.topicFilterList = topicFilterList;
    }

    public Map<String, String> getUserProperty() {
        return userProperty;
    }

    public void setUserProperty(Map<String, String> userProperty) {
        this.userProperty = userProperty;
    }
}
