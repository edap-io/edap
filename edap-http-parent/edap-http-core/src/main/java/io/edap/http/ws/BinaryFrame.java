package io.edap.http.ws;

public class BinaryFrame extends AbstractFrame {

    public BinaryFrame() {}

    public BinaryFrame(byte[] payload) {
        this.setPayload(payload);
    }
}
