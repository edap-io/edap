package io.edap.http.server.client;

import java.net.URI;
import java.net.URL;

public interface HttpReq {
    URI getUri();
    String getUrl();
    String getHeader(String name);
    HttpReq setHeader(String name, String value);
    <T> T getBody();
}
