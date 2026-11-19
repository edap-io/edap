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

import io.edap.container.app.RouterHub;
import io.edap.container.app.ErpcMethodDef;
import io.edap.container.app.asm.HandlerAsmGenerator;
import io.edap.container.event.ApplicationEvent;
import io.edap.container.event.ContextClosedEvent;
import io.edap.container.event.ContextRefreshedEvent;
import io.edap.container.event.ContextStartedEvent;
import io.edap.container.event.EventPublisher;
import io.edap.container.exc.RouteBindException;
import io.edap.container.mw.*;
import io.edap.container.scan.EarScanner;
import io.edap.container.ws.ServiceWSHandler;
import io.edap.container.ws.WSServiceMsgHandler;
import io.edap.http.HttpHandler;
import io.edap.http.PathInfo;
import io.edap.http.cache.MethodCache;
import io.edap.http.server.UrlMapping;
import io.edap.http.server.UrlMappingItem;
import io.edap.http.ws.WSAuthenticator;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.microservice.Scope;
import io.edap.microservice.annotation.Bean;
import io.edap.microservice.annotation.Configuration;
import io.edap.microservice.annotation.MicroServiceBean;
import io.edap.microservice.annotation.Primary;
import io.edap.nio.codec.FastBufDataRange;
import io.edap.props.Props;
import io.edap.erpc.ErpcHandler;
import io.edap.erpc.ErpcMethodIds;
import io.edap.erpc.ErpcAuthenticator;
import io.edap.erpc.ErpcServerInterceptor;
import io.edap.erpc.server.ErpcServer;
import io.edap.grpc.GrpcHandler;
import io.edap.grpc.GrpcMethod;
import io.edap.grpc.GrpcServerInterceptor;
import io.edap.grpc.server.GrpcServer;
import io.edap.protobuf.annotation.ProtoErpc;
import io.edap.protobuf.annotation.ProtoGrpc;
import io.edap.protobuf.annotation.ProtoHttp;
import io.edap.protobuf.annotation.ProtoService;
import io.edap.protobuf.annotation.ProtoWebSocket;
import io.edap.protobuf.annotation.Sharded;
import io.edap.tx.EdapTransactionManager;
import io.edap.tx.jdbc.DataSourceTransactionManager;
import io.edap.util.CollectionUtils;
import io.edap.util.timing.TimerHandle;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;

