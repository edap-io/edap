package io.edap.mqtt.wire;

import java.util.Map;

public class PubRec extends ControlPacket {

    private int packetIdentifier;
    private int reasonCode;
    private String reason;
    private Map<String, String> userProperty;

    public PubRec(int fixedHeaderByte) {
        super(ControlPacketType.PUBREC, fixedHeaderByte);
    }

    public int getPacketIdentifier() {
        return packetIdentifier;
    }

    public void setPacketIdentifier(int packetIdentifier) {
        this.packetIdentifier = packetIdentifier;
    }

    public int getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(int reasonCode) {
        this.reasonCode = reasonCode;
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
