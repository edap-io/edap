package io.edap.container;

import io.edap.container.app.RouterHub;
import io.edap.container.app.RouterHubAware;
import io.edap.container.event.EventPublisher;
import io.edap.container.exc.*;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.microservice.annotation.Optional;
import io.edap.microservice.annotation.Primary;
import io.edap.microservice.Scope;

import javax.inject.Inject;
import javax.inject.Named;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * BeanContainer = 单个 AppContext 的 Bean 容器。
 *
 * 责任 GATHERING / COMMITTING / READY / DESTROYING / DESTROYED 五态生命周期：
 *   - GATHERING：register BeanDef（Phase 1）
 *   - COMMITTING：instantiate → injectDependencies → invokeInit（Phase 2）
 *   - READY：startLifecycles（Phase 3）→ 业务 dispatch 触发 getBean / singletons()
 *   - DESTROYING / DESTROYED：逆序 stop / @PreDestroy / 清空 singletons
 *
 * 全程由 AppContext 的 lifecycleLock 串行化（Phase 1/2/3/销毁），运行时 getBean 无锁。
 */
public class BeanContainer {

    private static final Logger log = LoggerManager.getLogger(BeanContainer.class);

    // —— 字段 ——

    /** GATHERING 阶段写：所有注册的 BeanDef。LinkedHashMap 保证遍历顺序 = 注册顺序。 */
    private final LinkedHashMap<String, BeanDef> definitions = new LinkedHashMap<>();

    /** COMMITTING 阶段写、运行时多线程读：singleton / stateful 实例 + BeanWrap。 */
    private final ConcurrentHashMap<String, BeanWrap> singletons = new ConcurrentHashMap<>();

    /**
     * COMMITTING 阶段写、运行时多线程读：按 Type 索引（自类 + 父类 + 所有接口含父接口）。
     * <p>List value 是为支持"同 type 多实现"——{@link #beanWrapByType} 在多候选时按
     *     {@code @Primary} 消歧，无 @Primary 抛 {@link NoUniqueBeanException}。</p>
     * <p>与 {@link #singletons} 二者构成 BeanContainer 的两张查表：name → instance + type → instance。
     *     AppContext.generateAndBindRoutes 走 byType（接口→bean），运行时 @Inject 字段/方法注入
     *     也走 byType（类型→bean）。</p>
     */
    private final ConcurrentHashMap<Class<?>, List<BeanWrap>> byType = new ConcurrentHashMap<>();

    /** COMMITTING 阶段内、循环依赖检测用：当前正在 instantiate 的 bean name。 */
    private final HashSet<String> creating = new HashSet<>();

    /** 所有已实例化 BeanDef 的最终顺序（topologicalSort 输出）；Phase 2 用。 */
    private List<BeanDef> sorted = List.of();

    private final Environment       env;
    private final EventPublisher    events;
    private final AppContext        appContext;
    private final ShardRegistry     shards;
    /**
     * 父 Container 引用（仅 AppContext 级 BeanContainer 持有）。
     * <p>本字段为 {@link #beanWrapByType(Class)} / {@link #findBeanWrapByType(Class)}
     * 的 fallback 提供跳板：AppContext 级 miss 时查 {@code container.containerBeans()}。
     * 值为 {@code null} 表示本 BeanContainer 即 Container.beans 自身（无 fallback）。</p>
     */
    private final Container         container;

    private volatile BeanContainerState state = BeanContainerState.COLLECTING;

    public BeanContainer(AppContext appContext, Environment env, EventPublisher events,
                         ShardRegistry shards) {
        this.appContext = appContext;
        this.env        = env;
        this.events     = events;
        this.shards     = shards;
        // AppContext 级 → 拿父 Container 引用用于 fallback；Container.beans 自身 → null
        this.container  = appContext == null ? null : appContext.container();
    }

    /**
     * 状态迁移封装：canTransitionTo 校验 → 写回 state 字段。
     * 由 lifecycleLock 串行化（AppContext 持有），单线程写。
     * 不暴露为 public——只有同类的 transitionToCommitting / transitionToReady /
     * destroyAllSingletons 内部调，杜绝外部乱跳状态。
     */
    private void transitionTo(BeanContainerState to) {
        if (!state.canTransitionTo(to)) {
            throw new IllegalStateException(
                    "Illegal BeanContainerState transition: " + state + " -> " + to);
        }
        state = to;
    }

    // —— Phase 1 GATHERING ——

    /**
     * 注册一个 BeanDef。state == COLLECTING 才允许调。
     */
    public void register(BeanDef def) throws DuplicateBeanException {
        state.checkTransitionGuard(BeanContainerState.COLLECTING);
        if (definitions.putIfAbsent(def.name(), def) != null) {
            throw new DuplicateBeanException(def.name());
        }
    }

