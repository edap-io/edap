package io.edap.microservice.annotation;

import io.edap.microservice.enums.ParamType;

import java.lang.annotation.*;

@Target({ ElementType.PARAMETER })
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface ParamConf {

    String name();

    ParamType paramType() default ParamType.HTTP_PARAMETER;
}
