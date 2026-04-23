package io.edap.http;

import io.edap.http.ws.AbstractFrame;
import io.edap.http.ws.BinaryFrame;
import io.edap.http.ws.TextFrame;

import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Set;

public interface WSConnection {

    HttpRequest getHttpRequest();

    void setSessionContext(String key, Object value);

    Object getSessionContext(String key);

    void clearSessionContext();

    Set<String> getSessionContextKeys();

    SocketChannel getSocketChannel();

    void sendFrame(AbstractFrame frame);

    default void sendText(String text) {
        sendFrame(new TextFrame(text.getBytes(StandardCharsets.UTF_8)));
    }

    default void sendBinary(byte[] bytes) {
        sendFrame(new BinaryFrame(bytes));
    }
}
