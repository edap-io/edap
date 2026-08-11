package io.edap.container.exc;

public class BeanInitFailedException extends RuntimeException {

    public BeanInitFailedException(String message, Throwable threw) {
        super(message, threw);
    }
}