    /**
     * 拓扑排序：被依赖的先初始化。循环依赖立刻抛 CyclicDependencyException（§4.5.7）。
     */
    public List<BeanDef> topologicalSort() {
        // ★ 懒填 injectionNames：所有 BeanDef 都 register 完后,反射分析无 injectionNames 的
        //   BeanDef(典型是 @MicroServiceBean 扫出来的 BeanDef,AppContext.buildBeanDef
        //   不解析构造函数,直接传 null 进来)的构造函数参数,按类型在 definitions 里找
        //   匹配 bean,填进 injectionNames——这样拓扑排序才知道"被依赖的先 initialize",
        //   后续 instantiate → registerInstance → byType 填充的链路才能保证 ctorArgs
        //   resolveDependencyByType 拿到正确实例(否则会 NoSuchBeanException,或更糟
        //   单 ctor 无 @Inject 时 ctorArgs 给 null 然后 bean 默默"成功")。
        //   必须在所有 register 完后做,否则类型→bean 映射不全。
        enrichInjectionNames();

        List<BeanDef> result = new ArrayList<>(definitions.size());
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        for (BeanDef def : definitions.values()) {
            dfs(def, visited, inStack, result);
        }
        // 同层（依赖集相同）按 @Order 升序；同 order 内保留 DFS 拓扑序（List.sort 稳定排序）
        // ——之前 .thenComparing(name) 会跨层按 name 重排，破坏 @Configuration 必须在 @Bean 之前的契约。
        result.sort(Comparator.comparingInt((BeanDef d) -> d.order()));
        this.sorted = List.copyOf(result);
        return this.sorted;
    }

    /**
     * 懒填 injectionNames：扫描所有 BeanDef,反射分析:
     * <ul>
     *   <li>非 factoryMethod BeanDef(典型是 @MicroServiceBean 扫出来的,AppContext.buildBeanDef
     *       不解析构造函数,直接传 null 进来)→ 反射构造函数参数</li>
     *   <li>factoryMethod BeanDef(@Configuration.@Bean 产生)→ 反射方法参数。
     *       AppContext.buildConfigurationBeanDefs 显式设了 [configName],但只够保证 config
     *       先 instantiate;方法形参依赖(如 @Bean serviceCategoryViewDao(DataSource ds) 依赖
     *       @Bean createMainDataSource() 返回的 DataSource)没算进 injectionNames,导致拓扑
     *       顺序不可控 —— getDeclaredMethods() 顺序 JVM 不保证,若 serviceCategoryViewDao 偶然
     *       排在 createMainDataSource 之前,Phase 2 instantiate 时 byType[DataSource] 还是空的,
     *       instantiateViaFactory 内 resolveFactoryMethodArgs 抛 NoSuchBeanException("javax.sql.DataSource")。</li>
     * </ul>
     * 必须在所有 register 完后做,否则类型→bean 映射不全。
     */
    private void enrichInjectionNames() {
        // 存 (beanName, enrichedBeanDef) 对,不是 entry 本身 —— entry.getValue() 取的是原 def,
        // 写回等于啥都没改。Bug 修:用新的 SimpleEntry 包 (key, enriched) 才能把新 def 写进 definitions。
        List<Map.Entry<String, BeanDef>> replacements = new ArrayList<>();
        for (Map.Entry<String, BeanDef> e : definitions.entrySet()) {
            BeanDef def = e.getValue();
            BeanDef enriched = computeInjectionNames(def);
            if (enriched != def) {
                replacements.add(new java.util.AbstractMap.SimpleEntry<>(e.getKey(), enriched));
            }
        }
        for (Map.Entry<String, BeanDef> e : replacements) {
            definitions.put(e.getKey(), e.getValue());
        }
    }

    /**
     * 反射构造函数或工厂方法参数,按类型在已注册的 BeanDefs 里找匹配,产出含 injectionNames 的新 BeanDef。
     * 现有 injectionNames(如 factoryMethod BeanDef 已设的 [configName])作为基础,方法形参依赖追加。
     * 找不到任何新依赖或不可解析时返回原 def。
     */
    private BeanDef computeInjectionNames(BeanDef def) {
        List<String> existingDeps = def.injectionNames() == null ? List.of() : def.injectionNames();
        List<String> deps = new ArrayList<>(existingDeps);

        Parameter[] params;
        if (def.factoryMethod() != null) {
            // @Bean 工厂方法:反射方法参数
            params = def.factoryMethod().getParameters();
        } else {
            // 普通类:反射构造函数
            Constructor<?> ctor;
            try {
                ctor = selectConstructor(def.beanClass());
            } catch (Exception e) {
                // 多构造器无 @Inject 等异常场景,留原 def,instantiate 时会按 ctorArgs 规则处理
                return def;
            }
            params = ctor.getParameters();
        }

        for (Parameter p : params) {
            // 优先 @Named("xxx"):直接用名,跳过类型查找
            // —— 否则同类型多 bean 场景(如 3 个 JdbcViewDao)会被类型查找误指到首个
            // 遍历命中的 bean,导致拓扑顺序错位,@Named 运行时查不到目标
            Named named = p.getAnnotation(Named.class);
            if (named != null && !named.value().isEmpty()) {
                if (!deps.contains(named.value())) {
                    deps.add(named.value());
                }
                continue;
            }
            Class<?> type = p.getType();
            // java.util.Optional<T> 取 inner type 后再找依赖
            if (type == java.util.Optional.class) {
                try {
                    ParameterizedType pt = (ParameterizedType) p.getParameterizedType();
                    type = (Class<?>) pt.getActualTypeArguments()[0];
                } catch (Exception ignore) {
                    continue;
                }
            }
            String depName = findRegisteredBeanByType(type);
            if (depName != null && !deps.contains(depName)) {
                deps.add(depName);
            }
        }
        // deps 与 existingDeps 一致 → 无需重建 BeanDef
        if (deps.equals(existingDeps)) return def;

        return new BeanDef(def.name(), def.beanClass(), def.scope(),
                deps, def.injections(), def.initMethod(), def.destroyMethod(),
                def.order(), def.factoryMethod(), def.factoryBeanName());
    }

