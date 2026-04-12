package io.edap.data.jdbc.jdbc.test.entity;

import io.edap.data.jdbc.annotation.Id;

public class DemoLongId {

    @Id
    //@GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;
    private long createTime;
    private Long localDateTime;

    public long getId() {
        return id;
    }

    public void setId(long id) {
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
