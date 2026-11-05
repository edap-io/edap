package io.edap.http.server.client.method;

import io.edap.http.server.client.HttpBody;
import io.edap.http.server.client.HttpMethod;

import java.net.URI;

public class Post extends AbtractMethod {

    public Post() {}

    public Post(String url) {
        this.url = url;
    }

    public Post(URI uri) {
        this.uri = uri;
    }

    public Post setUrl(String url) {
        this.url = url;
        return this;
    }

    public Post setURI(URI uri) {
        this.uri = uri;
        return this;
    }

    @Override
    public Post setBody(HttpBody body) {
        super.setBody(body);
        return this;
    }


    @Override
    public HttpMethod getMethod() {
        return HttpMethod.POST;
    }
}
