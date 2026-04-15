package io.edap.http.ws;

public abstract class AbstractFrame {

    private boolean fin;
    private byte rsv1;
    private byte rsv2;
    private byte rsv3;
    private byte opcode;
    private boolean masked;
    private long payloadLength;
    private byte[] maskingKey;
    private byte[] payload;
}
