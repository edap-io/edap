package io.edap.beanconvert.test.dto;

import java.util.List;

public class DemoListFieldDTO {
    private String strField;
    private List<String> listField;

    public String getStrField() {
        return strField;
    }

    public void setStrField(String strField) {
        this.strField = strField;
    }

    public List<String> getListField() {
        return listField;
    }

    public void setListField(List<String> listField) {
        this.listField = listField;
    }
}
