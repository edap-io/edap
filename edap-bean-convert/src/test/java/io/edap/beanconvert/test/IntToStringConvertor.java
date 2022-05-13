package io.edap.beanconvert.test;

import io.edap.beanconvert.Convertor;

public class IntToStringConvertor implements Convertor<Integer, String> {
    @Override
    public String convert(Integer orignal) {
        return String.valueOf(orignal);
    }
}
