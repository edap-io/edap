package io.edap.data.jdbc.jdbc.test.entity;

import io.edap.data.jdbc.annotation.Column;

public class DemoNoIdFieldHasAnn {

    @Column(name = "order_id")
    private long orderId;

    private String name;

    public long getOrderId() {
        return orderId;
    }

    public void setOrderId(long orderId) {
        this.orderId = orderId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