    /**
     * 在已注册 BeanDefs 里按类型查找首个 isAssignableFrom 匹配的 bean 名。
     * 跳过 Object/primitive 包装类的"匹配所有"陷阱;多候选无 @Primary 时取首个
     * (与运行时 beanWrapByType 的 NoUniqueBeanException 行为不一致,但拓扑序阶段
     * 只关心"依赖必须先初始化",具体解析在 instantiate 时再处理 @Primary 消歧)。
     */
    private String findRegisteredBeanByType(Class<?> type) {
        if (type == null || type == Object.class) return null;
        for (BeanDef other : definitions.values()) {
            if (other.beanClass() != null && type.isAssignableFrom(other.beanClass())) {
                return other.name();
            }
        }
        return null;
    }

    private void dfs(BeanDef def, Set<String> visited, Set<String> inStack,
                     List<BeanDef> result) {
        if (visited.contains(def.name())) {
            return;
        }
        if (inStack.contains(def.name())) {
            throw new CyclicDependencyException(tracePath(inStack, def.name()));
        }
        inStack.add(def.name());
        if (def.injectionNames() != null && !def.injectionNames().isEmpty()) {
            for (String depName : def.injectionNames()) {
                BeanDef dep = definitions.get(depName);
                if (dep != null) {
                    dfs(dep, visited, inStack, result);
                }
            }
        }
        inStack.remove(def.name());
        visited.add(def.name());
        result.add(def);
    }

    /**
     * 构造循环依赖路径字符串（A → B → A → ...），仅用于异常 message。
     */
    private static String tracePath(Set<String> inStack, String backTo) {
        return inStack.stream().collect(Collectors.joining(" → ")) + " → " + backTo;
    }

    /**
     * Phase 1 → Phase 2 状态迁移。
     */
    public void transitionToCommitting() {
        transitionTo(BeanContainerState.INSTANTIATING);
    }

    // —— Phase 2 COMMITTING ——

    /** 实例化（不注入、不调 init）。{@code @Configuration} 类的 {@code @Bean} 工厂方法走
     *  {@link #instantiateViaFactory(BeanDef)}；其余按 §4.5.4.10.1 选构造器、§4.5.4.10.2 解析参数。 */
    public Object instantiate(BeanDef def) throws BeanInstantiationException, CyclicDependencyException {
        state.checkTransitionGuard(BeanContainerState.INSTANTIATING);
        if (creating.contains(def.name())) {
            throw new CyclicDependencyException("creating already has " + def.name());
        }
        creating.add(def.name());
        try {
            // @Bean 工厂方法路径：@Configuration BeanDef 必先实例化（拓扑序保证）
            if (def.factoryMethod() != null) {
                return instantiateViaFactory(def);
            }
            Constructor<?> ctor = selectConstructor(def.beanClass());
            ctor.setAccessible(true);
            return ctor.newInstance(ctorArgs(ctor, def));
        } catch (InvocationTargetException e) {
            throw new BeanInstantiationException(def.name(), e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw new BeanInstantiationException(def.name(), e);
        } finally {
            creating.remove(def.name());
        }
    }

    /**
     * {@code @Bean} 工厂方法实例化：从 singletons 取所属 {@code @Configuration} 实例，
     * 反射调 {@code configInstance.factoryMethod(args)}，参数按 {@code @Inject} 规则解析
     * （{@code @Inject} 必填；{@code @Optional} 缺失时返回 null——与 ctorArgs 一致）。
     *
     * <p>前置：拓扑序保证 {@code def.factoryBeanName()} 对应 BeanDef 已 instantiate 并
     * registerInstance 过；否则此处 {@code singletons.get(...)} 返回 null 抛错。</p>
     */
    private Object instantiateViaFactory(BeanDef def) {
        Method m = def.factoryMethod();
        m.setAccessible(true);
        BeanWrap configWrap = singletons.get(def.factoryBeanName());
        if (configWrap == null) {
            throw new BeanInstantiationException(def.name(),
                    new IllegalStateException("@Configuration bean '" + def.factoryBeanName()
                            + "' not instantiated — topological order broken"));
        }
        Object[] args = resolveFactoryMethodArgs(m);
        try {
            return m.invoke(configWrap.instance(), args);
        } catch (InvocationTargetException e) {
            throw new BeanInstantiationException(def.name(), e.getTargetException());
        } catch (IllegalAccessException e) {
            throw new BeanInstantiationException(def.name(), e);
        }
    }

    /**
     * @Bean 工厂方法参数解析：每个参数按类型解析（Spring 风格）。
     *
     * <p><b>为什么不像 {@link #resolveMethodArgs} 那样要求 {@code @Inject}</b>：
     * {@code javax.inject.Inject} 的 {@code @Target} 是 {@code METHOD/CONSTRUCTOR/FIELD}，
     * 不含 {@code PARAMETER}——JSR-330 不允许把 {@code @Inject} 写在方法参数上。所以
     * {@code param.getAnnotation(Inject.class)} 永远返回 null，原本的 setter 注入参数
     * 解析路径实际上从不解析参数（latent bug）。@Bean 工厂方法走"按类型自动装配"——
     * 每个参数都尝试 {@link #resolveDependencyByType}，无 {@code @Optional} 缺失则抛
     * {@code NoSuchBeanException}，有则返回 null。</p>
     */
    private Object[] resolveFactoryMethodArgs(Method m) {
        Parameter[] params = m.getParameters();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            Class<?> type = params[i].getType();
            io.edap.microservice.annotation.Optional opt =
                    params[i].getAnnotation(Optional.class);
            try {
                args[i] = resolveDependencyByType(type);
            } catch (NoSuchBeanException e) {
                if (opt == null) throw e; // 必需 → 冒泡
                args[i] = null;           // 可选 → null
            }
        }
        return args;
    }

