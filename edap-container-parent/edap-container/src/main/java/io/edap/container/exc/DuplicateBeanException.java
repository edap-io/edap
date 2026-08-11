package io.edap.container.exc;

public class DuplicateBeanException extends RuntimeException {

    public DuplicateBeanException(String type) {
        super(type);
    }
}
