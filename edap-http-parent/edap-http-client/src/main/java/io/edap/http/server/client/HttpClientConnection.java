package io.edap.http.server.client;

import javax.net.ssl.SSLEngine;

public class HttpClientConnection {
    boolean sync;
    boolean ssl;
    SSLEngine sslEngine;
    boolean handshaked;
}
