package io.edap.beanconvert.test;

import io.edap.beanconvert.AbstractConvertor;
import io.edap.beanconvert.Convertor;
import io.edap.beanconvert.ConvertorRegister;
import io.edap.beanconvert.test.dto.CarApiDTO;
import io.edap.beanconvert.test.dto.CarDTO;
import io.edap.beanconvert.test.vo.Car;
import org.junit.jupiter.api.Test;

import static io.edap.beanconvert.MapperConfig.mc;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestConvertorRegister {

    @Test
    public void testCreateListConvertor() {
        ConvertorRegister register = ConvertorRegister.instance();
        String convertorName = register.createListConvert(Car.class, CarDTO.class);

        String convertorNameNew = register.createListConvert(Car.class, CarDTO.class);
        assertEquals(convertorName, convertorNameNew);
    }

    @Test
    public void testGetConvertorWithMc() {
        ConvertorRegister register = ConvertorRegister.instance();
        AbstractConvertor<Car, CarDTO> convertor = register.getConvertor(Car.class, CarDTO.class,
                mc("numberOfSeats","seatCount"));
        assertNotNull(convertor);
    }

    @Test
    public void testGenerateError() {
        ConvertorRegister register = ConvertorRegister.instance();
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> {
                    register.getConvertor(Car.class, CarApiDTO.class);
                });
        assertTrue(thrown.getMessage().contains("generateConvertor "));

    }
}
