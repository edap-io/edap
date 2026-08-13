/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.container;

import io.edap.Server;
import io.edap.container.app.ErpcRouteEntry;
import io.edap.container.app.GrpcRouteEntry;
import io.edap.container.app.HttpRouteEntry;
import io.edap.container.app.RouterHub;
import io.edap.container.app.WsRouteEntry;
import io.edap.container.app.asm.HandlerAsmGenerator;
import io.edap.container.event.ApplicationEvent;
import io.edap.container.event.ContextClosedEvent;
import io.edap.container.event.ContextRefreshedEvent;
import io.edap.container.event.EventPublisher;
import io.edap.container.exc.RouteBindException;
import io.edap.container.mw.AnnoData;
import io.edap.container.mw.DeployComponent;
import io.edap.container.mw.DeployMetaData;
import io.edap.container.mw.ServiceMeta;
import io.edap.container.scan.EarScanner;
import io.edap.container.ws.WSServiceMsgHandler;
import io.edap.grpc.GrpcHandler;
import io.edap.http.HttpHandler;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.microservice.Scope;
import io.edap.microservice.annotation.Bean;
import io.edap.microservice.annotation.MicroServiceBean;
import io.edap.props.Props;
import io.edap.rpc.ErpcHandler;
import io.edap.util.CollectionUtils;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单个应用（{@code appId:version}）的运行期容器；由 {@link Container#deploy} 创建。
 * 完整生命周期：{@link #start()} 三段式（gather / commit / ready）→ {@link #stop()} 逆序。
 *
 * <p>三段式 + 路由绑定契约：</p>
 * <ul>
 *   <li>Phase 1 GATHERING：扫 EAR（{@link EarScanner} + {@code NestedJarScanner}）汇总 BeanDef，<b>不实例化</b></li>
 *   <li>Phase 2 COMMITTING：拓扑排序 → 逐 BeanDef instantiate / inject / @PostConstruct</li>
 *   <li>Phase 3 READY：{@code BeanContainer.startLifecycles()} → 内部 {@link #generateAndBindRoutes()}
 *       生成 4 份协议 Handler + 一次性写入 RouterHub；Container 仅负责后续的 registry 槽位
 *       写入 + currentRouters 指针切换（详见 §3.5.7）</li>
 * </ul>
 *
 * <p>AppContext <b>不持有兄弟 AppContext</b>；跨应用通信走 eRPC/gRPC。流量入口归 Container 的
 * {@code currentRouters[appId]} 指针——Edap 只持 NIO 抽象，不知道路由长什么样。</p>
 *
 * <p><b>路由绑定契约</b>：AppContext 在 Phase 3 末尾独立完成"生成 4 份协议 Handler → 一次性
 * 写入 {@link RouterHub#setHandlers}"。Container 不参与 Handler 生成、不知道 RouteEntry 形态，
 * 只在 Phase 3 成功后把本 AppContext 的路由集合挂到 NIO ServerGroup 的对应 appId 槽位。
 * 这样路由绑定与 appCL 绑定在同一生命周期，{@link #stop()} 期间可一并清空（防 appCL 内存泄漏）。</p>
 *
 * <p>全部生命周期阶段由 {@code Container.deploy} / {@code undeploy} / {@code stop} 触发，
 * 不直接被业务代码调。</p>
 */
public class AppContext implements Lifecycle {

    static Logger log = LoggerManager.getLogger(AppContext.class);

    // ─── 标识 ───
    private final String             appId;
    private final String             version;       // composite version（SNAPSHOT 拼接 buildTime）
    private final DeployMetaData     dmd;           // 部署元数据快照（不可变）
    private final EdapAppClassLoader appCL;         // per-app 隔离 CL；stop() 期间 close

    // ─── 容器归属 ───
    private final Container          container;     // 父容器：访问 deployMgr / edap / currentRouters

    // ─── 子系统（构造期组装，不做扫描 / 不做实例化）───
    private final Environment        env;           // 配置视图（build.json + Container.env）
    private final EventPublisher     events;        // 内部事件总线
    private final RouterHub          routers;       // 4 份协议 Handler List 的被动持有者
    private final BeanContainer      beans;         // Bean 装配核心
    private final ShardRegistry      shards;        // @Sharded 方法所属 bean 的分片实例
    private final AppResourceLoader  resourceLoader;// 通过 appCL 读 jar 内资源
    private final List<BeanPostProcessor> postProcessors = new ArrayList<>(); // Bean 初始化前后钩子

    /** Container.deploy() 末尾把本 AppContext 的 NIO Server 注册到 ServerGroup 时写入。 */
    private final List<Server>       servers = new ArrayList<>();

    // ─── 路由条目（Phase 3 内部 generateAndBindRoutes() 的输入；Phase 1 GATHERING 期间由 scanRouteEntries 汇总） ───
    private final List<HttpRouteEntry>  httpRoutes = new ArrayList<>();
    private final List<WsRouteEntry>    wsRoutes   = new ArrayList<>();
    private final List<ErpcRouteEntry>  erpcRoutes = new ArrayList<>();
    private final List<GrpcRouteEntry>  grpcRoutes = new ArrayList<>();

    // ─── ASM 生成 Handler impl class 的缓存 ───
    // 挂在 AppContext 上（不是 Container 单例），原因：Method → Class → genCL →(parent)→ appCL
    // 整条引用链必须与 ctx 同生死；放 Container 上会永久持有 appCL 引用 → appCL 永远 GC 不掉
    // （违反 §3.8 防内存泄漏不变量）。详见 §3.5.7 缓存归属说明。
    private final Map<HandlerKey, Class<?>>           generatedHandlers = new ConcurrentHashMap<>();
    private final Map<ClassLoader, ClassLoader>       generatedCLs      = new ConcurrentHashMap<>();

    private volatile AppState        state = AppState.NEW;

    public AppContext(Container container, String appId, String version,
                      EdapAppClassLoader appCL, DeployMetaData dmd) {
        this.container = container;
        this.appId     = appId;
        this.version   = version;
        this.appCL     = appCL;
        this.dmd       = dmd;

        this.env            = new Environment(this, container.env(), loadBuildJsonProps());
        this.events         = new EventPublisher();
        this.shards         = new ShardRegistry();                   // shardCount 由 ClusterShardRouter 运行时决定
        this.beans          = new BeanContainer(this, env, events, shards);
        this.routers        = new RouterHub();
        this.resourceLoader = new AppResourceLoader(appCL);
        // 构造函数到此为止——不做扫描、不做实例化、不调 Lifecycle.start
    }

    /**
     * 状态迁移封装：canTransitionTo 校验 → 写回 state 字段。
     * 由 lifecycleLock 串行化（start / stop 调用方），单线程写。
     * 不暴露为 public——只有本类的 start() / stop() 内部调，杜绝外部乱跳状态。
     */
    private void transitionTo(AppState to) {
        if (!state.canTransitionTo(to)) {
            throw new IllegalStateException(
                    "Illegal AppState transition: " + state + " -> " + to);
        }
        state = to;
    }

    /** 读 build.json 的 env 段（注入 Environment 优先级链第 3 层，详见 §4.8.3）。 */
    private Props loadBuildJsonProps() {
        // 由 dmd.getBuildInfo() + dmd.getComponentMap() 归并出 Props；
        // 字段级 schema（routePrefix 等）不在 Props 里——直接由 BeanContainer 读取
        Props props = new Props(new HashMap<>());
        return props;
    }

    @Override
    public void start() throws Throwable {
        transitionTo(AppState.GATHERING);                 // NEW -> GATHERING
        try {
            // Phase 1 GATHERING：扫 EAR → BeanDef + 4 份 RouteEntry 列表
            List<BeanDef> defs = scanBeanDefs();
            for (BeanDef def : defs) {
                beans.register(def);
            }
            beans.topologicalSort();                            // 循环依赖此处抛
            scanRouteEntries();                                 // 汇总 4 份 RouteEntry（httpRoutes/wsRoutes/erpcRoutes/grpcRoutes）

            // Phase 2 COMMITTING：实例化 → 注入 → init
            beans.transitionToCommitting();                     // COLLECTING -> INSTANTIATING
            for (BeanDef def : beans.sorted()) {
                Object instance = beans.instantiate(def);
                beans.injectDependencies(def, instance);
                beans.invokeInit(def, instance);
                beans.registerInstance(def, instance);
            }
            beans.transitionToReady();                          // INSTANTIATING -> READY

            transitionTo(AppState.COMMITTING);             // GATHERING -> COMMITTING

            // Phase 3 READY：Lifecycle.start() + 路由 bind
            beans.startLifecycles();
            generateAndBindRoutes();                             // ASM 生成 4 份 Handler + 一次性写入 RouterHub
            // Container 在 deploy() 末尾 / switchVersion() / 启动恢复时统一切 currentRouters 指针
            // —— AppContext 不回调 Container，保持单向数据流

            transitionTo(AppState.READY);                  // COMMITTING -> READY
            transitionTo(AppState.RUNNING);                // READY -> RUNNING

            events.publish(new ContextRefreshedEvent(this));
        } catch (Throwable t) {
            transitionTo(AppState.FAILED);
            throw t;
        }
    }



    /** SIGTERM / undeploy / switchVersion 时的标准停止路径。幂等。 */
    @Override
    public void stop() throws Throwable {
        AppState cur = state;
        if (cur == AppState.NEW || cur == AppState.STOPPED) return;
        if (cur == AppState.STOPPING) return;
        transitionTo(AppState.STOPPING);

        Throwable firstErr = null;
        // 1. 路由摘除（让 in-flight 之外不再有请求到达本 AppContext）
        try { routers.unbindAll(); }
        catch (Throwable t) { firstErr = t; }

        // 2. 逆序：Lifecycle.stop / @PreDestroy / 清空 singletons（含 ShardRegistry 分片）
        try { beans.destroyAllSingletons(); }
        catch (Throwable t) { if (firstErr == null) firstErr = t; }

        // 3. 事件总线清空（释放 listener 引用链）
        try { events.clear(); }
        catch (Throwable t) { if (firstErr == null) firstErr = t; }

        // 4. 关 per-app ClassLoader（释放 jar 文件句柄 + class 缓存，防泄漏）
        try { appCL.close(); }
        catch (Throwable t) { if (firstErr == null) firstErr = t; }

        // 5. 清空 ASM 生成 Handler 缓存（释放 genCL → appCL 引用链）
        try { generatedHandlers.clear(); generatedCLs.clear(); }
        catch (Throwable t) { if (firstErr == null) firstErr = t; }

        // 6. ContextClosedEvent（监听器抛错不影响 stop 整体）
        try { events.publish(new ContextClosedEvent(this)); }
        catch (Throwable t) { /* ignored */ }

        if (firstErr != null) {
            transitionTo(AppState.FAILED);
            throw firstErr;
        }
        transitionTo(AppState.STOPPED);
    }

    /**
     * 扫 EAR：EarScanner 解析 META-INF/maven + BUILD.json；NestedJarScanner 抽每个 .jar
     * 内的注解元数据；本方法把它们归并成本 AppContext 的 BeanDef 列表。
     *
     * <p>业务注解过滤（{@code @ProtoService} / {@code @MicroServiceBean} / {@code @Bean}）在
     * NestedJarScanner.visitAnnotation 阶段完成（详见 §4.4.1）。</p>
     */
    private List<BeanDef> scanBeanDefs() throws Exception {
        List<BeanDef> beanDefs = new ArrayList<>();
        for (Map.Entry<String, DeployComponent> entry : dmd.getComponentMap().entrySet()) {
            buildBeanDef(entry.getValue().getServiceMetaMap(), beanDefs);
        }
        return beanDefs;
    }

    private void buildBeanDef(Map<String, ServiceMeta> serviceMetaMap, List<BeanDef> beanDefs) {
        if (CollectionUtils.isEmpty(serviceMetaMap)) {
            return;
        }
        for (Map.Entry<String, ServiceMeta> entry : serviceMetaMap.entrySet()) {
            ServiceMeta meta = entry.getValue();
            String name;
            Scope scope = Scope.SINGLETON;
            Map<String, AnnoData> annoDatas = meta.getAnnoDatas();
            String microServicerBeanName = MicroServiceBean.class.getName();
            String beanAnnName = Bean.class.getName();
            if (annoDatas.containsKey(microServicerBeanName)) {
                AnnoData microServiceBeanAnn = annoDatas.get(microServicerBeanName);
                String beanName = (String)microServiceBeanAnn.getValues().get("name");
                if (beanName != null && beanName.trim().length() > 0) {
                    name = beanName;
                } else {
                    name = beanSimpleName(meta.getClassName());
                }
                String annScope = (String)microServiceBeanAnn.getValues().get("scope");
                if (annScope != null && annScope.trim().length() > 0) {
                    try {
                        scope = Scope.valueOf(annScope);
                    } catch (Exception e) {
                        log.warn("scope {} valueOf error", l -> l.arg(annScope).threw(e));
                    }
                }
            } else if (annoDatas.containsKey(beanAnnName)) {
                AnnoData beanAnn = annoDatas.get(beanAnnName);
                String beanName = (String)beanAnn.getValues().get("name");
                if (beanName != null && beanName.trim().length() > 0) {
                    name = beanName;
                } else {
                    name = beanSimpleName(meta.getClassName());
                }
                String annScope = (String)beanAnn.getValues().get("scope");
                if (annScope != null && annScope.trim().length() > 0) {
                    try {
                        scope = Scope.valueOf(annScope);
                    } catch (Exception e) {
                        log.warn("scope {} valueOf error", l -> l.arg(annScope).threw(e));
                    }
                }
            } else {
                name = beanSimpleName(meta.getClassName());
            }
            try {
                Class<?> beanCls = Class.forName(meta.getClassName(), false, appCL);
                beanDefs.add(new BeanDef(name, beanCls, scope, null, null, null, null, 0));
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private String beanSimpleName(String name) {
        int index = name.lastIndexOf(".");
        if (index != -1) {
            name = name.substring(index + 1);
        }
        return name.substring(0, 1).toLowerCase(Locale.ENGLISH) + name.substring(1);
    }

    /**
     * 汇总 4 份 RouteEntry 列表（Phase 1 末尾、Phase 2 之前）。
     *
     * <p>数据源：dmd.protoHttpMap / dmd.protoWebSocketMap（来自 EarScanner.filterProtoHttp）；
     * eRPC / gRPC 的 RouteEntry 列表当前为空（eRPC / gRPC option 解析尚未在
     * EarScanner 中实现，相关字段由后续 PR 补全）。</p>
     */
    private void scanRouteEntries() {
        Set<Capability> capabilities = container.capabilities();
        // HTTP：从 dmd.protoHttpMap（path → ProtoMethodData）汇总
        for (Map.Entry<String, DeployComponent> e : dmd.getComponentMap().entrySet()) {
            // 占位实现：EarScanner 当前的 ProtoMethodData → HttpRouteEntry 转换尚未落地
            // 等 Stage 3 option 体系稳定后由 EarScanner 直接产出 HttpRouteEntry，
            // 本方法改为直接遍历 dmd.getHttpRoutes() / dmd.getWsRoutes() 等。
        }
        // WS：当前 EarScanner 产出的是 Map<String, Map<String, ProtoMethodData>>，
        //     与 WsRouteEntry 一对一转换同样尚未实现
        // eRPC / gRPC：proto 层目前没有对应 option，列表保持空
    }

    /**
     * 把 (targetIf, entry, bean, method) 桥接为可立即 dispatch 的协议 typed Handler 实例。
     *
     * <p>同一 (targetIf, Method) 二元组只生成一次 Handler impl class（{@code generatedHandlers} 缓存）。
     * 生成类实现 {@code targetIf} 接口（HttpHandler / WSServiceMsgHandler / ErpcHandler / GrpcHandler）。
     * 生成类的 ClassLoader parent = appCL（能引用 appCL 加载的 bean 类 / entry 类）。
     * 缓存挂在 ctx 上：AppContext.stop() 后整条引用链断开，appCL 可 GC。</p>
     *
     * <p>当 {@code entry.shard() == true} 时，生成 Handler 持有 ShardRegistry 引用，
     * handle 内部按 shardKey 选实例。</p>
     *
     * @param targetIf 协议 typed Handler 接口的 Class 对象（HttpHandler.class / WSServiceMsgHandler.class / ...）
     * @param entry    具体 RouteEntry / GrpcMethodEntry
     * @param bean     已实例化的 bean
     * @param method   bean 上的目标 Method（已 setAccessible(true)）
     * @param shards   ShardRegistry 引用（entry.shard() == true 时生成 Handler 内部使用；否则可传 null）
     * @return 新实例化的 targetIf 实例
     * @throws RouteBindException ASM 生成 / 类加载 / 实例化失败时抛出
     */
    @SuppressWarnings("unchecked")
    public <T> T generateHandler(Class<T> targetIf, Object entry, Object bean, Method method,
                                 ShardRegistry shards) {
        Class<?> beanClass = bean.getClass();
        HandlerKey cacheKey = new HandlerKey(targetIf, method);

        // 1. 缓存查找：同一 (targetIf, Method) → 复用之前生成的 Handler impl class
        Class<?> handlerClass = generatedHandlers.computeIfAbsent(cacheKey, k -> {
            // 2. 拿生成类专用 ClassLoader（parent = appCL）
            ClassLoader appCL = beanClass.getClassLoader();      // bean 必被 appCL 加载
            ClassLoader genCL = generatedCLs.computeIfAbsent(appCL, cl -> new GeneratedClassLoader(cl));

            // 3. ASM 字节码生成（无状态工具 HandlerAsmGenerator.INSTANCE，不持有 app 状态）
            byte[] bytes = HandlerAsmGenerator.INSTANCE.generateHandlerClass(
                    targetIf, k.method(), entry.getClass(), beanClass);

            // 4. 加载类（defineClass 不走双亲委派；走 genCL → parent (appCL) 解析 bean/entry 类型）
            try {
                return Class.forName(
                        HandlerAsmGenerator.INSTANCE.className(targetIf, k.method()),
                        true, genCL);
            } catch (ClassNotFoundException e) {
                throw new RouteBindException(bean, k.method().getName(), k.method().getParameterTypes(), e);
            }
        });

        // 5. 反射实例化：(bean, entry, shards) → 构造器签名 (beanClass, entryClass, ShardRegistry)
        try {
            return (T) handlerClass
                .getConstructor(beanClass, entry.getClass(), ShardRegistry.class)
                .newInstance(bean, entry, shards);
        } catch (ReflectiveOperationException ex) {
            throw new RouteBindException(bean, method.getName(), method.getParameterTypes(), ex);
        }
    }

    /**
     * Phase 3 末尾调用：遍历 4 份 RouteEntry → 逐条 {@link #generateHandler} → 一次性写入
     * {@link RouterHub#setHandlers}。
     *
     * <p><b>按节点能力过滤</b>：4 份 RouteEntry 都会按 {@link Container#capabilities()} 过滤——
     * 节点不具备的能力（如 eRPC 节点没有 {@link Capability#HTTP}），即使应用里写了
     * {@code @HttpRoute}，也不会生成对应 Handler，避免白生成 + 永不 dispatch 的死代码。
     * 过滤点放在 Phase 3 而非 Phase 1 的 {@code scanRouteEntries}：
     * <ul>
     *   <li>RouteEntry 本身很小（POJO），内存占用可忽略</li>
     *   <li>scanRouteEntries 不依赖 Container 状态，保持纯解析职责</li>
     *   <li>未来节点能力热调整（hotswap capability）只需重跑本方法，不需要重扫 EAR</li>
     * </ul></p>
     *
     * <p><b>为什么在这里做、而不是 Container.bindAll</b>：Handler 类生成依赖 appCL（bean/entry
     * 类型由 appCL 解析），同时生成结果（generatedHandlers 缓存 + generatedCLs ClassLoader 映射）
     * 必须与 AppContext 同生死——否则 appCL 引用链会跨 stop 边界泄漏。
     * 把生成逻辑挪到 Container 会让 Container 单例永久持有 appCL 引用，违反 §3.8 防内存泄漏不变量。</p>
     *
     * <p><b>为什么 eRPC/gRPC 要切 TCCL</b>：{@code ErpcHandler.generateHandler} 内部要从
     * requestType 字符串解析 Class（appCL 加载），{@link java.lang.Class#forName(String)} 默认走
     * caller 的 ClassLoader——非 appCL。切到 TCCL 才能保证 appCL 找到类。</p>
     *
     * <p><b>失败处理</b>：任一 RouteEntry 生成失败 → 抛 {@link RouteBindException} →
     * start() 捕到后状态转 FAILED，Container.deploy 失败回滚。</p>
     */
    private void generateAndBindRoutes() {
        List<HttpHandler> httpH = new ArrayList<>(httpRoutes.size());
        List<WSServiceMsgHandler<?>> wsH = new ArrayList<>(wsRoutes.size());
        List<ErpcHandler> erpcH = new ArrayList<>(erpcRoutes.size());
        List<GrpcHandler> grpcH = new ArrayList<>(grpcRoutes.size());

        // eRPC requestType / gRPC reqDesc-respDesc 解析需要 appCL；切 TCCL 让 Class.forName 走 appCL
        ClassLoader prevCL = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(appCL);
        try {
            // 1. HTTP —— 节点具备 HTTP 能力才生成
            if (container.hasCapability(Capability.HTTP)) {
                for (HttpRouteEntry e : httpRoutes) {
                    Object bean = resolveBean(e.beanName());
                    Method m = resolveMethod(bean, e.methodName(), httpParamTypes(e));
                    httpH.add(generateHandler(HttpHandler.class, e, bean, m, shards));
                }
            }
            // 2. WS —— 节点具备 WS 能力才生成（HTTP + WS 是两个独立能力，见 Capability 注释）
            if (container.hasCapability(Capability.WS)) {
                for (WsRouteEntry e : wsRoutes) {
                    Object bean = resolveBean(e.beanName());
                    Method m = resolveMethod(bean, e.methodName(),
                            new Class<?>[]{ Class.forName(e.msgType(), false, appCL) });
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    WSServiceMsgHandler h = (WSServiceMsgHandler)generateHandler(
                            (Class) WSServiceMsgHandler.class, e, bean, m, shards);
                    wsH.add(h);
                }
            }
            // 3. eRPC
            if (container.hasCapability(Capability.ERPC)) {
                for (ErpcRouteEntry e : erpcRoutes) {
                    Object bean = resolveBean(e.beanName());
                    Method m = resolveMethod(bean, e.methodName(),
                            new Class<?>[]{ Class.forName(e.requestType(), false, appCL) });
                    erpcH.add(generateHandler(ErpcHandler.class, e, bean, m, shards));
                }
            }
            // 4. gRPC：每个 GrpcRouteEntry 含多个 GrpcMethodEntry → 每个方法一个 Handler
            if (container.hasCapability(Capability.GRPC)) {
                for (GrpcRouteEntry e : grpcRoutes) {
                    Object bean = resolveBean(e.serviceName());
                    for (GrpcRouteEntry.GrpcMethodEntry me : e.methods()) {
                        Method m = resolveMethod(bean, me.javaMethodName(),
                                new Class<?>[]{ Class.forName(me.reqDesc(), false, appCL) });
                        grpcH.add(generateHandler(GrpcHandler.class, me, bean, m, shards));
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(prevCL);
        }
        // 5. 一次性写入 RouterHub（替换原 4 份 Handler List 的原子操作）
        routers.setHandlers(httpH, wsH, erpcH, grpcH);
    }

    /** 从 BeanContainer 按 name 拿已实例化的 bean；找不到 → BeanContainer 内部抛 NoSuchBeanException（启动期 fail-fast）。 */
    private Object resolveBean(String beanName) {
        return beans.getBean(beanName);
    }

    /** 按 bean + methodName + 参数类型在 bean 类上找到 Method；找不到 → 抛 RouteBindException。 */
    private Method resolveMethod(Object bean, String methodName, Class<?>[] paramTypes) {
        Class<?> cls = bean.getClass();
        try {
            Method m = cls.getMethod(methodName, paramTypes);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException ex) {
            throw new RouteBindException(bean, methodName, paramTypes, ex);
        }
    }

    /** HttpRouteEntry → 协议方法参数 Class[]；当前实现直接走 entry.pathParams()（占位，待 Stage 3 稳定后调整）。 */
    private Class<?>[] httpParamTypes(HttpRouteEntry e) {
        // TODO Stage 3：按 pathParams + body + @HttpRoute(method, path, body) 推出参数类型
        return new Class<?>[0];
    }

    /**
     * 注册 BeanPostProcessor（在 BeanContainer.injectAware 阶段被回调，
     * 允许外部逻辑在 Bean 初始化前后插入切面）。单线程调用方：扫描器/扩展插件。
     */
    public void addBeanPostProcessor(BeanPostProcessor bpp) {
        postProcessors.add(bpp);
    }

    /** 返回已注册的 BeanPostProcessor 列表（BeanContainer 在 Phase 2 末尾读取）。 */
    public List<BeanPostProcessor> postProcessors() {
        return Collections.unmodifiableList(postProcessors);
    }

    /** 委托给 BeanContainer.getBean（state == READY/RUNNING 才允许调）。 */
    public <T> T getBean(String name, Class<T> type) {
        return beans.getBean(name, type);
    }

    /**
     * Container.bindAll 调：把本 AppContext 的 NIO Server 注册到容器统一 ServerGroup。
     * 列表由 Container.deploy 期间遍历 addServer() 阶段写入（详见 §3.5.6）。
     */
    public void addServer(Server s) {
        servers.add(s);
    }

    /** 返回本 AppContext 关联的 NIO Server 列表（不可修改视图）。 */
    public List<Server> getServers() {
        return Collections.unmodifiableList(servers);
    }

    /** 事件发布快捷入口（state == GATHERING 之后可调；NEW 不允许）。 */
    public void publishEvent(ApplicationEvent e) {
        events.publish(e);
    }

    public void destroyPartial() {}


    // ─── 路由条目访问器（Container.bindAll 入参）───

    /** HTTP 路由条目（来自 EarScanner 阶段汇总）。 */
    public List<HttpRouteEntry> httpRoutes() { return Collections.unmodifiableList(httpRoutes); }

    /** WS 路由条目。 */
    public List<WsRouteEntry>   wsRoutes()   { return Collections.unmodifiableList(wsRoutes); }

    /** eRPC 路由条目。 */
    public List<ErpcRouteEntry> erpcRoutes() { return Collections.unmodifiableList(erpcRoutes); }

    /** gRPC 路由条目。 */
    public List<GrpcRouteEntry> grpcRoutes() { return Collections.unmodifiableList(grpcRoutes); }


    // ─── 访问器 ───

    public String             appId()     { return appId; }
    public String             version()   { return version; }
    public DeployMetaData     dmd()       { return dmd; }
    public EdapAppClassLoader appCL()     { return appCL; }
    public Container          container() { return container; }
    public Environment        env()       { return env; }
    public EventPublisher     events()    { return events; }
    public BeanContainer      beans()     { return beans; }
    /**
     * 路由注册中心（HTTP/WS/eRPC/gRPC 四份 Handler List）。
     * BeanContainer.injectAware 在 RouterHubAware 回调时通过本方法取。
     */
    public RouterHub          routers()   { return routers; }
    public ShardRegistry      shards()    { return shards; }
    public AppResourceLoader resourceLoader() { return resourceLoader; }
    public AppState           state()     { return state; }


    // ─── 内部类型 ───

    /**
     * (targetIf, Method) 二元组，作为 generatedHandlers 的 key。
     *
     * <p>为什么 key 是 HandlerKey 而不是单个 Method：同一 bean method 可能被多个协议路由
     * （如 sayHello 同时是 HttpHandler 和 ErpcHandler），不同 targetIf → 不同实现类
     * （不同 typed 接口 + 不同协议提参 / 响应字节码），需要各自缓存、彼此互不干扰。</p>
     */
    public record HandlerKey(Class<?> targetIf, Method method) {
        // 自动 equals/hashCode 基于 targetIf + Method
    }

    /**
     * 生成类专用 ClassLoader（parent = appCL）。
     *
     * <p>作用：加载 ASM 生成的 Handler impl class。</p>
     * <ul>
     *   <li>defineClass 不走双亲委派——直接 define 字节码，不向上找</li>
     *   <li>getClass 时走双亲委派——bean / entry 类型由 parent (appCL) 解析</li>
     *   <li>每个 appCL 单独一份（缓存由 AppContext.generatedCLs 持有）</li>
     * </ul>
     *
     * <p>生命周期与 AppContext 同步：AppContext.stop() 期间清空 generatedCLs map，
     * 这些 ClassLoader 与 appCL 整条引用链一并 GC。</p>
     */
    static final class GeneratedClassLoader extends ClassLoader {
        GeneratedClassLoader(ClassLoader parent) {
            super(parent);
        }
    }

}
