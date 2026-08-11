package io.edap.container.exc;

public class BeanInstantiationException extends RuntimeException {

    public BeanInstantiationException(String className, Throwable threw) {
        super(className, threw);
    }
}
