package io.edap.container.test;

import com.estylr.api.v1.common.StylistsSortBy;

public class HelloReq {

    private long id;

    private String name;

    private boolean isTop;

    private StylistsSortBy sortBy;


    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isTop() {
        return isTop;
    }

    public void setTop(boolean top) {
        isTop = top;
    }

    public StylistsSortBy getSortBy() {
        return sortBy;
    }

    public void setSortBy(StylistsSortBy sortBy) {
        this.sortBy = sortBy;
    }
}
