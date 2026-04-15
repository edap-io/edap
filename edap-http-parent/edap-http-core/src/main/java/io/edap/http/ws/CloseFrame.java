package io.edap.http.ws;

public class CloseFrame extends AbstractFrame {

    public CloseFrame() {

    }

    public CloseFrame(byte[] payload) {
        this.setPayload(payload);
    }
}
