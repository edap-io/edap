package io.edap.microservice.annotation;

import java.lang.annotation.*;

/**
 * 标记一个类为 edap 容器的"配置类"——类内 {@link Bean @Bean} 标注的方法返回的实例
 * 注册为容器管理的 Bean。语义等价 Spring {@code @Configuration + @Bean}。
 *
 * <p><b>典型用法</b>：</p>
 * <pre>{@code
 * @Configuration
 * public class AppConfig {
 *     @Bean(name = "dataSource")
 *     public DataSource dataSource() {
 *       return new HikariDataSource(...);
 *     }
 *
 *     @Bean(name = "restTemplate")
 *     public RestTemplate restTemplate(DataSource ds) {    // ← 参数按 @Inject 规则解析
 *       return new RestTemplate(ds);
 *     }
 * }
 * }</pre>
 *
 * <p><b>生命周期</b>：</p>
 * <ol>
 *   <li>Phase 1 扫描：发现 {@code @Configuration} 类 → 实例化（容器内部类，正常 Bean 流程）</li>
 *   <li>Phase 1 扩展：遍历 {@code @Configuration} 类的 {@code @Bean} 方法 → 每个方法
 *       生成一个 BeanDef，{@code def.factoryMethod} 指向该 {@code @Bean} 方法</li>
 *   <li>Phase 2 实例化：BeanContainer.instantiate 看到 {@code factoryMethod} → 调
 *       {@code configInstance.beanMethod(...)} 拿结果（参数按 {@code @Inject} 规则解析
 *       —— 即方法级依赖注入）</li>
 *   <li>后续：registerInstance / injectDependencies / invokeInit 同普通 Bean</li>
 * </ol>
 *
 * <p><b>与 Spring {@code @Configuration} 的差异</b>：</p>
 * <ul>
 *   <li>Spring {@code @Configuration} 类被 CGLIB 代理，确保 {@code config.a()} 与
 *       {@code config.b()}（都调 {@code @Bean a()}）共享同一实例（单例语义）。
 *       <b>edap 当前不做 CGLIB 代理</b>——{@code @Bean} 方法互调会拿到新实例，调用方
 *       需要自己避免互调，或显式用 {@code @Inject} 注入 Bean 而不是调 {@code @Bean} 方法。</li>
 *   <li>Spring 支持 {@code @Profile} / {@code @Conditional} / {@code @Import} 等
 *       配置类元注解。edap 当前仅支持 {@code @Bean} 工厂方法 + {@code @Inject} 依赖注入，
 *       暂不引入条件配置元注解。</li>
 * </ul>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
@Documented
public @interface Configuration {

    /**
     * 配置类 Bean 名（用于容器注册 + 依赖注入引用）。
     * 缺省时按规则生成（类名首字母小写：{@code AppConfig → appConfig}）。
     */
    String name() default "";
}