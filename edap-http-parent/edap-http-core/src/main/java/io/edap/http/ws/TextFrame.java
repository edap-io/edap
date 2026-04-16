package io.edap.http.ws;

import java.nio.charset.StandardCharsets;

public class TextFrame extends AbstractFrame {

    public String message;

    public TextFrame() {
        opcode = TEXT_OPCODE;
    }

    public TextFrame(byte[] payload) {
        opcode = TEXT_OPCODE;
        this.setPayload(payload);
    }

    public String getMessage() {
        if (message == null && getPayload() != null) {
            message = new String(getPayload(), StandardCharsets.UTF_8);
        }
        return message;
    }
}
