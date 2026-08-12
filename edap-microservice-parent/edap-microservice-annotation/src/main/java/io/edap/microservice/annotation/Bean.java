package io.edap.microservice.annotation;

import io.edap.microservice.Scope;

import java.lang.annotation.*;

/**
 * 标记一个类为 edap 容器管理的普通 Bean（非 proto 生成的服务）。
 *
 * <p>与 {@code @ProtoService}（位于 {@code io.edap.protobuf.annotation}，由 .proto 生成的
 * 服务类）的区别：本注解面向手写的普通 Java 类。</p>
 *
 * <p>当前阶段只支持本注解一种 Bean 标记，其他候选（@Component / @Service 等）暂不引入。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Inherited
@Documented
public @interface Bean {

    String name();

    Scope scope() default Scope.SINGLETON;
}