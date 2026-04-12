package io.edap.data.jdbc.jdbc.test.entity;

public class DemoNoIdField {

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
