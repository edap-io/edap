package io.edap.data.jdbc.test.entity;


import io.edap.data.annotation.Column;
import io.edap.data.annotation.GeneratedValue;
import io.edap.data.annotation.Id;
import io.edap.data.annotation.Table;

@Table(name = "dao_demo")
public class DaoUtilDemo {

    @GeneratedValue
    private long id;

    @Column(name = "last_name")
    private String name;

    private int count;

    @Id
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

    @Column(name = "buy_count")
    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }
}
