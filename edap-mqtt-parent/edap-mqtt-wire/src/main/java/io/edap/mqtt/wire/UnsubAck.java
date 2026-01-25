package io.edap.mqtt.wire;

import java.util.List;
import java.util.Map;

public class UnsubAck extends ControlPacket {

    private int packetIdentifier;

    private String reason;

    private Map<String, String> userProperty;

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

    public List<Integer> getReasonCodes() {
        return reasonCodes;
    }

    public void setReasonCodes(List<Integer> reasonCodes) {
        this.reasonCodes = reasonCodes;
    }
}
