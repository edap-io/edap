package io.edap.mqtt.wire;

import java.util.Map;

public class Disconnect extends ControlPacket {

    private int reasonCode;
    private int sessionExpiryInterval;
    private String reason;
    private Map<String, String> userProperty;
    private String serverReference;

    public Disconnect(int fixedHeaderByte) {
        super(ControlPacketType.DISCONNECT, fixedHeaderByte);
    }

    public int getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(int reasonCode) {
        this.reasonCode = reasonCode;
    }

    public int getSessionExpiryInterval() {
        return sessionExpiryInterval;
    }

    public void setSessionExpiryInterval(int sessionExpiryInterval) {
        this.sessionExpiryInterval = sessionExpiryInterval;
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

    public String getServerReference() {
        return serverReference;
    }

    public void setServerReference(String serverReference) {
        this.serverReference = serverReference;
    }
}
