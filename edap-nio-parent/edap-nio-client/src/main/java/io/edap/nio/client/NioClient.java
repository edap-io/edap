package io.edap.nio.client;

import java.io.IOException;
import java.nio.channels.SocketChannel;

public class NioClient {

    public static void main(String[] args) throws IOException {
        SocketChannel channel = SocketChannel.open();
    }
}
