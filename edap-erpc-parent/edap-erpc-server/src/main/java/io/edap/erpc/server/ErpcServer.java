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
import io.edap.erpc.ErpcMethod;
import io.edap.erpc.ErpcStatus;
import io.edap.erpc.RpcException;
import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.codec.ErpcCodecRegistry;
import io.edap.erpc.frame.ErpcFrameType;
import io.edap.erpc.frame.ErpcRequest;
import io.edap.erpc.frame.ErpcResponse;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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

    public ErpcServer() {
        this(ErpcCodecRegistry.instance());
    }

    public ErpcServer(ErpcCodecRegistry serializers) {
        this.serializers = serializers == null ? ErpcCodecRegistry.instance() : serializers;
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
        int type = req.getType();
        if (type == ErpcFrameType.PING.getValue()) {
            session.writePong(req);
            return;
        }
        if (type != ErpcFrameType.REQUEST.getValue()) {
            // RESPONSE / PONG 是客户端 → 服务端方向不存在的帧，忽略
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

    /**
     * 执行一次调用（业务线程池里跑）。
     */
    private void invoke(ErpcServerSession session, ErpcRequest req, ErpcMethod method) {
        ErpcResponse resp = new ErpcResponse();
        resp.setCodec(req.getCodec());
        resp.setMethodId(req.getMethodId());
        resp.setServiceId(req.getServiceId());
        resp.setRequestId(req.getRequestId());
        resp.setTraceId(req.getTraceId());
        try {
            method.getHandler().handle(req, resp);
        } catch (Throwable t) {
            LOG.warn("erpc handler error: ", l -> l.arg(method.key()).threw(t));
            resp.setPayload(null);
            resp.setResult(null);
            resp.setException(toRpcException(t, ErpcStatus.INTERNAL));
        }
        session.writeResponse(req, resp);
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
