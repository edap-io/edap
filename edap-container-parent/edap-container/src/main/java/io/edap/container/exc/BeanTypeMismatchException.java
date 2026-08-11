package io.edap.container.exc;

public class BeanTypeMismatchException extends RuntimeException {

    public BeanTypeMismatchException(String message, Class<?> requiredType, Class<?> actualType) {
        super(message);
    }
}