    /**
     * 选构造器——按 §4.5.4.10.1 规则：
     * <ol>
     *   <li>单构造器 → 直接用（Spring 4.3+ 自动检测，无需 @Inject）</li>
     *   <li>多构造器 + 单 @Inject → 用它</li>
     *   <li>多构造器 + 多 @Inject → 抛 AmbiguousConstructorException</li>
     *   <li>多构造器 + 0 @Inject → 抛 NoSuitableConstructorException</li>
     * </ol>
     *
     * @param beanClass 目标 bean 类型
     * @return 选中的构造器
     * @throws AmbiguousConstructorException   规则 3：多个 @Inject 构造器
     * @throws NoSuitableConstructorException 规则 4：多构造器无 @Inject
     */
    private static Constructor<?> selectConstructor(Class<?> beanClass)
            throws AmbiguousConstructorException, NoSuitableConstructorException {
        Constructor<?>[] all = beanClass.getDeclaredConstructors();
        if (all.length == 1) return all[0];                                                // 规则 1

        List<Constructor<?>> injected = new ArrayList<>();
        for (Constructor<?> c : all) {
            if (c.getAnnotation(Inject.class) != null) injected.add(c);
        }
        if (injected.size() == 1) return injected.get(0);                                  // 规则 2
        if (injected.size() > 1)  throw new AmbiguousConstructorException(beanClass, injected);  // 规则 3
        throw new NoSuitableConstructorException(beanClass);                               // 规则 4
    }

    /**
     * 按构造器参数解析——按 §4.5.4.10.2 规则：
     * <ol>
     *   <li>参数类型是 {@code java.util.Optional<T>} → resolveOptional(内层类型)</li>
     *   <li>{@code @io.edap.container.Optional} → 缺失 → null；找到 → bean</li>
     *   <li>{@code @Inject}（默认必需）→ 缺失 → 抛 NoSuchBeanException；找到 → bean</li>
     *   <li>单构造器 + 无任何注解 → 按类型自动注入（Spring 风格：单 ctor 无需显式 @Inject）</li>
     *   <li>多构造器 + 无 @Inject → null（多 ctor 必须显式标 @Inject 才能确定选哪个）</li>
     * </ol>
     */
    private Object[] ctorArgs(Constructor<?> ctor, BeanDef def) {
        Parameter[] params = ctor.getParameters();
        Object[] args = new Object[params.length];
        boolean singleCtor = def.beanClass().getDeclaredConstructors().length == 1;
        for (int i = 0; i < params.length; i++) {
            Class<?> type = params[i].getType();
            Inject ann = params[i].getAnnotation(Inject.class);

            // 规则 a：java.util.Optional<T> 特殊处理
            //         （FQN 避免与本类的 io.edap.container.Optional 注解同名冲突）
            if (type == java.util.Optional.class) {
                Class<?> inner = (Class<?>) ((ParameterizedType) params[i].getParameterizedType())
                                  .getActualTypeArguments()[0];
                args[i] = resolveOptional(inner);
                continue;
            }

            // 规则 e：@Named("xxx") → 按名解析（DAO 同类型多 bean 场景必须）。
            //         与 @Optional 同时存在时，缺失由 @Optional 控制是否降级为 null。
            //         优先级高于规则 d/b/c —— 只要显式标了名就不再走类型。
            Named namedAnn = params[i].getAnnotation(Named.class);
            if (namedAnn != null && !namedAnn.value().isEmpty()) {
                io.edap.microservice.annotation.Optional opt =
                        params[i].getAnnotation(io.edap.microservice.annotation.Optional.class);
                try {
                    args[i] = resolveDependencyByName(namedAnn.value(), type);
                } catch (NoSuchBeanException e) {
                    if (opt == null) throw e;
                    args[i] = null;
                }
                continue;
            }

            // 规则 d：无 @Inject → 单 ctor 按类型自动注入(Spring 风格),多 ctor 才给 null
            //         之前的实现无论单/多 ctor 都给 null,导致 @MicroServiceBean 单 ctor
            //         + 依赖 @Configuration.@Bean 的 bean 全部拿到 null 还"成功"实例化。
            if (ann == null) {
                if (!singleCtor) {
                    args[i] = null;
                    continue;
                }
                io.edap.microservice.annotation.Optional opt =
                        params[i].getAnnotation(io.edap.microservice.annotation.Optional.class);
                try {
                    args[i] = resolveDependencyByType(type);
                } catch (NoSuchBeanException e) {
                    if (opt == null) throw e;
                    args[i] = null;
                }
                continue;
            }

            // 规则 b/c：@Optional 标记 → 缺失 → null；@Inject（默认必需）→ 缺失 → 抛
            io.edap.microservice.annotation.Optional opt = params[i].getAnnotation(Optional.class);
            try {
                args[i] = resolveDependencyByType(type);
            } catch (NoSuchBeanException e) {
                if (opt == null) throw e;               // 规则 c：必需 → 抛
                args[i] = null;                         // 规则 b：可选 → null
            }
        }
        return args;
    }

