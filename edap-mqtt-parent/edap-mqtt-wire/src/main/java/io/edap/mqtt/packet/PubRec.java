package io.edap.mqtt.packet;

import io.edap.mqtt.ControlPacket;
import io.edap.mqtt.ControlPacketType;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;

import java.util.LinkedHashMap;

public class PubRec extends ControlPacket {

    private int packetIdentifier;

    /**
     * @since mqtt-v5.0
     */
    private int reasonCode;

    /**
     * @since mqtt-v5.0
     */
    private LinkedHashMap<PropertyType, PacketProperty> properties;

    public PubRec(int fixedHeaderByte) {
        super(ControlPacketType.PUBREC, fixedHeaderByte);
    }

    public int getPacketIdentifier() {
        return packetIdentifier;
    }

    public void setPacketIdentifier(int packetIdentifier) {
        this.packetIdentifier = packetIdentifier;
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

    /**
     * @since mqtt-v5.0
     */
    public int getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(int reasonCode) {
        this.reasonCode = reasonCode;
    }
}
