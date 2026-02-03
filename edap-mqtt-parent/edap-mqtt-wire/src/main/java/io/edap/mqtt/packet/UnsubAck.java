package io.edap.mqtt.packet;

import io.edap.mqtt.ControlPacketType;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;

import java.util.LinkedHashMap;
import java.util.List;

public class UnsubAck extends ControlPacket {

    private int packetIdentifier;

    /**
     * @since mqtt-v5.0
     */
    private LinkedHashMap<PropertyType, PacketProperty> properties;
    /**
     * @since v5.0
     */
    private List<Integer> reasonCodes;

    public UnsubAck(int fixedHeaderByte) {
        super(ControlPacketType.UNSUBACK, fixedHeaderByte);
    }

    public int getPacketIdentifier() {
        return packetIdentifier;
    }

    public void setPacketIdentifier(int packetIdentifier) {
        this.packetIdentifier = packetIdentifier;
    }

    public List<Integer> getReasonCodes() {
        return reasonCodes;
    }

    public void setReasonCodes(List<Integer> reasonCodes) {
        this.reasonCodes = reasonCodes;
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
