package io.edap.container.exc;

public class NoSuitableConstructorException extends RuntimeException {

    public NoSuitableConstructorException(Class<?> requestType) {
        super(requestType.getName());
    }
}
