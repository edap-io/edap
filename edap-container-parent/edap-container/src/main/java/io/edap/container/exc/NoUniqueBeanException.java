package io.edap.container.exc;

import io.edap.container.BeanWrap;

import java.util.List;

public class NoUniqueBeanException extends RuntimeException {

    public NoUniqueBeanException(String message, List<BeanWrap> beans) {
        super(message);
    }

    public NoUniqueBeanException(String message, Throwable threw) {
        super(message, threw);
    }

    public NoUniqueBeanException(Class<?> requestType, List<BeanWrap> beans) {
        super(requestType.getName());
    }
}
