package io.edap.microservice.annotation;

import io.edap.microservice.Scope;

import java.lang.annotation.*;

/**
 * edap 容器的 Bean 标记。
 *
 * <p><b>两种使用方式</b>：</p>
 * <ol>
 *   <li><b>类级别</b>（最常见）：标记一个普通 Java 类为容器管理的 Bean——
 *       容器扫描时把类注册为 BeanDef，Phase 2 实例化 / 注入 / 初始化按 BeanContainer
 *       正常流程走。
 *       <pre>{@code
 *       @Bean(name = "userService")
 *       public class UserServiceImpl implements UserService { ... }
 *       }</pre>
 *   </li>
 *   <li><b>方法级别</b>（配置类工厂方法）：标注在 {@link Configuration @Configuration} 类
 *       的方法上，方法返回值注册为 Bean——等价 Spring {@code @Configuration + @Bean}。
 *       <pre>{@code
 *       @Configuration
 *       public class AppConfig {
 *           @Bean(name = "dataSource")
 *           public DataSource dataSource() {
 *               return new HikariDataSource(...);
 *           }
 *       }
 *       }</pre>
 *   </li>
 * </ol>
 *
 * <p>与 {@code @ProtoService}（位于 {@code io.edap.protobuf.annotation}，由 .proto 生成的
 * 服务类）的区别：本注解面向手写的普通 Java 类 / 工厂方法。</p>
 *
 * <p>{@link #scope()} 控制 Bean 作用域：SINGLETON（默认，Phase 2 实例化后缓存到 singletons）
 * / PROTOTYPE（每次 {@code getBean} 新建，{@code edap 4.x 暂未实现 PROTOTYPE 完整路径}）。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Inherited
@Documented
public @interface Bean {

    /** Bean 名（构造 BeanDef.name）；缺省时按规则生成（详见 §4.5.6 BeanDef 生成规则）。 */
    String name() default "";

    Scope scope() default Scope.SINGLETON;
}