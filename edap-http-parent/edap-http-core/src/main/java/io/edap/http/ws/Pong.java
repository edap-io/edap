package io.edap.http.ws;

public class Pong extends AbstractFrame {

    public Pong() {
        opcode = PONG_OPCODE;
    }

    public Pong(byte[] payload) {
        this.setPayload(payload);
    }
}
