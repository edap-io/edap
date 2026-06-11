package io.edap.data.jdbc.model;

public class TypeConvertorValue {
    private String jdbcPlaceholder;
    private Object value;

    public String getJdbcPlaceholder() {
        return jdbcPlaceholder;
    }

    public void setJdbcPlaceholder(String jdbcPlaceholder) {
        this.jdbcPlaceholder = jdbcPlaceholder;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }
}
