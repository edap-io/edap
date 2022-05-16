package io.edap.beanconvert.test.dto;

import io.edap.beanconvert.test.vo.Car;
import io.edap.beanconvert.test.vo.SportCar;

import java.util.List;

public class DemoListExtendFieldDTO {
    private String strField;
    private List<Car> listField;

    public String getStrField() {
        return strField;
    }

    public void setStrField(String strField) {
        this.strField = strField;
    }

    public List<Car> getListField() {
        return listField;
    }

    public void setListField(List<Car> listField) {
        this.listField = listField;
    }
}
