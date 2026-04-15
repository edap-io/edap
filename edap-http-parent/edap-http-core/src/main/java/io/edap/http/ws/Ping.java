package io.edap.http.ws;

public class Ping extends AbstractFrame {

    public Ping() {
    }

    public Ping(byte[] payload) {
        this.setPayload(payload);
    }
}
