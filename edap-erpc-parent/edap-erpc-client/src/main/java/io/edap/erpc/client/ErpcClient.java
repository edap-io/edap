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

package io.edap.erpc.client;

import io.edap.erpc.ErpcException;
import io.edap.erpc.ErpcRequest;
import io.edap.erpc.ErpcResponse;
import io.edap.erpc.LoadBalancer;
import io.edap.erpc.RpcInvoker;
import io.edap.erpc.client.loadbalance.RandomLoadBalancer;
import io.edap.erpc.codec.ErpcCodec;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.rpc.InterceptorChain;
import io.edap.rpc.InterceptorLoader;
import io.edap.rpc.RpcContext;
import io.edap.rpc.RpcMetadata;
import io.edap.rpc.RpcProtocol;

import java.io.Closeable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * eRPC 客户端。
 *
 * <p>★ <b>客户端不持有节点列表</b> —— 它持有的是<b>注册中心适配器</b>
 * （{@link ErpcNodeProvider}）：</p>
 * <pre>{@code
 *   ErpcClient client = new ErpcClient(provider, serializer, ErpcClientOptions.defaults());
 *   // provider 里注册中心推来几个节点就用几个；节点增减不需要重建 client
 * }</pre>
 *
 * <p><b>调用链</b>：</p>
 * <pre>{@code
 *   invoke(methodId, req, respType, timeout)
 *     → 序列化 req
 *     → LoadBalancer.select()  选节点（第一层均衡）
 *     → ErpcNodePool.next()    选连接（第二层均衡，round-robin）
 *     → ErpcChannel.call()      发帧 + 等响应（多路复用，靠 requestId 配对）
 *     → 反序列化 respType
 * }</pre>
 */
public class ErpcClient implements Closeable {

    private static final Logger LOG = LoggerManager.getLogger(ErpcClient.class);

    private final ErpcNodeRegistry registry;
    private final ErpcCodec serializer;
    private final LoadBalancer<Object> loadBalancer;
    private final ErpcClientOptions options;

    /** 链路号发生器。 */
    private final AtomicLong traceIds = new AtomicLong();

    /**
     * 客户端拦截器 —— 请求发出之前 / 调用结束之后各插一刀。
     *
     * <p>★ <b>{@code CopyOnWriteArrayList}</b>：读路径（每次调用都要遍历一遍）无锁，
     * 写路径（注册 / 卸载）极少发生。迭代是<b>快照</b>语义。</p>
     */
    private final CopyOnWriteArrayList<ErpcClientInterceptor> interceptors = new CopyOnWriteArrayList<>();

    /**
     * 用注册中心适配器构造（默认随机均衡）。
     *
     * @param provider  注册中心适配器（节点列表从这里来，且随其变更实时生效）
     * @param serializer 序列化器（决定线上载荷格式）
     * @param options   客户端参数
     */
    public ErpcClient(ErpcNodeProvider provider, ErpcCodec serializer, ErpcClientOptions options) {
        this(new ErpcNodeRegistry(provider, serializer, options), serializer, null, options);
    }

    /**
     * 用固定节点列表构造（静态部署 / 测试）。
     *
     * <p>★ 只是把列表包成一个 {@link StaticErpcNodeProvider} —— 后续
     * {@link StaticErpcNodeProvider#setNodes} 改了列表，客户端同样会跟着变。</p>
     */
    public ErpcClient(List<ErpcNode> nodes, ErpcCodec serializer, ErpcClientOptions options) {
        this(new StaticErpcNodeProvider(nodes), serializer, options);
    }

    /**
     * 用已建好的节点表 + 自定义均衡器构造。
     *
     * @param registry     节点表（活视图）
     * @param serializer   序列化器
     * @param loadBalancer 节点级均衡器；null ⇒ 用随机均衡
     * @param options      客户端参数
     */
    public ErpcClient(ErpcNodeRegistry registry, ErpcCodec serializer,
                      LoadBalancer<Object> loadBalancer, ErpcClientOptions options) {
        this.registry = registry;
        this.serializer = serializer;
        this.loadBalancer = loadBalancer != null ? loadBalancer : new RandomLoadBalancer(registry::pools);
        this.options = options;
        // ★ SPI 装载：META-INF/services/io.edap.erpc.client.ErpcClientInterceptor。
        //   装出来的实例排在编程式注册的前面（注册顺序 = 执行顺序）。
        interceptors.addAll(InterceptorLoader.load(ErpcClientInterceptor.class));
    }

