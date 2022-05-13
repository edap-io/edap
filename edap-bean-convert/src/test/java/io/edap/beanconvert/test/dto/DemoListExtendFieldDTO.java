package io.edap.beanconvert.test.dto;

import io.edap.beanconvert.test.vo.Car;
import io.edap.beanconvert.test.vo.SportCar;

import java.util.List;

public class DemoListExtendFieldDTO {
    private String strField;
    private List<CarDTO> listField;

    public String getStrField() {
        return strField;
    }

    public void setStrField(String strField) {
        this.strField = strField;
    }

    public List<CarDTO> getListField() {
        return listField;
    }

    public void setListField(List<CarDTO> listField) {
        this.listField = listField;
    }
}
