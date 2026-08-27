package io.edap.http.server.client;

public interface HttpResp {

    int code();

    HttpBody body();
}
