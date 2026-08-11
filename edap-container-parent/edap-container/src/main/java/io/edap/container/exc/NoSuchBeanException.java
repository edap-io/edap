package io.edap.container.exc;

public class NoSuchBeanException extends RuntimeException {

    public NoSuchBeanException(String message) {
        super(message);
    }

    public NoSuchBeanException(String message, Throwable threw) {
        super(message, threw);
    }

    public NoSuchBeanException(Class<?> requestType) {
        super(requestType.getName());
    }
}
