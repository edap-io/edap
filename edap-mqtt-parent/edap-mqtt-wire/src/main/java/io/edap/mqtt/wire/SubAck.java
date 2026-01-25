package io.edap.mqtt.wire;

import java.util.List;
import java.util.Map;

public class SubAck extends ControlPacket {

    private int packetIdentifier;

    private List<Integer> respCodes;

    private String reason;
    private Map<String, String> userProperty;

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

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Map<String, String> getUserProperty() {
        return userProperty;
    }

    public void setUserProperty(Map<String, String> userProperty) {
        this.userProperty = userProperty;
    }
}