import static io.edap.util.AsmUtil.saveClassFile;
import static io.edap.util.AsmUtil.toInternalName;

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
    //private final RouterHub          routers;       // 4 份协议 Handler List 的被动持有者
    private final BeanContainer      beans;         // Bean 装配核心
    private final ShardRegistry      shards;        // @Sharded 方法所属 bean 的分片实例
    private final AppResourceLoader  resourceLoader;// 通过 appCL 读 jar 内资源
    private final List<BeanPostProcessor> postProcessors = new ArrayList<>(); // Bean 初始化前后钩子

    /**
     * 本 ctx 在 {@link #configureTransactionalInfrastructure} 阶段向
     * {@link io.edap.tx.TransactionManagers} 静态表 register 的 TM 名称集合。
     * {@link #stop()} 末尾按此集合 unregister —— 释放 TM → DataSource 引用链,
     * 让旧 ctx 的 Hikari 等连接池能 GC,避免重新部署时新池报 "max pool size reached"
     * / "port in use"。
     */
    private final Set<String> registeredTxManagerNames = new HashSet<>();

    /**
     * 本 ctx 注册进<b>进程级共享</b>的 gRPC/eRPC Server 的拦截器。
     *
     * <p>★ <b>为什么要单独记一份</b>：gRPC/eRPC Server 在一个 Container 里只有
     * <b>一个</b>实例（所有 app 共用端口）⇒ 拦截器也是<b>进程级</b>的 ⇒
     * 本 ctx {@code stop()} 时必须把自己装上去的那些摘掉，否则会拿着已关闭的
     * appCL / bean 继续处理别人的请求。</p>
     */
    private final List<GrpcServerInterceptor> registeredGrpcInterceptors = new ArrayList<>();
    private final List<ErpcServerInterceptor> registeredErpcInterceptors = new ArrayList<>();

    /**
     * 本 ctx 装到进程级共享 eRPC Server 上的<b>连接级鉴权器</b>。
     *
     * <p>★ <b>只有一个槽位</b>（{@link ErpcServer#setAuthenticator}）—— 与拦截器
     * 「装一堆」的模型不同：多个 app 各带一个鉴权器时<b>先装的生效</b>，
     * 后装的记一条 warn（见 {@link #registerErpcAuthenticator()}）。</p>
     *
     * <p>本字段记的是「<b>我</b>装的那个」⇒ {@code stop()} 时只摘自己那个。</p>
     */
    private ErpcAuthenticator registeredErpcAuthenticator;

    // ─── per-app path → handler 索引 ───
    // Container.rebuildHttpMapping() 在 deploy / undeploy / switchVersion 末尾聚合各 app 的
    // httpHandlersByPath + wsHandlersByPath，整张替换 HttpServer 的 httpMapping（volatile 写，
    // dispatch 热路径无锁读）。同 app 不同 method 的 path 由 @ProtoHttp/@ProtoWebSocket 显式
    // 指定；未指定时默认派生 /<interfaceSimpleName>/<methodName> 全小写。
    private final Map<String, HttpHandler>            httpHandlersByPath = new HashMap<>();

    /**
     * WS method → 业务 {@link WSServiceMsgHandler}。
     * 仅用于 {@link #generateAndBindRoutes} 末尾一次性喂给 Container 持有的
     * {@link io.edap.container.ws.ServiceWSHandler} 单例的 msgHandlers（volatile 替换）。
     * dispatch 阶段不走此字段——只走 ServiceWSHandler.msgHandlers()。
     */
    private final Map<String, WSServiceMsgHandler<?>> wsMsgHandlers = new HashMap<>();

    /**
     * gRPC 的 {@code :path} → {@link GrpcMethod}（方法定义 + 字节码生成的 handler）。
     *
     * <p>与 {@link #httpHandlersByPath} 对称，但键不是 HTTP path 而是 gRPC 的
     * {@code /pkg.Svc/Method}（由 {@code @ProtoGrpc.path()} 或
     * {@code @ProtoService.serviceName()} + 方法名派生）。</p>
     *
     * <p>Container 在 deploy / undeploy / switchVersion / restoreToSlot 末尾聚合各 app 的
     * 这张表，整张替换 {@code GrpcServer} 的路由表（volatile 写，dispatch 热路径无锁读）。</p>
     */
    private final Map<String, GrpcMethod> grpcMethodsByPath = new HashMap<>();

    /**
     * eRPC 的方法键（{@code serviceName.methodName}）→ {@link ErpcMethodDef}。
     *
     * <p>与 {@link #grpcMethodsByPath} 对称，但键不是路径而是 <b>methodId 的计算输入</b>。
     * per-app 阶段<b>不定</b> methodId（{@code increment} 策略要全局排序才能分配），
     * 只记录「方法是什么」；{@link Container#rebuildErpcMapping()} 在合并所有 CURRENT 槽时
     * 统一按策略分配号、检测冲突，整张发布到 {@code ErpcServer}。</p>
     */
    private final Map<String, ErpcMethodDef> erpcMethodsByKey = new HashMap<>();

    /**
     * WS 固定路径。第一期约定：所有 {@code @ProtoWebSocket} 标注的方法共用同一 path（与 HTTP per-method
     * 不同——WS 用单一长连接 + 业务 method 二次路由）；{@code /ws} 写死在 PathInfo 里，避免每 method 自定
     * path 引入的多 ServiceWSHandler / 多 PathInfo.wsHandler 复杂度。
     *
     * <p>对应的 {@link io.edap.container.ws.ServiceWSHandler} 实例由 {@link Container}
     *     按 path 单例持有（{@code Container.wsHandlers}），AppContext 不再自持 —— 跨版本共享
     *     同一 wsHandler 实例，老 WS 连接（{@code HttpServerNioSession.wsHandler} 握手时钉住）
     *     靠 msgHandlers 被 rebind 切版本。</p>
     */
    public static final String WS_PATH = "/ws";

    // ─── ASM 生成 Handler impl class 的缓存 ───
    // 挂在 AppContext 上（不是 Container 单例），原因：Method → Class → genCL →(parent)→ appCL
    // 整条引用链必须与 ctx 同生死；放 Container 上会永久持有 appCL 引用 → appCL 永远 GC 不掉
    // （违反 §3.8 防内存泄漏不变量）。详见 §3.5.7 缓存归属说明。
    private final Map<HandlerKey, Class<?>>           generatedHandlers = new ConcurrentHashMap<>();
    private final Map<ClassLoader, GeneratedClassLoader> generatedCLs   = new ConcurrentHashMap<>();

    private volatile AppState        state = AppState.NEW;

    /**
     * 被 demote 到 PREVIOUS 槽的时间戳（毫秒）。0 = 未 demote；&gt;0 = demote 时间。
     *
     * <p>PREVIOUS 槽的语义：
     * <ol>
     *   <li>记录上次的版本（供 listSlots 查询 / 审计）</li>
     *   <li>承接 switchVersion 时还没跑完的 in-flight 请求 —— 路由已不可达（rebuildHttpMapping
     *       只看 current 槽），但 ctx 还 alive 一段时间让老请求 graceful drain</li>
     * </ol>
     * PREVIOUS <b>不接 rollback</b>：回滚路径统一走"deploy 到 staging → switchVersion(staging → current)"二段式。</p>
     *
     * <p>demote 时由 Container 设值，并排一个延迟 stop 任务（默认 60s，可配置）；
     *     到点由平台时间轮（Container 排的延迟任务）调 {@link #stop()} 释放连接池。
     *     若 60s 内又发生 switchVersion 新的 demote，Container 会先 cancel 旧任务再立即 stop 这个 ctx。</p>
     */
    private volatile long demotedAt;

    /**
     * demote 排的延迟 stop 任务的句柄，cancel 用于"下一次 switchVersion 提前释放"。
     * 由 Container 设值 / cancel；AppContext 自己不持有调度器。
     */
    private volatile TimerHandle releaseFuture;

    private Map<FastBufDataRange, PathInfo> pathInfoMap;

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
        //this.routers        = new RouterHub();
        this.resourceLoader = new AppResourceLoader(appCL);
        // SPI:从 appCL 加载 BeanPostProcessor 实现(per-app 隔离,避免跨 app 的 BPP 状态污染)。
        // TransactionalBeanPostProcessor 通过此机制零配置接入,业务方无需在 deploy 阶段手动 register。
        loadBeanPostProcessorsFromSPI();
        // 构造函数到此为止——不做扫描、不做实例化、不调 Lifecycle.start
    }

    /**
     * SPI 加载 BeanPostProcessor —— 读 {@code META-INF/services/io.edap.container.BeanPostProcessor},
     * 每个实现 addBeanPostProcessor 一次。
     *
     * <p><b>顺序</b>:用 {@link ClassLoader#getResources} 而不是 {@link ServiceLoader#load(Class)} 的
     * 默认 ClassLoader —— 因为 BPP 实现可能在 edap-container-boot 之外的 nested jar 里,
     * 必须走 appCL 才能看见。</p>
     */
    private void loadBeanPostProcessorsFromSPI() {
        try {
            java.util.ServiceLoader<BeanPostProcessor> loader =
                    java.util.ServiceLoader.load(BeanPostProcessor.class, appCL);
            for (BeanPostProcessor bpp : loader) {
                postProcessors.add(bpp);
                log.info("[" + appId + "] BeanPostProcessor registered via SPI: "
                        + bpp.getClass().getName());
            }
        } catch (Throwable t) {
            // SPI 加载失败不能让整个 AppContext 启不来 —— 业务方可能没装带 BPP 的 jar
            log.warn("[" + appId + "] BeanPostProcessor SPI load failed (continuing without BPP)", t);
        }
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
            // Phase 1 GATHERING：扫 EAR → BeanDef 列表
            // （路由信息直接来自 dmd.protoServiceInfos，Phase 3 末尾再消费——见 generateAndBindRoutes）
            List<BeanDef> defs = scanBeanDefs();
            for (BeanDef def : defs) {
                beans.register(def);
            }
            beans.topologicalSort();                            // 循环依赖此处抛

            // Phase 2 COMMITTING：实例化 → 注入 → init
            beans.transitionToCommitting();                     // COLLECTING -> INSTANTIATING
            for (BeanDef def : beans.sorted()) {
                Object instance = beans.instantiate(def);
                beans.injectDependencies(def, instance);
                beans.invokeInit(def, instance);
                beans.registerInstance(def, instance);
            }
            beans.transitionToReady();                          // INSTANTIATING -> READY

            // Phase 2.5:自动配置事务基础设施 —— 扫已 register 的 DataSource bean,
            // 为每个创建一个 DataSourceTransactionManager,register 到 TransactionManagers 静态表。
            // 必须在 Phase 2 之后(wrapper <clinit> 要能在 BPP 阶段命中已注册的 TM),
            // 也必须在 Phase 2.6 之前(BPP 生成的 wrapper 在 BPP.afterInit 时访问静态表)。
            configureTransactionalInfrastructure();

            // Phase 2.6:接通 BeanPostProcessor 调用链 —— 对每个已 register 的 bean 跑
            // postProcessAfterInit。BPP 可返回新实例替换原实例(典型:@Transactional
            // bean 被替换为 ASM 生成的 wrapper)。
            // 之所以放在 Phase 2 之后而非之中:TransactionalBeanPostProcessor 生成的 wrapper
            // 需要 TransactionManagers 静态表里有对应 TM,见上 Phase 2.5。
            applyBeanPostProcessors();

            transitionTo(AppState.COMMITTING);             // GATHERING -> COMMITTING

            // Phase 3 READY：Lifecycle.start() + 路由 bind
            beans.startLifecycles();
            registerServerInterceptors();                        // 应用 bean 里的 gRPC/eRPC 服务端拦截器 → 进程级共享的 Server
            generateAndBindRoutes();                             // ASM 生成 4 份 Handler + 一次性写入 RouterHub + container.deployAppRoutes
            // generateAndBindRoutes 末尾把全量 pathTable（HTTP + WS）推给 Container.deployAppRoutes；
            // Container 负责 WS path 冲突检测 + 合并 + 整张替换 HttpServer.httpMapping。
            // Container 还在 deploy() / switchVersion() / 启动恢复 时统一切 currentRouters 指针。

            transitionTo(AppState.READY);                  // COMMITTING -> READY
            transitionTo(AppState.RUNNING);                // READY -> RUNNING

            events.publish(new ContextRefreshedEvent(this));
        } catch (Throwable t) {
            transitionTo(AppState.FAILED);
            throw t;
        }
    }

    /**
     * 自动配置事务基础设施(Phase 4 钩子 —— pom.xml 注释里已声明此能力)。
     *
     * <p>扫描所有已 register 的 SINGLETON bean,挑出 {@link DataSource} 实例,
     * 为每个创建一个 {@link DataSourceTransactionManager},register 到全局静态表
     * {@link io.edap.tx.TransactionManagers},key 为 bean name。wrapper 字节码
     * <clinit> 期通过 {@code TransactionManagers.get(name)} 取 TM,不依赖任何注入路径。</p>
     *
     * <p>无 DataSource 时 no-op(纯无事务应用不需要 tx 基础设施)。</p>
     *
     * <p><b>默认 tm 解析规则</b>(唯一满足即用):</p>
     * <ol>
     *   <li>恰好一个 DataSource bean 标了 {@link Primary} → 用它,同时 register 到
     *       空串 key 和它自己的 bean name(write {@code @Transactional(transactionManager="")}
     *       和 {@code transactionManager="<beanName>"} 都能命中)</li>
     *   <li>只有一个 DataSource(无论是否 {@code @Primary})→ 用它,同上 register 到
     *       空串 key + 自己的 bean name</li>
     *   <li>多个 DataSource 且无 {@code @Primary},或多个 {@code @Primary} → 不
     *       register 空串 key,wrapper 调 {@code TransactionManagers.get("")} 时抛
     *       {@link IllegalStateException}(启动期 fail-fast,避免运行时 silent fallback
     *       到错误 ds —— 那种 bug 极难定位)</li>
     * </ol>
     */
    private void configureTransactionalInfrastructure() {
        Map<String, EdapTransactionManager> tmByName = new LinkedHashMap<>();
        Map<String, Class<?>> dsBeanClassByName = new LinkedHashMap<>();
        for (BeanDef def : beans.sorted()) {
            if (!DataSource.class.isAssignableFrom(def.beanClass())) continue;
            BeanWrap bw = beans.beanWrapByName(def.name());
            if (bw == null || !(bw.instance() instanceof DataSource)) continue;
            DataSource ds = (DataSource) bw.instance();
            tmByName.put(def.name(), new DataSourceTransactionManager(ds));
            dsBeanClassByName.put(def.name(), def.beanClass());
        }
        if (tmByName.isEmpty()) return;

        // 把 TM 注册到全局静态注册表,wrapper 类 <clinit> 期直接 TransactionManagers.get(...) 取。
        // 默认 tm 语义由 TransactionManagers.get("") 内部统一处理。
        // 解析"哪个 DataSource 作默认":1) 标了 @Primary 的(且只有一个);2) 单 DataSource 直接用;
        // 3) 多个且无 @Primary → 启动期 fail-fast。
        String defaultBeanName = resolveDefaultDataSourceBeanName(tmByName, dsBeanClassByName);

        for (Map.Entry<String, EdapTransactionManager> e : tmByName.entrySet()) {
            io.edap.tx.TransactionManagers.register(e.getKey(), e.getValue());
            registeredTxManagerNames.add(e.getKey());
        }
        if (defaultBeanName != null) {
            // 默认 ds 同时绑到空串 key 和它本身的 bean name —— 无论 @Transactional 写
            // transactionManager="" 还是 transactionManager="<beanName>",都能拿到同一个 TM。
            EdapTransactionManager defaultTm = tmByName.get(defaultBeanName);
            io.edap.tx.TransactionManagers.register("", defaultTm);
            registeredTxManagerNames.add("");
        } else {
            // 多 DataSource + 无唯一 @Primary —— fail-fast,避免运行时 silent fallback
            // 到错误 ds(那种 bug 极难定位)。先把"空串 → 默认"故意不注册,
            // TransactionManagers.get("") 在 wrapper 里被调时会抛 IllegalStateException。
            log.warn("[" + appId + "] Multiple DataSources " + tmByName.keySet()
                    + " with no unique @Primary — @Transactional methods must specify transactionManager=\"...\"");
        }

        log.info("[" + appId + "] Registered " + tmByName.size() + " EdapTransactionManager(s) with TransactionManagers: "
                + tmByName.keySet()
                + (defaultBeanName != null ? " (default='" + defaultBeanName + "')" : ""));
    }

    /**
     * 在已收集的 DataSource 集合里挑一个作默认 tm:
     * <ul>
     *   <li>恰好一个标了 {@link Primary} → 用它</li>
     *   <li>只有一个 DataSource(无论是否 @Primary)→ 用它</li>
     *   <li>多个且无 @Primary → 返回 null(调用方走 fail-fast 路径)</li>
     *   <li>多个 @Primary → 返回 null(无唯一默认,业务方必须显式 transactionManager="...")</li>
     * </ul>
     */
    private String resolveDefaultDataSourceBeanName(Map<String, EdapTransactionManager> tmByName,
                                                     Map<String, Class<?>> dsBeanClassByName) {
        if (tmByName.isEmpty()) return null;
        String primary = null;
        for (Map.Entry<String, Class<?>> e : dsBeanClassByName.entrySet()) {
            if (e.getValue().getAnnotation(Primary.class) != null) {
                if (primary != null) {
                    // 多个 @Primary → 无唯一默认
                    return null;
                }
                primary = e.getKey();
            }
        }
        if (primary != null) return primary;
        if (tmByName.size() == 1) return tmByName.keySet().iterator().next();
        return null;
    }

    /**
     * 对所有已 register 的 SINGLETON bean 跑 {@link BeanPostProcessor#postProcessAfterInit}。
     * BPP 可返回新实例替换原实例(典型 AOP wrapper 织入)—— 此时调
     * {@link BeanContainer#replaceInstance} 保证 byType 索引同步更新,
     * 避免运行时 {@code getBean(Interface.class)} 抛 NoUniqueBeanException。
     */
    private void applyBeanPostProcessors() {
        if (postProcessors.isEmpty()) return;
        for (BeanDef def : beans.sorted()) {
            BeanWrap bw = beans.beanWrapByName(def.name());
            if (bw == null) continue;
            Object current = bw.instance();
            Object replaced = current;
            for (BeanPostProcessor bpp : postProcessors) {
                Object after = bpp.postProcessAfterInit(replaced, def.name());
                if (after != replaced) {
                    replaced = after;
                }
            }
            if (replaced != current) {
                beans.replaceInstance(def, current, replaced);
            }
        }
    }



    /** SIGTERM / undeploy / switchVersion 时的标准停止路径。幂等。 */
    @Override
    public void stop() throws Throwable {
        stop(true);
    }

    /**
     * 内部 stop 入口。
     *
     * @param unbindFromShared true = 从 {@link ServiceWSHandler#appMsgHandlers} 摘除本 ctx appId
     *     的 method 表（undeploy / deploy 替换旧 STAGING 路径用 true）
     *     false = 保留 shared method 表（PREVIOUS 槽 demote 释放用 false —— 同 appId 的 current
     *     槽还有新版本 ctx 持有 method 表，按 appId 摘会误伤 current 槽的 method 表）
     */
    public void stop(boolean unbindFromShared) throws Throwable {
        AppState cur = state;
        if (cur == AppState.NEW || cur == AppState.STOPPED) return;
        if (cur == AppState.STOPPING) return;
        transitionTo(AppState.STOPPING);

        Throwable firstErr = null;
        // 1. 路由摘除（让 in-flight 之外不再有请求到达本 AppContext）
        try {
            //routers.unbindAll();
            httpHandlersByPath.clear();
            grpcMethodsByPath.clear();
            erpcMethodsByKey.clear();
            // 共享 ServiceWSHandler 的 msgHandlers 按 appId 二级分片 —— 本 ctx stop 时按需摘
            // 自己 appId 的 method 表（避免 method 残留或跨 app 误伤）。
            // unbindFromShared=false 时：保留 shared method 表（PREVIOUS 槽 demote 释放场景，
            //   同 appId 的 current 槽 ctx 仍持有 method 表，按 appId 摘会清掉 current 槽的 method 表）。
            wsMsgHandlers.clear();
            if (unbindFromShared) {
                ServiceWSHandler sharedWsHandler = container.getOrCreateWsHandler(WS_PATH);
                if (sharedWsHandler != null) {
                    sharedWsHandler.unbindAppMsgHandlers(this.appId);
                }
            }
        }
        catch (Throwable t) { firstErr = t; }

        // 1.5 摘掉本 ctx 装上去的服务端拦截器 —— gRPC/eRPC Server 是进程级共享的，
        // 不摘的话本 ctx 的 bean（下面就要随 appCL 关闭）会继续挂在 Server 上处理别人的请求。
        try { unregisterServerInterceptors(); }
        catch (Throwable t) { if (firstErr == null) firstErr = t; }

        // 2. 逆序：Lifecycle.stop / @PreDestroy / AutoCloseable.close / 清空 singletons（含 ShardRegistry 分片）
        try { beans.destroyAllSingletons(); }
        catch (Throwable t) { if (firstErr == null) firstErr = t; }

        // 2.5 释放本 ctx 在 TransactionManagers 静态表里 register 的 TM 引用 —— 必须放在
        // destroyAllSingletons 之后(此时 DataSource bean 已被 close),TM → DataSource 引用链
        // 完整断掉,旧 ctx 的 Hikari 等连接池才能 GC,重新部署新 ctx 时池才能正常起来
        try {
            for (String name : registeredTxManagerNames) {
                io.edap.tx.TransactionManagers.unregister(name);
            }
            registeredTxManagerNames.clear();
        } catch (Throwable t) { if (firstErr == null) firstErr = t; }

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
            DeployComponent comp = entry.getValue();
            buildBeanDef(comp.getServiceMetaMap(), beanDefs);
            buildConfigurationBeanDefs(comp.getConfigurationMetaMap(), beanDefs);
        }
        return beanDefs;
    }

    /**
     * 把 {@code ConfigurationMetaMap} 转成 BeanDef 列表：
     * <ol>
     *   <li>每个 {@code @Configuration} 类自身 → 一个普通 BeanDef（构造器注入）</li>
     *   <li>每个 {@code @Configuration} 类内 {@code @Bean} 方法 → 一个 factoryMethod BeanDef，
     *       拓扑依赖该 {@code @Configuration} bean（保证 config 先 instantiate）</li>
     * </ol>
     *
     * <p>方法枚举走反射（{@code configCls.getDeclaredMethods()}），不复用
     * {@code ConfigurationMetaData.methodInfos}——后者由 ASM 在 {@code parseWithMethods} 里
     * 把多参方法的描述符裁成了单类型字符串，反向定位 Method 不可靠。</p>
     */
    private void buildConfigurationBeanDefs(Map<String, ConfigurationMetaData> confMetaMap,
                                            List<BeanDef> beanDefs) {
        if (CollectionUtils.isEmpty(confMetaMap)) {
            return;
        }
        String confAnnName = Configuration.class.getName();

        for (Map.Entry<String, ConfigurationMetaData> e : confMetaMap.entrySet()) {
            ConfigurationMetaData cmd = e.getValue();
            String configName = deriveConfigBeanName(cmd, confAnnName);

            Class<?> configCls;
            try {
                configCls = Class.forName(cmd.getTypeName(), false, appCL);
            } catch (ClassNotFoundException ex) {
                throw new RuntimeException(ex);
            }

            // (1) @Configuration 类自身：普通 BeanDef，走构造器注入路径
            beanDefs.add(new BeanDef(configName, configCls, Scope.SINGLETON,
                    null, null, null, null, 0));

            // (2) @Bean 工厂方法：每个方法 → factoryMethod BeanDef，依赖 configName
            for (Method m : configCls.getDeclaredMethods()) {
                Bean beanAnn = m.getAnnotation(Bean.class);
                if (beanAnn == null) {
                    continue;
                }
                String beanName = beanAnn.name().trim().length() > 0 ? beanAnn.name() : m.getName();
                Scope beanScope = beanAnn.scope();
                List<String> deps = List.of(configName);
                beanDefs.add(new BeanDef(beanName, m.getReturnType(), beanScope,
                        deps, null, null, null, 0, m, configName));
            }
        }
    }

    /**
     * 解析 {@code @Configuration(name="...")} 的 {@code name} 属性；缺省或为空时按类名首字母小写
     * （与 {@link #beanSimpleName} 规则对齐）。
     */
    private String deriveConfigBeanName(ConfigurationMetaData cmd, String confAnnName) {
        for (AnnoData ad : cmd.getAnnoDatas()) {
            if (confAnnName.equals(ad.getType())) {
                Object n = ad.getValues().get("name");
                if (n instanceof String && ((String) n).trim().length() > 0) {
                    return (String) n;
                }
            }
        }
        return beanSimpleName(cmd.getTypeName());
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
                List<InjectionPoint> injections = scanInjectionPoints(beanCls);
                // 把字段级 @Named 名直接写进 injectionNames，拓扑排序优先 initialize 被按名依赖的 bean；
                // 没有 @Named 的字段依赖由 BeanContainer.enrichInjectionNames 反射构造器参数时补齐。
                List<String> injectionNames = injections.stream()
                        .map(InjectionPoint::beanName)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
                beanDefs.add(new BeanDef(name, beanCls, scope,
                        injectionNames.isEmpty() ? null : injectionNames,
                        injections.isEmpty() ? null : injections,
                        null, null, 0));
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * 扫描 bean 类的字段/方法注入点,产出 {@link InjectionPoint} 列表。
     *
     * <ul>
     *   <li>{@code @Inject} 字段 → {@link InjectionPoint#field},beanName 取字段上的
     *       {@code @Named("xxx")}(无则 null,运行时由 BeanContainer 按类型兜底)</li>
     *   <li>{@code @Inject} 方法 → {@link InjectionPoint#method},beanName 永远 null
     *       —— {@code @Named} 是参数级别而非方法级别,具体名查在
     *       {@link BeanContainer#resolveMethodArgs} 的参数循环里</li>
     * </ul>
     *
     * <p>构造器注入 <b>不走这里</b>,仍走 {@link BeanContainer#ctorArgs}——保持
     * topology/injectionNames 的现有分工。
     */
    private List<InjectionPoint> scanInjectionPoints(Class<?> beanCls) {
        List<InjectionPoint> out = new ArrayList<>();

        for (Field f : beanCls.getDeclaredFields()) {
            if (f.getAnnotation(Inject.class) == null) continue;
            String n = extractBeanName(f);
            out.add(InjectionPoint.field(f, n, f.getType()));
        }

        for (Method m : beanCls.getDeclaredMethods()) {
            if (m.getAnnotation(Inject.class) == null) continue;
            // 方法级 beanName 不通用,留给 BeanContainer.resolveMethodArgs 按参数解析
            out.add(InjectionPoint.method(m, null, null));
        }

        return out;
    }

    /**
     * 从 {@link AnnotatedElement}(Field / Method / Parameter)抽 {@code @Named("xxx")}。
     * 空值/无注解都返回 null,由调用方走类型兜底。
     */
    private static String extractBeanName(AnnotatedElement e) {
        Named n = e.getAnnotation(Named.class);
        if (n == null || n.value().isEmpty()) return null;
        return n.value();
    }

    private String beanSimpleName(String name) {
        int index = name.lastIndexOf(".");
        if (index != -1) {
            name = name.substring(index + 1);
        }
        return name.substring(0, 1).toLowerCase(Locale.ENGLISH) + name.substring(1);
    }

    /**
     * 把 (protoIf, targetIf, annoData, shard, method) 桥接为可立即 dispatch 的协议 typed Handler 实例。
     *
     * <p>同一 (targetIf, protoIf, methodName, annoType) 四元组只生成一次 Handler impl class
     * （{@code generatedHandlers} 缓存）。生成类实现 {@code targetIf} 接口（HttpHandler /
     * WSServiceMsgHandler / ErpcHandler / GrpcHandler），构造器签名 {@code (BeanContainer, ShardRegistry)}。
     * 生成类的 ClassLoader parent = appCL（能引用 appCL 加载的 bean / proto 接口类）。
     * 缓存挂在 ctx 上：AppContext.stop() 后整条引用链断开，appCL 可 GC。</p>
     *
     * <p><b>bean 由 Handler 自己查，AppContext 不参与</b>：Handler 构造时调
     * {@code this.beans.findBeanWrapByType(protoIf)}，找到则缓存到字段、缺失则抛
     * {@link IllegalStateException}（"No bean for <protoIf>"），本方法把异常包成
     * {@link RouteBindException} 冒泡 → deploy 失败（fail-fast）。是否使用 bean / 走本地
     * invokevirtual 还是 ShardRegistry / ClusterShardRouter 远端 RPC，<b>全由 Handler 字节码
     * 自己决定</b>，AppContext 不感知。</p>
     *
     * <p>当 {@code shard == true} 时，生成 Handler 持有 ShardRegistry 引用，
     * handle 内部按 shardKey 选实例。</p>
     *
     * @param targetIf 协议 typed Handler 接口的 Class 对象（HttpHandler.class / WSServiceMsgHandler.class / ...）
     * @param protoIf  proto 服务接口的 Class 对象（{@code GreeterService.class}）—— 用于 className、
     *                  cache key 与 Handler 构造时查 bean；<b>注意</b>是接口本身（FQCN），不是实现类
     * @param annoDatas 协议注解（{@code @ProtoHttp} / {@code @ProtoWebSocket} / ...）—— asm 模板从中读 path/method 等
     * @param annoData    true = 此路由 shard 亲和（{@code @Sharded} 标注）
     * @param method   proto 接口上的目标 Method（{@code protoIf.getMethod(...)} 取得）——
     *                  仅取方法名 + 参数类型用于 cache key 与 className
     * @param shards   ShardRegistry 引用（shard == true 时生成 Handler 内部使用；否则可传 null）
     * @return 新实例化的 targetIf 实例
     * @throws RouteBindException ASM 生成 / 类加载 / 实例化失败 / Handler 查不到 bean 时抛出
     */
    @SuppressWarnings("unchecked")
    public <T> T generateHandler(Class<T> targetIf, Class<?> protoIf, List<AnnoData> annoDatas, AnnoData annoData,
                                 Method method, ShardRegistry shards) {
        // cache key 用 (targetIf, protoIf, methodName, annoType) —— 不用 Method，
        // 避免 declaringClass（实现类）污染 cache，确保同 proto 接口的多种实现共享同一 Handler class
        HandlerKey cacheKey = new HandlerKey(targetIf, protoIf, method.getName(), annoData.getType());

        // 1. 缓存查找：同一四元组 → 复用之前生成的 Handler impl class
        Class<?> handlerClass = generatedHandlers.computeIfAbsent(cacheKey, k -> {
            // 2. 拿生成类专用 ClassLoader（parent = appCL）—— protoIf 必被 appCL 加载
            ClassLoader appCL = protoIf.getClassLoader();
            String handlerName = HandlerAsmGenerator.INSTANCE.handlerName(targetIf, protoIf, method);
            GeneratedClassLoader genCL = generatedCLs.computeIfAbsent(appCL, GeneratedClassLoader::new);
            try {
                Class handlerCls = Class.forName(handlerName, false, genCL);
                log.info("handlerCls {} exists", l -> l.arg(handlerName));
                return handlerCls;
            } catch (ClassNotFoundException e) {
                //log.warn("Class.forName {}", l -> l.arg(handlerName).threw(e));
            }
            // 3. ASM 字节码生成（无状态工具 HandlerAsmGenerator.INSTANCE，不持有 app 状态）
            byte[] bytes = HandlerAsmGenerator.INSTANCE.generateHandlerClass(
                    targetIf, protoIf, method, annoDatas, appCL);

            // 4. defineClass 把字节码实际注册到 genCL（不走双亲委派；不向上找 parent (appCL)）——
            //    用 Class.forName(name, true, genCL) 会先走 genCL → appCL 双亲委派，
            //    appCL 没有这个类就 CNFE，Handler impl 永远不会被实际注册。
            try {
                saveClassFile("./" + toInternalName(handlerName) + ".class", bytes);
                return genCL.define(handlerName, bytes);
            } catch (LinkageError | IllegalArgumentException | IOException e) {
                // LinkageError：重复 define 同一 name；IllegalArgumentException：name 不合法 / bytes 越界
                throw new RouteBindException(null, method.getName(), method.getParameterTypes(), e);
            }
        });

        // 5. 反射实例化：(beans, shards) → 构造器签名 (BeanContainer, ShardRegistry)
        // Handler 构造时自己按 protoIf 查 bean；缺失 → IllegalStateException → 包成 RouteBindException
        try {
            return (T) handlerClass
                .getConstructor(AppContext.class)
                .newInstance(this);
        } catch (ReflectiveOperationException ex) {
            throw new RouteBindException(null, method.getName(), method.getParameterTypes(), ex);
        }
    }

    /**
     * 把应用 bean 里的<b>服务端拦截器</b>装进进程级共享的 gRPC / eRPC Server。
     *
     * <p>调用时机：Phase 3（bean 已 instantiate + inject + {@code Lifecycle.start} 完成），
     * 路由 bind 之前。</p>
     *
     * <p>★ <b>语义提醒：拦截器是进程级的</b> —— gRPC/eRPC Server 在一个 Container 里
     * 只有<b>一个</b>实例（所有 app 共用端口）⇒ 本 app 注册的拦截器对<b>同进程内所有</b>
     * gRPC/eRPC 方法生效，不只是本 app 的方法。这是「共享 Server」这个部署模型的
     * 直接后果，不是 bug。</p>
     *
     * <p>节点未启用对应能力（{@code container.grpcServer()} / {@code erpcServer()} 为 null）
     * ⇒ 跳过，不报错 —— 应用可以同时部署到只开 HTTP 的节点上。</p>
     */
    private void registerServerInterceptors() {
        GrpcServer grpc = container.grpcServer();
        if (grpc != null) {
            for (GrpcServerInterceptor interceptor : getBeansOfType(GrpcServerInterceptor.class)) {
                grpc.addInterceptor(interceptor);
                registeredGrpcInterceptors.add(interceptor);
            }
        }
        ErpcServer erpc = container.erpcServer();
        if (erpc != null) {
            for (ErpcServerInterceptor interceptor : getBeansOfType(ErpcServerInterceptor.class)) {
                erpc.addInterceptor(interceptor);
                registeredErpcInterceptors.add(interceptor);
            }
        }
        registerErpcAuthenticator();
    }

    /**
     * 把应用 bean 里的<b>连接级鉴权器</b>装进进程级共享的 eRPC Server。
     *
     * <p>★ <b>鉴权器只有一个槽位</b>（{@link ErpcServer#setAuthenticator}）——
     * 与拦截器「装一堆」的模型不同：多个 app 各带一个鉴权器时<b>先装的生效</b>，
     * 后装的<b>不覆盖</b>（覆盖会让先启动的 app 突然失去鉴权策略），
     * 只记一条 warn 说明「多 app 抢同一个槽位」。</p>
     *
     * <p>★ <b>不改变「是否必须握手」</b>：装了鉴权器只是「客户端来握手时我会校验」，
     * 要不要强制握手是 {@link ErpcServer#setRequireAuth(boolean)} 的事。</p>
     */
    private void registerErpcAuthenticator() {
        ErpcServer erpc = container.erpcServer();
        if (erpc == null) {
            return;
        }
        List<ErpcAuthenticator> list = getBeansOfType(ErpcAuthenticator.class);
        if (list.isEmpty()) {
            return;
        }
        if (erpc.getAuthenticator() != null) {
            log.warn("erpc authenticator slot is already taken by another app —— "
                + "本次部署的 ErpcAuthenticator 不生效（鉴权器是进程级唯一的槽位）");
            return;
        }
        if (list.size() > 1) {
            log.warn("multiple ErpcAuthenticator beans found (" + list.size()
                + ") —— only the first one takes effect");
        }
        ErpcAuthenticator first = list.get(0);
        erpc.setAuthenticator(first);
        registeredErpcAuthenticator = first;
    }

    /**
     * 摘掉本 ctx 装上去的服务端拦截器（{@code stop()} 阶段调用）。
     *
     * <p>★ <b>必须摘</b>：Server 是进程级共享的 ⇒ 不摘的话，本 ctx 的 bean（已随 appCL
     * 关闭）会继续挂在 Server 上处理<b>别的</b> app 的请求。</p>
     */
    private void unregisterServerInterceptors() {
        GrpcServer grpc = container.grpcServer();
        if (grpc != null) {
            for (GrpcServerInterceptor interceptor : registeredGrpcInterceptors) {
                grpc.removeInterceptor(interceptor);
            }
        }
        registeredGrpcInterceptors.clear();

        ErpcServer erpc = container.erpcServer();
        if (erpc != null) {
            for (ErpcServerInterceptor interceptor : registeredErpcInterceptors) {
                erpc.removeInterceptor(interceptor);
            }
            // ★ 只摘「本 ctx 装的那个」—— 槽位可能是别的 app 装的（见 registerErpcAuthenticator）
            if (registeredErpcAuthenticator != null
                && erpc.getAuthenticator() == registeredErpcAuthenticator) {
                erpc.setAuthenticator(null);
            }
        }
        registeredErpcInterceptors.clear();
        registeredErpcAuthenticator = null;
    }

    /**
     * Phase 3 末尾调用：遍历 ProtoServiceData → 按方法上的协议注解（{@code @ProtoHttp} /
     * {@code @ProtoWebSocket} / ...）生成对应协议 typed Handler → 一次性写入 RouterHub。
     *
     * <p><b>为什么不再用 RouteEntry 中间结构</b>：扫描期产出的 {@link ProtoServiceData} 已
     * 包含 {@link ProtoServiceData#getTypeName()}（接口 FQCN）+ {@link ProtoMethodData#getName()}
     * （方法名）+ {@link ProtoMethodData#getAnnoDatas()}（含 {@code @ProtoHttp}/{@code @ProtoWebSocket}/
     * {@code @Sharded} 等所有方法级注解的值）；直接消费这两层数据 + {@link Capability} 过滤即可生成
     * 4 份协议 typed Handler，<b>不再需要中间的 RouteEntry POJO 二次封装</b>。
     * 好处：① 减少约 4 个 POJO + 4 份 List 字段 + 4 个 accessor；② 扫描器不需要为每条路由额外组装
     * POJO；③ 协议注解里的 path/method/body 等直接被 asm emit 模板消费，少一次字符串→字段的拷贝。</p>
     *
     * <p><b>数据流</b>：
     * <pre>
     *   dmd.getComponentMap().values()
     *       .forEach comp -&gt; comp.protoServiceInfos
     *           .forEach psi -&gt; psi.methodInfos.forEach pmd -&gt;
     *               pmd.annoDatas.forEach anno -&gt;
     *                   按 anno.type 分派到 (HTTP/WS/eRPC/gRPC) Handler 生成
     * </pre>
     *
     * <p><b>按节点能力过滤</b>：协议注解按 {@link Container#capabilities()} 过滤——
     * 节点不具备的能力（如 eRPC 节点没有 {@link Capability#HTTP}），即使应用里写了
     * {@code @ProtoHttp}，也不会生成对应 Handler，避免白生成 + 永不 dispatch 的死代码。
     * 过滤点放在 Phase 3 而非 Phase 1：
     * <ul>
     *   <li>ProtoServiceData 本身不大，遍历成本远低于 RouteEntry POJO 的二次封装</li>
     *   <li>本方法不依赖 EAR 重扫——只走 dmd 内存结构</li>
     *   <li>未来节点能力热调整（hotswap capability）只需重跑本方法，不需要重扫 EAR</li>
     * </ul></p>
     *
     * <p><b>为什么 eRPC/gRPC 要切 TCCL</b>：{@code ErpcHandler.generateHandler} 内部要从
     * requestType 字符串解析 Class（appCL 加载），{@link java.lang.Class#forName(String)} 默认走
     * caller 的 ClassLoader——非 appCL。切到 TCCL 才能保证 appCL 找到类。</p>
     *
     * <p><b>失败处理</b>：任一 Handler 生成失败 → 抛 {@link RouteBindException} →
     * start() 捕到后状态转 FAILED，Container.deploy 失败回滚。</p>
     */
    private void generateAndBindRoutes() {
        // 切 TCCL：后续 Class.forName(pmd.paramType, false, appCL) 不需要再切，但保持习惯避免后续 emit 漏掉
        ClassLoader prevCL = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(appCL);
        try {
            for (DeployComponent comp : dmd.getComponentMap().values()) {
                List<ProtoServiceData> psiList = comp.getProtoServiceInfos();
                if (CollectionUtils.isEmpty(psiList)) {
                    continue;
                }

                for (ProtoServiceData psi : psiList) {
                    // AppContext 只负责按 proto 接口生成 Handler 类 + 实例化；
                    // bean 由 Handler 构造时自己从 BeanContainer 查（缺失 → 抛 → deploy 失败）。
                    Class<?> ifaceClass = Class.forName(psi.getTypeName(), false, appCL);
                    generateMethodsHandlers(ifaceClass, psi.getMethodInfos(), protoServiceName(psi));
                }
            }
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(prevCL);
        }

        // 所有 ProtoService 遍历完后，本 ctx 的 wsMsgHandlers 已填好。
        // 不再此处调 ServiceWSHandler.rebindMsgHandlers —— wsHandler 是 Container
        // 持有的 per-path 单例，跨 ctx 共享；rebind 改在 Container.deploy /
        // switchVersion / restoreToSlot 末尾由 rebindCurrentWsHandlers 统一调，
        // 避免 STAGING ctx start 期间就 rebind 到不接流量的版本。
        // buildPathTable() 紧跟其后调 container.deployAppRoutes() 把 pathTable 注册出去。

        // 全部 Handler 生成 + RouterHub 写完后：构建全量 pathTable（HTTP + WS），推给 Container。
        // 顺序：buildPathTable() 内部取 Container 持有的共享 ServiceWSHandler（跨 ctx 单例）写到 WS PathInfo，
        // 再 deployAppRoutes → Container 做 WS path 冲突检测 + 写 appPathTables。
        // msgHandlers 的 rebind 由 Container.deploy/switchVersion/restoreToSlot 末尾统一调
        // （当前 STAGING ctx start 不 rebind，避免 STAGING 把自己的 method 表塞到 dispatch 路径）；
        // HttpServer.mapping 的发布由 Container.deploy 末位的 rebuildHttpMapping() 一次性完成
        // （dispatch 热路径无锁读）；本 AppContext 不直接触发 setHttpMapping。
        // 失败抛 RouteBindException → start() 转 FAILED → deploy 回滚。
        this.pathInfoMap = buildPathTable();

        // 锚点 2:接口回调(fail-fast) + 事件发布(沿用 EventPublisher log+continue 语义)
        notifyContextStarted();

        container.deployAppRoutes(appId, pathInfoMap);
    }

    /**
     * 构建本 AppContext 的全量 pathTable（HTTP + WS），供 {@link #generateAndBindRoutes} 末尾
     * 一次性推给 {@link Container#deployAppRoutes}。
     *
     * <p><b>HTTP 段</b>：每个 {@code @ProtoHttp} 方法 → 一个 PathInfo entry（含 httpHandlers[]）。
     * path 来自 {@link #deriveHttpPath}。</p>
     *
     * <p><b>WS 段</b>：所有 {@code @ProtoWebSocket} 方法共用单一 path {@link #WS_PATH} →
     * 一个 PathInfo entry（wsHandler = Container 持有的 per-path 单例 ServiceWSHandler +
     * wsAuthenticator）。多个 {@code @ProtoWebSocket} 方法不产生多个 PathInfo entry——WS 走
     * 长连接 + 业务 method 二次路由，path 仅作连接入口。</p>
     *
     * <p><b>WSAuthenticator 取值</b>：{@code beans.beanWrapByType(WSAuthenticator.class)} miss
     *     → 由 BeanContainer fallback 到 {@code container.containerBeans()} 的
     *     {@link HeaderTokenAuthenticator} 默认实现（开箱即用）；app 提供自己的 WSAuthenticator
     *     bean 时自动覆盖。应用 bean miss 且 Container.beans 也没注册（理论上不应发生）→
     *     该 PathInfo 不设 wsAuthenticator → 握手阶段返回 401。</p>
     */
    private Map<FastBufDataRange, PathInfo> buildPathTable() {
        Map<FastBufDataRange, PathInfo> table = new HashMap<>();
        List<UrlMapping> urlMappings = getBeansOfType(UrlMapping.class);
        MethodCache methodCache = MethodCache.instance();
        if (!CollectionUtils.isEmpty(urlMappings)) {
            if (container.hasCapability(Capability.HTTP)) {
                for (UrlMapping um : urlMappings) {
                    Map<String, UrlMappingItem> umis = um.urlMappings();
                    if (CollectionUtils.isEmpty(umis)) {
                        continue;
                    }
                    for (Map.Entry<String, UrlMappingItem> e : umis.entrySet()) {
                        UrlMappingItem umi = e.getValue();
                        FastBufDataRange range = FastBufDataRange.from(umi.getPath());
                        PathInfo pi = table.get(range);
                        String method = umi.getMethod();
                        if (method != null) {
                            method = method.toUpperCase(Locale.ENGLISH);
                        } else {
                            method = "GET";
                        }
                        int methodIndex = methodCache.getMethodIndex(method);
                        if (pi == null) {
                            String path = umi.getPath();
                            pi = new PathInfo();
                            pi.setPath(path);
                            pi.setFound(true);
                            HttpHandler[] handlers = new HttpHandler[methodIndex + 1];
                            handlers[methodIndex] = umi.getHandler();
                            pi.setHttpHandlers(handlers);
                            table.put(range, pi);
                        } else {
                            HttpHandler[] handlers = pi.getHttpHandlers();
                            if (handlers.length > methodIndex) {
                                handlers[methodIndex] = umi.getHandler();
                            } else {
                                HttpHandler[] tmp = new HttpHandler[methodIndex+1];
                                System.arraycopy(handlers, 0, tmp, 0, handlers.length);
                                tmp[methodIndex] = umi.getHandler();
                                pi.setHttpHandlers(tmp);
                            }
                        }
                    }
                }
            }
        }
        // 1. HTTP entries
        for (Map.Entry<String, HttpHandler> e : httpHandlersByPath.entrySet()) {
            String path = e.getKey();
            int index = path.indexOf(':');
            String method;
            if (index == -1) {
                method = "GET";
            } else {
                method = path.substring(0, index).toUpperCase(Locale.ENGLISH);
                path = path.substring(index+1);
            }
            FastBufDataRange range = FastBufDataRange.from(path);
            PathInfo pi = table.get(range);
            int methodIndex = methodCache.getMethodIndex(method);
            if (pi == null) {
                pi = new PathInfo();
                pi.setPath(path);
                pi.setFound(true);
                HttpHandler[] handlers = new HttpHandler[methodIndex+1];
                handlers[methodIndex] = e.getValue();
                pi.setHttpHandlers(handlers);
                table.put(range, pi);
            } else {
                HttpHandler[] handlers = pi.getHttpHandlers();
                if (handlers.length > methodIndex) {
                    handlers[methodIndex] = e.getValue();
                } else {
                    HttpHandler[] tmp = new HttpHandler[methodIndex+1];
                    tmp[methodIndex] = e.getValue();
                    pi.setHttpHandlers(tmp);
                }
            }
        }

        // 2. WS entry（仅当本 app 有 @ProtoWebSocket 方法时才写）
        if (!wsMsgHandlers.isEmpty()) {
            PathInfo pi = new PathInfo();
            pi.setPath(WS_PATH);
            pi.setFound(true);
            // 从 Container 拿 per-path 共享 ServiceWSHandler —— 跨 ctx 复用同一实例，
            // 老连接 session.wsHandler 引用不变，靠 msgHandlers 被 rebind 感知版本切换。
            pi.setWsHandler(container.getOrCreateWsHandler(WS_PATH));
            BeanWrap bw = beans.beanWrapByType(WSAuthenticator.class);
            if (bw != null && bw.instance() instanceof WSAuthenticator) {
                pi.setWsAuthenticator((WSAuthenticator) bw.instance());
            }
            table.put(FastBufDataRange.from(WS_PATH), pi);
        }

        return table;
    }

    public Map<FastBufDataRange, PathInfo> getPathInfoMap() {
        return Collections.unmodifiableMap(pathInfoMap);
    }

    /**
     * 锚点 2 双轨通知：{@link ContextStartedAware} 接口回调 + {@link ContextStartedEvent} 事件发布。
     *
     * <p>两者的差异：
     * <ul>
     *   <li>{@link ContextStartedAware} 接口：按 BeanDef 拓扑序遍历 beans，fail-fast
     *       （任一回调抛错 → {@link #start()} 转 FAILED，deploy 回滚）</li>
     *   <li>{@link ContextStartedEvent} 事件：沿用 {@link EventPublisher} 默认语义，
     *       listener 抛错 log warn + 继续（与 {@link ContextRefreshedEvent} 一致）</li>
     * </ul>
     *
     * <p>调用方：{@link #generateAndBindRoutes()} 内部，在 {@code buildPathTable()}
     * 之后、{@link Container#deployAppRoutes} 之前。</p>
     */
    private void notifyContextStarted() {
        invokeContextStartedAware();
        events.publish(new ContextStartedEvent(this));
    }

    /**
     * 按 BeanDef 拓扑序遍历 beans，对每个实现 {@link ContextStartedAware} 的实例调
     * {@link ContextStartedAware#onContextStarted}。任一回调抛错 → 包成
     * {@link RuntimeException} 冒泡，{@link #start()} 转入 {@code FAILED} 状态（deploy 回滚）。
     *
     * <p>异常信息带上 bean name + appId 便于定位失败源头。</p>
     */
    private void invokeContextStartedAware() {
        for (BeanDef def : beans.sorted()) {
            BeanWrap bw = beans.beanWrapByName(def.name());
            if (bw == null) continue;
            Object inst = bw.instance();
            if (inst instanceof ContextStartedAware) {
                try {
                    ((ContextStartedAware) inst).onContextStarted(this);
                } catch (Throwable t) {
                    throw new RuntimeException(
                            "[" + appId + "] ContextStartedAware failed on bean '"
                                    + def.name() + "': " + t.getMessage(), t);
                }
            }
        }
    }

    private void generateMethodsHandlers(Class<?> protoIf, List<ProtoMethodData> protoMethodDatas,
                                         String serviceName)
            throws ClassNotFoundException {
//        List<HttpHandler>            httpH = routers.httpHandlers();
//        List<WSServiceMsgHandler<?>> wsH   = routers.wsHandlers();
//        List<ErpcHandler>            erpcH = routers.erpcHandlers();
//        List<GrpcHandler>            grpcH = routers.grpcHandlers();

        String protoHttpAnn = ProtoHttp.class.getName();
        String protoWsAnn   = ProtoWebSocket.class.getName();
        String protoGrpcAnn = ProtoGrpc.class.getName();
        String shardedAnn   = Sharded.class.getName();
        for (ProtoMethodData pmd : protoMethodDatas) {
            // Method 从 proto 接口上取（不是 bean 实例）—— Handler 字节码后续自己按 protoIf 查 bean
            Method m;
            try {
                m = resolveProtoMethod(protoIf, pmd);
            } catch (NoSuchMethodException ex) {
                throw new RouteBindException(null, pmd.getName(), pmdParamTypes(pmd), ex);
            }
            boolean shard = false;
            for (AnnoData a : pmd.getAnnoDatas()) {
                if (shardedAnn.equals(a.getType())) {
                    shard = true;
                    break;
                }
            }

            // 0. eRPC：绑定不依赖方法级协议注解 —— 一元方法自动经 eRPC 暴露。
            //    放在 anno 循环之外：没写任何协议注解的方法也要能被 eRPC 调用。
            if (container.hasCapability(Capability.ERPC)) {
                bindErpcMethod(protoIf, pmd, m, serviceName, shards);
            }

            for (AnnoData anno : pmd.getAnnoDatas()) {
                String t = anno.getType();
                // 1. HTTP：方法上有 @ProtoHttp 才生成；节点具备 HTTP 能力
                if (protoHttpAnn.equals(t)) {
                    if (container.hasCapability(Capability.HTTP)) {
                        HttpHandler h = generateHandler(HttpHandler.class, protoIf, pmd.getAnnoDatas(), anno, m, shards);
                        //httpH.add(h);
                        httpHandlersByPath.put(deriveHttpPath(protoIf, m, anno), h);
                    }
                    // 2. WS：方法上有 @ProtoWebSocket 才生成；节点具备 WS 能力
                } else if (protoWsAnn.equals(t)) {
                    if (container.hasCapability(Capability.WS)) {
                        @SuppressWarnings({"rawtypes", "unchecked"})
                        WSServiceMsgHandler h = (WSServiceMsgHandler) generateHandler(
                                (Class) WSServiceMsgHandler.class, protoIf, pmd.getAnnoDatas(), anno, m, shards);
                        //wsH.add(h);
                        // 按 method 名而非 path 索引：dispatch 由 ServiceWSHandler 按 JSON method 字段查
                        String methodName;
                        Object methodObj = anno.getValues().get("method");
                        if (methodObj == null || !(methodObj instanceof String)
                                || ((String)methodObj).trim().length() <= 0) {
                            methodName = protoIf.getSimpleName().substring(0, 1).toLowerCase(Locale.ENGLISH);
                            if (protoIf.getSimpleName().length() > 1) {
                                methodName += protoIf.getSimpleName().substring(1);
                            }
                            methodName += ".";
                            methodName += m.getName();
                        } else {
                            methodName = (String)methodObj;
                        }
                        wsMsgHandlers.put(methodName, h);
                    }
                    // 3. gRPC：方法上有 @ProtoGrpc 才生成；节点具备 GRPC 能力
                } else if (protoGrpcAnn.equals(t)) {
                    if (container.hasCapability(Capability.GRPC)) {
                        GrpcHandler h = generateHandler(GrpcHandler.class, protoIf, pmd.getAnnoDatas(), anno, m, shards);
                        String path = deriveGrpcPath(serviceName, protoIf, m, anno);
                        grpcMethodsByPath.put(path, new GrpcMethod(path, h,
                                readGrpcMethodType(anno), loadGrpcDtoType(anno, "req"), loadGrpcDtoType(anno, "resp")));
                    }
                }
            }
        }
        // 注意：ServiceWSHandler.rebindMsgHandlers 不再在本方法调用 —— wsHandler 是
        // Container 持有的 per-path 单例，rebind 统一在 Container.deploy / switchVersion
        // / restoreToSlot 末尾由 rebindCurrentWsHandlers(currentCtx.wsMsgHandlers()) 调。
    }

    /**
     * 把一个 proto 方法绑成 eRPC 方法。
     *
     * <p><b>只支持一元调用</b>（v1 的硬约束）：恰好 1 个参数 + 非 void 返回值，
     * 且参数/返回值都不是基本类型（基本类型没法走 protobuf / json 的 DTO 编解码）。
     * 不满足条件的方法<b>跳过</b>（不报错）—— 同一个接口完全可以同时提供 gRPC 流式方法，
     * 那类方法只是不经 eRPC 暴露而已。</p>
     *
     * <p><b>methodId 在这里不算</b>：per-app 阶段看不到全局方法集合
     * （{@code increment} 策略要全局排序），所以只把「方法是什么」记进
     * {@link #erpcMethodsByKey}，号由 {@link Container#rebuildErpcMapping()} 统一分配。</p>
     */
    private void bindErpcMethod(Class<?> protoIf, ProtoMethodData pmd, Method m,
                                String serviceName, ShardRegistry shards) {
        Class<?>[] params = m.getParameterTypes();
        if (params.length != 1) {
            return;                                     // 非一元（流式 / 多参）—— 不经 eRPC 暴露
        }
        if (m.getReturnType() == void.class) {
            return;                                     // 无返回值 —— 不经 eRPC 暴露
        }
        if (params[0].isPrimitive() || m.getReturnType().isPrimitive()) {
            return;                                     // 基本类型没法编解码 —— 不经 eRPC 暴露
        }
        if (Modifier.isStatic(m.getModifiers())) {
            return;
        }
        // serviceName 取 proto 的服务全名（proto package + "." + service 名）——
        // 不能从 java 包名反推（proto 的 java_package option 可以覆盖 proto package）。
        // 没有 @ProtoService.serviceName() 时退化为 proto 接口 FQCN（至少保证键唯一）。
        String svcName = (serviceName != null && !serviceName.isEmpty()) ? serviceName : protoIf.getName();
        int explicitId = readExplicitErpcMethodId(pmd);
        ErpcHandler h = generateHandler(ErpcHandler.class, protoIf, pmd.getAnnoDatas(),
                erpcAnnoData(pmd), m, shards);
        String key = ErpcMethodIds.key(svcName, m.getName());
        erpcMethodsByKey.put(key, new ErpcMethodDef(svcName, m.getName(), h,
                params[0], m.getReturnType(), explicitId));
    }

    /**
     * 取 {@code @ProtoErpc(methodId = ...)} 的显式 methodId；没有该注解 ⇒ {@code -1}（自动分配）。
     */
    private static int readExplicitErpcMethodId(ProtoMethodData pmd) {
        String type = ProtoErpc.class.getName();
        for (AnnoData anno : pmd.getAnnoDatas()) {
            if (!type.equals(anno.getType())) {
                continue;
            }
            Object v = anno.getValues().get("methodId");
            if (v == null) {
                continue;
            }
            try {
                int id = Integer.parseInt(v.toString().trim());
                if (id > 0) {
                    return id;
                }
            } catch (NumberFormatException ignore) {
                // 注解值不是合法整数 —— 按「没写」处理，交给自动分配
            }
        }
        return -1;
    }

    /**
     * 给 {@code generateHandler} 用的注解数据。
     *
     * <p>{@code generateHandler} 的 cache key 用 {@code annoData.getType()}，不能传 null ——
     * 方法上没写 {@code @ProtoErpc} 时合成一个（类型名一致 ⇒ cache key 稳定）。</p>
     */
    private static AnnoData erpcAnnoData(ProtoMethodData pmd) {
        String type = ProtoErpc.class.getName();
        for (AnnoData anno : pmd.getAnnoDatas()) {
            if (type.equals(anno.getType())) {
                return anno;
            }
        }
        return new AnnoData(type);
    }

    /**
     * 从类级 {@code @ProtoService} 注解里取 proto 的服务全名（{@code protoPackage.ServiceName}）。
     *
     * <p>gRPC 的 {@code :path} = {@code /<服务全名>/<方法名>}；eRPC 的 methodId 计算输入
     * = {@code <服务全名>.<方法名>} —— 两者都<b>不能</b>从 Java 包名反推
     * （proto 的 {@code java_package} option 可以覆盖 proto package）。代码生成器
     * 已经把完整的 {@code :path} 写进 {@code @ProtoGrpc.path()}，这个值只作为手写注解时的兜底。</p>
     *
     * @return 服务全名；没有 {@code @ProtoService.serviceName()} 时返回 null
     */
    private static String protoServiceName(ProtoServiceData psi) {
        String type = ProtoService.class.getName();
        for (AnnoData anno : psi.getAnnoDatas()) {
            if (!type.equals(anno.getType())) {
                continue;
            }
            Object name = anno.getValues().get("serviceName");
            if (name != null && !name.toString().isEmpty()) {
                return name.toString();
            }
        }
        return null;
    }

    /**
     * 解析 proto 接口上的目标方法。
     *
     * <p>优先按「参数类型完全匹配」找 —— 一元方法只有 1 个参数，{@code pmd.getParamType()}
     * 拿得到。</p>
     *
     * <p><b>服务端流式方法有 2 个参数</b>（Req + StreamObserver），而扫描器只记了第一个
     * （{@code ProtoMethodData.paramType} 是单值），按参数匹配必然找不到 —— 退化成
     * 「按方法名找」。proto service 内方法不重载，名字唯一。</p>
     */
    private Method resolveProtoMethod(Class<?> protoIf, ProtoMethodData pmd)
            throws ClassNotFoundException, NoSuchMethodException {
        try {
            return protoIf.getMethod(pmd.getName(), pmdParamTypes(pmd));
        } catch (NoSuchMethodException ex) {
            for (Method candidate : protoIf.getMethods()) {
                if (candidate.getName().equals(pmd.getName())) {
                    return candidate;
                }
            }
            throw ex;
        }
    }

    /**
     * 派生 gRPC 的 {@code :path}：优先用 {@code @ProtoGrpc.path()}（代码生成器填的是
     * proto 里的原始方法名）；未填则按 {@code /<serviceName>/<methodName>} 派生。
     *
     * @param serviceName {@code @ProtoService.serviceName()}（proto 包名 + 服务名）
     */
    private static String deriveGrpcPath(String serviceName, Class<?> protoIf, Method m, AnnoData anno) {
        Object p = anno.getValues().get("path");
        if (p != null) {
            String s = p.toString();
            if (!s.isEmpty()) {
                return s;
            }
        }
        if (serviceName != null && !serviceName.isEmpty()) {
            return "/" + serviceName + "/" + m.getName();
        }
        // 没有 serviceName 也没有 path —— 用接口简单名兜底（至少路由是可用的）
        return "/" + protoIf.getSimpleName() + "/" + m.getName();
    }

    /**
     * 读 {@code @ProtoGrpc.type()} —— 注解扫描器把枚举值存成字面名字（{@code "UNARY"}）。
     */
    private static ProtoGrpc.MethodType readGrpcMethodType(AnnoData anno) {
        Object value = anno.getValues().get("type");
        if (value == null) {
            return ProtoGrpc.MethodType.UNARY;
        }
        String name = value.toString();
        for (ProtoGrpc.MethodType t : ProtoGrpc.MethodType.values()) {
            if (t.name().equals(name)) {
                return t;
            }
        }
        throw new IllegalArgumentException("未知的 @ProtoGrpc.type(): " + name);
    }

    /**
     * 读 {@code @ProtoGrpc.req()/resp()} 里的 DTO 类。
     *
     * <p>注解扫描器把 {@code Class} 值归一化成 FQCN 字符串，要用 appCL 加载 ——
     * DTO 归 appCL 管，生成类的 ClassLoader 也是 appCL 的子类。</p>
     *
     * @param key {@code "req"} 或 {@code "resp"}
     */
    private Class<?> loadGrpcDtoType(AnnoData anno, String key) {
        Object value = anno.getValues().get(key);
        if (value == null) {
            throw new IllegalArgumentException("@ProtoGrpc 缺少 " + key + " 类型");
        }
        try {
            return Class.forName(value.toString(), false, appCL);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("加载 gRPC DTO 类失败: " + value, e);
        }
    }

    /**
     * 派生 HTTP/WS path：优先用注解的 path 属性；未指定则按 "/<simpleName>/<methodName>" 全小写派生。
     * 同 app 内不同 method 共享 FQCN → simpleName 段，method 段区分——保证 path 全局唯一（同 app 内）。
     * Container 在 deploy 时再做跨 app 冲突检测（FQCN 维度）。
     */
    private static String deriveHttpPath(Class<?> protoIf, Method m, AnnoData anno) {
        Object p = anno.getValues().get("path");
        String path = null;
        if (p != null) {
            String s = p.toString();
            if (!s.isEmpty()) {
                path = s;
            }
        }
        Object methodObj = anno.getValues().get("method");
        String method;
        if (methodObj != null) {
            method = methodObj.toString().toUpperCase(Locale.ENGLISH);
        } else {
            method = "GET";
        }
        if (path == null) {
            String simple = protoIf.getSimpleName();
            path =  ("/" + simple + "/" + m.getName()).toLowerCase(Locale.ENGLISH);
        }

        return method + ":" + path;
    }

    /** 从 BeanContainer 按 name 拿已实例化的 bean；找不到 → BeanContainer 内部抛 NoSuchBeanException（启动期 fail-fast）。 */
    private Object resolveBean(String beanName) {
        return beans.getBean(beanName);
    }

    /**
     * 从 ProtoMethodData 推 Method 参数类型。当前实现按 pmd.paramType（单个 FQCN，对应单参数方法）展开；
     * 复杂多参数方法（HTTP 按 path/body 拆）由后续 emit 模板补全。
     */
    private Class<?>[] pmdParamTypes(ProtoMethodData pmd) throws ClassNotFoundException {
        String pt = pmd.getParamType();
        if (pt == null || pt.isEmpty()) {
            return new Class<?>[0];
        }
        return new Class<?>[]{ Class.forName(pt, false, appCL) };
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
     * 按类型查 bean（多实现场景走 @Primary 消歧逻辑，与 BeanContainer.getBean(Class) 一致）。
     * 委托给 BeanContainer.getBean(Class),这里只做 type.cast() 类型断言。
     *
     * <p>比 {@link #getBean(String, Class)} 更常用 —— 业务方拿到 ctx 后通常已知目标类型,
     * 名字(@MicroServiceBean(name="...") 派生)反倒记不住。多候选 + 0/多个 @Primary 时抛
     * {@link io.edap.container.exc.NoUniqueBeanException};byType miss 时抛
     * {@link io.edap.container.exc.NoSuchBeanException}。</p>
     */
    public <T> T getBean(Class<T> type) {
        return type.cast(beans.getBean(type));
    }

    /**
     * 按类型查该类型下全部 bean 实例(不做 @Primary 消歧,不做 Container.beans fallback)。
     *
     * <p>多实现场景(典型:全部 EventListener、全部 HandlerInterceptor、全部
     * {@code WSAuthenticator} 候选)用本方法,比 {@link #getBean(Class)} 更合适 —— 后者
     * 在多候选时只能按 @Primary 消歧,且 miss 时抛 NoSuchBeanException,而批量查场景
     * "没注册" 是合法结果。</p>
     *
     * <p>空集不抛异常 —— 调用方按场景自己判空。
     * 返回的列表是 {@link Collections#unmodifiableList} 包装,后续 register /
     * replaceInstance 不会反映到已返回的列表上(快照语义)。
     * 注意:PROTOTYPE scope 不在 byType 索引中(@Sharded 分片实例同样),本方法看不到,
     * 拿 PROTOTYPE 候选请走 {@link #getBean(String, Class)} byName。</p>
     */
    public <T> List<T> getBeansOfType(Class<T> type) {
        List<BeanWrap> wraps = beans.beanWrapsByType(type);
        if (wraps.isEmpty()) {
            return Collections.emptyList();
        }
        List<T> out = new ArrayList<>(wraps.size());
        for (BeanWrap bw : wraps) {
            out.add(type.cast(bw.instance()));
        }
        return Collections.unmodifiableList(out);
    }

    /** 事件发布快捷入口（state == GATHERING 之后可调；NEW 不允许）。 */
    public void publishEvent(ApplicationEvent e) {
        events.publish(e);
    }

    public void destroyPartial() {}


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
    //public RouterHub          routers()   { return routers; }
    /**
     * 本 app 的 HTTP path → handler 映射（Container.rebuildHttpMapping 聚合来源）。
     * 不可修改视图：返回的 Map 由本 AppContext 独占，外部只读，stop() 期间随 routers.unbindAll 一起清空。
     */
    public Map<String, HttpHandler> httpHandlersByPath() {
        return Collections.unmodifiableMap(httpHandlersByPath);
    }
    /** WS method → 业务 {@link WSServiceMsgHandler}。dispatch 阶段不走此字段（走 Container 持有的 ServiceWSHandler.msgHandlers()）。 */
    public Map<String, WSServiceMsgHandler<?>> wsMsgHandlers() {
        return Collections.unmodifiableMap(wsMsgHandlers);
    }
    /**
     * 本 app 的 gRPC {@code :path} → 方法定义（Container.rebuildGrpcMapping 聚合来源）。
     * 不可修改视图：返回的 Map 由本 AppContext 独占，外部只读。
     */
    public Map<String, GrpcMethod> grpcMethodsByPath() {
        return Collections.unmodifiableMap(grpcMethodsByPath);
    }
    /**
     * 本 app 的 eRPC 方法键（{@code serviceName.methodName}）→ 方法定义
     * （{@link Container#rebuildErpcMapping} 聚合来源）。
     * 不可修改视图：返回的 Map 由本 AppContext 独占，外部只读。
     */
    public Map<String, ErpcMethodDef> erpcMethodsByKey() {
        return Collections.unmodifiableMap(erpcMethodsByKey);
    }
    /**
     * 本 ctx 是否处于 PREVIOUS 槽（被 demote 的 current）。true 表示等待 {@link #releaseFuture}
     * 延迟 stop —— 由 {@link Container#schedulePrevRelease(AppContext)} 调度。
     */
    public boolean isDemoted() {
        return demotedAt > 0;
    }

    /** demote 时间戳（毫秒）；0 = 未 demote。 */
    public long demotedAt() {
        return demotedAt;
    }

    /** 由 Container.switchVersion demote 时调用，记录被降级到 PREVIOUS 槽的时间。 */
    public void markDemoted() {
        this.demotedAt = System.currentTimeMillis();
    }

    /** 由 Container 设值：延迟 stop 任务句柄（用于 cancel 提前释放）。 */
    public void setReleaseFuture(TimerHandle f) {
        this.releaseFuture = f;
    }

    /**
     * 由 Container.cancelAndStopPrev 调用 —— 仅 cancel 延迟任务，<b>不调 stop()</b>。
     * stop 由调用方在 cancel 之后立即触发，调用边界显式。
     */
    public void cancelRelease() {
        TimerHandle f = this.releaseFuture;
        if (f != null) {
            f.cancel();                                         // 平台时间轮的 cancel 不打断已投递的任务
            this.releaseFuture = null;
        }
    }

    public ShardRegistry      shards()    { return shards; }
    public AppResourceLoader resourceLoader() { return resourceLoader; }
    public AppState           state()     { return state; }


    // ─── 内部类型 ───

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

        /**
         * 把 ASM 字节码注册为 named class。直接 defineClass，<b>不走双亲委派</b>——
         * 不会因 parent (appCL) 已有同名 class 而被遮蔽；多次 define 同一 name 抛 LinkageError。
         *
         * <p>专供 {@code AppContext.generateHandler} 用；包内可见。</p>
         */
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

}
