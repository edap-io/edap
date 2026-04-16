package io.edap.http.ws;

public class BinaryFrame extends AbstractFrame {

    public BinaryFrame() {
        opcode = BINARY_OPCODE;
    }

    public BinaryFrame(byte[] payload) {
        this.setPayload(payload);
    }
}
