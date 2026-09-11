package io.edap.http.server;

import io.edap.http.HttpHandler;

public class UrlMappingItem {
    private String path;
    private String method;
    private HttpHandler handler;

    public UrlMappingItem() {}

    public UrlMappingItem(String path, String method, HttpHandler handler) {
        this.path = path;
        this.method = method;
        this.handler = handler;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public HttpHandler getHandler() {
        return handler;
    }

    public void setHandler(HttpHandler handler) {
        this.handler = handler;
    }
}
