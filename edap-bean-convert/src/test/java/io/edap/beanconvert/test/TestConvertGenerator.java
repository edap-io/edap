package io.edap.beanconvert.test;

import io.edap.beanconvert.*;
import io.edap.beanconvert.test.dto.*;
import io.edap.beanconvert.test.vo.*;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestConvertGenerator {

    @Test
    public void testBasicGenerator() {
        MapperRegister register = MapperRegister.instance();
        register.addMapper(MapperInfo.mi(Car.class, CarDTO.class,
                MapperConfig.mc("numberOfSeats","seatCount")));
        Convertor<Car, CarDTO> convertor = ConvertorRegister.instance().getConvertor(Car.class, CarDTO.class);
        Car car = new Car();
        car.setMake("bmw");
        car.setType(CarType.SEDAN);
        car.setNumberOfSeats(5);

        CarDTO carDto = convertor.convert(car);
        assertEquals(carDto.getType(), "SEDAN");
        assertEquals(carDto.getMake(), "bmw");
        assertEquals(carDto.getSeatCount(), 5);

    }

    @Test
    public void testFieldConvertorGenerator() {
        MapperRegister register = MapperRegister.instance();
        CarTypeToStringConvertor fieldConvertor = new CarTypeToStringConvertor();
        register.addMapper(MapperInfo.mi(Car.class, CarDTO.class,
                MapperConfig.mc("numberOfSeats","seatCount"),
                MapperConfig.mc("type", "type", fieldConvertor)));
        Convertor<Car, CarDTO> convertor = ConvertorRegister.instance().getConvertor(Car.class, CarDTO.class);
        Car car = new Car();
        car.setMake("bmw");
        car.setType(CarType.SEDAN);
        car.setNumberOfSeats(5);

        CarDTO carDto = convertor.convert(car);
        assertEquals(carDto.getType(), "SEDAN");
        assertEquals(carDto.getMake(), "bmw");
        assertEquals(carDto.getSeatCount(), 5);

    }

    @Test
    public void testFieldConfigGenerator() {
        MapperRegister register = MapperRegister.instance();
        CarTypeToStringConvertor fieldConvertor = new CarTypeToStringConvertor();
        register.addMapper(MapperInfo.mi(Car.class, CarDTO.class,
                MapperConfig.mc("numberOfSeats","seatCount"),
                MapperConfig.mc("type", "type")));
        Convertor<Car, CarDTO> convertor = ConvertorRegister.instance().getConvertor(Car.class, CarDTO.class);
        Car car = new Car();
        car.setMake("bmw");
        car.setType(CarType.SEDAN);
        car.setNumberOfSeats(5);

        CarDTO carDto = convertor.convert(car);
        assertEquals(carDto.getType(), "SEDAN");
        assertEquals(carDto.getMake(), "bmw");
        assertEquals(carDto.getSeatCount(), 5);

    }

    @Test
    public void testChildClassGenerator() {
        MapperRegister register = MapperRegister.instance();
        register.addMapper(MapperInfo.mi(Car.class, CarDTO.class,
                MapperConfig.mc("numberOfSeats","seatCount"),
                MapperConfig.mc("type", "type")));
        Convertor<Car, CarDTO> convertor = ConvertorRegister.instance().getConvertor(Car.class, CarDTO.class);
        SportCar car = new SportCar();
        car.setMake("bmw");
        car.setType(CarType.SEDAN);
        car.setNumberOfSeats(5);

        CarDTO carDto = convertor.convert(car);
        assertEquals(carDto.getType(), "SEDAN");
        assertEquals(carDto.getMake(), "bmw");
        assertEquals(carDto.getSeatCount(), 5);
    }

    @Test
    public void testNoGetSet() throws NoSuchFieldException, IllegalAccessException {
        Convertor<DemoNoGetSet, DemoNoGetSetDTO> convertor = ConvertorRegister.instance().getConvertor(DemoNoGetSet.class, DemoNoGetSetDTO.class);
        DemoNoGetSet demo = new DemoNoGetSet();
        Field f1;
        Field f2;
        Field f3;
        Field f4;
        Field f5;
        Field f6;
        Field f7;
        Field f8;
        f1 = DemoNoGetSet.class.getDeclaredField("field1");
        f2 = DemoNoGetSet.class.getDeclaredField("field2");
        f3 = DemoNoGetSet.class.getDeclaredField("field3");
        f4 = DemoNoGetSet.class.getDeclaredField("field4");
        f5 = DemoNoGetSet.class.getDeclaredField("field5");
        f6 = DemoNoGetSet.class.getDeclaredField("field6");
        f7 = DemoNoGetSet.class.getDeclaredField("field7");
        f8 = DemoNoGetSet.class.getDeclaredField("field8");
        f1.setAccessible(true);
        f2.setAccessible(true);
        f3.setAccessible(true);
        f4.setAccessible(true);
        f5.setAccessible(true);
        f6.setAccessible(true);

        f1.set(demo, 1);
        f2.set(demo, 2);
        f3.set(demo, 3.1415926f);
        f4.set(demo, (short)4);
        f5.set(demo, 6.123456d);
        f6.set(demo, true);

        Field ff1;
        Field ff2;
        Field ff3;
        Field ff4;
        Field ff5;
        Field ff6;
        Field ff7;
        Field ff8;
        ff1 = DemoNoGetSetDTO.class.getDeclaredField("field1");
        ff2 = DemoNoGetSetDTO.class.getDeclaredField("field2");
        ff3 = DemoNoGetSetDTO.class.getDeclaredField("field3");
        ff4 = DemoNoGetSetDTO.class.getDeclaredField("field4");
        ff5 = DemoNoGetSetDTO.class.getDeclaredField("field5");
        ff6 = DemoNoGetSetDTO.class.getDeclaredField("field6");
        ff7 = DemoNoGetSetDTO.class.getDeclaredField("field7");
        ff8 = DemoNoGetSetDTO.class.getDeclaredField("field8");
        DemoNoGetSetDTO demoDto = convertor.convert(demo);
        ff1.setAccessible(true);
        ff2.setAccessible(true);
        ff3.setAccessible(true);
        ff4.setAccessible(true);
        ff5.setAccessible(true);
        ff6.setAccessible(true);
        ff7.setAccessible(true);
        ff8.setAccessible(true);
        assertEquals(ff1.get(demoDto), f1.get(demo));
        assertEquals(ff2.get(demoDto), f2.get(demo));
        assertEquals(ff3.get(demoDto), f3.get(demo));
        assertEquals(ff4.get(demoDto), f4.get(demo));
        assertEquals(ff5.get(demoDto), f5.get(demo));
        assertEquals(ff6.get(demoDto), f6.get(demo));
        assertEquals(ff7.get(demoDto), f7.get(demo));
        assertEquals(ff8.get(demoDto), f8.get(demo));
    }

    @Test
    public void testDtoHasSet() {
        Convertor<DemoBoxedNoGetSet, DemoDTO> convertor = ConvertorRegister.instance().getConvertor(DemoBoxedNoGetSet.class, DemoDTO.class);
        DemoBoxedNoGetSet demo = new DemoBoxedNoGetSet();
        demo.field1 = 1;
        demo.field2 = 2L;
        demo.field3 = 3.1415926f;
        demo.field4 = 4;
        demo.field5 = 6.123456d;
        demo.field6 = true;
        demo.field7 = 'a';
        demo.field8 = 'b';
        DemoDTO demoDTO = convertor.convert(demo);
        assertEquals(demoDTO.getField1(), demo.field1);
        assertEquals(demoDTO.getField2(), demo.field2);
        assertEquals(demoDTO.getField3(), demo.field3);
        assertEquals(demoDTO.getField4(), demo.field4);
        assertEquals(demoDTO.getField5(), demo.field5);
        assertEquals(demoDTO.isField6(), demo.field6);
        assertEquals(demoDTO.getField7(), demo.field7);
        assertEquals(demoDTO.getField8(), demo.field8);
    }

    @Test
    public void testDtoHasnotField() {
        Convertor<DemoBoxedNoGetSet, DemoNoField8DTO> convertor = ConvertorRegister.instance().getConvertor(DemoBoxedNoGetSet.class, DemoNoField8DTO.class);
        DemoBoxedNoGetSet demo = new DemoBoxedNoGetSet();
        demo.field1 = 1;
        demo.field2 = 2L;
        demo.field3 = 3.1415926f;
        demo.field4 = 4;
        demo.field5 = 6.123456d;
        demo.field6 = true;
        demo.field7 = 'a';
        demo.field8 = 'b';
        DemoNoField8DTO demoDTO = convertor.convert(demo);
        assertEquals(demoDTO.getField1(), demo.field1);
        assertEquals(demoDTO.getField2(), demo.field2);
        assertEquals(demoDTO.getField3(), demo.field3);
        assertEquals(demoDTO.getField4(), demo.field4);
        assertEquals(demoDTO.getField5(), demo.field5);
        assertEquals(demoDTO.isField6(), demo.field6);
        assertEquals(demoDTO.getField7(), demo.field7);
    }

    @Test
    public void testUnboxedSrcDtoHasSet() {
        Convertor<DemoNoGetSet, DemoBoxedDTO> convertor = ConvertorRegister.instance().getConvertor(DemoNoGetSet.class, DemoBoxedDTO.class);
        DemoNoGetSet demo = new DemoNoGetSet();
        demo.field1 = 1;
        demo.field2 = 2L;
        demo.field3 = 3.1415926f;
        demo.field4 = 4;
        demo.field5 = 6.123456d;
        demo.field6 = true;
        demo.field7 = 'a';
        demo.field8 = 'b';
        DemoBoxedDTO demoDTO = convertor.convert(demo);
        assertEquals(demoDTO.getField1(), demo.field1);
        assertEquals(demoDTO.getField2(), demo.field2);
        assertEquals(demoDTO.getField3(), demo.field3);
        assertEquals(demoDTO.getField4(), demo.field4);
        assertEquals(demoDTO.getField5(), demo.field5);
        assertEquals(demoDTO.getField6(), demo.field6);
        assertEquals(demoDTO.getField7(), demo.field7);
        assertEquals(demoDTO.getField8(), demo.field8);
    }

    @Test
    public void testBoxedToUnboxed() throws NoSuchFieldException, IllegalAccessException {
        Convertor<DemoBoxedNoGetSet, DemoNoGetSetDTO> convertor = ConvertorRegister.instance().getConvertor(DemoBoxedNoGetSet.class, DemoNoGetSetDTO.class);
        DemoBoxedNoGetSet demo = new DemoBoxedNoGetSet();
        Field f1;
        Field f2;
        Field f3;
        Field f4;
        Field f5;
        Field f6;
        Field f7;
        Field f8;
        f1 = DemoBoxedNoGetSet.class.getDeclaredField("field1");
        f2 = DemoBoxedNoGetSet.class.getDeclaredField("field2");
        f3 = DemoBoxedNoGetSet.class.getDeclaredField("field3");
        f4 = DemoBoxedNoGetSet.class.getDeclaredField("field4");
        f5 = DemoBoxedNoGetSet.class.getDeclaredField("field5");
        f6 = DemoBoxedNoGetSet.class.getDeclaredField("field6");
        f7 = DemoBoxedNoGetSet.class.getDeclaredField("field7");
        f8 = DemoBoxedNoGetSet.class.getDeclaredField("field8");
        f1.setAccessible(true);
        f2.setAccessible(true);
        f3.setAccessible(true);
        f4.setAccessible(true);
        f5.setAccessible(true);
        f6.setAccessible(true);
        f7.setAccessible(true);
        f8.setAccessible(true);

        f1.set(demo, new Integer(1));
        f2.set(demo, new Long(2));
        f3.set(demo, new Float(3.1415926f));
        f4.set(demo, (short)4);
        f5.set(demo, 6.123456d);
        f6.set(demo, true);
        f7.set(demo, (byte)'a');
        f8.set(demo, 'b');

        Field ff1;
        Field ff2;
        Field ff3;
        Field ff4;
        Field ff5;
        Field ff6;
        Field ff7;
        Field ff8;
        ff1 = DemoNoGetSetDTO.class.getDeclaredField("field1");
        ff2 = DemoNoGetSetDTO.class.getDeclaredField("field2");
        ff3 = DemoNoGetSetDTO.class.getDeclaredField("field3");
        ff4 = DemoNoGetSetDTO.class.getDeclaredField("field4");
        ff5 = DemoNoGetSetDTO.class.getDeclaredField("field5");
        ff6 = DemoNoGetSetDTO.class.getDeclaredField("field6");
        ff7= DemoNoGetSetDTO.class.getDeclaredField("field7");
        ff8 = DemoNoGetSetDTO.class.getDeclaredField("field8");
        DemoNoGetSetDTO demoDto = convertor.convert(demo);
        ff1.setAccessible(true);
        ff2.setAccessible(true);
        ff3.setAccessible(true);
        ff4.setAccessible(true);
        ff5.setAccessible(true);
        ff6.setAccessible(true);
        ff7.setAccessible(true);
        ff8.setAccessible(true);
        assertEquals(ff1.get(demoDto), f1.get(demo));
        assertEquals(ff2.get(demoDto), f2.get(demo));
        assertEquals(ff3.get(demoDto), f3.get(demo));
        assertEquals(ff4.get(demoDto), f4.get(demo));
        assertEquals(ff5.get(demoDto), f5.get(demo));
        assertEquals(ff6.get(demoDto), f6.get(demo));
        assertEquals(ff7.get(demoDto), f7.get(demo));
        assertEquals(ff8.get(demoDto), f8.get(demo));
    }

    @Test
    public void testUnboxedToBoxed() throws NoSuchFieldException, IllegalAccessException {
        Convertor<DemoNoGetSet, DemoBoxedNoGetSetDTO> convertor = ConvertorRegister.instance().getConvertor(DemoNoGetSet.class, DemoBoxedNoGetSetDTO.class);
        DemoNoGetSet demo = new DemoNoGetSet();
        Field f1;
        Field f2;
        Field f3;
        Field f4;
        Field f5;
        Field f6;
        Field f7;
        Field f8;
        f1 = DemoNoGetSet.class.getDeclaredField("field1");
        f2 = DemoNoGetSet.class.getDeclaredField("field2");
        f3 = DemoNoGetSet.class.getDeclaredField("field3");
        f4 = DemoNoGetSet.class.getDeclaredField("field4");
        f5 = DemoNoGetSet.class.getDeclaredField("field5");
        f6 = DemoNoGetSet.class.getDeclaredField("field6");
        f7 = DemoNoGetSet.class.getDeclaredField("field7");
        f8 = DemoNoGetSet.class.getDeclaredField("field8");
        f1.setAccessible(true);
        f2.setAccessible(true);
        f3.setAccessible(true);
        f4.setAccessible(true);
        f5.setAccessible(true);
        f6.setAccessible(true);
        f7.setAccessible(true);
        f8.setAccessible(true);

        f1.set(demo, new Integer(1));
        f2.set(demo, new Long(2));
        f3.set(demo, new Float(3.1415926f));
        f4.set(demo, (short)4);
        f5.set(demo, 6.123456d);
        f6.set(demo, true);
        f7.set(demo, (byte)'a');
        f8.set(demo, 'b');

        Field ff1;
        Field ff2;
        Field ff3;
        Field ff4;
        Field ff5;
        Field ff6;
        Field ff7;
        Field ff8;
        ff1 = DemoBoxedNoGetSetDTO.class.getDeclaredField("field1");
        ff2 = DemoBoxedNoGetSetDTO.class.getDeclaredField("field2");
        ff3 = DemoBoxedNoGetSetDTO.class.getDeclaredField("field3");
        ff4 = DemoBoxedNoGetSetDTO.class.getDeclaredField("field4");
        ff5 = DemoBoxedNoGetSetDTO.class.getDeclaredField("field5");
        ff6 = DemoBoxedNoGetSetDTO.class.getDeclaredField("field6");
        ff7 = DemoBoxedNoGetSetDTO.class.getDeclaredField("field7");
        ff8 = DemoBoxedNoGetSetDTO.class.getDeclaredField("field8");
        DemoBoxedNoGetSetDTO demoDto = convertor.convert(demo);
        ff1.setAccessible(true);
        ff2.setAccessible(true);
        ff3.setAccessible(true);
        ff4.setAccessible(true);
        ff5.setAccessible(true);
        ff6.setAccessible(true);
        ff7.setAccessible(true);
        ff8.setAccessible(true);
        assertEquals(ff1.get(demoDto), f1.get(demo));
        assertEquals(ff2.get(demoDto), f2.get(demo));
        assertEquals(ff3.get(demoDto), f3.get(demo));
        assertEquals(ff4.get(demoDto), f4.get(demo));
        assertEquals(ff5.get(demoDto), f5.get(demo));
        assertEquals(ff6.get(demoDto), f6.get(demo));
        assertEquals(ff7.get(demoDto), f7.get(demo));
        assertEquals(ff8.get(demoDto), f8.get(demo));
    }

    @Test
    public void testTypeToString() throws NoSuchFieldException, IllegalAccessException {
        Convertor<DemoNoGetSet, DemoStringFieldDTO> convertor = ConvertorRegister.instance().getConvertor(DemoNoGetSet.class, DemoStringFieldDTO.class);
        DemoNoGetSet demo = new DemoNoGetSet();
        Field f1;
        Field f2;
        Field f3;
        Field f4;
        Field f5;
        Field f6;
        Field f7;
        Field f8;
        f1 = DemoNoGetSet.class.getDeclaredField("field1");
        f2 = DemoNoGetSet.class.getDeclaredField("field2");
        f3 = DemoNoGetSet.class.getDeclaredField("field3");
        f4 = DemoNoGetSet.class.getDeclaredField("field4");
        f5 = DemoNoGetSet.class.getDeclaredField("field5");
        f6 = DemoNoGetSet.class.getDeclaredField("field6");
        f7 = DemoNoGetSet.class.getDeclaredField("field7");
        f8 = DemoNoGetSet.class.getDeclaredField("field8");
        f1.setAccessible(true);
        f2.setAccessible(true);
        f3.setAccessible(true);
        f4.setAccessible(true);
        f5.setAccessible(true);
        f6.setAccessible(true);
        f7.setAccessible(true);
        f8.setAccessible(true);

        f1.set(demo, new Integer(1));
        f2.set(demo, new Long(2));
        f3.set(demo, new Float(3.1415926f));
        f4.set(demo, (short)4);
        f5.set(demo, 6.123456d);
        f6.set(demo, true);
        f7.set(demo, (byte)'a');
        f8.set(demo, 'b');

        Field ff1;
        Field ff2;
        Field ff3;
        Field ff4;
        Field ff5;
        Field ff6;
        Field ff7;
        Field ff8;
        ff1 = DemoStringFieldDTO.class.getDeclaredField("field1");
        ff2 = DemoStringFieldDTO.class.getDeclaredField("field2");
        ff3 = DemoStringFieldDTO.class.getDeclaredField("field3");
        ff4 = DemoStringFieldDTO.class.getDeclaredField("field4");
        ff5 = DemoStringFieldDTO.class.getDeclaredField("field5");
        ff6 = DemoStringFieldDTO.class.getDeclaredField("field6");
        ff7 = DemoStringFieldDTO.class.getDeclaredField("field7");
        ff8 = DemoStringFieldDTO.class.getDeclaredField("field8");
        DemoStringFieldDTO demoDto = convertor.convert(demo);
        ff1.setAccessible(true);
        ff2.setAccessible(true);
        ff3.setAccessible(true);
        ff4.setAccessible(true);
        ff5.setAccessible(true);
        ff6.setAccessible(true);
        ff7.setAccessible(true);
        ff8.setAccessible(true);
        assertEquals(ff1.get(demoDto), String.valueOf(f1.get(demo)));
        assertEquals(ff2.get(demoDto), String.valueOf(f2.get(demo)));
        assertEquals(ff3.get(demoDto), String.valueOf(f3.get(demo)));
        assertEquals(ff4.get(demoDto), String.valueOf(f4.get(demo)));
        assertEquals(ff5.get(demoDto), String.valueOf(f5.get(demo)));
        assertEquals(ff6.get(demoDto), String.valueOf(f6.get(demo)));
        assertEquals(ff7.get(demoDto), String.valueOf(f7.get(demo)));
        assertEquals(ff8.get(demoDto), String.valueOf(f8.get(demo)));
    }

    @Test
    public void testListField() {
        Convertor<DemoListField, DemoListFieldDTO> convertor = ConvertorRegister.instance().getConvertor(DemoListField.class, DemoListFieldDTO.class);
        DemoListField demo = new DemoListField();
        demo.setStrField("abcd");
        demo.setListField(Arrays.asList("1","2","3"));

        DemoListFieldDTO demoDto = convertor.convert(demo);
        assertEquals(demo.getStrField(), demoDto.getStrField());
        assertNotNull(demoDto.getListField());
        List<String> list = demoDto.getListField();
        assertEquals(list.size(), 3);
        assertEquals(list.get(0), "1");
        assertEquals(list.get(1), "2");
        assertEquals(list.get(2), "3");
    }

    @Test
    public void testListObjField() {
        Convertor<DemoListObjField, DemoListObjFieldDTO> convertor = ConvertorRegister.instance().getConvertor(DemoListObjField.class, DemoListObjFieldDTO.class);
        DemoListObjField demo = new DemoListObjField();
        demo.setStrField("abcd");
        demo.setListField(Arrays.asList("1","2","3"));

        DemoListObjFieldDTO demoDto = convertor.convert(demo);
        assertEquals(demo.getStrField(), demoDto.getStrField());
        assertNotNull(demoDto.getListField());
        List<String> list = demoDto.getListField();
        assertEquals(list.size(), 3);
        assertEquals(list.get(0), "1");
        assertEquals(list.get(1), "2");
        assertEquals(list.get(2), "3");
    }

    @Test
    public void testListExtendField() {

        Convertor<DemoListExtendField, DemoListExtendFieldDTO> convertor = ConvertorRegister.instance().getConvertor(DemoListExtendField.class, DemoListExtendFieldDTO.class);
        DemoListExtendField demo = new DemoListExtendField();
        demo.setStrField("abcd");
        SportCar sportCar = new SportCar();
        sportCar.setMake("bmw");
        sportCar.setType(CarType.SEDAN);
        sportCar.setNumberOfSeats(5);
        demo.setListField(Arrays.asList(sportCar));

        DemoListExtendFieldDTO demoDto = convertor.convert(demo);
        assertEquals(demo.getStrField(), demoDto.getStrField());
        assertNotNull(demoDto.getListField());
        List<Car> list = demoDto.getListField();
        assertEquals(list.size(), 1);
        Car dto = list.get(0);
        assertEquals(dto.getMake(), "bmw");
        assertEquals(dto.getType(), CarType.SEDAN);
        assertEquals(dto.getNumberOfSeats(), 5);


        Convertor<DemoListExtendThreeLevelField, DemoListExtendFieldDTO> convertor2 = ConvertorRegister.instance().getConvertor(DemoListExtendThreeLevelField.class, DemoListExtendFieldDTO.class);
        DemoListExtendThreeLevelField demo2 = new DemoListExtendThreeLevelField();
        demo2.setStrField("abcd");
        BmwCar bmwCar = new BmwCar();
        bmwCar.setMake("bmw");
        bmwCar.setType(CarType.SEDAN);
        bmwCar.setNumberOfSeats(5);
        demo2.setListField(Arrays.asList(bmwCar));

        demoDto = convertor2.convert(demo2);
        assertEquals(demo2.getStrField(), demoDto.getStrField());
        assertNotNull(demoDto.getListField());
        list = demoDto.getListField();
        assertEquals(list.size(), 1);
        dto = list.get(0);
        assertEquals(dto.getMake(), "bmw");
        assertEquals(dto.getType(), CarType.SEDAN);
        assertEquals(dto.getNumberOfSeats(), 5);


        Convertor<DemoListExtendThreeLevelField, DemoListExtendFieldDTO> convertor3 =
                ConvertorRegister.instance().getConvertor(DemoListExtendThreeLevelField.class, DemoListExtendFieldErrorDTO.class);
        assertNotNull(convertor3);
    }

    @Test
    public void testAbstractConvertor() {
        AbstractConvertor<Car, CarDTO> convertor = ConvertorRegister.instance().getConvertor(Car.class, CarDTO.class);
        assertEquals(convertor.getDestClazz(), CarDTO.class);
    }
}
