package io.edap.data.jdbc.test.entity;

import io.edap.data.annotation.Id;

public class DemoLongObjId {

    @Id
    //@GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long createTime;
    private Long localDateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
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
