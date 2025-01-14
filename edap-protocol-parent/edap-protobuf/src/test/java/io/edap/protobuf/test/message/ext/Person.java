package io.edap.protobuf.test.message.ext;

import java.util.Date;

public class Person {

    private int age;
    private String name;
    /**
     * transient字段不会序列化
     */
    private transient String sensitiveInformation;
    private Date birthDay;

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    /**
     * transient字段不会序列化
     */
    public String getSensitiveInformation() {
        return sensitiveInformation;
    }

    public void setSensitiveInformation(String sensitiveInformation) {
        this.sensitiveInformation = sensitiveInformation;
    }

    public Date getBirthDay() {
        return birthDay;
    }

    public void setBirthDay(Date birthDay) {
        this.birthDay = birthDay;
    }
}
