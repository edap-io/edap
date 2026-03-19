package io.edap.http.server.client.method;

import io.edap.http.server.client.HttpReq;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

public abstract class AbtractMethod implements HttpReq {

    private Map<String, String> headers = new HashMap<>();
    private String url;
    private URI uri;

    public String getHeader(String name) {
        return headers.get(name);
    }

    public HttpReq setHeader(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public String getUrl() {
        return url;
    }

    public URI getUri() {
        return uri;
    }
}