    /**
     * java.util.Optional&lt;T&gt; 解析：找到 → Optional.of(bean)；找不到 → Optional.empty()。
     * 用于构造器参数解析（§4.5.4.10.2 规则 a），不抛 NoSuchBeanException。
     */
    private java.util.Optional<Object> resolveOptional(Class<?> inner) {
        try {
            return java.util.Optional.ofNullable(resolveDependencyByType(inner));
        } catch (NoSuchBeanException e) {
            return java.util.Optional.empty();
        }
    }

    /**
     * 依赖注入 + Aware 回调。顺序：Aware → @Inject 字段 → @Inject 方法。
     */
    public void injectDependencies(BeanDef def, Object instance) {
        injectAware(def, instance);          // 4 个 Aware 接口
        if (def.injections() == null || def.injections().isEmpty()) {
            return;
        }
        for (InjectionPoint ip : def.injections()) {
            if (ip.isField()) {
                Object dep = resolveDependency(ip);
                Field f = ip.field();
                f.setAccessible(true);
                try {
                    f.set(instance, dep);
                } catch (IllegalAccessException e) {
                    throw new BeanInjectFailedException(def.name(), f.getName(), e);
                }
            } else {                          // setter-style method
                Method m = ip.method();
                Object[] args = resolveMethodArgs(m, def);
                m.setAccessible(true);
                try {
                    m.invoke(instance, args);
                } catch (InvocationTargetException e) {
                    throw new BeanInjectFailedException(def.name(), m.getName(), e.getTargetException());
                } catch (IllegalAccessException e) {
                    throw new BeanInjectFailedException(def.name(), m.getName(), e);
                }
            }
        }
    }

    /**
     * 4 个 Aware 接口回调。顺序：ApplicationContextAware → EnvironmentAware → BeanNameAware → RouterHubAware。
     */
    private void injectAware(BeanDef def, Object instance) {
        if (instance instanceof ApplicationContextAware) {
            ((ApplicationContextAware) instance).setApplicationContext(this.appContext);
        }
        if (instance instanceof EnvironmentAware) {
            ((EnvironmentAware) instance).setEnvironment(this.env);
        }
        if (instance instanceof BeanNameAware) {
            ((BeanNameAware) instance).setBeanName(def.name());
        }
        if (instance instanceof RouterHubAware) {
            ((RouterHubAware) instance).setRouterHub(this.appContext.routers());
        }
    }

    /**
     * 单 InjectionPoint 解析：beanName 非空 → 按名;空/null → 按类型兜底
     * （覆盖 @Inject 字段未带 @Named 的情况,保持类型注入的零回归）。
     */
    private Object resolveDependency(InjectionPoint ip) {
        String bn = ip.beanName();
        if (bn != null && !bn.isEmpty()) {
            return resolveDependencyByName(bn, ip.requiredType());
        }
        return resolveDependencyByType(ip.requiredType());
    }

