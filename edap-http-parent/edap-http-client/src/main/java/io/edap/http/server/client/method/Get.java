package io.edap.http.server.client.method;

import io.edap.http.server.client.HttpBody;
import io.edap.http.server.client.HttpMethod;

import java.net.URI;

public class Get extends AbtractMethod {

    public Get() {}

    public Get(String url) {
        this.url = url;
    }

    public Get(URI uri) {
        this.uri = uri;
    }

    @Override
    public HttpMethod getMethod() {
        return HttpMethod.GET;
    }

    @Override
    public HttpBody getBody() {
        return null;
    }
}