    // ------------------------------------------------------------------
    // 拦截器
    // ------------------------------------------------------------------

    /**
     * 注册一个客户端拦截器。
     *
     * <p>排在已有拦截器<b>后面</b> ⇒ {@code beforeCall} 是正序、{@code afterCall} 是逆序。</p>
     *
     * @param interceptor 拦截器；{@code null} 忽略
     */
    public void addInterceptor(ErpcClientInterceptor interceptor) {
        if (interceptor != null) {
            interceptors.add(interceptor);
        }
    }

    /**
     * 移除一个客户端拦截器。
     *
     * @param interceptor 拦截器
     * @return true 表示确实移除了一个
     */
    public boolean removeInterceptor(ErpcClientInterceptor interceptor) {
        return interceptor != null && interceptors.remove(interceptor);
    }

    /**
     * 当前的客户端拦截器。
     *
     * <p>★ <b>活视图</b>：注册/移除之后这里立刻能看到。<b>不要直接改这个列表</b>，
     * 用 {@link #addInterceptor} / {@link #removeInterceptor}。</p>
     *
     * @return 拦截器列表
     */
    public List<ErpcClientInterceptor> interceptors() {
        return interceptors;
    }

    /**
     * 一元调用（用客户端默认超时）。
     */
    public <T> T invoke(int methodId, Object req, Class<T> respType) {
        return invoke(methodId, req, respType, options.getCallTimeoutMillis());
    }

    /**
     * 一元调用。
     *
     * @param methodId      方法号
     * @param req           请求 DTO
     * @param respType      响应 DTO 类型
     * @param timeoutMillis 超时（毫秒）
     * @return 响应 DTO
     */
    public <T> T invoke(int methodId, Object req, Class<T> respType, long timeoutMillis) {
        return invoke(methodId, req, respType, timeoutMillis, null);
    }

    /**
     * 一元调用 —— <b>带可上线的元数据</b>（txId 之类）。
     *
     * <p>★ <b>元数据与业务参数分开</b>：它不混进请求 DTO，而是走帧内容区里
     * <b>独立的一段</b>（帧头 {@code type} 字节的 bit7 置位）⇒ 服务端在
     * {@code RpcContext.metadata()} 里读到它，<b>业务 DTO 的编解码完全不受影响</b>。</p>
     *
     * <p>★ <b>元数据为空 ⇒ 线上帧与不带元数据时字节级一致</b>（默认路径零开销）。</p>
     *
     * @param methodId      方法号
     * @param req           请求 DTO
     * @param respType      响应 DTO 类型
     * @param timeoutMillis 超时（毫秒）
     * @param metadata      本次调用的元数据；{@code null} 或空 ⇒ 不带
     * @return 响应 DTO
     */
    public <T> T invoke(int methodId, Object req, Class<T> respType, long timeoutMillis,
                        RpcMetadata metadata) {
        ErpcRequest request = new ErpcRequest();
        request.setMethodId(methodId);
        // ★ 交对象而不是编码好的字节：编码推迟到出站写帧那一刻
        //   ⇒ 载荷在网络上只被拷一次（与 gRPC 的帧头内联同一形态）。
        request.setRequestObject(req);
        long traceId = traceIds.incrementAndGet();
        request.setTraceId(traceId);
        request.setTimeoutMillis(timeoutMillis);
        // 本次调用的上下文：deadline 会随帧头 flags 发给服务端 ⇒ 两端各有自己的截止时刻
        RpcContext ctx = RpcContext.builder()
            .protocol(RpcProtocol.ERPC)
            .method("erpc:" + methodId)
            .traceId(traceId)
            .timeoutMillis(timeoutMillis)
            .metadata(metadata)
            .build();
        request.setContext(ctx);
        // ★ 请求侧拦截器：抛异常 ⇒ 本次调用失败（不选节点、不发帧，也不跑 afterCall）
        //   上下文仍然绑着 ⇒ 钩子里 RpcContext.current() 拿得到本次调用的上下文
        try (RpcContext.Scope scope = RpcContext.attach(ctx)) {
            runBeforeInterceptors(request);
        }
        RpcInvoker invoker = loadBalancer.select(request);
        ErpcResponse response = null;
        Throwable error = null;
        // ★ 调用期间把上下文绑到当前线程 ⇒ 调用方代码里 RpcContext.current() 拿得到它
        try (RpcContext.Scope scope = RpcContext.attach(ctx)) {
            try {
                response = invoker.invoke(request);
                if (!response.isSuccess()) {
                    throw new ErpcException("eRPC 调用失败: methodId=" + methodId);
                }
            } catch (RuntimeException e) {
                error = e;
                throw e;
            } catch (Error e) {
                error = e;
                throw e;
            } finally {
                // ★ 收尾侧拦截器：一定跑一次（仍在上下文作用域内 ⇒ 钩子里 RpcContext.current() 拿得到）
                runAfterInterceptors(request, response, error);
            }
        } finally {
            // 调用结束（正常结束也一样）⇒ 标记完成，之后再 cancel 不再生效
            ctx.cancellation().complete();
        }
        byte[] bytes = response.getPayload();
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        return serializer.decode(bytes, 0, bytes.length, respType);
    }

