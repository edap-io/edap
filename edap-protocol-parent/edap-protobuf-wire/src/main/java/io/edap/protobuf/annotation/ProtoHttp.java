package io.edap.protobuf.annotation;

import java.lang.annotation.*;

@Target({ ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface ProtoHttp {

    String path() default "";

    String method() default "POST";
}
