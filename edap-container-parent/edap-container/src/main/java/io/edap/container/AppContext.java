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
import io.edap.container.app.asm.HandlerAsmGenerator;
import io.edap.container.BeanWrap;
import io.edap.container.event.ApplicationEvent;
import io.edap.container.event.ContextClosedEvent;
import io.edap.container.event.ContextRefreshedEvent;
import io.edap.container.event.EventPublisher;
import io.edap.container.exc.RouteBindException;
import io.edap.container.mw.*;
import io.edap.container.scan.EarScanner;
import io.edap.container.ws.ServiceWSHandler;
import io.edap.container.ws.WSServiceMsgHandler;
import io.edap.grpc.GrpcHandler;
import io.edap.http.HttpHandler;
import io.edap.http.PathInfo;
import io.edap.http.ws.WSAuthenticator;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.microservice.Scope;
import io.edap.microservice.annotation.Bean;
import io.edap.microservice.annotation.MicroServiceBean;
import io.edap.nio.codec.FastBufDataRange;
import io.edap.props.Props;
import io.edap.protobuf.annotation.ProtoHttp;
import io.edap.protobuf.annotation.ProtoWebSocket;
import io.edap.protobuf.annotation.Sharded;
import io.edap.rpc.ErpcHandler;
import io.edap.util.CollectionUtils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
    private final RouterHub          routers;       // 4 份协议 Handler List 的被动持有者
    private final BeanContainer      beans;         // Bean 装配核心
    private final ShardRegistry      shards;        // @Sharded 方法所属 bean 的分片实例
    private final AppResourceLoader  resourceLoader;// 通过 appCL 读 jar 内资源
    private final List<BeanPostProcessor> postProcessors = new ArrayList<>(); // Bean 初始化前后钩子

    // ─── per-app path → handler 索引 ───
    // Container.rebuildHttpMapping() 在 deploy / undeploy / switchVersion 末尾聚合各 app 的
    // httpHandlersByPath + wsHandlersByPath，整张替换 HttpServer 的 httpMapping（volatile 写，
    // dispatch 热路径无锁读）。同 app 不同 method 的 path 由 @ProtoHttp/@ProtoWebSocket 显式
    // 指定；未指定时默认派生 /<interfaceSimpleName>/<methodName> 全小写。
    private final Map<String, HttpHandler>            httpHandlersByPath = new HashMap<>();

    /**
     * WS method → 业务 {@link WSServiceMsgHandler}。
     * 仅用于 {@link #generateAndBindRoutes} 末尾一次性喂给 {@link #serviceWSHandler} 的
     * msgHandlers（volatile 替换）。dispatch 阶段不走此字段——只走 serviceWSHandler.msgHandlers()。
     */
    private final Map<String, WSServiceMsgHandler<?>> wsMsgHandlers = new HashMap<>();

    /**
     * 单 app 唯一一个 {@link ServiceWSHandler}（{@link #WS_PATH} 路径专用）。
     * 持有 method → 业务 WSServiceMsgHandler 的 volatile map（version 切换时整张替换）。
     * 长连接不断开，跨 version 复用同一 serviceWSHandler 实例 → in-flight 消息按老版本处理。
     */
    private final ServiceWSHandler serviceWSHandler;

    /**
     * WS 固定路径。第一期约定：所有 {@code @ProtoWebSocket} 标注的方法共用同一 path（与 HTTP per-method
     * 不同——WS 用单一长连接 + 业务 method 二次路由）；{@code /ws} 写死在 PathInfo 里，避免每 method 自定
     * path 引入的多 ServiceWSHandler / 多 PathInfo.wsHandler 复杂度。
     */
    public static final String WS_PATH = "/ws";

    // ─── ASM 生成 Handler impl class 的缓存 ───
    // 挂在 AppContext 上（不是 Container 单例），原因：Method → Class → genCL →(parent)→ appCL
    // 整条引用链必须与 ctx 同生死；放 Container 上会永久持有 appCL 引用 → appCL 永远 GC 不掉
    // （违反 §3.8 防内存泄漏不变量）。详见 §3.5.7 缓存归属说明。
    private final Map<HandlerKey, Class<?>>           generatedHandlers = new ConcurrentHashMap<>();
    private final Map<ClassLoader, GeneratedClassLoader> generatedCLs   = new ConcurrentHashMap<>();

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
        this.serviceWSHandler = new ServiceWSHandler(this);
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

            transitionTo(AppState.COMMITTING);             // GATHERING -> COMMITTING

            // Phase 3 READY：Lifecycle.start() + 路由 bind
            beans.startLifecycles();
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



    /** SIGTERM / undeploy / switchVersion 时的标准停止路径。幂等。 */
    @Override
    public void stop() throws Throwable {
        AppState cur = state;
        if (cur == AppState.NEW || cur == AppState.STOPPED) return;
        if (cur == AppState.STOPPING) return;
        transitionTo(AppState.STOPPING);

        Throwable firstErr = null;
        // 1. 路由摘除（让 in-flight 之外不再有请求到达本 AppContext）
        try {
            routers.unbindAll();
            httpHandlersByPath.clear();
            // ServiceWSHandler 的 msgHandlers 表也清空 —— 长连接下次 msg 按老 handler 实例 dispatch
            // （in-flight 安全）；新 msg 因 msgHandlers 已空会回 404 method not found
            serviceWSHandler.rebindMsgHandlers(java.util.Collections.<String, WSServiceMsgHandler<?>>emptyMap());
            wsMsgHandlers.clear();
        }
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
            GeneratedClassLoader genCL = generatedCLs.computeIfAbsent(appCL, GeneratedClassLoader::new);

            // 3. ASM 字节码生成（无状态工具 HandlerAsmGenerator.INSTANCE，不持有 app 状态）
            byte[] bytes = HandlerAsmGenerator.INSTANCE.generateHandlerClass(
                    targetIf, protoIf, method, annoDatas, appCL);

            // 4. defineClass 把字节码实际注册到 genCL（不走双亲委派；不向上找 parent (appCL)）——
            //    用 Class.forName(name, true, genCL) 会先走 genCL → appCL 双亲委派，
            //    appCL 没有这个类就 CNFE，Handler impl 永远不会被实际注册。
            try {
                String handlerName = HandlerAsmGenerator.INSTANCE.handlerName(targetIf, protoIf, method);
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
                    generateMethodsHandlers(ifaceClass, psi.getMethodInfos());
                }
            }
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        } finally {
            Thread.currentThread().setContextClassLoader(prevCL);
        }

        // 全部 Handler 生成 + RouterHub 写完后：构建全量 pathTable（HTTP + WS），推给 Container。
        // 顺序：先写 RouterHub / serviceWSHandler.msgHandlers（dispatch 路径就绪），
        // 再 deployAppRoutes → Container 做 WS path 冲突检测 + 整张替换 HttpServer.mapping
        // （dispatch 热路径无锁读）。失败抛 RouteBindException → start() 转 FAILED → deploy 回滚。
        Map<FastBufDataRange, PathInfo> pathTable = buildPathTable();
        container.deployAppRoutes(appId, pathTable);
    }

    /**
     * 构建本 AppContext 的全量 pathTable（HTTP + WS），供 {@link #generateAndBindRoutes} 末尾
     * 一次性推给 {@link Container#deployAppRoutes}。
     *
     * <p><b>HTTP 段</b>：每个 {@code @ProtoHttp} 方法 → 一个 PathInfo entry（含 httpHandlers[]）。
     * path 来自 {@link #deriveHttpPath}。</p>
     *
     * <p><b>WS 段</b>：所有 {@code @ProtoWebSocket} 方法共用单一 path {@link #WS_PATH} →
     * 一个 PathInfo entry（wsHandler = {@link #serviceWSHandler} + wsAuthenticator）。
     * 多个 {@code @ProtoWebSocket} 方法不产生多个 PathInfo entry——WS 走长连接 + 业务 method
     * 二次路由，path 仅作连接入口。</p>
     *
     * <p><b>WSAuthenticator 取值</b>：{@code beans.beanWrapByType(WSAuthenticator.class)} miss
     *     → 由 BeanContainer fallback 到 {@code container.containerBeans()} 的
     *     {@link HeaderTokenAuthenticator} 默认实现（开箱即用）；app 提供自己的 WSAuthenticator
     *     bean 时自动覆盖。应用 bean miss 且 Container.beans 也没注册（理论上不应发生）→
     *     该 PathInfo 不设 wsAuthenticator → 握手阶段返回 401。</p>
     */
    private Map<FastBufDataRange, PathInfo> buildPathTable() {
        Map<FastBufDataRange, PathInfo> table = new HashMap<>();

        // 1. HTTP entries
        for (Map.Entry<String, HttpHandler> e : httpHandlersByPath.entrySet()) {
            String path = e.getKey();
            PathInfo pi = new PathInfo();
            pi.setPath(path);
            pi.setFound(true);
            pi.setHttpHandlers(new HttpHandler[]{e.getValue()});
            table.put(FastBufDataRange.from(path), pi);
        }

        // 2. WS entry（仅当本 app 有 @ProtoWebSocket 方法时才写）
        if (!wsMsgHandlers.isEmpty()) {
            PathInfo pi = new PathInfo();
            pi.setPath(WS_PATH);
            pi.setFound(true);
            pi.setWsHandler(serviceWSHandler);
            BeanWrap bw = beans.beanWrapByType(WSAuthenticator.class);
            if (bw != null && bw.instance() instanceof WSAuthenticator) {
                pi.setWsAuthenticator((WSAuthenticator) bw.instance());
            }
            table.put(FastBufDataRange.from(WS_PATH), pi);
        }

        return table;
    }

    private void generateMethodsHandlers(Class<?> protoIf, List<ProtoMethodData> protoMethodDatas)
            throws ClassNotFoundException {
        List<HttpHandler>            httpH = routers.httpHandlers();
        List<WSServiceMsgHandler<?>> wsH   = routers.wsHandlers();
        List<ErpcHandler>            erpcH = routers.erpcHandlers();
        List<GrpcHandler>            grpcH = routers.grpcHandlers();

        String protoHttpAnn = ProtoHttp.class.getName();
        String protoWsAnn   = ProtoWebSocket.class.getName();
        String shardedAnn   = Sharded.class.getName();
        for (ProtoMethodData pmd : protoMethodDatas) {
            // Method 从 proto 接口上取（不是 bean 实例）—— Handler 字节码后续自己按 protoIf 查 bean
            Method m;
            try {
                m = protoIf.getMethod(pmd.getName(), pmdParamTypes(pmd));
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

            for (AnnoData anno : pmd.getAnnoDatas()) {
                String t = anno.getType();
                // 1. HTTP：方法上有 @ProtoHttp 才生成；节点具备 HTTP 能力
                if (protoHttpAnn.equals(t)) {
                    if (container.hasCapability(Capability.HTTP)) {
                        HttpHandler h = generateHandler(HttpHandler.class, protoIf, pmd.getAnnoDatas(), anno, m, shards);
                        httpH.add(h);
                        httpHandlersByPath.put(deriveHttpPath(protoIf, m, anno), h);
                    }
                    // 2. WS：方法上有 @ProtoWebSocket 才生成；节点具备 WS 能力
                } else if (protoWsAnn.equals(t)) {
                    if (container.hasCapability(Capability.WS)) {
                        @SuppressWarnings({"rawtypes", "unchecked"})
                        WSServiceMsgHandler h = (WSServiceMsgHandler) generateHandler(
                                (Class) WSServiceMsgHandler.class, protoIf, pmd.getAnnoDatas(), anno, m, shards);
                        wsH.add(h);
                        // 按 method 名而非 path 索引：dispatch 由 ServiceWSHandler 按 JSON method 字段查
                        wsMsgHandlers.put(m.getName(), h);
                    }
                }
                // eRPC / gRPC option 解析尚未在 EarScanner 落地——对应 Capability 检查留待后续 PR
            }
        }
        // 所有 @ProtoWebSocket 方法遍历完后，一次性把整张 method 表喂给 ServiceWSHandler。
        // serviceWSHandler.rebindMsgHandlers 是 volatile store（原子发布），reader 要么看到旧版本
        // 要么看到新版本；in-flight 消息走老 handler 完整返回（老 bean 实例不被 GC）。
        if (!wsMsgHandlers.isEmpty()) {
            serviceWSHandler.rebindMsgHandlers(new HashMap<>(wsMsgHandlers));
        }
    }

    /**
     * 派生 HTTP/WS path：优先用注解的 path 属性；未指定则按 "/<simpleName>/<methodName>" 全小写派生。
     * 同 app 内不同 method 共享 FQCN → simpleName 段，method 段区分——保证 path 全局唯一（同 app 内）。
     * Container 在 deploy 时再做跨 app 冲突检测（FQCN 维度）。
     */
    private static String deriveHttpPath(Class<?> protoIf, Method m, AnnoData anno) {
        Object p = anno.getValues().get("path");
        if (p != null) {
            String s = p.toString();
            if (!s.isEmpty()) {
                return s;
            }
        }
        String simple = protoIf.getSimpleName();
        return ("/" + simple + "/" + m.getName()).toLowerCase(Locale.ENGLISH);
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
    public RouterHub          routers()   { return routers; }
    /**
     * 本 app 的 HTTP path → handler 映射（Container.rebuildHttpMapping 聚合来源）。
     * 不可修改视图：返回的 Map 由本 AppContext 独占，外部只读，stop() 期间随 routers.unbindAll 一起清空。
     */
    public Map<String, HttpHandler> httpHandlersByPath() {
        return Collections.unmodifiableMap(httpHandlersByPath);
    }
    /** WS method → 业务 {@link WSServiceMsgHandler}。dispatch 阶段不走此字段（走 serviceWSHandler.msgHandlers()）。 */
    public Map<String, WSServiceMsgHandler<?>> wsMsgHandlers() {
        return Collections.unmodifiableMap(wsMsgHandlers);
    }
    /** 本 app 唯一一个 WS 入口 handler（{@link #WS_PATH} path 专用），持有 method → 业务 handler 的 volatile 表。 */
    public ServiceWSHandler serviceWSHandler() {
        return serviceWSHandler;
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
