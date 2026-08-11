package io.edap.container.exc;

public class LifecycleStartFailedException extends RuntimeException {

    public LifecycleStartFailedException(String message, Throwable threw) {
        super(message, threw);
    }
}
