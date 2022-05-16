package io.edap.beanconvert.test.vo;

import java.util.List;

public class DemoListExtendThreeLevelField {

    private String strField;
    private List<BmwCar> listField;

    public String getStrField() {
        return strField;
    }

    public void setStrField(String strField) {
        this.strField = strField;
    }

    public List<BmwCar> getListField() {
        return listField;
    }

    public void setListField(List<BmwCar> listField) {
        this.listField = listField;
    }
}
