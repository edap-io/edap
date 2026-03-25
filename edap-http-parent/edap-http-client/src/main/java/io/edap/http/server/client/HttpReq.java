package io.edap.http.server.client;

import java.net.URI;

public interface HttpReq {
    URI getUri();
    String getUrl();
    HttpMethod getMethod();
    String getHeader(String name);
    HttpReq setHeader(String name, String value);
    HttpBody getBody();
    HttpReq setBody(HttpBody body);
}
