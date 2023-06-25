package io.edap.data.jdbc.test.entity;

import io.edap.data.annotation.Column;
import io.edap.data.annotation.Id;

public class DemoIntId {

    @Id
    //@GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    private long createTime;
    private Long localDateTime;

    public int getId() {
        return id;
    }

    public void setId(int id) {
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
