package io.edap.http.ws;

public class CloseFrame extends AbstractFrame {

    public CloseFrame() {
        opcode = CLOSE_OPCODE;
    }

    public CloseFrame(byte[] payload) {
        this.setPayload(payload);
    }
}
