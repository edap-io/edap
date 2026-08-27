package io.edap.container;

import io.edap.microservice.Scope;

import java.lang.reflect.Method;
import java.util.List;

public class BeanDef {
    private final String               name;        // bean 名（默认类简单名）
    private final Class<?>             beanClass;   // bean 类型（ClassLoader 加载后才有）
    private final Scope scope;       // SINGLETON / PROTOTYPE
    private final List<String>         injectionNames;  // 字段 / 方法依赖的 bean 名（拓扑排序用）
    private final List<InjectionPoint> injections;  // 字段 / 方法注入点的反射元数据
    private final Method               initMethod;  // @PostConstruct / @Init
    private final Method               destroyMethod; // @PreDestroy / @Destroy
    private final int                  order;       // @Order（同层拓扑序二级排序）
    /**
     * 工厂方法（{@code @Configuration} 类的 {@code @Bean} 方法）。{@code null} 表示普通 Bean，
     * 由构造器注入路径实例化；非 {@code null} 时 {@link BeanContainer#instantiate(BeanDef)} 改走
     * {@code configInstance.factoryMethod(args)} 路径。
     */
    private final Method               factoryMethod;
    /** 工厂方法所属 {@code @Configuration} Bean 的 name——instantiate 时按它查 singletons。 */
    private final String               factoryBeanName;

    public BeanDef(String name,
                   Class<?> beanClass,
                   Scope scope,
                   List<String> injectionNames,
                   List<InjectionPoint> injections,
                   Method initMethod,
                   Method destroyMethod,
                   int order) {

        this(name, beanClass, scope, injectionNames, injections,
             initMethod, destroyMethod, order, null, null);
    }

    public BeanDef(String name,
                   Class<?> beanClass,
                   Scope scope,
                   List<String> injectionNames,
                   List<InjectionPoint> injections,
                   Method initMethod,
                   Method destroyMethod,
                   int order,
                   Method factoryMethod,
                   String factoryBeanName) {

        this.name = name;
        this.beanClass = beanClass;
        this.scope = scope;
        this.injectionNames = injectionNames;
        this.injections = injections;
        this.initMethod = initMethod;
        this.destroyMethod = destroyMethod;
        this.order = order;
        this.factoryMethod = factoryMethod;
        this.factoryBeanName = factoryBeanName;
    }



    public String name()              { return name; }
    public Class<?> beanClass()       { return beanClass; }
    public Scope scope()              { return scope; }
    public List<String> injectionNames() { return injectionNames; }
    public List<InjectionPoint> injections() { return injections; }
    public Method initMethod()        { return initMethod; }
    public Method destroyMethod()     { return destroyMethod; }
    public int order()                { return order; }
    public Method factoryMethod()     { return factoryMethod; }
    public String factoryBeanName()   { return factoryBeanName; }
}


