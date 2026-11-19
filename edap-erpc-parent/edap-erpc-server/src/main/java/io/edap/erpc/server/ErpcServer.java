/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.erpc.server;

import io.edap.NioServerSession;
import io.edap.Server;
import io.edap.erpc.ErpcAuthResult;
import io.edap.erpc.ErpcAuthenticator;
import io.edap.erpc.ErpcMethod;
import io.edap.erpc.ErpcRequest;
import io.edap.erpc.ErpcResponse;
import io.edap.erpc.ErpcServerInterceptor;
import io.edap.erpc.ErpcStatus;
import io.edap.erpc.ErpcStatusException;
import io.edap.erpc.RpcException;
import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.codec.ErpcCodecRegistry;
import io.edap.erpc.frame.ErpcFrameType;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.rpc.InterceptorChain;
import io.edap.rpc.InterceptorLoader;
import io.edap.rpc.RpcChannelContext;
import io.edap.rpc.RpcContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * eRPC 服务端。
 *
 * <p><b>复用 edap 的 NIO 服务端框架</b>（{@link Server}）：eRPC 走的是自己定义的
 * 线格式（32 字节帧头 + 业务载荷），不是 HTTP/2，所以直接继承
 * {@code io.edap.Server} 而不是 {@code HttpServer}。</p>
 *
 * <p><b>★ 线程模型</b>：</p>
 * <ul>
 *   <li><b>IO 线程</b>（框架的 Disruptor 读分发器）：只做「读 → 切帧 → 投递」，
 *       <b>不执行业务</b>；</li>
 *   <li><b>业务线程池</b>（本类自带）：执行 handler、回写响应帧。</li>
 * </ul>
 * <p>（gRPC 那边是「IO 线程直调 handler」，那是已知缺陷，这里不抄。）</p>
 *
 * <p><b>路由表</b>：{@code methodId → ErpcMethod}，<b>整表替换</b>
 * （{@link #setMethods}）、读路径无锁 —— 与 gRPC 的 {@code setMethods} 同构。
 * 容器在部署/卸载/切版本时整表换掉。</p>
 */
public class ErpcServer extends Server<ErpcRequest, ErpcServerSession> {

    private static final Logger LOG = LoggerManager.getLogger(ErpcServer.class);

    /**
     * methodId → 方法定义。
     *
     * <p>volatile 替换整张表：读者要么看到旧表要么看到新表，不会看到「表被改了一半」
     * 的中间态。</p>
     */
    private volatile Map<Integer, ErpcMethod> methods = Collections.emptyMap();

    /** 序列化器注册表 —— 服务端认识哪些 codec。 */
    private final ErpcCodecRegistry serializers;

    /** 业务线程池。 */
    private volatile ThreadPoolExecutor workers;

    /**
     * ★ <b>诊断开关</b>：true ⇒ 在 IO 线程上直接执行业务（不投 worker 池）。
     *
     * <p>★ <b>这是 gRPC 侧的模型</b>，而 gRPC 侧的已知缺陷正是「业务跑在 IO 线程上」
     * ⇒ 一个慢业务会拖垮整条连接的所有流。<b>本框架的默认形态是「池化 + 单写者」，
     * 这个开关只用于基准归因实验</b>（量化「IO 线程 → worker 线程」这一次切换的成本），
     * <b>不要在生产里打开</b>。</p>
     */
    private volatile boolean inlineDispatch = false;

    private int workerThreads   = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
    private int workerQueueSize = 1024;

    /**
     * 服务端拦截器 —— 请求进来之前 / 调用结束之后各插一刀。
     *
     * <p>★ <b>{@code CopyOnWriteArrayList}</b>：读路径（每次调用都要遍历一遍）无锁，
     * 写路径（注册 / 卸载）极少发生。迭代是<b>快照</b>语义。</p>
     */
    private final CopyOnWriteArrayList<ErpcServerInterceptor> interceptors = new CopyOnWriteArrayList<>();

    /**
     * 连接级鉴权器 —— 校验 AUTH 帧带来的凭据。
     *
     * <p>★ <b>{@code null} ⇒ 不做校验</b>：客户端坚持握手也会被放行
     * （服务端处于「不校验」模式，不该把坚持握手的客户端拒掉）。</p>
     *
     * <p>★ <b>进程级共享</b>：容器里 eRPC Server 是进程级共享的，
     * 多个应用跑在同一个 Server 上 ⇒ 多个 {@code ErpcAuthenticator} bean
     * 只能生效一个（容器装配时取第一个 + warn）。</p>
     */
    private volatile ErpcAuthenticator authenticator;

    /**
     * 是否<b>要求</b>连接先通过鉴权。
     *
     * <p>★ <b>默认 false</b>：不改变现有行为（没握手的客户端照常发请求）。
     * 打开后：未鉴权的连接发请求 ⇒ 回 {@link ErpcStatus#UNAUTHENTICATED} 错误帧。</p>
     */
    private volatile boolean requireAuth = false;

    public ErpcServer() {
        this(ErpcCodecRegistry.instance());
    }

    public ErpcServer(ErpcCodecRegistry serializers) {
        this.serializers = serializers == null ? ErpcCodecRegistry.instance() : serializers;
        // ★ SPI 装载：META-INF/services/io.edap.erpc.ErpcServerInterceptor。
        //   装出来的实例排在编程式注册的前面（注册顺序 = 执行顺序）。
        interceptors.addAll(InterceptorLoader.load(ErpcServerInterceptor.class));
    }

    // ------------------------------------------------------------------
    // 拦截器
    // ------------------------------------------------------------------

    /**
     * 注册一个服务端拦截器。
     *
     * <p>排在已有拦截器<b>后面</b> ⇒ {@code beforeCall} 是正序、{@code afterCall} 是逆序。</p>
     *
     * @param interceptor 拦截器；{@code null} 忽略
     */
    public void addInterceptor(ErpcServerInterceptor interceptor) {
        if (interceptor != null) {
            interceptors.add(interceptor);
        }
    }

    /**
     * 移除一个服务端拦截器。
     *
     * @param interceptor 拦截器
     * @return true 表示确实移除了一个
     */
    public boolean removeInterceptor(ErpcServerInterceptor interceptor) {
        return interceptor != null && interceptors.remove(interceptor);
    }

    /**
     * 当前的服务端拦截器。
     *
     * <p>★ <b>活视图</b>：注册/移除之后这里立刻能看到。<b>不要直接改这个列表</b>，
     * 用 {@link #addInterceptor} / {@link #removeInterceptor}。</p>
     *
     * @return 拦截器列表
     */
    public List<ErpcServerInterceptor> interceptors() {
        return interceptors;
    }

    // ------------------------------------------------------------------
    // 连接级鉴权
    // ------------------------------------------------------------------

    /**
     * 装连接级鉴权器。
     *
     * <p>★ <b>不影响 {@link #requireAuth}</b>：装了鉴权器只是「客户端来握手时我会校验」，
     * 要不要<b>强制</b>握手是 {@link #setRequireAuth(boolean)} 的事。</p>
     *
     * @param authenticator 鉴权器；{@code null} ⇒ 摘掉（回到「不校验」模式）
     */
    public void setAuthenticator(ErpcAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    /**
     * 当前的鉴权器。
     *
     * @return 鉴权器；没装返回 {@code null}
     */
    public ErpcAuthenticator getAuthenticator() {
        return authenticator;
    }

    /**
     * 是否<b>要求</b>连接先通过鉴权。
     *
     * <p>★ <b>默认 false</b>（不改变现有行为）。打开后，未鉴权的连接发请求 ⇒
     * 回 {@link ErpcStatus#UNAUTHENTICATED} 错误帧。</p>
     *
     * <p>★ <b>注意</b>：开了它就得有 {@link #setAuthenticator(ErpcAuthenticator)} ——
     * 否则没有任何办法让连接通过鉴权，请求会被全拒。</p>
     *
     * @param requireAuth true ⇒ 未鉴权的连接发请求被拒
     */
    public void setRequireAuth(boolean requireAuth) {
        if (requireAuth && authenticator == null) {
            LOG.warn("erpc requireAuth=true but no authenticator is set —— "
                + "所有请求都会被拒（客户端没法握手）");
        }
        this.requireAuth = requireAuth;
    }

    /**
     * 是否要求连接先通过鉴权。
     */
    public boolean isRequireAuth() {
        return requireAuth;
    }

    @Override
    public void init() {
        setDecoder(new ErpcServerDecoder());
        workerPool();
    }

    @Override
    public NioServerSession createNioSession() {
        return new ErpcServerSession(this);
    }

    // ------------------------------------------------------------------
    // 路由表
    // ------------------------------------------------------------------

    /**
     * 整张替换路由表（容器在部署/卸载/切版本时调用）。
     */
    public void setMethods(Map<Integer, ErpcMethod> methods) {
        this.methods = methods == null || methods.isEmpty()
            ? Collections.emptyMap()
            : new HashMap<>(methods);
    }

    /**
     * @return 当前路由表（只读快照）
     */
    public Map<Integer, ErpcMethod> getMethods() {
        return methods;
    }

    /**
     * 按方法号查找。
     *
     * @return 找不到返回 {@code null}
     */
    public ErpcMethod lookup(int methodId) {
        return methods.get(methodId);
    }

    // ------------------------------------------------------------------
    // 编解码器
    // ------------------------------------------------------------------

    /**
     * 按线上协议 id 找编解码器。
     *
     * @return 找不到返回 {@code null}
     */
    public ErpcCodec codecById(int id) {
        return serializers.byId(id);
    }

    /**
     * 服务端默认的编解码器（protobuf）。
     */
    public ErpcCodec defaultCodec() {
        return serializers.defaultCodec();
    }

    // ------------------------------------------------------------------
    // 业务线程池
    // ------------------------------------------------------------------

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public int getWorkerQueueSize() {
        return workerQueueSize;
    }

    public void setWorkerQueueSize(int workerQueueSize) {
        this.workerQueueSize = workerQueueSize;
    }

    /**
     * 诊断开关：是否在 IO 线程上直接执行业务（见 {@link #inlineDispatch} 的说明）。
     */
    public void setInlineDispatch(boolean inlineDispatch) {
        this.inlineDispatch = inlineDispatch;
    }

    /**
     * 业务线程池（幂等 —— 第一次调用时创建）。
     */
    private ThreadPoolExecutor workerPool() {
        ThreadPoolExecutor pool = workers;
        if (pool != null) {
            return pool;
        }
        synchronized (this) {
            if (workers == null) {
                workers = new ThreadPoolExecutor(workerThreads, workerThreads,
                    60L, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(workerQueueSize),
                    new ErpcThreadFactory(),
                    // 队列满了直接拒 —— 回 RESOURCE_EXHAUSTED，比阻塞 IO 线程好
                    new ThreadPoolExecutor.AbortPolicy());
            }
            return workers;
        }
    }

    /**
     * 停掉业务线程池（服务器下线时调用）。
     */
    public void stop() {
        ThreadPoolExecutor pool = workers;
        if (pool == null) {
            return;
        }
        pool.shutdown();
    }

    // ------------------------------------------------------------------
    // 分发
    // ------------------------------------------------------------------

    /**
     * 分发一个请求帧。
     *
     * <p>IO 线程调用：路由 + 投递到业务线程池，<b>不在这里执行业务</b>。</p>
     */
    void dispatch(ErpcServerSession session, ErpcRequest req) {
        // ★ type 字节的 bit7 是「本帧带 RPC 上下文」标志位 ⇒ 取种类要掩掉它
        int kind = ErpcFrameType.kindOf(req.getType());
        if (kind == ErpcFrameType.PING.getValue()) {
            session.writePong(req);
            return;
        }
        if (kind == ErpcFrameType.AUTH.getValue()) {
            // 连接级鉴权握手 —— 投 worker 池（校验可能要查库/验签，不能挡住 IO 线程）
            try {
                workerPool().execute(() -> handleAuth(session, req));
            } catch (RejectedExecutionException e) {
                session.writeError(req, ErpcStatus.RESOURCE_EXHAUSTED, "业务线程池已满", e);
            }
            return;
        }
        if (kind != ErpcFrameType.REQUEST.getValue()) {
            // RESPONSE / PONG 是客户端 → 服务端方向不存在的帧，忽略
            return;
        }
        // ★ 连接级鉴权：要求握手而这条连接还没握手 ⇒ 直接拒（不投 worker 池）
        if (requireAuth && !session.channelContext().isAuthenticated()) {
            session.writeError(req, ErpcStatus.UNAUTHENTICATED, "连接未通过鉴权", null);
            return;
        }
        ErpcMethod method = methods.get(req.getMethodId());
        if (method == null) {
            session.writeError(req, ErpcStatus.UNKNOWN_METHOD,
                "未知的方法号: " + req.getMethodId(), null);
            return;
        }
        ErpcCodec serializer = serializers.byId(req.getCodec());
        if (serializer == null) {
            session.writeError(req, ErpcStatus.UNKNOWN_CODEC,
                "未知的序列化器 id: " + req.getCodec(), null);
            return;
        }
        req.setSerializer(serializer);
        try {
            if (inlineDispatch) {
                // 诊断路径：IO 线程直调（gRPC 的模型）—— 只用于归因实验
                invoke(session, req, method);
            } else {
                workerPool().execute(() -> invoke(session, req, method));
            }
        } catch (RejectedExecutionException e) {
            session.writeError(req, ErpcStatus.RESOURCE_EXHAUSTED, "业务线程池已满", e);
        }
    }

    // ------------------------------------------------------------------
    // 连接级鉴权
    // ------------------------------------------------------------------

    /**
     * 处理 AUTH 帧（<b>跑在业务 worker 线程里</b>）。
     *
     * <p>★ <b>连接级</b>：校验通过后把「已鉴权」标记 + 认证主体记进
     * <b>那条连接</b>的 {@link RpcChannelContext} ⇒ 同一连接的后续请求免校验。</p>
     *
     * <p>★ <b>握手应答复用 RESPONSE / ERROR 帧</b>：成功 ⇒ 空载荷的 RESPONSE；
     * 失败 ⇒ {@link ErpcStatus#UNAUTHENTICATED} 的 ERROR 帧 + <b>关连接</b>
     * （连都过不去，这条连接留着也没用）。</p>
     *
     * <p>★ <b>服务端没配鉴权器 ⇒ 视为通过</b>：服务端处于「不校验」模式，
     * 不该把坚持握手的客户端拒掉。</p>
     */
    private void handleAuth(ErpcServerSession session, ErpcRequest req) {
        ErpcAuthenticator auth = authenticator;
        RpcChannelContext channel = session.channelContext();
        if (auth == null) {
            // 不校验模式 —— 放行并标记（后续请求若开了 requireAuth 也不用再握手）
            channel.markAuthenticated();
            session.writeAuthOk(req);
            return;
        }
        String credential = req.getPayload() == null ? ""
            : new String(req.getPayload(), StandardCharsets.UTF_8);
        ErpcAuthResult result;
        try {
            result = auth.authenticate(credential);
        } catch (Throwable t) {
            LOG.warn("erpc authenticate failed: ", l -> l.threw(t));
            result = null;
        }
        if (result == null || !result.ok()) {
            String reason = result == null ? "鉴权异常" : result.reason();
            session.writeError(req, ErpcStatus.UNAUTHENTICATED,
                reason == null ? "鉴权失败" : reason, null);
            session.close();
            return;
        }
        channel.markAuthenticated();
        if (result.principal() != null) {
            channel.put(RpcChannelContext.PRINCIPAL, result.principal());
        }
        session.writeAuthOk(req);
    }

    /**
     * 执行一次调用（业务线程池里跑）。
     *
     * <p>★ <b>上下文绑定</b>：业务 handler 跑在 worker 线程上 ⇒ 调用前把
     * {@link RpcContext} 绑到当前线程、调用后解绑 —— 业务代码里
     * {@code RpcContext.current()} 拿到的就是本次调用的上下文。</p>
     *
     * <p>★ <b>deadline 真的会掐断调用</b>：到期后定时器线程回一个
     * {@code DEADLINE_EXCEEDED} 错误帧；{@code responded} 保证同一个请求
     * 不会回两帧（业务随后返回时就不再回响应帧了）。</p>
     *
     * <p>★ <b>拦截器</b>：{@code beforeCall} 在业务 handler 之前跑（抛异常 ⇒ 业务不跑、
     * 回 ERROR 帧）；{@code afterCall} 在响应帧写出之后跑（一定跑一次）。
     * 两个钩子都拿到<b>本连接</b>的 {@link RpcChannelContext}（每连接一个）——
     * 权限校验这类「只做一次」的事记在上面即可。</p>
     */
    private void invoke(ErpcServerSession session, ErpcRequest req, ErpcMethod method) {
        RpcContext ctx = req.getContext();
        // 客户端给的 deadline 已经过了 ⇒ 业务根本不该跑
        if (ctx != null && ctx.isExpired()) {
            session.writeError(req, ErpcStatus.DEADLINE_EXCEEDED, "deadline exceeded", null);
            return;
        }
        // 同一个请求只回一帧：deadline 定时器与正常响应二选一
        AtomicBoolean responded = new AtomicBoolean();
        // deadline 真的掐断了这次调用（业务还在跑，但响应已经被定时器抢走）
        AtomicBoolean deadlineHit = new AtomicBoolean();
        if (ctx != null) {
            ctx.onDeadline(() -> {
                deadlineHit.set(true);
                if (responded.compareAndSet(false, true)) {
                    session.writeError(req, ErpcStatus.DEADLINE_EXCEEDED, "deadline exceeded", null);
                }
            });
        }
        ErpcResponse resp = new ErpcResponse();
        resp.setCodec(req.getCodec());
        resp.setMethodId(req.getMethodId());
        resp.setServiceId(req.getServiceId());
        resp.setRequestId(req.getRequestId());
        resp.setTraceId(req.getTraceId());
        // ★ 连接级上下文：每条连接一个（session 持有）⇒ 权限校验这类「只做一次」
        //   的事可以记在上面，同一连接的后续请求不用重做。
        RpcChannelContext channel = session.channelContext();
        Throwable error = null;
        try (RpcContext.Scope scope = RpcContext.attach(ctx)) {
            try {
                // ---- 请求侧：拦截器 → 业务 handler ----
                try {
                    runBeforeInterceptors(req, channel);
                } catch (Throwable t) {
                    LOG.warn("erpc interceptor rejected the call: ", l -> l.arg(method.key()).threw(t));
                    resp.setPayload(null);
                    resp.setResult(null);
                    resp.setException(toRpcException(t, rejectStatus(t)));
                    error = t;
                }
                if (error == null) {
                    try {
                        method.getHandler().handle(req, resp);
                    } catch (Throwable t) {
                        LOG.warn("erpc handler error: ", l -> l.arg(method.key()).threw(t));
                        resp.setPayload(null);
                        resp.setResult(null);
                        resp.setException(toRpcException(t, ErpcStatus.INTERNAL));
                        error = t;
                    }
                }
                // ---- 回响应帧 ----
                if (responded.compareAndSet(false, true)) {
                    try {
                        session.writeResponse(req, resp);
                    } catch (Throwable t) {
                        error = t;
                        LOG.warn("erpc write response failed: ", l -> l.arg(method.key()).threw(t));
                    }
                } else if (error == null && deadlineHit.get()) {
                    // 响应帧被 deadline 定时器抢走了 ⇒ 收尾钩子看到的是「超时」
                    error = new ErpcStatusException(ErpcStatus.DEADLINE_EXCEEDED, "deadline exceeded");
                }
            } finally {
                // ---- 收尾侧：一定跑一次 ----
                runAfterInterceptors(req, resp, error, channel);
            }
        }
        if (ctx != null) {
            // 撤掉 deadline 定时器 —— 否则长连接服务会攒下一堆僵尸任务
            ctx.clearDeadline();
            ctx.cancellation().complete();
        }
    }

    // ------------------------------------------------------------------
    // 拦截器
    // ------------------------------------------------------------------

    /**
     * 跑「请求进来」这一段的拦截器（正序）—— <b>带连接级上下文</b>。
     *
     * <p>★ <b>连接级上下文来自 session</b>：每条连接一个 ⇒ 权限校验这类
     * 「只做一次」的事可以记在上面，同一连接的后续请求不用重做。</p>
     *
     * @param req     本次请求
     * @param channel 本连接的上下文
     * @throws Exception 拦截器抛的异常（调用方据此回 ERROR 帧）
     */
    private void runBeforeInterceptors(ErpcRequest req, RpcChannelContext channel) throws Exception {
        if (interceptors.isEmpty()) {
            return;
        }
        InterceptorChain.before(interceptors, i -> i.beforeCall(req, channel));
    }

    /**
     * 跑「调用结束」这一段的拦截器（逆序）—— <b>带连接级上下文</b>。
     *
     * <p>★ <b>单个拦截器抛异常不影响后面的</b> —— 收尾钩子不该影响调用结果。
     * 框架只记一条 warn 日志。</p>
     *
     * @param req     本次请求
     * @param resp    本次响应（被拒绝时里面带的是异常）
     * @param error   出错原因；{@code null} 表示正常结束
     * @param channel 本连接的上下文
     */
    private void runAfterInterceptors(ErpcRequest req, ErpcResponse resp, Throwable error,
                                      RpcChannelContext channel) {
        if (interceptors.isEmpty()) {
            return;
        }
        InterceptorChain.after(interceptors, i -> {
            try {
                i.afterCall(req, resp, error, channel);
            } catch (Throwable t) {
                LOG.warn("erpc interceptor afterCall error: ", l -> l.threw(t));
            }
        });
    }

    /**
     * 拦截器拒绝调用时的状态码：{@link ErpcStatusException} 用它带的，
     * 其它异常一律 {@link ErpcStatus#INTERNAL}。
     */
    private static ErpcStatus rejectStatus(Throwable t) {
        if (t instanceof ErpcStatusException) {
            return ((ErpcStatusException) t).status();
        }
        return ErpcStatus.INTERNAL;
    }

    // ------------------------------------------------------------------
    // 异常转换
    // ------------------------------------------------------------------

    /**
     * 把业务异常转换成线上的错误体。
     */
    static RpcException toRpcException(Throwable t, ErpcStatus status) {
        RpcException err = new RpcException();
        err.setStatus(status.code());
        err.setClazzName(t.getClass().getName());
        err.setMessage(t.getMessage() == null ? t.toString() : t.getMessage());
        err.setStackElement(toStackTrace(t.getStackTrace()));
        return err;
    }

    /**
     * 转换堆栈（截断到 32 帧 —— 错误体不该比响应本身还大）。
     */
    static List<RpcException.StackTraceElement> toStackTrace(StackTraceElement[] elements) {
        if (elements == null || elements.length == 0) {
            return null;
        }
        int n = Math.min(elements.length, 32);
        List<RpcException.StackTraceElement> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            StackTraceElement e = elements[i];
            RpcException.StackTraceElement se = new RpcException.StackTraceElement();
            se.setDeclaringClass(e.getClassName());
            se.setMethodName(e.getMethodName());
            se.setFileName(e.getFileName());
            se.setLineNumber(e.getLineNumber());
            out.add(se);
        }
        return out;
    }

    /**
     * 业务线程的命名（便于排查线程 dumps）。
     */
    private static class ErpcThreadFactory implements ThreadFactory {

        private final AtomicInteger index = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "edap-erpc-worker-" + index.getAndIncrement());
            // 守护线程：服务器下线时不会拖着 JVM 不退
            t.setDaemon(true);
            return t;
        }
    }
}
