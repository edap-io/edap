package io.edap.http.ws;

import java.nio.charset.StandardCharsets;

public class TextFrame extends AbstractFrame {

    public String message;

    public TextFrame() {}

    public TextFrame(byte[] payload) {
        this.setPayload(payload);
    }

    public String getMessage() {
        if (message == null && getPayload() != null) {
            message = new String(getPayload(), StandardCharsets.UTF_8);
        }
        return message;
    }
}