    /**
     * 方法注入参数解析：{@code @Named("xxx")} 按名，否则按类型；
     * {@code @Optional} 标记的参数缺失时降级为 null，未标记则抛。
     *
     * <p><b>为什么不按参数上的 {@code @Inject} 过滤</b>：{@code javax.inject.Inject} 的
     * {@code @Target} 只有 METHOD / CONSTRUCTOR / FIELD，参数上永远拿不到它——按它过滤
     * 等于所有参数恒为 null。{@code @Inject} 标在方法上，能进 {@code def.injections()}
     * 的 method IP 本身就是扫描期认定的注入点，参数全部注入即可（JSR-330 语义）。</p>
     */
    private Object[] resolveMethodArgs(Method m, BeanDef def) {
        Parameter[] params = m.getParameters();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            Class<?> type = params[i].getType();

            if (type == java.util.Optional.class) {
                Class<?> inner = (Class<?>) ((ParameterizedType) params[i].getParameterizedType())
                                  .getActualTypeArguments()[0];
                args[i] = resolveOptional(inner);
                continue;
            }

            io.edap.microservice.annotation.Optional opt =
                    params[i].getAnnotation(io.edap.microservice.annotation.Optional.class);
            Named named = params[i].getAnnotation(Named.class);
            try {
                args[i] = (named != null && !named.value().isEmpty())
                        ? resolveDependencyByName(named.value(), type)
                        : resolveDependencyByType(type);
            } catch (NoSuchBeanException e) {
                if (opt == null) throw e;
                args[i] = null;
            }
        }
        return args;
    }

    /**
     * 按类型 + @Primary 解析候选 bean（依赖注入 / getBean(type) 走它）。
     * <p>实现：直查 {@link #byType} O(1)，多候选时按 {@code @Primary} 消歧——
     *     与原"扫 singletons 全表 isInstance 过滤"等价但常数开销降到 1。
     *     零候选 → {@link NoSuchBeanException}；多候选无 @Primary 或多个 @Primary → {@link NoUniqueBeanException}。</p>
     */
    private Object resolveDependencyByType(Class<?> type) throws NoUniqueBeanException, NoSuchBeanException {
        if (type == AppContext.class)     return this.appContext;
        if (type == Environment.class)    return this.env;
        if (type == EventPublisher.class) return this.events;
        if (type == RouterHub.class)      return this.appContext.routers();
        if (type == ShardRegistry.class)  return this.shards;
        return beanWrapByType(type).instance();
    }

    /**
     * 按类型直查 {@code byType}，返回唯一 BeanWrap。多候选时按 {@code @Primary} 消歧。
     * <p>由 {@link #resolveDependencyByType} 复用，也是 {@link #getBean(Class)} 的核心。
     * <b>为什么 List value</b>：同 type 可能多实现（{@code class A implements I, class B implements I}）；
     *     List 保留所有候选，{@code @Primary} 消歧逻辑一处统一；不是 Map<type, Bean> 单值
     *     "last-write-wins"——那种写法碰到多实现会丢失早期的 bean。</p>
     *
     * <p><b>双层 fallback</b>：AppContext 级 miss 时自动 fallback 到
     *     {@code container.containerBeans()}（{@code container != null} 时）。
     *     走 AppContext → Container.beans 单向 fallback（Container.beans 自身不再 fallback）——
     *     保证 edap 框架默认 bean（如 {@code WSAuthenticator}）开箱即用，应用 bean 自动覆盖。</p>
     *
     * @throws NoSuchBeanException   byType 中无该 type 的注册（fallback 后仍无）
     * @throws NoUniqueBeanException 多候选 + 0/多个 @Primary
     */
    public BeanWrap beanWrapByType(Class<?> type) {
        BeanWrap bw = lookupLocal(type);
        if (bw != null) return bw;
        if (container != null) {
            bw = container.containerBeans().beanWrapByType(type);
            if (bw != null) return bw;
        }
        throw new NoSuchBeanException(type);
    }

    /**
     * 查当前 BeanContainer 的 byType（不 fallback）。返回 null = miss，
     * 由 {@link #beanWrapByType} / {@link #findBeanWrapByType} 决定后续 fallback 或抛错。
     */
    private BeanWrap lookupLocal(Class<?> type) {
        List<BeanWrap> list = byType.get(type);
        if (list != null && !list.isEmpty()) {
            return disambiguateByType(type, list);
        }
        return null;
    }

    /** Class identity 命中后的多候选 @Primary 消歧。 */
    private BeanWrap disambiguateByType(Class<?> type, List<BeanWrap> list) {
        if (list.size() == 1) return list.get(0);
        BeanWrap primary = null;
        for (BeanWrap bw : list) {
            if (bw.def().beanClass().isAnnotationPresent(Primary.class)) {
                if (primary != null) throw new NoUniqueBeanException(type, list);
                primary = bw;
            }
        }
        if (primary == null) throw new NoUniqueBeanException(type, list);
        return primary;
    }

    /**
     * 自类 + 父类链 + 全部接口（含父接口继承）的 Class token 集合。
     * <p>BFS：{@link ArrayDeque} 做层序，{@link LinkedHashSet} 同时去重 + 保遍历序确定性。
     *     {@code Object} 终止递归。</p>
     * <p>典型情况输出 ≈ 5-15 个 Class（一个含 2-3 个接口的 service 实现）；
     *     注册开销可忽略。</p>
     */
    private static Set<Class<?>> collectTypeTokens(Class<?> cls) {
        Set<Class<?>> tokens = new LinkedHashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>();
        queue.add(cls);
        while (!queue.isEmpty()) {
            Class<?> c = queue.poll();
            if (c == null || c == Object.class) continue;
            if (tokens.add(c)) {
                Collections.addAll(queue, c.getInterfaces());
                Class<?> sup = c.getSuperclass();
                if (sup != null) queue.add(sup);
            }
        }
        return tokens;
    }

    /** 按 bean 名直接取（InjectionPoint 编译期已绑定名）。 */
    private Object resolveDependencyByName(String beanName, Class<?> requiredType)
            throws NoSuchBeanException, BeanTypeMismatchException {
        BeanWrap bw = singletons.get(beanName);
        if (bw == null) throw new NoSuchBeanException(beanName);
        if (requiredType != null && !requiredType.isInstance(bw.instance())) {
            throw new BeanTypeMismatchException(beanName, requiredType, bw.instance().getClass());
        }
        return bw.instance();
    }

    /** 调 @PostConstruct / @Init。 */
    public void invokeInit(BeanDef def, Object instance) throws BeanInitFailedException {
        if (def.initMethod() == null) return;
        try {
            def.initMethod().setAccessible(true);
            def.initMethod().invoke(instance);
        } catch (InvocationTargetException e) {
            throw new BeanInitFailedException(def.name(), e.getTargetException());
        } catch (ReflectiveOperationException e) {
            throw new BeanInitFailedException(def.name(), e);
        }
    }

    /** 把 instance 存入 singletons（按 BeanDef.scope 选 SINGLETON / PROTOTYPE 路径）。
     *
     * <p>同时填充 {@link #byType}（自类 + 父类链 + 全部接口含父接口）——runtime @Inject
     * 类型注入 / {@link #getBean(Class)} / AppContext.generateAndBindRoutes 走 byType 直查。</p>
     *
     * <p>PROTOTYPE scope 不写入 byType（每次新建，不缓存）；分片实例注册由
     *     {@code @Sharded} 标注的方法扫描阶段单独触发
     *     {@code shards.registerSharded(...)}，本方法只管"主实例"的 SINGLETON / PROTOTYPE 落点。</p>
     */
    public void registerInstance(BeanDef def, Object instance) {
        if (def.scope() == Scope.SINGLETON) {
            BeanWrap wrap = new BeanWrap(def, instance);
            singletons.put(def.name(), wrap);
            for (Class<?> t : collectTypeTokens(instance.getClass())) {
                byType.computeIfAbsent(t, k -> new ArrayList<>()).add(wrap);
            }
        }
        // PROTOTYPE 不缓存
    }

    /**
     * 用新实例替换 BeanDef 对应的 SINGLETON —— 专为 {@link io.edap.container.BeanPostProcessor}
     * 织入 wrapper 设计:原实例已 register 过(byType 含其类型 token),若直接再
     * {@link #registerInstance} 会导致 byType 出现两个 BeanWrap,运行时
     * {@code getBean(Interface.class)} 抛 {@code NoUniqueBeanException}。
     *
     * <p>语义:
     * <ul>
     *   <li>从 byType 移除 oldInstance 的所有类型 token;</li>
     *   <li>用 newInstance 创建新 BeanWrap,按 name 覆盖 singletons,按 type token 注册到 byType;</li>
     *   <li>若 oldInstance 已经在 singletonsByName 里但 byType 没记录(理论上不应发生),
     *       直接按 name put 一次覆盖;</li>
     *   <li>PROTOTYPE scope 不处理(prototype 不缓存,无 wrapper 替换需求)。</li>
     * </ul>
     */
    public void replaceInstance(BeanDef def, Object oldInstance, Object newInstance) {
        if (def.scope() != Scope.SINGLETON) return;
        // 1. 移除 old 的 byType 索引
        BeanWrap oldWrap = singletons.get(def.name());
        if (oldWrap != null && oldWrap.instance() == oldInstance) {
            for (Class<?> t : collectTypeTokens(oldInstance.getClass())) {
                List<BeanWrap> list = byType.get(t);
                if (list != null) {
                    list.remove(oldWrap);
                    if (list.isEmpty()) byType.remove(t);
                }
            }
        }
        // 2. 注册 new —— 走 registerInstance 走全路径(name put + byType add)
        registerInstance(def, newInstance);
    }

    /** Phase 2 → Phase 3 状态迁移。 */
    public void transitionToReady() {
        transitionTo(BeanContainerState.READY);
    }

    // —— Phase 3 READY ——

    /** 启动所有 SmartLifecycle bean（按 @Order 升序）。 */
    public void startLifecycles() throws LifecycleStartFailedException {
        List<BeanWrap> ordered = singletons.values().stream()
                .filter(bw -> bw.instance() instanceof Lifecycle)
                .sorted(Comparator
                        .comparingInt((BeanWrap bw) -> bw.def().order())
                        .thenComparing(bw -> bw.def().name()))
                .collect(Collectors.toList());
        for (BeanWrap bw : ordered) {
            try {
                ((Lifecycle) bw.instance()).start();
            } catch (Throwable t) {
                throw new LifecycleStartFailedException(bw.def().name(), t);
            }
        }
    }

    // —— 运行时查询（多线程读）——

    /** 按 BeanDef.name() 查 BeanWrap(返回 null = miss,不抛)。仅查本容器,不做 fallback。 */
    public BeanWrap beanWrapByName(String name) {
        return singletons.get(name);
    }

    public Object getBean(String name) throws NoSuchBeanException {
        BeanWrap bw = singletons.get(name);
        if (bw == null && container != null) {
            bw = container.containerBeans().singletonsByName().get(name);
        }
        if (bw == null) throw new NoSuchBeanException(name);
        return bw.instance();
    }

    public <T> T getBean(String name, Class<T> type) throws NoSuchBeanException {
        return type.cast(getBean(name));
    }

    /** 暴露 singletons 供同包/同模块其它 BeanContainer 做按名 fallback；不做任何拷贝。 */
    Map<String, BeanWrap> singletonsByName() {
        return singletons;
    }

    /**
     * 按 type 查 bean 实例（O(1) 直查 {@link #byType}）。
     * <p>多实现 + @Primary 消歧逻辑与 {@link #resolveDependencyByType} 共用
     *     {@link #beanWrapByType}。</p>
     *
     * @throws NoSuchBeanException   byType 中无该 type 的注册
     * @throws NoUniqueBeanException 多候选 + 0/多个 @Primary
     */
    public Object getBean(Class<?> type) throws NoSuchBeanException, NoUniqueBeanException {
        return beanWrapByType(type).instance();
    }

    /**
     * 同 {@link #getBean(Class)} 但返回 BeanWrap（含 def.name()，
     * AppContext.generateAndBindRoutes 需要 beanName 给 ShardRegistry 用）。
     *
     * <p>无匹配返回 null（不同于 {@link #beanWrapByType} 抛 NoSuchBeanException
     *     ——router 生成阶段对未实现接口选择性跳过，<b>不</b>中断启动）。</p>
     *
     * <p>同样支持 fallback 到 {@code container.containerBeans()}：
     *     AppContext 级 miss 时查 Container.beans，开箱即用框架默认 bean。</p>
     */
    public BeanWrap findBeanWrapByType(Class<?> type) {
        BeanWrap bw = findLocal(type);
        if (bw != null) return bw;
        if (container != null) {
            bw = container.containerBeans().findBeanWrapByType(type);
            if (bw != null) return bw;
        }
        return null;
    }

    /** 查当前 BeanContainer byType（不 fallback）。返回 null = miss，无歧义抛错。 */
    private BeanWrap findLocal(Class<?> type) {
        List<BeanWrap> list = byType.get(type);
        if (list == null || list.isEmpty()) return null;
        if (list.size() == 1) return list.get(0);
        BeanWrap primary = null;
        for (BeanWrap bw : list) {
            if (bw.def().beanClass().isAnnotationPresent(Primary.class)) {
                if (primary != null) return null;       // 多个 @Primary：路由阶段视作歧义，返回 null
                primary = bw;
            }
        }
        return primary != null ? primary : list.get(0);  // 多实现无 @Primary：取首个（注册顺序）
    }

    public boolean containsBean(String name) {
        return singletons.containsKey(name);
    }

    /** Container.bindAll 用：拿所有 singleton BeanWrap，弱一致迭代。 */
    public Collection<BeanWrap> singletons() {
        return singletons.values();
    }

    /** BeanContainer 持有的 appCL（AppContext 持有真 CL，BeanContainer 只透传）。 */
    public ClassLoader appClassLoader() {
        return appContext.appCL();
    }

    // —— 销毁（AppContext.stop 单线程）——

    /** 逆序：Lifecycle.stop() → @PreDestroy → 清空 singletons。
     *  异常一律记 WARN 继续——已 unbind 路由，业务不会再到这。 */
    public void destroyAllSingletons() {
        transitionTo(BeanContainerState.DESTROYING);

        // 1. 逆序 Lifecycle.stop()
        List<BeanWrap> ordered = new ArrayList<>(singletons.values());
        ordered.sort(Comparator
                .comparingInt((BeanWrap bw) -> bw.def().order())
                .reversed());
        for (BeanWrap bw : ordered) {
            if (!(bw.instance() instanceof Lifecycle)) continue;
            try { ((Lifecycle) bw.instance()).stop(); }
            catch (Throwable t) {
                log.warn("Lifecycle.stop failed for {}", l-> l.arg(bw.def().name()).threw(t));
            }
        }

        // 2. 逆序 @PreDestroy
        for (BeanWrap bw : ordered) {
            Method dm = bw.def().destroyMethod();
            if (dm == null) continue;
            try {
                dm.setAccessible(true);
                dm.invoke(bw.instance());
            } catch (Throwable t) {
                log.warn("@PreDestroy failed for {}", l-> l.arg(bw.def().name()).threw(t));
            }
        }

        // 3. 清空 singletons + byType（@Sharded 方法的分片实例也由 ShardRegistry 释放）
        singletons.clear();
        byType.clear();
        shards.clear();
        transitionTo(BeanContainerState.DESTROYED);
    }

    /** destroyAll 是 destroyAllSingletons 的语义别名（classDiagram 兼容）。 */
    public void destroyAll() { destroyAllSingletons(); }

    // —— 访问器 ——

    public BeanContainerState state()   { return state; }
    public int                 size()   { return singletons.size(); }
    public List<BeanDef>       sorted() { return sorted; }
}
