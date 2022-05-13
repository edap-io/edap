package io.edap.beanconvert.test;

import io.edap.beanconvert.MapperConfig;
import io.edap.beanconvert.MapperInfo;
import io.edap.beanconvert.test.dto.CarDTO;
import io.edap.beanconvert.test.vo.Car;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.edap.beanconvert.MapperConfig.mc;
import static org.junit.jupiter.api.Assertions.*;

public class TestMapperInfo {

    @Test
    public void testMi() {
        MapperInfo mi = MapperInfo.mi(Car.class, CarDTO.class, null);
        assertEquals(mi.getOrignalClazz(), Car.class);
        assertEquals(mi.getDestClazz(), CarDTO.class);
        assertNull(mi.getConfigList());

        mi = MapperInfo.mi(Car.class, CarDTO.class, mc("seatCount", "seat_count"));
        assertNotNull(mi.getConfigList());
        assertEquals(mi.getConfigList().size(), 1);
    }

    @Test
    public void testMiInit() {
        List<MapperConfig> mcs = new ArrayList<>();
        mcs.add(mc("seatCount", "seat_count"));
        MapperInfo mi = new MapperInfo();
        mi.setOrignalClazz(Car.class);
        mi.setDestClazz(CarDTO.class);
        mi.setConfigList(mcs);
        assertEquals(mi.getOrignalClazz(), Car.class);
        assertEquals(mi.getDestClazz(), CarDTO.class);
        assertNotNull(mi.getConfigList());
        assertEquals(mi.getConfigList().size(), 1);
    }
}
