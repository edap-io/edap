package io.edap.data.jdbc.jdbc.test.entity;

import io.edap.data.jdbc.annotation.GeneratedValue;

public class DemoIdMethodGeneratedValue {

    private long id;

    @GeneratedValue
    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }
}
