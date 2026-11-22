package io.edap.protobuf.annotation;

import java.lang.annotation.*;

@Target({ ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface ProtoHttp {

    String path() default "";

    String method() default "POST";

    /**
     * 路径配置的Http的处理器，urlmapping不支持路径参数，没办法快速匹配
     * 如果有路径参数的情况，需要使用其他路由配置，URL和method对应关系处理的HttpHandler的路径，
     * 用于匹配到通配符路由的httphandler。
     * @return
     */
    String handlerPath() default "";
}
