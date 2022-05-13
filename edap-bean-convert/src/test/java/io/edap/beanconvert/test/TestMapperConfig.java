package io.edap.beanconvert.test;

import io.edap.beanconvert.MapperConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestMapperConfig {

    @Test
    public void testMcTwoArgs() {
        MapperConfig mc = MapperConfig.mc("seatCount", "seat_count");
        assertEquals(mc.getOriginalName(), "seatCount");
        assertEquals(mc.getDestName(), "seat_count");
        assertNull(mc.getConvertor());
    }

    @Test
    public void testMcThreeArgs() {
        IntToStringConvertor convertor = new IntToStringConvertor();
        MapperConfig mc = MapperConfig.mc("seatCount", "seat_count", convertor);
        assertEquals(mc.getOriginalName(), "seatCount");
        assertEquals(mc.getDestName(), "seat_count");
        assertNotNull(mc.getConvertor());
        assertEquals(mc.getConvertor().getClass().getName(), IntToStringConvertor.class.getName());
    }

    @Test
    public void testMcFourArgs() {
        IntToStringConvertor convertor = new IntToStringConvertor();
        List<MapperConfig> configList = new ArrayList<>();
        configList.add(MapperConfig.mc("name", "trueName"));
        MapperConfig mc = MapperConfig.mc("seatCount", "seat_count", convertor, configList);
        assertEquals(mc.getOriginalName(), "seatCount");
        assertEquals(mc.getDestName(), "seat_count");
        assertNotNull(mc.getConvertor());
        assertEquals(mc.getConvertor().getClass().getName(), IntToStringConvertor.class.getName());
        assertNotNull(mc.getMappperConfigs());
        assertEquals(mc.getMappperConfigs().size(), 1);
    }

    @Test
    public void testMcInit() {
        IntToStringConvertor convertor = new IntToStringConvertor();
        MapperConfig mc = new MapperConfig();
        mc.setOriginalName("seatCount");
        mc.setDestName("seat_count");
        mc.setConvertor(convertor);
        assertEquals(mc.getOriginalName(), "seatCount");
        assertEquals(mc.getDestName(), "seat_count");
        assertNotNull(mc.getConvertor());
        assertEquals(mc.getConvertor().getClass().getName(), IntToStringConvertor.class.getName());
        assertNull(mc.getMappperConfigs());

        List<MapperConfig> configList = new ArrayList<>();
        configList.add(MapperConfig.mc("name", "trueName"));
        mc.setMappperConfigs(configList);
        assertNotNull(mc.getMappperConfigs());
        assertEquals(mc.getMappperConfigs().size(), 1);
    }
}
