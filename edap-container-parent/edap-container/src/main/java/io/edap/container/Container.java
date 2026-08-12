package io.edap.container;

import io.edap.Edap;
import io.edap.Server;
import io.edap.ServerGroup;
import io.edap.Stoppable;
import io.edap.container.app.RouterHub;
import io.edap.container.mw.*;
import io.edap.container.scan.EarScanner;
import io.edap.json.Eson;
import io.edap.launcher.NestedJarFile;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.props.Props;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class Container {

    static Logger log = LoggerManager.getLogger(Container.class);

    private Edap          edap;
    private ClassLoader   containerCL;
    private DeployManager deployManager;
    private ServerGroup   appServerGroup;
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

    private final File appsDir;
    private static final ReentrantLock lifecycleLock = new ReentrantLock();


    public Container(File appsDir) {
        this.state       = ContainerState.NEW;
        this.appsDir     = appsDir;
        this.containerCL = Container.class.getClassLoader();
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
     */
    public void start() {
        lifecycleLock.lock();
        try {
            state.checkTransitionTo(ContainerState.STARTING);  // ATTACHED -> STARTING
            state = ContainerState.STARTING;
        } finally {
            lifecycleLock.unlock();
        }

        // 锁外做恢复；deploy() 内部用各自 appId 的 appLock 串行（不同 appId 并行）
        boolean fatal = false;
        List<String> appIds = readDeployAppIds();
        for (String appId : appIds) {
            // 按 previous → current → staging 顺序部署，
            // 对应 firstEmptySlot() 的填充顺序，槽位语义自然对齐。
            String[] roles = { "previous", "current", "staging" };
            for (String role : roles) {
                DeployMeta meta = readDeployMetaFile(role + "-" + appId + ".json");
                if (meta == null) continue;
                File ear = locateEar(meta.getEarName());
                if (ear == null) {
                    log.warn("[{}] {} 记录的 EAR {} 不存在，跳过",
                            l -> l.arg(appId).arg(role).arg(meta.getEarName()));
                    continue;
                }
                BaseResult<String> r = deploy(ear);
                if (!r.isSuccess() && isFatalDeployCode(r.getCode())) {
                    fatal = true;
                    break;
                } else if (!r.isSuccess()) {
                    log.warn("EAR {} 启动失败: {}",
                            l -> l.arg(ear.getAbsolutePath()).arg(r.getMessage()));
                }
            }
            if (fatal) break;
        }

        // 恢复完 registry 之后，把每个 appId 的 currentRouters 指针拨到当前槽的 RouterHub，
        // 否则重启后首条请求拿不到路由。
        for (Map.Entry<String, SlotEntry> e : registry.entrySet()) {
            AppContext cur = e.getValue().current();
            if (cur != null && cur.routers() != null) {
                currentRouters.put(e.getKey(), cur.routers());
            }
        }

        lifecycleLock.lock();
        try {
            if (fatal) {
                state = ContainerState.START_FAILED;
            } else {
                state = ContainerState.RUNNING;                // STARTING -> RUNNING
            }
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

    private boolean isFatalDeployCode(int code) {
        return code == 10000;
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
        }

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
        try {
            dmd = new EarScanner(new NestedJarFile(ear)).scanDeployMetaData();
        } catch (IOException e) {
            return BaseResult.fail(103, "EAR 包结构错误: " + e.getMessage());
        }
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

            // 6. 三段式启动（GATHERING → COMMITTING → READY）
            try {
                ctx.start();                                   // 详见 §4
            } catch (Throwable t) {
                ctx.destroyPartial();                          // 回滚已注册的 Bean / 路由
                appCL.close();                                // 释放 ClassLoader
                return BaseResult.fail(104, "AppContext 启动失败: " + t.getMessage());
            }

            // 7. 写 registry（整 SlotEntry 替换，原子发布）
            Slot target = firstEmptySlot(empty);
            SlotEntry next = empty.withSlot(target, ctx);
            registry.put(appId, next);
            // 8. 把 ctx 的 NIO Server 注册到 ServerGroup；Container 只做"映射"（registry 槽位 +
            //    currentRouters 指针切换），路由生成 / RouterHub 写入由 AppContext.start() Phase 3
            //    内部完成（详见 AppContext.generateAndBindRoutes()）
            for (Server s : ctx.getServers()) {
                appServerGroup.addServer(s);
            }
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
     * 辅助：找第一个空槽位（deploy target）
     */
    private Slot firstEmptySlot(SlotEntry entry) {
        if (entry.previous() == null) {
            return Slot.PREVIOUS;
        }
        if (entry.current()  == null) {
            return Slot.CURRENT;
        }
        if (entry.staging()  == null) {
            return Slot.STAGING;
        }
        return null;       // 三个都非空——调用方已检查
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
            // 2. 写 registry（整 SlotEntry 替换）
            SlotEntry next = prev.withSlot(slot, null);
            if (next.isEmpty()) {
                registry.remove(appId);
                appLocks.remove(appId, appLock);                // 锁对象 GC 友好
            } else {
                registry.put(appId, next);
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
            } else if (prev.previous() != null && version.equals(compositeOf(prev.previous()))) {
                // previous → current（快速回滚）；current 落入 staging
                next = new SlotEntry(null, prev.previous(), demotedCurrent);
            } else {
                return BaseResult.fail(404, "版本不在 staging/previous 中，无法切换");
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
            // 持久化 current-*.json
            writeDeployMeta(appId, "current", next.current().dmd());
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
     * 用于 Container.start 启动恢复（读） + switchVersion/undeploy 阶段回写（写）。
     *
     * 写入格式：JSON 序列化 DeployMetaData（依赖 edap.json.Eson）；
     * 当前 stub 阶段只写空文件占位，等 Eson 注册 DeployMetaData 序列化器后接上完整逻辑。
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
            // TODO: Eson 注册 DeployMetaData 序列化器后改为 Eson.toJson(dmd)
            // 当前 stub 阶段先写空内容，让启动恢复逻辑跳过该文件（与 readDeployMetaFile 返回 null 一致）
        } catch (Exception e) {
            log.warn("writeDeployMeta 失败: {}", l -> l.arg(metaFile.getAbsolutePath()).threw(e));
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
}
