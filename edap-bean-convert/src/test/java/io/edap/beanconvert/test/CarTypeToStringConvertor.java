package io.edap.beanconvert.test;

import io.edap.beanconvert.AbstractConvertor;
import io.edap.beanconvert.Convertor;

public class CarTypeToStringConvertor extends AbstractConvertor<CarType, String> implements Convertor<CarType, String> {

    public CarTypeToStringConvertor() {
        super(CarType.class, String.class);
    }

    @Override
    public String convert(CarType orignal) {
        return orignal.name();
    }
}
