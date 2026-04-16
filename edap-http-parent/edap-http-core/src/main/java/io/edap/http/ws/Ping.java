package io.edap.http.ws;

public class Ping extends AbstractFrame {

    public Ping() {
        opcode = PING_OPCODE;
    }

    public Ping(byte[] payload) {
        this.setPayload(payload);
    }
}