    // ------------------------------------------------------------------
    // 拦截器
    // ------------------------------------------------------------------

    /**
     * 跑「请求发出前」这一段的拦截器（正序）。
     *
     * <p>★ <b>抛异常 ⇒ 本次调用失败</b>：不选节点、不发帧。</p>
     *
     * @param request 本次请求
     */
    private void runBeforeInterceptors(ErpcRequest request) {
        if (interceptors.isEmpty()) {
            return;
        }
        try {
            InterceptorChain.before(interceptors, i -> i.beforeCall(request));
        } catch (Exception e) {
            throw new ErpcException("eRPC 客户端拦截器拒绝了本次调用: methodId="
                + request.getMethodId() + " (" + e.getMessage() + ")", e);
        }
    }

    /**
     * 跑「调用结束」这一段的拦截器（逆序）。
     *
     * <p>★ <b>单个拦截器抛异常不影响后面的</b> —— 收尾钩子不该影响调用结果。
     * 框架只记一条 warn 日志。</p>
     *
     * @param request  本次请求
     * @param response 收到的响应；调用失败时可能是 null
     * @param error    出错原因；{@code null} 表示正常结束
     */
    private void runAfterInterceptors(ErpcRequest request, ErpcResponse response, Throwable error) {
        if (interceptors.isEmpty()) {
            return;
        }
        InterceptorChain.after(interceptors, i -> {
            try {
                i.afterCall(request, response, error);
            } catch (Throwable t) {
                LOG.warn("erpc interceptor afterCall error: ", l -> l.threw(t));
            }
        });
    }

    /**
     * 本地取消一次在途调用。
     *
     * <p>★ <b>线上没有 CANCEL 帧</b>（eRPC v1 的帧格式不变）⇒ 服务端不会立刻
     * 停下手上的活；本地效果是调用方立刻拿到失败、取消信号被 trip。</p>
     *
     * @param requestId 请求号（{@link RpcContext#requestId()}）
     * @param cause     取消原因
     * @return true 表示确实取消掉了一次在途调用
     */
    public boolean cancel(int requestId, Throwable cause) {
        for (ErpcNodePool pool : registry.pools()) {
            if (pool.cancel(requestId, cause)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 当前节点池（诊断 / 测试用）。
     */
    public List<ErpcNodePool> pools() {
        return registry.pools();
    }

    /**
     * 当前节点数（诊断 / 测试用）。
     */
    public int nodeCount() {
        return registry.nodeCount();
    }

    public ErpcNodeRegistry registry() {
        return registry;
    }

    @Override
    public void close() {
        registry.close();
    }
}
