package io.edap.container.ws;

public interface WSServiceMsgHandler<T> {

    T handle(T msg);
}
