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

    public BeanDef(String name,
                   Class<?> beanClass,
                   Scope scope,
                   List<String> injectionNames,
                   List<InjectionPoint> injections,
                   Method initMethod,
                   Method destroyMethod,
                   int order) {

        this.name = name;
        this.beanClass = beanClass;
        this.scope = scope;
        this.injectionNames = injectionNames;
        this.injections = injections;
        this.initMethod = initMethod;
        this.destroyMethod = destroyMethod;
        this.order = order;
    }



    public String name()              { return name; }
    public Class<?> beanClass()       { return beanClass; }
    public Scope scope()              { return scope; }
    public List<String> injectionNames() { return injectionNames; }
    public List<InjectionPoint> injections() { return injections; }
    public Method initMethod()        { return initMethod; }
    public Method destroyMethod()     { return destroyMethod; }
    public int order()                { return order; }
}


