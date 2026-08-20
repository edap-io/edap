package io.edap.container;

import io.edap.Edap;
import io.edap.ServerGroup;
import io.edap.container.app.RouterHub;
import io.edap.container.event.EventPublisher;
import io.edap.container.mw.*;
import io.edap.container.scan.EarScanner;
import io.edap.http.server.HttpServer;
import io.edap.http.HttpHandler;
import io.edap.http.PathInfo;
import io.edap.http.ws.HeaderTokenAuthenticator;
import io.edap.http.ws.WSAuthenticator;
import io.edap.microservice.Scope;
import io.edap.nio.codec.FastBufDataRange;
import io.edap.json.Eson;
import io.edap.launcher.NestedJarFile;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
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
    private final ConcurrentHashMap<String, RouterHub> currentRouters = new ConcurrentHashMap<>();
    /**
     * ③ 锁表：appId → 写锁。只增不删（原因见 §3.7.5）
     */
    private final ConcurrentHashMap<String, ReentrantLock> appLocks = new ConcurrentHashMap<>();

    /**
     * ④ ProtoService 接口 FQCN → 拥有它的 appId。冲突检测表：deploy / switchVersion 时
     * 检查 dmd 里所有 ProtoService FQCN，若已被另一个 appId 注册 → 409 拒绝部署。
     * 同 appId 重部署允许（覆盖语义，version 切换场景）；undeploy 摘除。
     */
    private final ConcurrentHashMap<String, String> registeredIfs = new ConcurrentHashMap<>();

    /**
     * 框架级 Bean 容器：edap 容器内置功能 bean 集合（如 {@link WSAuthenticator} 默认实现）。
     * <p>AppContext 级 BeanContainer 在 {@code beanWrapByType} miss 时自动 fallback 查此容器，
     *     实现"应用零配置即用内置功能，应用 bean 自动覆盖默认实现"的语义。</p>
     */
    private BeanContainer containerBeans;

    /**
     * ⑤ WS path → owner appId。冲突检测表：deploy / version 切换时
     * 检查 app 全量 pathTable 中所有 WS path，若已被另一个 appId 注册 → 409 拒绝部署。
     * 同 appId 重部署允许（version 切换场景）；undeploy 摘除。
     * <p>为什么需要独立的 WS path 冲突检测：HTTP path 冲突由 ProtoService FQCN 唯一性间接挡住，
     *     不同 appId 不能持有同 FQCN；但 WS path 是字符串粒度，不同 FQCN 的两个 proto service
     *     完全可能标同一个 {@code @ProtoWebSocket(path="/ws")}——FQCN 检测挡不住 WS path 冲突。</p>
     */
    private final ConcurrentHashMap<String, String> wsPathOwners = new ConcurrentHashMap<>();

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
                int httpPort = Integer.parseInt(
                        System.getProperty("edap.container.http.port", "8080"));
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
        containerBeans.topologicalSort();
        containerBeans.transitionToCommitting();
        for (BeanDef def : containerBeans.sorted()) {
            Object instance = containerBeans.instantiate(def);
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
     *   <li>对 {@code newTable} 中所有 PathInfo.wsHandler != null 的 entry 做跨 app WS path 冲突检测
     *       （同 appId 覆盖放行；不同 appId 抛 IllegalStateException → deploy 失败）</li>
     *   <li>{@link #wsPathOwners} 写 owner</li>
     *   <li>{@link #appPathTables} put(appId, newTable)</li>
     * </ol>
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
     * @throws IllegalStateException 跨 app WS path 冲突
     */
    public void deployAppRoutes(String appId, Map<FastBufDataRange, PathInfo> newTable) {
        if (newTable == null) {
            newTable = Collections.emptyMap();
        }
        // 1. 冲突检测（同 appId 覆盖放行；不同 appId 抛异常）
        for (Map.Entry<FastBufDataRange, PathInfo> e : newTable.entrySet()) {
            PathInfo pi = e.getValue();
            if (pi != null && pi.getWsHandler() != null) {
                String pathStr = pi.getPath();
                if (pathStr == null || pathStr.isEmpty()) {
                    continue;                                       // 无 path 字段的 PathInfo 跳过
                }
                String prevOwner = wsPathOwners.putIfAbsent(pathStr, appId);
                if (prevOwner != null && !prevOwner.equals(appId)) {
                    throw new IllegalStateException(
                            "WS path [" + pathStr + "] already owned by appId=" + prevOwner
                                    + ", cannot register for appId=" + appId
                                    + "（同一 Container 内 WS path 需唯一）");
                }
                // 同 appId 重 deploy / version 切换：putIfAbsent 不会覆盖，需手动放行
                if (prevOwner != null && prevOwner.equals(appId)) {
                    wsPathOwners.put(pathStr, appId);               // 同 owner 强制刷新（顺序无影响）
                }
            }
        }
        // 2. 存表（不 publish，等调用方 rebuildHttpMapping 一次性写）
        appPathTables.put(appId, newTable);
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
            if (cur != null && cur.routers() != null) {
                currentRouters.put(appId, cur.routers());
            }
        }
        // 启动恢复末位 rebuild HTTP mapping：所有 current 指针已就位，dispatch 必须 ready 才接受流量
        rebuildHttpMapping();

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

        // 锁外：逆序停所有 AppContext（从所有 3 槽位收集）
        List<AppContext> all = new ArrayList<>();
        for (SlotEntry entry : registry.values()) {
            if (entry.previous() != null) all.add(entry.previous());
            if (entry.current()  != null) all.add(entry.current());
            if (entry.staging()  != null) all.add(entry.staging());
        }
        Collections.reverse(all);
        for (AppContext ctx : all) {
            try {
                // 同 undeploy：ctx.stop() 已覆盖路由/Server 摘除，不另调 removeServer
                ctx.stop();
            } catch (Throwable t) {
                log.warn("Container.stop 时 {} 异常", l -> l.arg(ctx.appId()).threw(t));
            }
            // 同步清掉本 appId 的 currentRouters 指针 + FQCN 注册
            // （ctx.stop() 不动这两张表，因为 undeploy 路径由 Container 自己清 —— 这里是 stop 路径）
            currentRouters.remove(ctx.appId());
            unregisterIfs(ctx.appId(), ctx.dmd());
        }
        // 最终 rebuild HTTP mapping：所有 appId 已停 → 重建结果为空 mapping，dispatch 兜底 404
        rebuildHttpMapping();

        lifecycleLock.lock();
        try {
            state = ContainerState.STOPPED;                        // STOPPING -> STOPPED
        } finally {
            lifecycleLock.unlock();
        }
        log.info("Container stopped.");
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
            // 4. 槽位满检查
            if (empty.previous() != null && empty.current() != null && empty.staging() != null) {
                return BaseResult.fail(105, "已存在3个版本，请先 undeploy");
            }

            // 5. 建 ClassLoader + AppContext
            EdapAppClassLoader appCL = new EdapAppClassLoader(ear, containerCL);
            AppContext ctx = new AppContext(this, appId, version, appCL, dmd);

            // 5.5 FQCN 冲突检测（不同 appId 抛 409；同 appId 允许覆盖语义）
            //     必须在 ctx.start() 之前 —— 否则 Bean 已经注册到容器再发现冲突，回滚成本高
            String ifErr = checkAndRegisterIfs(appId, dmd);
            if (ifErr != null) {
                appCL.close();
                return BaseResult.fail(409, ifErr);
            }

            // 6. 三段式启动（GATHERING → COMMITTING → READY）
            try {
                ctx.start();                                   // 详见 §4
            } catch (Throwable t) {
                ctx.destroyPartial();                          // 回滚已注册的 Bean / 路由
                unregisterIfs(appId, dmd);                     // 回滚 FQCN 注册
                appCL.close();                                // 释放 ClassLoader
                return BaseResult.fail(104, "AppContext 启动失败: " + t.getMessage());
            }

            // 7. 写 registry（整 SlotEntry 替换，原子发布）
            Slot target = firstEmptySlot(empty);
            SlotEntry next = empty.withSlot(target, ctx);
            registry.put(appId, next);
            // 7.5 整体 rebuild HTTP mapping
            //     deploy() 路径 target 只可能是 STAGING（firstEmptySlot 永不返回 CURRENT/PREVIOUS），
            //     不拨 currentRouters —— STAGING 不接流量，需 switchVersion(staging → current) 才上线
            rebuildHttpMapping();
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

            // 5.5 FQCN 冲突检测（启动恢复路径同样要拦 —— 多个 appId 的 EAR 并存时必须唯一）
            String ifErr = checkAndRegisterIfs(appId, dmd);
            if (ifErr != null) {
                appCL.close();
                return BaseResult.fail(409, ifErr);
            }

            // 6. 三段式启动（GATHERING → COMMITTING → READY）；失败回滚 + close appCL
            try {
                ctx.start();
            } catch (Throwable t) {
                ctx.destroyPartial();
                unregisterIfs(appId, dmd);
                appCL.close();
                return BaseResult.fail(104, "AppContext 启动失败: " + t.getMessage());
            }

            // 7. 写 registry（按 role 指定的 slot 直接写，不调 firstEmptySlot()）
            registry.put(appId, empty.withSlot(slot, ctx));
            // 7.5 拨 currentRouters + rebuild mapping：只在落 CURRENT 时拨指针，其它槽位只 rebuild
            //     mapping（rebuildHttpMapping 从当前 currentRouters 全集读，无 current 变动 = no-op 重建）
            if (slot == Slot.CURRENT) {
                currentRouters.put(appId, ctx.routers());
            }
            rebuildHttpMapping();
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
     * 启动期 previous 槽位空，但 {@code .deploy/previous-<appId>.json} 可能在（上次运行时
     * switchVersion 退位写下的"待命角色"快照）。switchVersion() 切到 previous 时按需 lazy restore：
     * 读 metadata 拿 EAR，{@link #restoreToSlot(File, Slot)} 写到 PREVIOUS 槽。
     *
     * <p>三种返回：
     * <ul>
     *   <li>registry.previous 已是 target version → 直接返回（in-memory hit，无需重建）</li>
     *   <li>previous 槽空 + .deploy/previous-*.json 有 EAR → restoreToSlot 后返回</li>
     *   <li>previous 槽被占（且版本不同）/ .deploy 缺文件 / restore 失败 → 返回 null（调用方 404）</li>
     * </ul>
     *
     * <p>前置：appLock[appId] 已持有（switchVersion 持有外层锁；restoreToSlot 内部 lock 为
     * ReentrantLock 重复入同一线程，不冲突）。
     */
    private AppContext lazyRestorePrevious(String appId, String version) {
        SlotEntry entry = registry.get(appId);
        if (entry != null && entry.previous() != null) {
            if (version.equals(compositeOf(entry.previous()))) {
                return entry.previous();                                      // in-memory hit
            }
            return null;                                                      // 槽被占且版本不同 → 不覆盖
        }
        DeployMeta meta = readDeployMetaFile("previous-" + appId + ".json");
        if (meta == null) return null;
        File ear = locateEar(meta.getEarName());
        if (ear == null) return null;
        BaseResult<String> r = restoreToSlot(ear, Slot.PREVIOUS);
        if (!r.isSuccess()) return null;
        entry = registry.get(appId);
        if (entry == null || entry.previous() == null
                || !version.equals(compositeOf(entry.previous()))) {
            return null;                                                      // restore 后版本不匹配
        }
        return entry.previous();
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
     *   <li>STAGING 占用 → null（返回 105：先 undeploy staging 或 switchVersion 把它挪走）</li>
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
            // 1.5 摘除本 appId 注册的 ProtoService FQCN —— 必须 ctx.stop() 之后调，避免新 deploy
            //     自冲突的瞬时误判（见 unregisterIfs javadoc）
            unregisterIfs(appId, ctx.dmd());
            // 2. 写 registry（整 SlotEntry 替换）
            SlotEntry next = prev.withSlot(slot, null);
            if (next.isEmpty()) {
                registry.remove(appId);
                appLocks.remove(appId, appLock);                // 锁对象 GC 友好
            } else {
                registry.put(appId, next);
            }
            // 3. 清掉 currentRouters 指针：被卸的是 current → 业务不再接流量；非 current 不动
            if (next.current() == null) {
                currentRouters.remove(appId);
            }
            // 3.5 rebuild HTTP mapping：current 变动必触发；非 current 变动 → 重建是 no-op（指针未动）
            rebuildHttpMapping();
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
                // staging → current；current 落入 previous
                next = new SlotEntry(demotedCurrent, prev.staging(), null);
            } else {
                // previous → current（快速回滚）；current 落入 staging
                // 启动期 previous 槽位空但 .deploy/previous-*.json 可能在 → lazyRestorePrevious 按需重建
                AppContext restored = lazyRestorePrevious(appId, version);
                if (restored == null) {
                    return BaseResult.fail(404, "版本不在 staging/previous 中，无法切换");
                }
                next = new SlotEntry(null, restored, demotedCurrent);
            }
            // 整 SlotEntry 替换，ConcurrentHashMap.put 原子发布
            registry.put(appId, next);
            // 更新 currentRouters 指针：业务 dispatch 走 currentRouters.get(appId)
            //   - 不调 edap.rebindRouter：Edap 不知道 Router 逻辑，不持有路由表
            //   - 各 AppContext 的 routes 已在 ctx.start() Phase 3 由 AppContext.generateAndBindRoutes()
            //     生成并写入 ctx.routers()，NIO Server 注册由 Container.deploy() 末尾的
            //     appServerGroup.addServer(s) 完成
            //   - 切换版本只是换"哪个 RouterHub 接流量"，不是重新注册 routes
            currentRouters.put(appId, next.current().routers());
            // rebuild HTTP mapping：current 指针动了 → 必须重建 dispatch 表
            rebuildHttpMapping();
            // 同步 .deploy/<role>-<appId>.json 三个文件：非空 slot 写、空 slot 删
            syncDeployMetaFiles(appId);
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
    private String checkAndRegisterIfs(String appId, DeployMetaData dmd) {
        Set<String> fqcns = extractProtoServiceFQCNs(dmd);
        if (fqcns.isEmpty()) {
            return null;                                   // 没有 ProtoService 接口，无需检测
        }
        // 先全部校验（不中途写）——避免半写状态
        for (String fqcn : fqcns) {
            String owner = registeredIfs.get(fqcn);
            if (owner != null && !owner.equals(appId)) {
                return "ProtoService " + fqcn + " 已被 appId=" + owner + " 注册，与 "
                        + appId + " 冲突（同一 Container 内 ProtoService FQCN 需唯一）";
            }
        }
        // 全部通过 → 注册（覆盖同 appId 的旧条目；version 切换场景）
        for (String fqcn : fqcns) {
            registeredIfs.put(fqcn, appId);
        }
        return null;
    }

    /**
     * 摘除本 appId 注册的 FQCN。undeploy 调用。注意：只在 ctx 真的被释放后才调——
     * 否则同 appId 立即重新 deploy 会失败（自冲突——其实不会，因为 check 允许同 appId，
     * 但 FQCN 还没摘除时，会看到 "owner=我自己" 通过，行为正确）。
     *
     * <p>为了避免「先 undeploy 再 deploy 同 appId 的瞬间」误报，建议 undeploy 末尾调。</p>
     */
    private void unregisterIfs(String appId, DeployMetaData dmd) {
        Set<String> fqcns = extractProtoServiceFQCNs(dmd);
        for (String fqcn : fqcns) {
            // 仅当 owner 是自己时才删——避免误删并发注册的同 FQCN（虽然冲突检测会拦住）
            registeredIfs.remove(fqcn, appId);
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
        Map<FastBufDataRange, PathInfo> combined = new HashMap<>();
        for (String appId : currentRouters.keySet()) {
            Map<FastBufDataRange, PathInfo> t = appPathTables.get(appId);
            if (t != null) {
                combined.putAll(t);
            }
        }
        httpServer.setHttpMapping(combined);
    }
}
