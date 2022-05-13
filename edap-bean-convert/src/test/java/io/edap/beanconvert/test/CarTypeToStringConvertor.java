package io.edap.beanconvert.test;

import io.edap.beanconvert.Convertor;

public class CarTypeToStringConvertor implements Convertor<CarType, String> {
    @Override
    public String convert(CarType orignal) {
        return orignal.name();
    }
}
