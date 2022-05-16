package io.edap.beanconvert.test;

import io.edap.beanconvert.Convertor;
import io.edap.beanconvert.MapperConfig;
import io.edap.beanconvert.MapperInfo;
import io.edap.beanconvert.MapperRegister;
import io.edap.beanconvert.test.dto.CarDTO;
import io.edap.beanconvert.test.vo.Car;
import io.edap.beanconvert.test.vo.SportCar;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestMapperRegister {

    @Test
    public void testAddMapperInfo() {
        MapperRegister mapperRegister = MapperRegister.instance();
        MapperInfo mi = MapperInfo.mi(Car.class, CarDTO.class, null);
        mapperRegister.addMapper(mi);
        MapperInfo mapper = mapperRegister.getMapperInfo(Car.class, CarDTO.class);
        assertEquals(mi.getDestClazz(), mapper.getDestClazz());
        assertEquals(mi.getOrignalClazz(), mapper.getOrignalClazz());
        assertNull(mapper.getConfigList());
    }

    @Test
    public void testAddMapperThreeArgs() {
        MapperRegister mapperRegister = MapperRegister.instance();
        mapperRegister.addMapper(Car.class, CarDTO.class, null);
        MapperInfo mapper = mapperRegister.getMapperInfo(Car.class, CarDTO.class);
        assertEquals(Car.class, mapper.getOrignalClazz());
        assertEquals(CarDTO.class, mapper.getDestClazz());
        assertNull(mapper.getConfigList());

        List<MapperConfig> configList = new ArrayList<>();
        configList.add(MapperConfig.mc("name", "trueName"));
        mapperRegister.addMapper(Car.class, CarDTO.class, configList);
        mapper = mapperRegister.getMapperInfo(Car.class, CarDTO.class);
        assertEquals(Car.class, mapper.getOrignalClazz());
        assertEquals(CarDTO.class, mapper.getDestClazz());
        assertNotNull(mapper.getConfigList());
        assertEquals(mapper.getConfigList().size(), 1);
    }

    @Test
    public void testGetConvertor() {

        MapperRegister mapperRegister = MapperRegister.instance();
        List<MapperConfig> configList = new ArrayList<>();
        mapperRegister.addMapper(Car.class, CarDTO.class, configList);
        Convertor convertor = mapperRegister.getConvertor(Car.class.getName(), "field1");
        assertNull(convertor);

        mapperRegister = MapperRegister.instance();
        configList = new ArrayList<>();
        configList.add(MapperConfig.mc("name", "trueName"));
        mapperRegister.addMapper(Car.class, CarDTO.class, configList);

        convertor = mapperRegister.getConvertor(SportCar.class.getName(), "field1");
        assertNull(convertor);

        convertor = mapperRegister.getConvertor(Car.class.getName(), "field1");
        assertNull(convertor);
    }
}
