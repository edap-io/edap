package io.edap.protobuf.test.message.ext;

import java.util.List;

/**
 * @author : luysh@yonyou.com
 * @date : 2020/9/28
 */
public class ComplexModel<T> {

    private Integer id;
    private Person person;
    private List<T> points;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Person getPerson() {
        return person;
    }

    public void setPerson(Person person) {
        this.person = person;
    }

    public List<T> getPoints() {
        return points;
    }

    public void setPoints(List<T> points) {
        this.points = points;
    }
}
