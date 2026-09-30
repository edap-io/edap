package io.edap.container;

import io.edap.Edap;
import io.edap.ServerGroup;
import io.edap.auth.jwt.DefaultJwtService;
import io.edap.auth.jwt.JwtService;
import io.edap.container.event.EventPublisher;
import io.edap.container.mw.*;
import io.edap.container.scan.EarScanner;
import io.edap.container.ws.ServiceWSHandler;
import io.edap.container.ws.WSServiceMsgHandler;
import io.edap.http.server.HttpServer;
import io.edap.http.PathInfo;
import io.edap.http.server.PathInfoMatcher;
import io.edap.http.ws.HeaderTokenAuthenticator;
import io.edap.http.HttpHandler;
import io.edap.http.ws.WSAuthenticator;
import io.edap.microservice.Scope;
import io.edap.mw.context.JwtUserResolver;
import io.edap.nio.codec.FastBufDataRange;
import io.edap.json.Eson;
import io.edap.launcher.NestedJarFile;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.nio.util.ConfigUtils;
import io.edap.props.Props;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static io.edap.container.scan.EarScanner.clazzCount;

public class Container {

    static Logger log = LoggerManager.getLogger(Container.class);

    private Edap          edap;
    private ClassLoader   containerCL;
    private DeployManager deployManager;
    private ServerGroup   appServerGroup;
    /**
     * HTTP 协议 Server 实例。attach() 阶段按 Capability.HTTP 创建并加入 appServerGroup；
     * deploy / undeploy / switchVersion / 启动恢复 时通过 {@link HttpServer#setHttpMapping}
     * 整体替换 path → handler 映射（dispatch 热路径无锁读）。
     */
    private HttpServer    httpServer;
    private Props         env;

    private volatile ContainerState state;

    /**
     * ① 真值表：appId → SlotEntry（不可变 POJO）。回答"部署了什么"
     */
    private final ConcurrentHashMap<String, SlotEntry> registry = new ConcurrentHashMap<>();
    /**
     * ② 指针表：appId → 当前接流量的 RouterHub。回答"流量走哪个"
     */
    //private final ConcurrentHashMap<String, RouterHub> currentRouters = new ConcurrentHashMap<>();
    /**
     * ③ 锁表：appId → 写锁。只增不删（原因见 §3.7.5）
     */
    private final ConcurrentHashMap<String, ReentrantLock> appLocks = new ConcurrentHashMap<>();

    /**
     * ④ ProtoService 接口 FQCN → 拥有它的 appId。按槽位拆三个 map（CURRENT/STAGING/PREVIOUS），
     * 让 PREVIOUS 槽的注册在物理上就不参与 deploy 期冲突检测。
     *
     * <p>冲突检测只在活动槽（CURRENT + STAGING）之间做；PREVIOUS 短暂承接 in-flight，
     * 不参与命名空间仲裁。</p>
     *
     * <p>同 appId 重部署允许（覆盖语义）；跨 appId 同 FQCN 在活动槽里 → 409 拒绝。</p>
     */
    private final ConcurrentHashMap<String, String> currentRegistered  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> stagingRegistered  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> previousRegistered = new ConcurrentHashMap<>();

    /**
     * 框架级 Bean 容器：edap 容器内置功能 bean 集合（如 {@link WSAuthenticator} 默认实现）。
     * <p>AppContext 级 BeanContainer 在 {@code beanWrapByType} miss 时自动 fallback 查此容器，
     *     实现"应用零配置即用内置功能，应用 bean 自动覆盖默认实现"的语义。</p>
     */
    private BeanContainer containerBeans;

    /**
     * ⑤ WS path → 持有该 path 的 appId 集合（多 owner）。
     *
     * <p><b>语义变更</b>：不再做"同 path 不同 appId 冲突抛异常"。不同 appId 注册同一 WS path 是
     *     合法场景（frontend 和 backend 都用 {@code /ws}，dispatch 通过
     *     {@link ServiceWSHandler#appMsgHandlers} 按 appId 二级分片 + 跨 app method 名冲突检测）。
     *     本表仅用于记录"哪些 appId 注册过这个 path"，undeploy 时反向摘除。</p>
     *
     * <p>为何保留：undeploy 时需要知道"这个 path 还有没有其他 owner"，决定是否要从 shared
     *     wsHandler 里摘 wsHandler 字段。无 owner 时 wsPathOwners.remove(path, owners) 触发
     *     combined mapping 摘 path。</p>
     */
    private final ConcurrentHashMap<String, Set<String>> wsPathOwners = new ConcurrentHashMap<>();

    /**
     * ⑤.5 WS path → 共享 {@link ServiceWSHandler} 单例。
     * 跨 AppContext 实例、跨版本共享同一 ServiceWSHandler 引用；
     * 借 {@link ServiceWSHandler#rebindMsgHandlers} 整张替换 msgHandlers volatile map，
     * 让老 WS 连接（{@code HttpServerNioSession.wsHandler} 握手时钉住）也能感知版本切换。
     *
     * <p>粒度对齐 {@link #wsPathOwners} —— key 是 WS path 字符串（如 {@code "/ws"}）。
     *     当前架构一 Container 一 HttpServer，HttpServer 维度无变化时 per-path 单例等价于
     *     per-(HttpServer × path)，多 Container 之间天然隔离（不用跨 Container 同步）。</p>
     */
    private final ConcurrentHashMap<String, ServiceWSHandler> wsHandlers = new ConcurrentHashMap<>();

