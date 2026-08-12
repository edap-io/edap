package io.edap.microservice.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 多候选消歧注解。当某个类型存在多个 bean 时（接口有多个实现、抽象类有多个子类），
 * BeanContainer.resolveDependencyByType 优先选择带 @Primary 的候选。
 *
 * 用法：
 *   - 加在类上：标记该 bean 在按类型注入时被优先选
 *   - 多个 @Primary 候选时仍抛 NoUniqueBeanException
 *
 * 与 Spring 的 @Primary 语义一致。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Primary {
}