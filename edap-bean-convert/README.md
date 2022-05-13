## edap-bean-convert

高效的Javabean转换工具，对常见的VO转DTO等操作提供使用简单，抓换效果等同于手写代码效率的转换框架。

### 基本使用方法

#### VO代码

```java
public class Car {
    private String make;
    private int numberOfSeats;
    private CarType type;

    //constructor, getters, setters etc.
}
```

#### DTO代码

```java
public class CarDTO {
    private String make;
    private int seatCount;
    private String type;

    //constructor, getters, setters etc.
}
```

#### 转换代码

```java
import io.edap.beanconvert.ConvertorRegister;

public class ConvertDemo {
    public static void main(String[] arg) {
        Convertor<CarDTO, Car> carConvertor = ConvertorRegister.instance().getConvertor(Car.class, CarDTO.class);
        Car car = new Car();
        car.setMake("BMW");
        car.setSeatCount(5);
        car.setType(CarType.SEDAN);
        CarDTO carDTO = carConvertor.convert(car);
    }
}
```

### 支持目标class的类继承

```java
public class SportCar extends Car {
    
    //constructor, getters, setters etc.
}
```

```java
import io.edap.beanconvert.ConvertorRegister;

public class ConvertDemo {
    public static void main(String[] arg) {
        Convertor<CarDTO, Car> carConvertor = ConvertorRegister.instance().getConvertor(Car.class, CarDTO.class);
        SportCar car = new SportCar();
        car.setMake("BMW");
        car.setSeatCount(2);
        car.setType(CarType.SPORTCAR);
        CarDTO carDTO = carConvertor.convert(car);
    }
}
```

### 框架实现机制

框架使用ASM字节增强的机制为每个类转换在第一次调用getConvertor时生成一个转换器的实现类，将该实现类动态加载到虚拟机中，下载再次使用时直接使用该转换器的实现类进行转换，抓换效率和手工编码的效率一致。
