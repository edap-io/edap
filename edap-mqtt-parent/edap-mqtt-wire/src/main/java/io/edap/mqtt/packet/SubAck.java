package io.edap.mqtt.packet;

import io.edap.mqtt.ControlPacket;
import io.edap.mqtt.ControlPacketType;
import io.edap.mqtt.PacketProperty;
import io.edap.mqtt.PropertyType;

import java.util.LinkedHashMap;
import java.util.List;

import static io.edap.mqtt.ControlPacketType.SUBACK_VALUE;

public class SubAck extends ControlPacket {

    private int packetIdentifier;

    private List<Integer> respCodes;

    /**
     * @since mqtt-v5.0
     */
    private LinkedHashMap<PropertyType, PacketProperty> properties;

    public SubAck() {
        super(ControlPacketType.SUBACK, SUBACK_VALUE);
    }

    public SubAck(int fixedHeaderByte) {
        super(ControlPacketType.SUBACK, fixedHeaderByte);
    }

    public int getPacketIdentifier() {
        return packetIdentifier;
    }

    public void setPacketIdentifier(int packetIdentifier) {
        this.packetIdentifier = packetIdentifier;
    }

    public List<Integer> getRespCodes() {
        return respCodes;
    }

    public void setRespCodes(List<Integer> respCodes) {
        this.respCodes = respCodes;
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
