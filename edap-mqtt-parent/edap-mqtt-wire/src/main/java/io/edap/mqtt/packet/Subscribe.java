package io.edap.mqtt.packet;

import io.edap.mqtt.ControlPacket;
import io.edap.mqtt.ControlPacketType;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;

import java.util.LinkedHashMap;
import java.util.List;

public class Subscribe extends ControlPacket {

    private int packetIdentifier;
    private List<TopicFilter> topicFilterList;

    /**
     * @since mqtt-v5.0
     */
    private LinkedHashMap<PropertyType, PacketProperty> properties;


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

    /**
     * @since mqtt-v5.0
     */
    public LinkedHashMap<PropertyType, PacketProperty> getProperties() {
        return properties;
    }

    public void setProperties(LinkedHashMap<PropertyType, PacketProperty> properties) {
        this.properties = properties;
    }
}
