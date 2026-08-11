package io.edap.container.exc;

public class CyclicDependencyException extends RuntimeException {

    public CyclicDependencyException(String message) {
        super(message);
    }
}