    /**
     * PREVIOUS 槽延迟释放调度器。
     *
     * <p>switchVersion(staging → current) 时老 current 被 demote 到 PREVIOUS 槽。
     * PREVIOUS 槽的语义是"承接 in-flight 老请求 graceful drain + 记录上次版本"，
     * 不是"rollback 备用"。调度器在 demote 那一刻起 {@link #prevReleaseDelayMs} 毫秒后
     * 调 ctx.stop() —— destroyAllSingletons 关闭 Hikari 等连接池，释放数据库连接。</p>
     *
     * <p>单线程 daemon executor：延迟任务不需要并行；daemon 不会阻止 JVM 退出。</p>
     */
    private final ScheduledExecutorService prevReleaseScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "container-prev-release");
                t.setDaemon(true);
                return t;
            });

    /**
     * PREVIOUS 槽 ctx 延迟释放时长（毫秒）。给 in-flight 老请求一个收尾窗口。
     * 60s 到点直接调 ctx.stop()，即使还有请求没跑完也会被强杀（Hikari close）——
     * 业务方需保证关键请求在 60s 内完成；后续如需精细化可改为 in-flight 计数归零触发。
     */
    private volatile long prevReleaseDelayMs = 60_000L;

    /**
     * ⑥ appId → 该 app 贡献的 path 表。{@link #deployAppRoutes} 每次写入 / 重建 combined map 时按 app 合并。
     * <p>为什么按 app 存：HTTP 路由分属不同 app，跨 app path 冲突由 FQCN 检测挡；但 WS path 是字符串粒度，
     *     需要在合并阶段做 wsPathOwners 冲突检测，再整张写入 HttpServer。</p>
     */
    private final ConcurrentHashMap<String, Map<FastBufDataRange, PathInfo>> appPathTables = new ConcurrentHashMap<>();

    private final File appsDir;
    private static final ReentrantLock lifecycleLock = new ReentrantLock();

    /**
     * 容器节点的能力集——决定启动期 bind 哪些 Router（HTTP/WS/eRPC/gRPC）。
     * 详见 {@link Capability}。
     *
     * <p>来源：构造器显式传入（推荐测试 / 单节点脚本用）或默认构造时从
     * 系统属性 {@code edap.node.capabilities} 解析（逗号分隔，大小写不敏感）。
     * 空值 / 未识别 token / 属性缺失 → 兜底为 {@code HTTP_ROUTING + WS_ROUTING}
     * （HTTP 节点默认形态）。</p>
     */
    private final Set<Capability> capabilities;


    public Container(File appsDir) {
        this(appsDir, parseDefaultCapabilities());
    }

    public Container(File appsDir, Set<Capability> capabilities) {
        this.state        = ContainerState.NEW;
        this.appsDir      = appsDir;
        this.containerCL  = Container.class.getClassLoader();
        this.capabilities = capabilities == null || capabilities.isEmpty()
                ? EnumSet.of(Capability.HTTP, Capability.WS)
                : EnumSet.copyOf(capabilities);
    }

    public File appsDir() {
        return appsDir;
    }

    /**
     * 从系统属性 {@code edap.node.capabilities} 解析能力集合。
     * 格式 "http,ws,erpc"；token 简写自动补 {@code _ROUTING} 后缀。
     */
    private static Set<Capability> parseDefaultCapabilities() {
        String raw = System.getProperty("edap.node.capabilities");
        if (raw == null) raw = System.getenv("EDAP_NODE_CAPABILITIES");
        Set<Capability> parsed = Capability.parse(raw);
        if (parsed.isEmpty()) {
            return EnumSet.of(Capability.HTTP, Capability.WS);
        }
        return parsed;
    }

    /** 节点能力集合（不可变副本）。Router bind 阶段按这个集合选择性挂载。 */
    public Set<Capability> capabilities() {
        return Collections.unmodifiableSet(capabilities);
    }

    public boolean hasCapability(Capability c) {
        return capabilities.contains(c);
    }

    public Props env() {
        return env;
    }

    /**
     * Bootstrap 里调
     * @param edap
     */
    public void attach(Edap edap) {
        lifecycleLock.lock();
        try {
            ServerGroup sg = new ServerGroup();
            sg.setName("apps");
            state.checkTransitionTo(ContainerState.ATTACHED);  // NEW -> ATTACHED
            this.edap = edap;
            this.env  = edap.getProps().child("container");
            this.appServerGroup = sg;

            // 按 capabilities 建对应协议 Server 实例。所有 app 共享端口（同一 Container 内
            // HTTP/WS 各只 listen 一个端口），dispatch 通过 HttpServer.httpMapping 区分 app。
            // 不同端口需求 → 起多个 Container（每个 Container 独立进程、独立 classloader、
            // 互不干扰）。
            //
            // 当前依赖只覆盖 edap-http-server；WS / eRPC / gRPC Server impl 暂缺，留 TODO
            // 等对应 server impl jar 加入依赖后再启用。
            if (capabilities.contains(Capability.HTTP)) {
                int httpPort = env.getInt("http.port", 8080);
                HttpServer http = new HttpServer();
                http.listen(httpPort);
                sg.addServer(http);
                this.httpServer = http;                          // rebuildHttpMapping 时引用
            }
            // TODO: Capability.WS / ERPC / GRPC Server 实例创建

            // 框架级 Bean 容器：注册 edap 内置功能默认实现（开箱即用，应用 bean 可覆盖）
            initContainerBeans();

            edap.addServerGroup(appServerGroup);               // 唯一对外暴露点
            // 进程停止时触发 Container.stop()（在 Edap.doStop() 中位于 ServerGroup.stop() 之前）：
            // 先做内存级清理（unbind routes / @PreDestroy / appCL.close），再关监听 socket。
            // 此时 Container.stop() 内部已 try/catch Throwable，安全。
            edap.addOnStop(this::stop);
            state = ContainerState.ATTACHED;
        } finally {
            lifecycleLock.unlock();
        }
    }

    public Edap getEdap() {
        return this.edap;
    }

    /**
     * 框架级 BeanContainer 访问器（AppContext 级 BeanContainer fallback 目标）。
     * <p>仅在 {@link #attach} 之后非 null；之前调抛 {@link IllegalStateException}。</p>
     */
    public BeanContainer containerBeans() {
        if (containerBeans == null) {
            throw new IllegalStateException("containerBeans 未初始化（attach 之前调？）");
        }
        return containerBeans;
    }

    /**
     * 初始化框架级 Bean 容器并注册 edap 内置功能默认实现。
     *
     * <p><b>当前注册</b>：
     * <ul>
     *   <li>{@link HeaderTokenAuthenticator}（{@code WSAuthenticator} 默认实现）</li>
     * </ul>
     *
     * <p>注册为 SINGLETON（无依赖），立即 commit。后续 AppContext 级 BeanContainer 的
     *     {@code beanWrapByType(WSAuthenticator.class)} miss 时自动 fallback 到本容器，
     *     实现"应用零配置即用内置功能"。</p>
     *
     * <p>应用可注册自己的 {@link WSAuthenticator} bean 自动覆盖——AppContext 级 byType 命中时
     *     直接返回应用 bean，框架默认 bean 不会被查到。</p>
     */
    private void initContainerBeans() {
        if (containerBeans != null) {
            return;                                                 // 幂等
        }
        EventPublisher events = new EventPublisher();
        ShardRegistry  shards = new ShardRegistry();
        // Container.beans 没有 AppContext 上级 —— 用 null 替代；
        // Environment 字段取自 this.env（edap.getProps().child("container")），
        // BeanContainer 仅读取，不依赖 AppContext 注入
        this.containerBeans = new BeanContainer(null, null, events, shards);

        // 注册框架默认 WSAuthenticator bean
        try {
            BeanDef def = new BeanDef(
                    "container." + HeaderTokenAuthenticator.class.getSimpleName(),
                    HeaderTokenAuthenticator.class,
                    Scope.SINGLETON,
                    null, null, null, null, 0);
            containerBeans.register(def);
        } catch (Exception e) {
            log.warn("注册框架默认 {} bean 失败", l -> l.arg(HeaderTokenAuthenticator.class.getName()).threw(e));
            return;
        }
        // 注册框架默认 JwtService bean (DefaultJwtService 实现)。signKey 从
        // edap.getProps().child("jwt").getString("signKey") 读,缺失则跳过注册
        // (应用若 @Inject JwtService,容器查不到 → NoSuchBeanException,业务自己 register)。
        BeanDef jwtDef = null;
        String jwtSignKey = edap.getProps().child("jwt").getString("signKey", "");
        if (jwtSignKey == null || jwtSignKey.trim().length() == 0) {
            log.warn("未配置 jwt.signKey,使用默认的key");
            jwtSignKey = "20a4d5e1-3c3d-4259-962f-78b2c07b2b06";
        }
        try {
            jwtDef = new BeanDef(
                    "container." + DefaultJwtService.class.getSimpleName(),
                    DefaultJwtService.class,
                    Scope.SINGLETON,
                    null, null, null, null, 0);
            containerBeans.register(jwtDef);
        } catch (Exception e) {
            log.warn("注册框架默认 {} bean 失败",
                    l -> l.arg(DefaultJwtService.class.getName()).threw(e));
        }
        // 注册框架默认 UserResolver bean ("jwtUserResolver")。ctor 依赖 JwtService 接口,
        // 跨 CL 安全:appCL.loadClass("io.edap.auth.jwt.JwtService") 通过双亲委派拿到 containerCL
        // 加载的同一份接口 Class,instance 注入时 isAssignableFrom 永远 true。
        try {
            BeanDef resolverDef = new BeanDef(
                    "jwtUserResolver",
                    JwtUserResolver.class,
                    Scope.SINGLETON,
                    null, null, null, null, 0);
            containerBeans.register(resolverDef);
        } catch (Exception e) {
            log.warn("注册框架默认 {} bean 失败",
                    l -> l.arg(JwtUserResolver.class.getName()).threw(e));
        }
        containerBeans.topologicalSort();
        containerBeans.transitionToCommitting();
        for (BeanDef def : containerBeans.sorted()) {
            Object instance;
            // DefaultJwtService 构造器需要 String signKey,BeanContainer.ctorArgs 按类型查不到 String,
            // 必须反射手工调 ctor(String) 跳过 ctorArgs 路径。其它 bean 走正常 instantiate()
            if (def.beanClass() == DefaultJwtService.class) {
                try {
                    java.lang.reflect.Constructor<?> ctor =
                            DefaultJwtService.class.getDeclaredConstructor(String.class);
                    ctor.setAccessible(true);
                    instance = ctor.newInstance(jwtSignKey);
                } catch (Exception e) {
                    log.warn("实例化框架默认 {} 失败",
                            l -> l.arg(DefaultJwtService.class.getName()).threw(e));
                    continue;
                }
            } else if (def.beanClass() == JwtUserResolver.class) {
                // JwtUserResolver ctor(JwtService) 走类型解析会经由 beanWrapByType → fallback 到
                // container.beans 拿 DefaultJwtService 实例,但 instantiate() 时该实例可能尚未
                // registerInstance(拓扑序只保证依赖先 instantiate,不保证先 register)。改成直接反射
                // 拿 JwtService 实例,绕过 ctorArgs 的 byType 查找。
                try {
                    Object jwtSvc = containerBeans.getBean(
                            "container." + DefaultJwtService.class.getSimpleName());
                    java.lang.reflect.Constructor<?> ctor =
                            JwtUserResolver.class.getDeclaredConstructor(JwtService.class);
                    ctor.setAccessible(true);
                    instance = ctor.newInstance(jwtSvc);
                } catch (Exception e) {
                    log.warn("实例化框架默认 {} 失败",
                            l -> l.arg(JwtUserResolver.class.getName()).threw(e));
                    continue;
                }
            } else {
                instance = containerBeans.instantiate(def);
            }
            containerBeans.injectDependencies(def, instance);
            containerBeans.invokeInit(def, instance);
            containerBeans.registerInstance(def, instance);
        }
        containerBeans.transitionToReady();
        containerBeans.startLifecycles();
    }

    /**
     * 部署 / version 切换：把 app 的全量 path 表登记到 {@link #appPathTables}。
     *
     * <p>流程：
     * <ol>
     *   <li>对 {@code newTable} 中所有 PathInfo.wsHandler != null 的 entry，{@link #wsPathOwners}
     *       记录 owner（多 owner Set，不做跨 app 冲突检测）</li>
     *   <li>{@link #appPathTables} put(appId, newTable)</li>
     * </ol>
     *
     * <p><b>跨 app WS method 冲突</b>不在此处检测 —— dispatch 路径走 shared {@link
     *     ServiceWSHandler}，由 {@link ServiceWSHandler#rebindMsgHandlers(String, Map)}
     *     在 deploy / switchVersion 末尾 rebind 时按 appId 维度检测 method 名是否撞 —— 撞了
     *     抛 {@link IllegalStateException}，触发 {@code Container.deploy} catch 的
     *     {@code destroyPartial} 回滚整 ctx。</p>
     *
     * <p><b>本方法不再触发 setHttpMapping</b> —— 发布的责任统一交给 {@link #rebuildHttpMapping}。
     * 起初 deployAppRoutes 内部会自己 publish 一次（{@code setHttpMapping(mergeAllAppPathTables)}），
     * 但 Container.deploy / restoreToSlot / switchVersion 末尾会再调一次 rebuildHttpMapping，
     * 导致同一 deploy 流程里 setHttpMapping 被调两次（且第二次才包含 WS path，第一次漏掉），
     * 既冗余又漏 WS。现在两条路径只剩 rebuildHttpMapping 一次 publish，dispatch 表始终一致。</p>
     *
     * <p><b>设计取舍</b>：HTTP + WS path 一起部署（无单独注册 WS path 的 API）——
     *     app 的 deploy / version 切换天然走全量 pathTable，整张合并后一次性写入 HttpServer，
     *     避免单 path 增删 API 引入的并发复杂度。</p>
     *
     * @param appId    当前部署的应用 ID
     * @param newTable app 全量 path 表（含 HTTP entries + WS entries）
     */
    public void deployAppRoutes(String appId, Map<FastBufDataRange, PathInfo> newTable) {
        if (newTable == null) {
            newTable = Collections.emptyMap();
        }
        // 1. WS path → 多 owner Set 记录（不做跨 app 冲突检测）。
        //    跨 app method 名冲突检测由 ServiceWSHandler.rebindMsgHandlers 在 deploy/switchVersion
        //    末尾做（抛 IllegalStateException → Container.deploy catch 走 destroyPartial 回滚）。
        for (Map.Entry<FastBufDataRange, PathInfo> e : newTable.entrySet()) {
            PathInfo pi = e.getValue();
            if (pi != null && pi.getWsHandler() != null) {
                String pathStr = pi.getPath();
                if (pathStr == null || pathStr.isEmpty()) {
                    continue;                                       // 无 path 字段的 PathInfo 跳过
                }
                Set<String> owners = wsPathOwners.computeIfAbsent(pathStr,
                        k -> ConcurrentHashMap.newKeySet());
                owners.add(appId);                                  // 多 owner 记录，重复添加幂等
            }
        }
        // 2. 存表（不 publish，等调用方 rebuildHttpMapping 一次性写）
        appPathTables.put(appId, newTable);
    }

    /**
     * 摘除某 appId 在指定 WS path 上的 owner 记录。{@code undeployAppRoutes} 末尾调；
     * 路径 owner Set 空时同步从 wsPathOwners map 移除 key。
     */
    private void unregisterWsPathOwner(String pathStr, String appId) {
        if (pathStr == null || pathStr.isEmpty()) return;
        Set<String> owners = wsPathOwners.get(pathStr);
        if (owners == null) return;
        owners.remove(appId);
        if (owners.isEmpty()) {
            wsPathOwners.remove(pathStr, owners);
        }
    }

    /**
     * undeploy：摘除 app 贡献的 path 表。调用方：AppContext.stop 末尾。
     *
     * <p><b>本方法不再触发 setHttpMapping</b> —— 发布的责任统一交给 {@link #rebuildHttpMapping}，
     * 由 undeploy() 末位调用一次。保持 deploy / undeploy / switchVersion 三条路径都走同一发布入口，
     * dispatch 表永远一致（HTTP + WS 都在）。</p>
     */
    public void undeployAppRoutes(String appId) {
        Map<FastBufDataRange, PathInfo> oldTable = appPathTables.remove(appId);
        if (oldTable != null) {
            for (PathInfo pi : oldTable.values()) {
                if (pi != null && pi.getWsHandler() != null
                        && pi.getPath() != null && !pi.getPath().isEmpty()) {
                    wsPathOwners.remove(pi.getPath(), appId);
                }
            }
        }
    }

    /**
     * 按 WS path 拿共享 {@link ServiceWSHandler} 实例。miss 时由 Container 创建并放进 {@link #wsHandlers} map。
     *
     * <p>跨 AppContext 实例、跨版本共享同一 ServiceWSHandler 引用 —— 老 WS 连接
     *     （{@code HttpServerNioSession.wsHandler} 握手时钉住）下次 decode 仍走同一实例，
     *     内部 msgHandlers 被 {@link ServiceWSHandler#rebindMsgHandlers} 整张替换后即感知新版本。</p>
     *
     * <p>首次创建时，{@code userResolver} 从 {@link #containerBeans()} 取（"jwtUserResolver" 是
     *     Container.attach() 阶段 register 的框架默认 bean，不依赖任何 app）。</p>
     *
     * <p>调用方：AppContext.buildPathTable()（PathInfo.wsHandler 写入）。其他需要 rebind 的场景
     *     走 {@link #rebindCurrentWsHandlers}，不要直接调本接口后再操作 msgHandlers。</p>
     *
     * @param path WS path 字符串（如 {@code "/ws"}）
     * @return path 上的共享 ServiceWSHandler 实例
     */
    public ServiceWSHandler getOrCreateWsHandler(String path) {
        return wsHandlers.computeIfAbsent(path, p -> new ServiceWSHandler(this));
    }

    /**
     * 把 entry.current() 槽 ctx 的 {@code wsMsgHandlers} 整张 rebind 到 path 共享 wsHandler。
     *
     * <p>调用方：{@link #deploy} / {@link #switchVersion} / {@link #restoreToSlot} / {@link #undeploy}
     *     末尾，{@link #rebuildHttpMapping} 整张发布之后。语义：dispatch 路径的 PathInfo.wsHandler
     *     已是 current（rebuildHttpMapping 已写过），现在让 wsHandler 内部的 msgHandlers 也同步到
     *     current 版本的方法表 —— 老连接下一条消息即走新版本。</p>
     *
     * <p>跳过条件：entry / current 为 null（无 current 槽）、current.wsMsgHandlers() 空（无
     *     {@code @ProtoWebSocket} 方法）、共享 wsHandler 还没创建（理论上不会 —— buildPathTable
     *     会触发 computeIfAbsent —— 但防御性检查）。</p>
     *
     * @param entry 写完 {@link #registry} 之后的 SlotEntry
     */
    private void rebindCurrentWsHandlers(SlotEntry entry) {
        if (entry == null) return;
        AppContext cur = entry.current();
        if (cur == null) return;
        Map<String, WSServiceMsgHandler<?>> msgHandlers = cur.wsMsgHandlers();
        if (msgHandlers.isEmpty()) return;  // 无 @ProtoWebSocket 方法的 app 跳过
        ServiceWSHandler shared = wsHandlers.get(AppContext.WS_PATH);
        if (shared == null) return;          // /ws 路径未被访问过（理论上不会 —— buildPathTable 会触发）
        // 按 appId 维度 rebind：同 appId 整张覆盖（version 切换）；跨 appId 检测 method 名冲突
        // （撞了抛 IllegalStateException，调用方走 destroyPartial 回滚）
        shared.rebindMsgHandlers(cur.appId(), msgHandlers);
    }

    /**
     * 给刚 demote 到 PREVIOUS 槽的 ctx 排延迟 stop。
     *
     * <p>到点由 {@link #prevReleaseScheduler} 调 ctx.stop()：destroyAllSingletons 阶段
     * 会关掉 AutoCloseable bean（含 HikariDataSource）→ 连接池释放 → 数据库连接回收。
     * in-flight 老请求会被强杀 —— 业务方需保证关键请求在 60s 内完成。</p>
     *
     * <p>调用方：{@link #switchVersion} 第一分支末尾（staging → current 时 demotedCurrent）。
     *     {@link AppContext#setReleaseFuture} 持有句柄，下一次 switchVersion 又产生 demote
     *     时先 cancel 再立即 stop。</p>
     */
    private void schedulePrevRelease(AppContext ctx) {
        if (ctx == null) return;
        long delayMs = this.prevReleaseDelayMs;
        ScheduledFuture<?> f = prevReleaseScheduler.schedule(() -> {
            try {
                log.info("PREVIOUS 槽 ctx 超时释放 [{}:{}]",
                        l -> l.arg(ctx.appId()).arg(compositeOf(ctx)));
                // PREVIOUS 槽 demote 释放：unbindFromShared=false —— 同 appId 的 current 槽
                // 还有新版本 ctx 持有 method 表，按 appId 摘会误伤 current 的 method 表
                ctx.stop(false);
                // 清掉 PREVIOUS map 里本 appId 的 FQCN 注册（PREVIOUS 短暂承接期间写入的）。
                // 此时 PREVIOUS 槽在 SlotEntry 里仍是这个 ctx，map 状态对应仍正确；下一次
                // switchVersion 时 SlotEntry 替换才会触发新的 move。
                unregisterSlotIfs(Slot.PREVIOUS, ctx.appId(), ctx.dmd());
                // 同步 .deploy/previous-*.json —— delayed stop 路径原来漏调，导致磁盘档案残留。
                // 现在 PREVIOUS 槽在 SlotEntry 里仍是这个 ctx（syncDeployMetaFiles 看到非空会重写），
                // 下一次 switchVersion 把这个 PREVIOUS 替换掉时 syncDeployMetaFiles 才会删文件。
                // 所以这里**不删** JSON，等下次 SlotEntry 真变空时再删 —— 跟 undeploy / switchVersion
                // 的 sync 时机对齐。
            } catch (Throwable t) {
                log.warn("PREVIOUS 槽 ctx.stop() 异常 [{}]",
                        l -> l.arg(ctx.appId()).threw(t));
            }
        }, delayMs, TimeUnit.MILLISECONDS);
        ctx.setReleaseFuture(f);
    }

    /**
     * 立即停止 PREVIOUS 槽里上一个 demoted 的 ctx。用于"60s 内又发生 switchVersion 又要 demote 新
     * current" 的场景：旧 PREVIOUS 给新 PREVIOUS 腾位置。
     *
     * <p>流程：ctx.cancelRelease() 取消延迟任务（{@code mayInterruptIfRunning=false} 让
     *     已触发的任务跑完，避免和 stop() 并发打架），然后立即同步调 ctx.stop(false)。
     *     ctx.stop() 内部 phase 2 走 destroyAllSingletons 释放连接池。</p>
     *
     * <p>unbindFromShared=false：PREVIOUS 槽 demote 释放场景，同 appId 的 current 槽还有新版本 ctx
     *     持有 method 表，按 appId 摘会误伤 current 槽的 method 表（参见 AppContext.stop(boolean)）。</p>
     *
     * <p>与 60s 到点 stop 同样会打断 in-flight —— 业务方需自担。</p>
     */
    private void stopPrevImmediate(AppContext ctx) {
        if (ctx == null) return;
        ctx.cancelRelease();
        try {
            log.info("PREVIOUS 槽 ctx 提前释放 [{}:{}]",
                    l -> l.arg(ctx.appId()).arg(compositeOf(ctx)));
            ctx.stop(false);
        } catch (Throwable t) {
            log.warn("PREVIOUS 槽 ctx.stop() 异常 [{}]",
                    l -> l.arg(ctx.appId()).threw(t));
        }
    }

    /**
     * 设置 PREVIOUS 槽延迟释放时长（毫秒）。用于测试 / 业务方调整。默认 60000。
     */
    public void setPrevReleaseDelayMs(long ms) {
        this.prevReleaseDelayMs = ms;
    }

    /** PREVIOUS 槽延迟释放时长（毫秒）。 */
    public long getPrevReleaseDelayMs() {
        return prevReleaseDelayMs;
    }

    /**
     * 统一启动入口：attach(edap) + start() 一行完成。
     *
     * 用途：Bootstrap 不再分别调两个方法，调用方语义清晰——"把 Container 跑起来"。
     *
     * 状态迁移：NEW → ATTACHED（attach）→ STARTING → RUNNING（start）。
     *
     * **不在此方法里阻塞或持有线程**：业务请求由 Edap.run() 启动的 NIO server groups 处理；
     * 本方法只完成生命周期初始化，不进入 accept loop。SIGTERM 时外部调 {@link #stop()}。
     *
     * @param edap 已构造好的 Edap 实例（Container 不 new Edap——避免反向依赖与构造顺序耦合）
     */
    public void run(Edap edap) {
        attach(edap);                            // NEW → ATTACHED：注入 Edap + 注册 "apps" ServerGroup
        start();                                  // ATTACHED → RUNNING：恢复 .deploy 下所有 previous/current/staging 部署
    }

    /**
     * 根据 .deploy 目录里的部署记录恢复部署：apps.json 列 appId，
     * 每个 appId 对应 current / previous / staging 三份元数据，
     * 元数据里的 earName 指明要启动的具体 EAR 包。
     * 不再遍历 appsDir 下所有 .ear，否则同一个 app 的多个历史版本都会被加载，
     * 三个槽位的语义就失效了。
     *
     * <p><b>恢复路径与 {@link #deploy(File)} 路径分离</b>：按文件名里的 role 强制写到对应槽位
     * （{@code current-*.json} → CURRENT 槽），不重走 {@code firstEmptySlot()}——否则
     * 只有 {@code current-*.json} 存在时 EAR 会落进 PREVIOUS 槽，{@code currentRouters}
     * 拨不到指针，业务首条请求拿不到路由。
     *
     * <p>启动期只恢复 <b>current + staging</b> 两个槽位，<b>previous 不初始化</b>——
     * previous 是"快速回滚"语义下的"待命角色"，由 {@link #switchVersion} 退位时填入
     * （把走下舞台的 current 落入 previous 槽），启动期过早初始化 previous 会浪费 Phase 1/2/3
     * 全部开销（Bean 实例化、路由 ASM 生成），且 previous 暂时不在 currentRouters 视野内，
     * 没有 dispatch 价值。
     *
     * <p>两个槽位独立恢复：缺哪个就跳过哪个；恢复失败 WARN 跳过，不阻断其它 appId / 槽位。
     */
    public void start() {
        lifecycleLock.lock();
        try {
            state.checkTransitionTo(ContainerState.STARTING);  // ATTACHED -> STARTING
            state = ContainerState.STARTING;
        } finally {
            lifecycleLock.unlock();
        }

        // 锁外做恢复；restoreToSlot() 内部用各自 appId 的 appLock 串行（不同 appId 并行）
        List<String> appIds = readDeployAppIds();
        for (String appId : appIds) {
            // current + staging 走 restoreToSlot()；previous 跳过（理由见 javadoc）
            for (String role : new String[]{"current", "staging"}) {
                DeployMeta meta = readDeployMetaFile(role + "-" + appId + ".json");
                if (meta == null) continue;
                File ear = locateEar(meta.getEarName());
                if (ear == null) {
                    log.warn("[{}] {} 记录的 EAR {} 不存在，跳过",
                            l -> l.arg(appId).arg(role).arg(meta.getEarName()));
                    continue;
                }
                BaseResult<String> r = restoreToSlot(ear, Slot.valueOf(role.toUpperCase()));
                if (!r.isSuccess()) {
                    log.warn("EAR {} 恢复失败: {}",
                            l -> l.arg(ear.getAbsolutePath()).arg(r.getMessage()));
                }
            }
        }

        // 拨 currentRouters 指针：只对 current 槽非空的 appId 拨；staging-only 等 switchVersion
        for (String appId : appIds) {
            SlotEntry e = registry.get(appId);
            if (e == null) {
                continue;
            }
            AppContext cur = e.current();
//            if (cur != null && cur.routers() != null) {
//                currentRouters.put(appId, cur.routers());
//            }
        }
        // 启动恢复末位 rebuild HTTP mapping：所有 current 指针已就位，dispatch 必须 ready 才接受流量
        rebuildHttpMapping();
        // 把所有 current 槽 ctx 的 wsMsgHandlers rebind 到共享 wsHandler —— 老连接 session.wsHandler
        // 引用不变，但启动期第一次握手后 decode 即能命中新方法表（不存在"上次运行残留"问题，但
        // 保持与运行时一致的 rebind 路径，避免后续切版本时的代码差异）。
        for (String appId : appIds) {
            SlotEntry e = registry.get(appId);
            if (e != null) {
                rebindCurrentWsHandlers(e);
            }
        }

        appServerGroup.run();
        lifecycleLock.lock();
        try {
            state = ContainerState.RUNNING;                // STARTING -> RUNNING
        } finally {
            lifecycleLock.unlock();
        }
    }

    private List<String> readDeployAppIds() {
        File deployDir = new File(appsDir, ".deploy");
        File appsFile = new File(deployDir, "apps.json");
        if (!deployDir.exists() || !appsFile.exists()) {
            return Collections.emptyList();
        }
        String json = readToString(appsFile);
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        List<Object> arr = Eson.parseArray(json);
        if (arr == null || arr.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> ids = new ArrayList<>(arr.size());
        for (Object o : arr) {
            if (o != null) ids.add(String.valueOf(o));
        }
        return ids;
    }

    private DeployMeta readDeployMetaFile(String fileName) {
        File metaFile = new File(new File(appsDir, ".deploy"), fileName);
        if (!metaFile.exists()) {
            return null;
        }
        String json = readToString(metaFile);
        if (json == null || json.isEmpty()) {
            return null;
        }
        return Eson.parseObject(json, DeployMeta.class);
    }

    private File locateEar(String earName) {
        if (earName == null || earName.isEmpty()) return null;
        File ear = new File(appsDir, earName);
        return ear.exists() ? ear : null;
    }

    private String readToString(File file) {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Bootstrap / SIGTERM 时调
     */
    public void stop() {
        log.info("Container stop...");
        lifecycleLock.lock();
        try {
            if (state == ContainerState.STOPPED) {
                return;          // 幂等
            }
            if (state == ContainerState.NEW
                    || state == ContainerState.ATTACHED) {            // 还没启动
                state = ContainerState.STOPPED;
                return;
            }
            if (state == ContainerState.STOPPING) {
                return;         // 已经在停
            }
            state.checkTransitionTo(ContainerState.STOPPING);    // RUNNING/START_FAILED -> STOPPING
            state = ContainerState.STOPPING;
        } finally {
            lifecycleLock.unlock();
        }

        // 锁外：逆序停所有 AppContext（从所有 3 槽位收集）。记下每个 ctx 的槽位以正确清 map。
        List<Map.Entry<AppContext, Slot>> all = new ArrayList<>();
        for (SlotEntry entry : registry.values()) {
            if (entry.previous() != null) all.add(Map.entry(entry.previous(), Slot.PREVIOUS));
            if (entry.current()  != null) all.add(Map.entry(entry.current(),  Slot.CURRENT));
            if (entry.staging()  != null) all.add(Map.entry(entry.staging(),  Slot.STAGING));
        }
        Collections.reverse(all);
        for (Map.Entry<AppContext, Slot> e : all) {
            AppContext ctx = e.getKey();
            Slot slot = e.getValue();
            try {
                // 同 undeploy：ctx.stop() 已覆盖路由/Server 摘除，不另调 removeServer
                ctx.stop();
            } catch (Throwable t) {
                log.warn("Container.stop 时 {} 异常", l -> l.arg(ctx.appId()).threw(t));
            }
            // 同步清掉本 appId 在对应槽的 FQCN 注册
            // （ctx.stop() 不动 map，因为 undeploy 路径由 Container 自己清 —— 这里是 stop 路径）
            unregisterSlotIfs(slot, ctx.appId(), ctx.dmd());
        }
        // 最终 rebuild HTTP mapping：所有 appId 已停 → 重建结果为空 mapping，dispatch 兜底 404
        rebuildHttpMapping();
        // 清空 wsHandlers map：所有 AppContext 已 stop，map entry 的 ServiceWSHandler 实例
        // 无外部引用（dispatch 路径已空），显式 clear 让 wsHandler 随 map 一起释放。
        wsHandlers.clear();
        // shutdown PREVIOUS 延迟释放调度器：awaitTermination 等所有未触发的延迟任务跑完，
        // 避免容器关闭期间还有线程在调 ctx.stop() 引起竞态。60s 上限防止 Container.stop() 阻塞过久。
        prevReleaseScheduler.shutdown();
        try {
            if (!prevReleaseScheduler.awaitTermination(60, TimeUnit.SECONDS)) {
                prevReleaseScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            prevReleaseScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        lifecycleLock.lock();
        try {
            state = ContainerState.STOPPED;                        // STOPPING -> STOPPED
        } finally {
            lifecycleLock.unlock();
        }
        log.info("Container stopped.");
    }

    /**
     * 查询 {@code appId} 当前在 3 个槽里的部署状态。供 {@code undeploy(appId, version)} /
     * {@code switchVersion(appId, version)} 调用方先查 compositeVersion（这两个方法都要求
     * compositeVersion 作参数,直接传 mavenVersion 在 SNAPSHOT 多 build 时会撞到 101 "已部署同版本")。
     *
     * <p><b>读语义,无锁</b>:从 {@link #registry} 直接读 —— SlotEntry 不可变,registry 是
     * ConcurrentHashMap,get 无锁,无需进入 {@code appLocks}。{@code deploy / undeploy /
     * switchVersion} 在替换 SlotEntry 那一刻 listSlots 看到的可能是"半旧半新",但每个
     * SlotEntry 快照自身是原子的;返回的 List 也是单次快照,在调用方拿到 List 那一刻 3 个槽
     * 之间不会撕裂(但和后续 deploy/undeploy 操作可能冲突)。</p>
     *
     * <p><b>返回</b>:
     * <ul>
     *   <li>appId 未部署 → {@code BaseResult.fail(404, "未部署: " + appId)}</li>
     *   <li>已部署 → {@code BaseResult.success(data=List<SlotInfo>)},按 PREVIOUS → CURRENT → STAGING
     *       顺序排;空槽不返回(避免 noise);每个 SlotInfo 含 slot / compositeVersion /
     *       mavenVersion / buildTime / earName,详见 {@link SlotInfo}</li>
     * </ul>
     */
    public BaseResult<List<SlotInfo>> listSlots(String appId) {
        SlotEntry entry = registry.get(appId);
        if (entry == null || entry.isEmpty()) {
            return BaseResult.fail(404, "未部署: " + appId);
        }
        List<SlotInfo> slots = new ArrayList<>(3);
        addSlotInfo(slots, Slot.PREVIOUS, entry.previous());
        addSlotInfo(slots, Slot.CURRENT,  entry.current());
        addSlotInfo(slots, Slot.STAGING,  entry.staging());
        BaseResult<List<SlotInfo>> r = new BaseResult<>();
        r.setCode(BaseResult.SUCCESS);
        r.setData(slots);
        return r;
    }

    private void addSlotInfo(List<SlotInfo> slots, Slot slot, AppContext ctx) {
        if (ctx == null) return;                       // 空槽不返回
        DeployMetaData dmd = ctx.dmd();
        SlotInfo info = new SlotInfo();
        info.setSlot(slot.name());
        info.setCompositeVersion(ctx.version());
        if (dmd != null && dmd.getMavenInfo() != null) {
            info.setMavenVersion(dmd.getMavenInfo().getVersion());
            info.setEarName(dmd.getOrignalFile() != null
                    ? dmd.getOrignalFile().getName() : null);
        }
        if (dmd != null && dmd.getBuildInfo() != null) {
            info.setBuildTime(dmd.getBuildInfo().getBuildTime());
        }
        slots.add(info);
    }

    // 部署入口
    public BaseResult<String> deploy(File ear) {
        // 1. 解析 EAR
        DeployMetaData dmd;
        long start = System.currentTimeMillis();
        try {
            dmd = new EarScanner(new NestedJarFile(ear)).scanDeployMetaData();
        } catch (IOException e) {
            return BaseResult.fail(103, "EAR 包结构错误: " + e.getMessage());
        }
        dmd.setOrignalFile(ear);                          // EarScanner 不主动设，writeDeployMeta 依赖
        log.info("DeployMetaData scan {} file time: {}", l -> l.arg(clazzCount.get())
                .arg(System.currentTimeMillis() - start));
        String appId   = dmd.getMavenInfo().getGroupId() + ":" + dmd.getMavenInfo().getArtifactId();
        String mavenVersion = dmd.getMavenInfo().getVersion();
        // 2. 计算 composite version（SNAPSHOT 加 buildTime 后缀；详见 resolveVersion）
        String version = resolveVersion(mavenVersion, dmd.getBuildInfo());

        ReentrantLock appLock = appLocks.computeIfAbsent(appId, k -> new ReentrantLock());
        appLock.lock();
        try {
            SlotEntry prev = registry.get(appId);
            SlotEntry empty = prev == null ? new SlotEntry(null, null, null) : prev;

            // 3. 重复部署检查（composite version 匹配才算重复）
            if (findSlotByCompositeVersion(empty, version) != null) {
                return BaseResult.fail(101, "已部署同版本: " + appId + ":" + version);
            }
            // 4. STAGING 替换：STAGING 槽位已被占 → 卸掉旧版本后再写入新版本。
            //    STAGING 不接流量,语义上"未上线版本被新版本覆盖"是合理的(常见的"改 bug 重新打
            //    包"场景,不必每次先 undeploy staging 再 deploy);PREVIOUS / CURRENT 不在此
            //    替换范围 —— 那是已上线 / 回滚备份,不能默默丢掉。
            //    第 3 步已排除"新版本 = 旧 staging 版本" → 这里替换时新版本必然 ≠ 旧 staging。
            AppContext oldStaging = empty.staging();
            if (oldStaging != null) {
                String oldStagingVersion = compositeOf(oldStaging);
                log.info("STAGING 槽被占,卸掉旧版本 [{}:{}] 后写入新版本 [{}:{}]",
                        l -> l.arg(appId).arg(oldStagingVersion).arg(appId).arg(version));
                try {
                    oldStaging.stop();
                } catch (Throwable t) {
                    log.warn("旧 STAGING AppContext.stop() 异常", t);
                }
                // 替换 STAGING：清掉旧版本在 STAGING map 的注册（条件删除，安全）
                unregisterSlotIfs(Slot.STAGING, appId, oldStaging.dmd());
                // 重建 empty:staging 槽位腾空。注意:此处不动 appPathTables —— ctx.stop()
                // 已通过 RouterHub.unbindAll() 摘路由;新 STAGING 的 ctx.start() 会重新调
                // deployAppRoutes() 覆盖同名 appId 条目。
                empty = new SlotEntry(empty.previous(), empty.current(), null);
            }
            // (原"3 槽全满"检查已删除 —— STAGING 总是可替换,本路径下不会撞到该条件)

            // 5. 建 ClassLoader + AppContext
            EdapAppClassLoader appCL = new EdapAppClassLoader(ear, containerCL);
            AppContext ctx = new AppContext(this, appId, version, appCL, dmd);

            // 5.5 FQCN 注册（覆盖写,无冲突检测）。BeanContainer 是 per-AppContext 的,
            //     同名 ProtoService FQCN 由各自 ClassLoader 天然隔离,这里只写诊断表。
            checkAndRegisterStaging(appId, dmd);

            // 6. 三段式启动（GATHERING → COMMITTING → READY）
            try {
                ctx.start();                                   // 详见 §4
            } catch (Throwable t) {
                // 必须先打 ERROR 日志(含完整堆栈),再清理资源、返回前端。
                // 顺序:日志优先 —— 一旦 destroyPartial/appCL.close 抛异常覆盖原 throwable,
                // 原始故障就丢了。
                log.error("AppContext 启动失败 [" + appId + ":" + version + "]", t);
                ctx.destroyPartial();                          // 回滚已注册的 Bean / 路由
                // 回滚 STAGING 槽的 FQCN 注册（deploy 路径只会写到 STAGING）
                unregisterSlotIfs(Slot.STAGING, appId, dmd);
                appCL.close();                                // 释放 ClassLoader
                return BaseResult.fail(104, "AppContext 启动失败: "
                        + t.getClass().getName()
                        + (t.getMessage() != null ? ": " + t.getMessage() : ""));
            }

            // 7. 写 registry（整 SlotEntry 替换，原子发布）
            Slot target = firstEmptySlot(empty);
            SlotEntry next = empty.withSlot(target, ctx);
            registry.put(appId, next);
            // 7.5 整体 rebuild HTTP mapping
            //     deploy() 路径 target 只可能是 STAGING（firstEmptySlot 永不返回 CURRENT/PREVIOUS），
            //     不拨 currentRouters —— STAGING 不接流量，需 switchVersion(staging → current) 才上线
            rebuildHttpMapping();
            // 注意：deploy() 末尾不 rebind 共享 wsHandler.msgHandlers —— STAGING 不接流量，
            // 此时 rebind 会让 STAGING 的 method 表被 dispatch 路径看到，与"STAGING 不接流量"
            // 语义冲突。rebind 时机推迟到 switchVersion(staging → current) 末尾。
            // 8. 持久化 .deploy/<role>-<appId>.json（start() 启动恢复靠它定位 EAR）
            writeDeployMeta(appId, target.name().toLowerCase(), dmd);
            // 9. 更新 apps.json（start() 启动恢复靠它找 appId）
            appendDeployAppId(appId);
            return BaseResult.success(appId + ":" + version + " -> " + target);

        } catch (RuntimeException e) {
            log.error("deploy 异常", e);
            return BaseResult.fail(105, e.getMessage());
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            appLock.unlock();
        }
    }

    /**
     * 恢复路径专用的 deploy：按 role 强制写指定槽位，不调 firstEmptySlot()，
     * 不写 .deploy/*.json（恢复是只读磁盘，持久化由 deploy()/switchVersion() 负责）。
     *
     * <p>调用方：
     * <ul>
     *   <li>{@link #start} 启动期 current/staging 恢复</li>
     *   <li>{@link #lazyRestorePrevious} switchVersion() 回滚 previous 按需重建</li>
     * </ul>
     *
     * <p>与 {@link #deploy(File)} 的差异：
     * <ul>
     *   <li>槽位由参数传入（按文件名 role 决定），不调 firstEmptySlot()</li>
     *   <li>不查 findSlotByCompositeVersion（重名 composite 表示恢复目标，不该当重复部署）</li>
     *   <li>不写 apps.json / role-*.json（已经在磁盘上）</li>
     *   <li>不调 writeDeployMeta（恢复路径不该回写）</li>
     * </ul>
     */
    private BaseResult<String> restoreToSlot(File ear, Slot slot) {
        DeployMetaData dmd;
        long start = System.currentTimeMillis();
        try {
            dmd = new EarScanner(new NestedJarFile(ear)).scanDeployMetaData();
        } catch (IOException e) {
            return BaseResult.fail(103, "EAR 包结构错误: " + e.getMessage());
        }
        dmd.setOrignalFile(ear);                          // EarScanner 不主动设，writeDeployMeta 依赖
        log.info("DeployMetaData scan {} file time: {}", l -> l.arg(clazzCount.get())
                .arg(System.currentTimeMillis() - start));
        String appId   = dmd.getMavenInfo().getGroupId() + ":" + dmd.getMavenInfo().getArtifactId();
        String version = resolveVersion(dmd.getMavenInfo().getVersion(), dmd.getBuildInfo());

        ReentrantLock appLock = appLocks.computeIfAbsent(appId, k -> new ReentrantLock());
        appLock.lock();
        try {
            SlotEntry prev = registry.get(appId);
            SlotEntry empty = prev == null ? new SlotEntry(null, null, null) : prev;

            // 槽位已被占 → 跳过（不该出现，但 .deploy 串了不能挂）
            if (empty.slotOf(slot) != null) {
                return BaseResult.fail(106, "slot " + slot + " of " + appId + " already occupied");
            }

            // 5. 建 ClassLoader + AppContext
            EdapAppClassLoader appCL = new EdapAppClassLoader(ear, containerCL);
            AppContext ctx = new AppContext(this, appId, version, appCL, dmd);

            // 5.5 FQCN 注册到目标 slot（覆盖写,无冲突检测）。详见 deploy() 路径注释。
            checkAndRegisterInSlot(appId, slot, dmd);

            // 6. 三段式启动（GATHERING → COMMITTING → READY）；失败回滚 + close appCL
            try {
                ctx.start();
            } catch (Throwable t) {
                // 必须先打 ERROR 日志(含完整堆栈),再清理资源、返回前端。
                // 顺序:日志优先 —— 一旦 destroyPartial/appCL.close 抛异常覆盖原 throwable,
                // 原始故障就丢了。
                // 用 error(String, Throwable) 重载,走 LogArgs.threw → MessageFormatter.printToBuilder
                // 完整路径(类名+message+at+Caused by 都已修通),堆栈不会再丢。
                log.error("AppContext 启动失败 [" + appId + ":" + version + "]", t);
                ctx.destroyPartial();
                // 回滚刚注册的 FQCN（按本路径的目标 slot）
                unregisterSlotIfs(slot, appId, dmd);
                appCL.close();
                return BaseResult.fail(104, "AppContext 启动失败: "
                        + t.getClass().getName()
                        + (t.getMessage() != null ? ": " + t.getMessage() : ""));
            }

            // 7. 写 registry（按 role 指定的 slot 直接写，不调 firstEmptySlot()）
            registry.put(appId, empty.withSlot(slot, ctx));
            // 7.5 拨 currentRouters + rebuild mapping：只在落 CURRENT 时拨指针，其它槽位只 rebuild
            //     mapping（rebuildHttpMapping 从当前 currentRouters 全集读，无 current 变动 = no-op 重建）
//            if (slot == Slot.CURRENT) {
//                currentRouters.put(appId, ctx.routers());
//            }
            rebuildHttpMapping();
            // 只在恢复 CURRENT 槽时 rebind —— PREVIOUS / STAGING 不接流量，与 deploy() 同理。
            if (slot == Slot.CURRENT) {
                rebindCurrentWsHandlers(registry.get(appId));
            }
            return BaseResult.success(appId + ":" + version + " -> " + slot);

        } catch (RuntimeException e) {
            log.error("restoreToSlot 异常", e);
            return BaseResult.fail(105, e.getMessage());
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            appLock.unlock();
        }
    }

    /**
     * 计算 composite version：
     *   - 正式版（原样返回 mavenVersion）
     *   - SNAPSHOT 版（拼接 buildTime；同一 EAR 重发 buildTime 不变 → composite 不变 → 视为重复）
     *   - 兜底（SNAPSHOT 但 buildTime 缺失 → 回退 mavenVersion + warn）
     */
    private String resolveVersion(String mavenVersion, BuildInfo buildInfo) {
        if (mavenVersion == null || !mavenVersion.endsWith("-SNAPSHOT")) {
            return mavenVersion;
        }
        String buildTime = buildInfo == null ? null : buildInfo.getBuildTime();
        if (buildTime == null || buildTime.isEmpty()) {
            log.warn("SNAPSHOT 包缺少 buildTime，回退 mavenVersion={}（同 mavenVersion 的二次部署会被拒）",
                    l -> l.arg(mavenVersion));
            return mavenVersion;
        }
        return mavenVersion + "@" + buildTime;       // "1.0.0-SNAPSHOT@20260811093000"
    }

    /** 辅助：找 composite version 所在的槽位，没有返回 null。
     *  比对 AppContext 持有的 composite（deploy 时写入 AppContext.version），
     *  用 composite 而非 mavenVersion，区分 SNAPSHOT 的多次构建。 */
    private Slot findSlotByCompositeVersion(SlotEntry entry, String compositeVersion) {
        if (entry.previous() != null && compositeVersion.equals(entry.previous().version())) return Slot.PREVIOUS;
        if (entry.current()  != null && compositeVersion.equals(entry.current().version()))  return Slot.CURRENT;
        if (entry.staging()  != null && compositeVersion.equals(entry.staging().version()))  return Slot.STAGING;
        return null;
    }

    /**
     * 选 deploy 目标槽（按 §3.6.2 语义）：
     * <ul>
     *   <li>STAGING 空闲 → 写 STAGING（新版本默认进灰度槽，需 switchVersion 才接流量）</li>
     *   <li>STAGING 占用 → 不会走到这里（{@link #deploy} 在前面已经把旧 STAGING 卸掉再调本方法）</li>
     * </ul>
     * <b>PREVIOUS 不在选择范围内</b> —— PREVIOUS 是"上一个 current 的快速回滚备份"，
     * 只由 {@link #switchVersion} 退位时填入（deploy() 永不主动写 PREVIOUS）。
     * <b>CURRENT 不在选择范围内</b> —— CURRENT 只由 switchVersion 把 staging 切过来、
     * 或 {@link #restoreToSlot} 启动恢复期间按磁盘文件名写。
     *
     * <p>历史 bug：原实现按 {@code PREVIOUS → CURRENT → STAGING} 顺序找空槽，导致
     * 全新应用首次 deploy 落到 PREVIOUS 槽，写出 {@code previous-*.json}，
     * 且不接流量（{@code currentRouters} 未拨指针 → 业务 503），必须再手工 switchVersion 一次。
     */
    private Slot firstEmptySlot(SlotEntry entry) {
        if (entry.staging() == null) {
            return Slot.STAGING;
        }
        return null;       // staging 已被占——三个槽里只有 staging 允许 deploy 写入
    }

    public BaseResult<String> undeploy(String appId, String version) {
        // version 是 composite version（deploy 时计算的字符串）
        ReentrantLock appLock = appLocks.get(appId);
        if (appLock == null) return BaseResult.fail(404, "未部署: " + appId);
        appLock.lock();
        try {
            SlotEntry prev = registry.get(appId);
            if (prev == null) return BaseResult.fail(404, "未部署: " + appId);

            // 按 composite version 找槽位；SNAPSHOT 多个 build 共存时也能区分
            Slot slot = findSlotByCompositeVersion(prev, version);
            if (slot == null) return BaseResult.fail(404, "版本 " + version + " 未部署: " + appId);

            AppContext ctx = prev.slotOf(slot);

            // 1. 停 AppContext（RouterHub.unbindAll → @PreDestroy → Lifecycle.stop() → CL close）
            //    注意：不需要从 appServerGroup 移除 Server
            //      - "停止接收流量"由 ctx.stop() → RouterHub.unbindAll() 完成（路由层摘除）
            //      - removeServer 只改 ServerGroup 列表引用，对 NIO 绑定无影响，是冗余
            //      - Server 生命周期由 Edap.run() / Edap.stop() 管理，不归 Container 操纵
            try {
                ctx.stop();
            } catch (Throwable t) {
                log.warn("undeploy 时 AppContext.stop() 异常", t);
            }
            // 1.5 摘除本 appId 在指定 slot 注册的 ProtoService FQCN —— 必须 ctx.stop() 之后调，
            //     避免新 deploy 自冲突的瞬时误判
            unregisterSlotIfs(slot, appId, ctx.dmd());
            // 2. 写 registry（整 SlotEntry 替换）
            SlotEntry next = prev.withSlot(slot, null);
            if (next.isEmpty()) {
                registry.remove(appId);
                appLocks.remove(appId, appLock);                // 锁对象 GC 友好
            } else {
                registry.put(appId, next);
            }
            // 3. 清掉 currentRouters 指针：被卸的是 current → 业务不再接流量；非 current 不动
            // 3.5 rebuild HTTP mapping：current 变动必触发；非 current 变动 → 重建是 no-op（指针未动）
            rebuildHttpMapping();
            // 3.6 rebind 共享 wsHandler.msgHandlers：被卸的若是 current，新 current（如果有）
            //     已经接过 ctx.stop() 之外的 slot 切换；dispatch 表换了，但 wsHandler 内的
            //     msgHandlers 还是老 ctx 的 method 表 —— 用新 current 的 wsMsgHandlers 覆盖。
            //     非 current 卸 → current 没动 → rebindCurrentWsHandlers 见 entry.current() 无变化无副作用。
            SlotEntry afterUndeploy = registry.get(appId);
            if (afterUndeploy != null) {
                rebindCurrentWsHandlers(afterUndeploy);
            }
            // 4. 同步 .deploy/<role>-<appId>.json（被卸的 slot 文件删，其它 slot 文件按 registry 实际状态重写）
            syncDeployMetaFiles(appId);
            // 5. SlotEntry 全空 → apps.json 移除 appId
            if (next.isEmpty()) {
                removeDeployAppId(appId);
            }
            return BaseResult.success(appId + ":" + version + " (slot=" + slot + ")");

        } finally {
            appLock.unlock();
        }
    }

    public BaseResult<String> switchVersion(String appId, String version) {
        // version = composite version（含 SNAPSHOT 的 @buildTime）
        // 调用方应先 listSlots(appId) 查到目标 compositeVersion 再传入
        // 前置：appLocks[appId] 持有
        ReentrantLock appLock = appLocks.get(appId);
        if (appLock == null) return BaseResult.fail(404, "appId 未部署");
        appLock.lock();
        try {
            SlotEntry prev = registry.get(appId);
            if (prev == null) return BaseResult.fail(404, "appId 未部署");
            // 比对用 composite；SNAPSHOT 同 mavenVersion 不同 buildTime 也能正确识别
            if (prev.current() != null && version.equals(compositeOf(prev.current()))) {
                return BaseResult.fail(101, "已是当前版本");
            }
            SlotEntry next;
            AppContext demotedCurrent = prev.current();
            if (prev.staging() != null && version.equals(compositeOf(prev.staging()))) {
                // staging → current；先把 PREVIOUS 槽里上一个 demoted（如果有）cancel + 立即 stop
                // —— 新 demotedCurrent 落入 PREVIOUS 槽之前要腾位置
                AppContext oldPrev = prev.previous();
                if (oldPrev != null && oldPrev.isDemoted()) {
                    oldPrev.cancelRelease();
                    stopPrevImmediate(oldPrev);
                }
                // staging → current；current 落入 previous
                next = new SlotEntry(demotedCurrent, prev.staging(), null);
            } else {
                // rollback 统一走 "deploy 到 staging → switchVersion(staging → current)" 二段式
                // PREVIOUS 槽不接 rollback —— 它只承接 in-flight 跑完 + 记录上次版本
                return BaseResult.fail(400, "rollback 路径已统一：先 deploy 到 staging 槽，再 switchVersion(staging → current)。"
                        + "PREVIOUS 槽不接 rollback，版本 " + version + " 不在 staging 槽");
            }
            // 整 SlotEntry 替换，ConcurrentHashMap.put 原子发布
            registry.put(appId, next);
            // FQCN 注册跟随 slot 转移：
            //   - demotedCurrent（原 current → 现在 PREVIOUS 槽）的 FQCN：currentRegistered → previousRegistered
            //   - 新 current（曾 staging）的 FQCN：stagingRegistered → currentRegistered
            // moveSlotIfs 用 owner.equals(appId) 严格 scope 到本 appId —— 跨 appId 的 switchVersion
            // 是并行的（不同 appId 走各自 appLock），但 moveSlotIfs 不会误动其他 app 的 entries。
            // 同一 appId 的 switchVersion 在 appLock 串行下，不会并发对同一 map 做反向 move。
            if (demotedCurrent != null) {
                moveSlotIfs(Slot.CURRENT, Slot.PREVIOUS, appId, demotedCurrent.dmd());
            }
            moveSlotIfs(Slot.STAGING, Slot.CURRENT, appId, prev.staging().dmd());
            // 更新 currentRouters 指针：业务 dispatch 走 currentRouters.get(appId)
            //   - 不调 edap.rebindRouter：Edap 不知道 Router 逻辑，不持有路由表
            //   - 各 AppContext 的 routes 已在 ctx.start() Phase 3 由 AppContext.generateAndBindRoutes()
            //     生成并写入 ctx.routers()，NIO Server 注册由 Container.deploy() 末尾的
            //     appServerGroup.addServer(s) 完成
            //   - 切换版本只是换"哪个 RouterHub 接流量"，不是重新注册 routes
            //currentRouters.put(appId, next.current().routers());
            // rebuild HTTP mapping：current 指针动了 → 必须重建 dispatch 表
            rebuildHttpMapping();
            // 把 next.current() 的 wsMsgHandlers rebind 到 /ws 共享 wsHandler —— 老连接
            // session.wsHandler 引用不变，靠 msgHandlers 被替换感知新版本方法表。
            rebindCurrentWsHandlers(next);
            // 同步 .deploy/<role>-<appId>.json 三个文件：非空 slot 写、空 slot 删
            syncDeployMetaFiles(appId);
            // PREVIOUS 槽里新 demotedCurrent 排延迟释放 —— 给 in-flight 一个收尾窗口（默认 60s），
            // 到点由 prevReleaseScheduler 调 ctx.stop()，destroyAllSingletons 释放 Hikari 等连接池。
            // 如果 60s 内又发生 switchVersion 新的 demote，上面的分支已 cancel + 立即 stop 这个 ctx。
            if (demotedCurrent != null) {
                demotedCurrent.markDemoted();
                schedulePrevRelease(demotedCurrent);
            }
            return BaseResult.success("切换到 " + version);
        } finally {
            appLock.unlock();
        }
    }

    /** 从 AppContext 拿 composite version（从 dmd 反算，保持与 deploy 时一致） */
    private String compositeOf(AppContext ctx) {
        DeployMetaData dmd = ctx.dmd();
        return resolveVersion(dmd.getMavenInfo().getVersion(), dmd.getBuildInfo());
    }

    /**
     * 持久化部署元数据到 appsDir/.deploy/&lt;role&gt;-&lt;appId&gt;.json。
     * 用于 Container.start 启动恢复（读） + deploy/switchVersion 阶段回写（写）。
     *
     * <p>写入 DeployMeta（轻量记录：earName / buildTime / artifactVersion / deployTime /
     * onlineTime / deployer / onliner / previousEarName），不是 DeployMetaData（后者过重：包含
     * 整个 EAR 扫出的 Bean 定义 / 注解元数据，反序列化开销不值）。start() 启动恢复只读
     * {@link #readDeployMetaFile} 拿 earName 即可定位 EAR。
     *
     * <p>前置：{@code dmd.getOrignalFile()} 必须已设（deploy() / restoreToSlot() 解析后立即调
     * {@code dmd.setOrignalFile(ear)}），否则 {@code getName()} NPE。
     */
    private void writeDeployMeta(String appId, String role, DeployMetaData dmd) {
        File metaFile = new File(new File(appsDir, ".deploy"), role + "-" + appId + ".json");
        try {
            // 确保 .deploy 目录存在
            File deployDir = metaFile.getParentFile();
            if (deployDir != null && !deployDir.exists() && !deployDir.mkdirs()) {
                log.warn("无法创建 .deploy 目录: {}", l -> l.arg(deployDir.getAbsolutePath()));
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            String time = now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            DeployMeta meta = new DeployMeta();
            meta.setEarName(dmd.getOrignalFile().getName());
            meta.setBuildTime(dmd.getBuildInfo() == null ? null : dmd.getBuildInfo().getBuildTime());
            meta.setArtifactVersion(dmd.getMavenInfo() == null ? null : dmd.getMavenInfo().getVersion());
            meta.setDeployer("container");
            meta.setOnliner("container");
            meta.setDeployTime(time);
            meta.setOnlineTime(time);
            // previousEarName: 仅 current 角色关心（"刚退位的老 current" ->
            // stashVersion staging→current/previous→current 时 registry.previous() 就是它）；
            // previous/staging 角色无"前一个 current"语义，统一空串。
            if ("current".equals(role)) {
                SlotEntry entry = registry.get(appId);
                if (entry != null && entry.previous() != null
                        && entry.previous().dmd().getOrignalFile() != null) {
                    meta.setPreviousEarName(entry.previous().dmd().getOrignalFile().getName());
                } else {
                    meta.setPreviousEarName("");
                }
            } else {
                meta.setPreviousEarName("");
            }
            try (FileOutputStream out = new FileOutputStream(metaFile)) {
                out.write(Eson.toJsonString(meta).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            log.warn("writeDeployMeta 失败: {}", l -> l.arg(metaFile.getAbsolutePath()).threw(e));
        }
    }

    /**
     * 追加 appId 到 appsDir/.deploy/apps.json。start() 启动恢复以这个文件为 appId 索引，
     * 缺了它 {apps.json, current-*.json, staging-*.json} 三个文件就脱节。
     *
     * <p>已存在则 no-op（不重复写）；不存在则读现有列表 → 追加 → 整文件回写。
     * 写盘失败只 WARN 不抛 —— 启动期恢复退化为空 registry，不阻断运行期。
     */
    private void appendDeployAppId(String appId) {
        File deployDir = new File(appsDir, ".deploy");
        if (!deployDir.exists() && !deployDir.mkdirs()) {
            log.warn("无法创建 .deploy 目录: {}", l -> l.arg(deployDir.getAbsolutePath()));
            return;
        }
        File appsFile = new File(deployDir, "apps.json");
        List<String> appIds = new ArrayList<>(readDeployAppIds());
        if (appIds.contains(appId)) {
            return;
        }
        appIds.add(appId);
        try (FileOutputStream out = new FileOutputStream(appsFile)) {
            out.write(Eson.toJsonString(appIds).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("更新 apps.json 失败: {}", l -> l.arg(appsFile.getAbsolutePath()).threw(e));
        }
    }

    /**
     * 从 appsDir/.deploy/apps.json 移除 appId。undeploy 末位 SlotEntry 全空时调。
     * 列表变空则删整个文件（保持目录干净），否则整文件回写。
     */
    private void removeDeployAppId(String appId) {
        File deployDir = new File(appsDir, ".deploy");
        if (!deployDir.exists()) return;
        File appsFile = new File(deployDir, "apps.json");
        if (!appsFile.exists()) return;
        List<String> appIds = new ArrayList<>(readDeployAppIds());
        if (!appIds.remove(appId)) {
            return;                                                  // 本来就不在
        }
        if (appIds.isEmpty()) {
            if (!appsFile.delete()) {
                log.warn("删除空 apps.json 失败: {}", l -> l.arg(appsFile.getAbsolutePath()));
            }
        } else {
            try (FileOutputStream out = new FileOutputStream(appsFile)) {
                out.write(Eson.toJsonString(appIds).getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                log.warn("更新 apps.json 失败: {}", l -> l.arg(appsFile.getAbsolutePath()).threw(e));
            }
        }
    }

    /**
     * 同步 registry 当前状态到 .deploy/role-*.json。每个 slot 非空写文件，slot 空删文件。
     * 用于 switchVersion / undeploy 之后清理磁盘 —— 比"按事件驱动的精确写"鲁棒（有重复写开销，
     * 但 deploy/switch/undeploy 不是热路径）。
     *
     * <p>覆盖三种典型场景：
     * <ul>
     *   <li>switchVersion staging→current：写 current、新写 previous、删 staging</li>
     *   <li>switchVersion previous→current（lazy restore）：写 current、写 staging、删 previous</li>
     *   <li>undeploy：被卸的 slot 文件删，其它 slot 文件按 registry 实际状态重写</li>
     *   <li>registry 整条 appId 都没了（undeploy 卸最后一个版本）：3 个文件全删</li>
     * </ul>
     */
    private void syncDeployMetaFiles(String appId) {
        SlotEntry entry = registry.get(appId);
        syncDeployMetaSlot(appId, "current",  entry == null ? null : entry.current());
        syncDeployMetaSlot(appId, "staging",  entry == null ? null : entry.staging());
        syncDeployMetaSlot(appId, "previous", entry == null ? null : entry.previous());
    }

    /**
     * 单 slot 同步：ctx != null → 写文件；ctx == null → 删文件（不存在 no-op）。
     */
    private void syncDeployMetaSlot(String appId, String role, AppContext ctx) {
        File metaFile = new File(new File(appsDir, ".deploy"), role + "-" + appId + ".json");
        if (ctx == null) {
            if (metaFile.exists() && !metaFile.delete()) {
                log.warn("删除 .deploy/{} 失败", l -> l.arg(metaFile.getName()));
            }
        } else {
            writeDeployMeta(appId, role, ctx.dmd());
        }
    }

    // 查询
    public List<MicroServiceInfo> listApps() {
        List<MicroServiceInfo> apps = new ArrayList<>();

        return apps;
    }

    public AppContext getAppContext(String appId, Slot slot) {
        SlotEntry entry = registry.get(appId);                       // ConcurrentHashMap.get：无锁
        return entry == null ? null : entry.slotOf(slot);
    }

    // 注入
    public void setDeployManager(DeployManager dm) {
        this.deployManager = dm;
    }

    // 状态
    public ContainerState getState() {
        return state;
    }

    // ─── ProtoService FQCN 冲突检测 + HTTP mapping rebuild ───

    /**
     * 从 dmd 提取所有 ProtoService FQCN（去重）。覆盖 dmd.protoServiceInfos（顶层）和
     * dmd.componentMap[*].protoServiceInfos（per-component），两者可能并存。
     *
     * <p>为什么用 LinkedHashSet：保留遍历顺序便于报错时列出；FQCN 通常 < 100，set 开销可忽略。</p>
     */
    private Set<String> extractProtoServiceFQCNs(DeployMetaData dmd) {
        Set<String> fqcns = new LinkedHashSet<>();
        if (dmd == null) {
            return fqcns;
        }
        List<ProtoServiceData> top = dmd.getProtoServiceInfos();
        if (top != null) {
            for (ProtoServiceData psi : top) {
                if (psi != null && psi.getTypeName() != null) {
                    fqcns.add(psi.getTypeName());
                }
            }
        }
        Map<String, DeployComponent> comps = dmd.getComponentMap();
        if (comps != null) {
            for (DeployComponent comp : comps.values()) {
                if (comp == null) continue;
                List<ProtoServiceData> psiList = comp.getProtoServiceInfos();
                if (psiList == null) continue;
                for (ProtoServiceData psi : psiList) {
                    if (psi != null && psi.getTypeName() != null) {
                        fqcns.add(psi.getTypeName());
                    }
                }
            }
        }
        return fqcns;
    }

    /**
     * 冲突检测 + 注册：deploy / switchVersion 入口。
     *
     * <p>规则：每个 ProtoService FQCN 在一 Container 内只能被一个 appId 注册。
     * <ul>
     *   <li>已被「其他 appId」注册 → 拒绝（409）</li>
     *   <li>已被「同一 appId」注册 → 允许（version 切换覆盖语义）</li>
     *   <li>未注册 → 直接注册</li>
     * </ul>
     *
     * @return null 表示成功；非 null 是失败原因（BaseResult 的 message）
     */
    /**
     * deploy 路径专用：deploy 永远写 STAGING 槽。
     * <p>无冲突检测 —— BeanContainer 是 per-AppContext 的,同名 ProtoService FQCN 在不同 app 间
     * 由各自的 ClassLoader 天然隔离。注册表只用于诊断/反向查询,谁后写谁赢。
     */
    private void checkAndRegisterStaging(String appId, DeployMetaData dmd) {
        Set<String> fqcns = extractProtoServiceFQCNs(dmd);
        if (fqcns.isEmpty()) {
            return;
        }
        // 覆盖写：不同 appId 也允许,后写者赢。cleanup 走 unregisterSlotIfs 的条件删除,
        // 不会误删并发注册的同 FQCN。
        for (String fqcn : fqcns) {
            stagingRegistered.put(fqcn, appId);
        }
    }

    /**
     * 启动恢复路径专用：restoreToSlot 已知目标槽。
     * <p>无冲突检测 —— 直接覆盖写。{@link #checkAndRegisterStaging} 同理。
     */
    private void checkAndRegisterInSlot(String appId, Slot slot, DeployMetaData dmd) {
        Set<String> fqcns = extractProtoServiceFQCNs(dmd);
        if (fqcns.isEmpty()) {
            return;
        }
        Map<String, String> target = mapOf(slot);
        // 覆盖写：target map 中已有的同名 FQCN（不论 owner 是哪个 appId）一律被本 appId 顶掉
        for (String fqcn : fqcns) {
            target.put(fqcn, appId);
        }
    }

    /**
     * 摘除本 appId 在指定 slot 的 FQCN 注册。条件删除（{@code remove(fqcn, appId)}），
     * 不会误删并发注册的同 FQCN。
     */
    private void unregisterIfs(String appId, DeployMetaData dmd) {
        // 兼容旧调用点：从三个 map 都尝试条件删除（清理可能跨槽的残留）
        for (String fqcn : extractProtoServiceFQCNs(dmd)) {
            currentRegistered.remove(fqcn, appId);
            stagingRegistered.remove(fqcn, appId);
            previousRegistered.remove(fqcn, appId);
        }
    }

    /**
     * 摘除本 appId 在指定 slot 的 FQCN 注册（slot 已知版本）。条件删除。
     */
    private void unregisterSlotIfs(Slot slot, String appId, DeployMetaData dmd) {
        Map<String, String> m = mapOf(slot);
        for (String fqcn : extractProtoServiceFQCNs(dmd)) {
            m.remove(fqcn, appId);
        }
    }

    /**
     * 把 FQCN 注册从 from 槽移到 to 槽（switchVersion 用）。
     * 仅当 entry 当前 owner 是 appId 时才移动，避免误移并发注册的同 FQCN。
     */
    private void moveSlotIfs(Slot from, Slot to, String appId, DeployMetaData dmd) {
        Map<String, String> src = mapOf(from);
        Map<String, String> dst = mapOf(to);
        for (String fqcn : extractProtoServiceFQCNs(dmd)) {
            String owner = src.get(fqcn);
            if (owner != null && owner.equals(appId)) {
                src.remove(fqcn, appId);
                dst.put(fqcn, appId);
            }
        }
    }

    /**
     * 按槽位取对应的注册 map。Slot 是单文件 enum，switch 直接覆盖三个分支。
     */
    private Map<String, String> mapOf(Slot slot) {
        switch (slot) {
            case PREVIOUS: return previousRegistered;
            case CURRENT:  return currentRegistered;
            case STAGING:  return stagingRegistered;
            default: throw new IllegalArgumentException("unknown slot: " + slot);
        }
    }

    /**
     * 从所有 currentRouters 的 app 收集 path 映射，整张替换 {@link HttpServer#setHttpMapping}。
     *
     * <p>调用时机（都在 appLock 持有内）：
     * <ul>
     *   <li>deploy() 末尾：ctx.start() 后 + currentRouters 指针拨到新 ctx 之后</li>
     *   <li>restoreToSlot(CURRENT) 末尾：同上</li>
     *   <li>switchVersion() 替换 currentRouters 之后</li>
     *   <li>undeploy() 删 currentRouters 之后</li>
     *   <li>start() 启动恢复 currentRouters 拨完之后</li>
     * </ul>
     *
     * <p>数据来源：{@link #appPathTables}（由 {@link #deployAppRoutes} 写入）—— 单 app 完整
     * path 表（含 HTTP entries + WS entries，已在 AppContext.buildPathTable() 阶段组装好）。
     * 这里只挑 currentRouters 里的 appId（slot = CURRENT），即"当前接流量的 app 集合"；
     * STAGING / PREVIOUS 槽的 app 已部署但暂不接流量，不参与 dispatch。</p>
     *
     * <p>为什么从 {@link #appPathTables} 读而非 per-AppContext 字段（httpHandlersByPath /
     * serviceWSHandler）：appPathTables 由 {@link #deployAppRoutes} 写入完整 pathTable
     * （HTTP + WS），直接 putAll 同时拿到两份协议，且保留 currentRouters 槽位过滤语义。
     * 原写法只读 httpHandlersByPath 会漏掉 WS PathInfo（{@code /ws} 路径）。</p>
     *
     * <p>空 mapping（无任何 app 部署）：传空 Map 而非 null，HttpServer 内部兜底。</p>
     */
    private void rebuildHttpMapping() {
        if (httpServer == null) {
            return;                                        // HTTP capability 未启用
        }
        PathInfoMatcher pathInfoMatcher = new PathInfoMatcher();
        Map<FastBufDataRange, PathInfo> combined = new HashMap<>();
        // 仅消费 CURRENT 槽位的 pathTable —— STAGING / PREVIOUS 已部署但暂不接流量。
        // 历史 bug:遍历 appPathTables.values() 把 3 个槽位的 PathInfo 全混进 combined,
        // 导致 switchVersion 后 wildcard 路由命中陈旧版本(后注册者覆盖前者的非确定性 +
        // PrefixWildcardPathRouter.registerPathInfo 用引用 equals 去重失败)。
        for (Map.Entry<String, SlotEntry> appEntry : registry.entrySet()) {
            AppContext cur = appEntry.getValue().current();
            if (cur == null) {
                continue;                                  // 该 appId 暂无 current 槽位
            }
            Map<FastBufDataRange, PathInfo> t = appPathTables.get(appEntry.getKey());
            if (t == null) {
                continue;
            }
            for (Map.Entry<FastBufDataRange, PathInfo> e : t.entrySet()) {
                PathInfo src = e.getValue();
                if (src == null) {
                    continue;
                }
                if (src.getPath().indexOf('*') == 0) {
                    pathInfoMatcher.registerPrefixMatcher(src);
                    continue;
                } else if (src.getPath().indexOf('*') > 0) {
                    pathInfoMatcher.registerPostfixMatcher(src);
                    continue;
                }
                PathInfo dst = combined.get(e.getKey());
                if (dst == null) {
                    combined.put(e.getKey(), src);          // 首次注册,直接占位
                    continue;
                }
                // 同 path 已被另一个 app 注册 —— 按 method 下标并集合并,
                // 避免 combined.putAll 把先注册的 PathInfo 整体替换,
                // 丢掉它在其他 method 上的 handler。
                mergeHttpHandlers(dst, src);
                // wsHandler / wsAuthenticator: deployAppRoutes 已通过 wsPathOwners
                // 保证同 path 唯一 owner,这里做防御性合并(空槽才填)。
                if (dst.getWsHandler() == null && src.getWsHandler() != null) {
                    dst.setWsHandler(src.getWsHandler());
                }
                if (dst.getWsAuthenticator() == null && src.getWsAuthenticator() != null) {
                    dst.setWsAuthenticator(src.getWsAuthenticator());
                }
            }
        }
        pathInfoMatcher.setCache(combined);
        httpServer.setPathInfoMatcher(pathInfoMatcher);
    }

    /**
     * 按 HTTP method 下标合并 src.httpHandlers 到 dst.httpHandlers。
     *
     * <p>语义:
     * <ul>
     *   <li>dst 数组不够长 → 扩容到 src.length,旧元素保留</li>
     *   <li>同 method 下标:dst 已占位则保留(dst 先注册优先),否则填入 src</li>
     *   <li>这样不同 method 的 handler 不会互相覆盖,跨 app 注册同一 path 不同 method 的场景
     *       (典型:App A 注册 GET /x,App B 注册 POST /x)能各自保留</li>
     * </ul>
     */
    private static void mergeHttpHandlers(PathInfo dst, PathInfo src) {
        HttpHandler[] srcHandlers = src.getHttpHandlers();
        if (srcHandlers == null || srcHandlers.length == 0) {
            return;
        }
        HttpHandler[] dstHandlers = dst.getHttpHandlers();
        int srcLen = srcHandlers.length;
        if (dstHandlers == null || dstHandlers.length < srcLen) {
            HttpHandler[] grown = new HttpHandler[srcLen];
            if (dstHandlers != null) {
                System.arraycopy(dstHandlers, 0, grown, 0, dstHandlers.length);
            }
            dst.setHttpHandlers(grown);
            dstHandlers = grown;
        }
        for (int i = 0; i < srcLen; i++) {
            if (dstHandlers[i] == null && srcHandlers[i] != null) {
                dstHandlers[i] = srcHandlers[i];
            }
        }
    }
}
