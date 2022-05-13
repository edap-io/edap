package io.edap.beanconvert.test;

import io.edap.beanconvert.Convertor;
import io.edap.beanconvert.ConvertorRegister;
import io.edap.beanconvert.test.dto.CarDTO;
import io.edap.beanconvert.test.vo.SportCar;

public class T {
    public static void main(String[] args) {

        Convertor<SportCar, CarDTO> convertor = ConvertorRegister.instance()
                .getConvertor(SportCar.class, CarDTO.class);
        System.out.println("convertor=" + convertor);
    }
}
