package io.edap.data.jdbc.jdbc.test.entity;

import io.edap.data.jdbc.annotation.Column;

public class DemoStringId {

    @Column(name = "id")
    //@GeneratedValue(strategy = GenerationType.IDENTITY)
    private String id;
    private long createTime;
    private Long localDateTime;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public Long getLocalDateTime() {
        return localDateTime;
    }

    public void setLocalDateTime(Long localDateTime) {
        this.localDateTime = localDateTime;
    }
}
