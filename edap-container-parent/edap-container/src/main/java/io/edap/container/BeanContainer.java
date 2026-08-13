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

    /** COMMITTING 阶段内、循环依赖检测用：当前正在 instantiate 的 bean name。 */
    private final HashSet<String> creating = new HashSet<>();

    /** 所有已实例化 BeanDef 的最终顺序（topologicalSort 输出）；Phase 2 用。 */
    private List<BeanDef> sorted = List.of();

    private final Environment       env;
    private final EventPublisher    events;
    private final AppContext        appContext;
    private final ShardRegistry     shards;

    private volatile BeanContainerState state = BeanContainerState.COLLECTING;

    public BeanContainer(AppContext appContext, Environment env, EventPublisher events,
                         ShardRegistry shards) {
        this.appContext = appContext;
        this.env        = env;
        this.events     = events;
        this.shards     = shards;
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
        List<BeanDef> result = new ArrayList<>(definitions.size());
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        for (BeanDef def : definitions.values()) {
            dfs(def, visited, inStack, result);
        }
        // 同层（依赖集相同）按 @Order 升序，再按 name 字典序
        result.sort(Comparator
                .comparingInt((BeanDef d) -> d.order())
                .thenComparing(BeanDef::name));
        this.sorted = List.copyOf(result);
        return this.sorted;
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

    /** 实例化（不注入、不调 init）。selectConstructor 按 §4.5.4.10.1 规则选构造器；ctorArgs 按 §4.5.4.10.2 解析参数。 */
    public Object instantiate(BeanDef def) throws BeanInstantiationException, CyclicDependencyException {
        state.checkTransitionGuard(BeanContainerState.INSTANTIATING);
        if (creating.contains(def.name())) {
            throw new CyclicDependencyException("creating already has " + def.name());
        }
        creating.add(def.name());
        try {
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
     *   <li>无 @Inject 注解 → null（保留原行为，业务代码自主处理）</li>
     * </ol>
     */
    private Object[] ctorArgs(Constructor<?> ctor, BeanDef def) {
        Parameter[] params = ctor.getParameters();
        Object[] args = new Object[params.length];
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

            // 规则 d：无 @Inject → null
            if (ann == null) {
                args[i] = null;
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
     * 单 InjectionPoint 解析：直接按 ip 所指示的 bean 名（构建期已绑定）取。
     */
    private Object resolveDependency(InjectionPoint ip) {
        return resolveDependencyByName(ip.beanName(), ip.requiredType());
    }

    /** 方法注入参数解析：按参数类型递归 getBean（仅 @Inject 标注参数）。 */
    private Object[] resolveMethodArgs(Method m, BeanDef def) {
        Parameter[] params = m.getParameters();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            if (params[i].getAnnotation(Inject.class) == null) {
                args[i] = null;
            } else {
                args[i] = resolveDependencyByType(params[i].getType());
            }
        }
        return args;
    }

    /** 按类型 + @Primary 解析候选 bean。 */
    private Object resolveDependencyByType(Class<?> type) throws NoUniqueBeanException, NoSuchBeanException {
        if (type == AppContext.class)     return this.appContext;
        if (type == Environment.class)    return this.env;
        if (type == EventPublisher.class) return this.events;
        if (type == RouterHub.class)      return this.appContext.routers();
        if (type == ShardRegistry.class)  return this.shards;

        List<BeanWrap> candidates = new ArrayList<>();
        for (BeanWrap bw : singletons.values()) {
            if (type.isInstance(bw.instance())) {
                candidates.add(bw);
            }
        }
        if (candidates.isEmpty()) throw new NoSuchBeanException(type);
        if (candidates.size() == 1) return candidates.get(0).instance();

        // 多个候选：@Primary 消歧
        BeanWrap primary = null;
        for (BeanWrap bw : candidates) {
            if (bw.def().beanClass().isAnnotationPresent(Primary.class)) {
                if (primary != null) throw new NoUniqueBeanException(type, candidates);
                primary = bw;
            }
        }
        if (primary == null) throw new NoUniqueBeanException(type, candidates);
        return primary.instance();
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

    /** 把 instance 存入 singletons（按 BeanDef.scope 选 SINGLETON / PROTOTYPE 路径）。 */
    public void registerInstance(BeanDef def, Object instance) {
        if (def.scope() == Scope.SINGLETON) {
            singletons.put(def.name(), new BeanWrap(def, instance));
        }
        // PROTOTYPE 不缓存
        // 分片实例的注册由 @Sharded 标注的方法扫描阶段单独触发 shards.registerSharded(...)，
        // 本方法只管"主实例"的 SINGLETON / PROTOTYPE 落点。
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

    public Object getBean(String name) throws NoSuchBeanException {
        BeanWrap bw = singletons.get(name);
        if (bw == null) throw new NoSuchBeanException(name);
        return bw.instance();
    }

    public <T> T getBean(String name, Class<T> type) throws NoSuchBeanException {
        return type.cast(getBean(name));
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

        // 3. 清空 singletons（@Sharded 方法的分片实例也由 ShardRegistry 释放）
        singletons.clear();
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
