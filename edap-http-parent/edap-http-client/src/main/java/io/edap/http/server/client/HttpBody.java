package io.edap.http.server.client;

import java.io.OutputStream;

public interface HttpBody {

    void writeTo(OutputStream out);
}
