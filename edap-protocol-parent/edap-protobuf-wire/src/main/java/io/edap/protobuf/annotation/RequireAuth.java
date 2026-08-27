package io.edap.protobuf.annotation;

import java.lang.annotation.*;

/**
 * 该注解标记该Method为需要用户登录后操作，如果resolver为空则默认使用jwt的认证方式，
 * 容器拦截http请求header的Authorization值获取jwt的token，解析到用户信息后，将用户信息
 * 放到当前线程上下文中，服务实现可以从线程上下文中获取用户信息。
 */
@Target({ ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface RequireAuth {

    String resolver() default "";
}
