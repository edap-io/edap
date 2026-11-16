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
import io.edap.erpc.LoadBalancer;
import io.edap.erpc.RpcInvoker;
import io.edap.erpc.RpcRequest;
import io.edap.erpc.RpcResponse;
import io.edap.erpc.client.loadbalance.RandomLoadBalancer;
import io.edap.erpc.codec.ErpcCodec;

import java.io.Closeable;
import java.util.List;
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

    private final ErpcNodeRegistry registry;
    private final ErpcCodec serializer;
    private final LoadBalancer<Object> loadBalancer;
    private final ErpcClientOptions options;

    /** 链路号发生器。 */
    private final AtomicLong traceIds = new AtomicLong();

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
        RpcRequest request = new RpcRequest();
        request.setMethodId(methodId);
        // ★ 交对象而不是编码好的字节：编码推迟到出站写帧那一刻
        //   ⇒ 载荷在网络上只被拷一次（与 gRPC 的帧头内联同一形态）。
        request.setRequestObject(req);
        request.setTraceId(traceIds.incrementAndGet());
        request.setTimeoutMillis(timeoutMillis);
        RpcInvoker invoker = loadBalancer.select(request);
        RpcResponse response = invoker.invoke(request);
        if (!response.isSuccess()) {
            throw new ErpcException("eRPC 调用失败: methodId=" + methodId);
        }
        byte[] bytes = response.getResponse();
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        return serializer.decode(bytes, 0, bytes.length, respType);
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
