package io.edap.http.ws;

public abstract class AbstractFrame {

    public static final byte TEXT_OPCODE   = 0x01;
    public static final byte BINARY_OPCODE = 0x02;
    public static final byte CLOSE_OPCODE  = 0x08;
    public static final byte PING_OPCODE   = 0x09;
    public static final byte PONG_OPCODE   = 0x0a;

    private boolean fin;
    private byte rsv;
    protected byte opcode;
    private boolean masked;
    private long payloadLength;
    private byte[] maskingKey;
    private byte[] payload;

    public boolean isFin() {
        return fin;
    }

    public void setFin(boolean fin) {
        this.fin = fin;
    }

    public byte getRsv() {
        return rsv;
    }

    public void setRsv(byte rsv) {
        this.rsv = rsv;
    }

    public byte getOpcode() {
        return opcode;
    }

    public void setOpcode(byte opcode) {
        this.opcode = opcode;
    }

    public boolean isMasked() {
        return masked;
    }

    public void setMasked(boolean masked) {
        this.masked = masked;
    }

    public long getPayloadLength() {
        return payloadLength;
    }

    public void setPayloadLength(long payloadLength) {
        this.payloadLength = payloadLength;
    }

    public byte[] getMaskingKey() {
        return maskingKey;
    }

    public void setMaskingKey(byte[] maskingKey) {
        this.maskingKey = maskingKey;
    }

    public byte[] getPayload() {
        return payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
        this.payloadLength = payload.length;
    }
}
