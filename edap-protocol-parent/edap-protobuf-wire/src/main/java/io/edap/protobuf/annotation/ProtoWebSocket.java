package io.edap.protobuf.annotation;

import java.lang.annotation.*;

@Target({ ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface ProtoWebSocket {
    /**
     * WebSocket路由的标记，默认为方法名
     * @return
     */
    String method();

    /**
     * WebSocket的路径名，默认使用容器统一的WebSocket的地址"/ws"
     * @return
     */
    String path() default "/ws";
}
