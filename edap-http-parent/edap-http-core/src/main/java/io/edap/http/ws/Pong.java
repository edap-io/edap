package io.edap.http.ws;

public class Pong extends AbstractFrame {

    public Pong() {
    }

    public Pong(byte[] payload) {
        this.setPayload(payload);
    }
}
