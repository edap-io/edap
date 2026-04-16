package io.edap.http;

import io.edap.http.ws.AbstractFrame;
import io.edap.http.ws.BinaryFrame;
import io.edap.http.ws.TextFrame;

import java.nio.charset.StandardCharsets;

public interface WSConnection {

    void sendFrame(AbstractFrame frame);

    default void sendText(String text) {
        sendFrame(new TextFrame(text.getBytes(StandardCharsets.UTF_8)));
    }

    default void sendBinary(byte[] bytes) {
        sendFrame(new BinaryFrame(bytes));
    }
}
