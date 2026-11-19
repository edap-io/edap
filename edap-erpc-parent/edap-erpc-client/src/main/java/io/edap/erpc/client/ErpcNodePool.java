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
import io.edap.erpc.RpcInvoker;
import io.edap.erpc.ErpcRequest;
import io.edap.erpc.ErpcResponse;
import io.edap.erpc.codec.ErpcCodec;
import io.edap.rpc.RpcContext;

import java.io.Closeable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * 一个节点的<b>连接池</b> —— N 条长连接（{@link ErpcChannel}），调用时 round-robin 取一条。
 *
 * <p><b>两层负载均衡</b>里的第二层：</p>
 * <ul>
 *   <li>第一层（节点级）：{@link io.edap.erpc.client.loadbalance} 在多个<b>节点</b>之间选；</li>
 *   <li>第二层（连接级）：本类在同一个节点的 N 条<b>连接</b>之间选。</li>
 * </ul>
 *
 * <p>★ <b>连接是懒建的</b>：节点刚被注册中心推过来时不去连（那会阻塞注册中心的
 * 回调线程），第一次用到这个节点时才连。连接断了也在这里惰性重建。</p>
 */
public final class ErpcNodePool implements RpcInvoker, Closeable {

    private final ErpcNode node;
    private final ErpcCodec serializer;
    private final ErpcClientOptions options;

    /** 池里的连接槽位（懒建）。 */
    private final AtomicReferenceArray<ErpcChannel> channels;

    /**
     * 每个槽位一把锁 —— <b>建连接时按槽位隔离</b>。
     *
     * <p>★ 为什么不用一把全局锁：某个节点的连接建得慢（网络抖动）不该拖住
     * 其它槽位的调用；按槽位加锁，不同槽位互不阻塞。</p>
     *
     * <p>★ 为什么需要锁：没有锁时 N 个线程同时首次用到同一个空槽位，
     * 会各自建一条连接（CAS 只有一个赢家），另外 N-1 条刚建好就被关掉 ——
     * 32 个并发首调就会在服务端留下 32 次 TCP 握手。加锁后同一槽位只建一条。</p>
     */
    private final Object[] slotLocks;

    /** round-robin 游标。 */
    private final AtomicInteger cursor = new AtomicInteger();

    public ErpcNodePool(ErpcNode node, ErpcCodec serializer, ErpcClientOptions options) {
        this.node = node;
        this.serializer = serializer;
        this.options = options;
        this.channels = new AtomicReferenceArray<>(options.getPoolSize());
        this.slotLocks = new Object[options.getPoolSize()];
        for (int i = 0; i < slotLocks.length; i++) {
            slotLocks[i] = new Object();
        }
    }

    public ErpcNode node() {
        return node;
    }

    /**
     * 取一条可用连接（round-robin；断开的槽位在这里惰性重建）。
     */
    public ErpcChannel next() {
        int size = channels.length();
        for (int i = 0; i < size; i++) {
            int idx = Math.floorMod(cursor.getAndIncrement(), size);
            ErpcChannel current = channels.get(idx);
            if (current != null && !current.isClosed()) {
                return current;
            }
            synchronized (slotLocks[idx]) {
                // 双重检查：等锁期间别的线程可能已经把这个槽位填好了
                current = channels.get(idx);
                if (current != null && !current.isClosed()) {
                    return current;
                }
                ErpcChannel fresh = connect();
                channels.set(idx, fresh);
                return fresh;
            }
        }
        throw new ErpcException("eRPC 节点没有可用连接: " + node.id());
    }

    private ErpcChannel connect() {
        // ★ 凭据传进去 ⇒ 建连后自动做一次连接级鉴权握手；
        //   握手失败 ⇒ 构造函数抛异常 ⇒ 槽位保持空（不会把坏连接留在池里）。
        return new ErpcChannel(node, serializer, options.getConnectTimeoutMillis(),
            options.getCredential());
    }

    /**
     * 在池里所有连接上发一次调用（{@link RpcInvoker} 这条老接口）。
     */
    @Override
    public ErpcResponse invoke(ErpcRequest request) {
        long timeout = request.getTimeoutMillis() > 0 ? request.getTimeoutMillis() : options.getCallTimeoutMillis();
        return invoke(request, timeout);
    }

    /**
     * 在池里所有连接上发一次调用（带显式超时）。
     */
    public ErpcResponse invoke(ErpcRequest request, long timeoutMillis) {
        ErpcChannel channel = next();
        // ★ 选中连接之后才能填连接级上下文（选连接之前还不知道走哪条连接）
        request.setChannelContext(channel.channelContext());
        byte[] response;
        // ★ 对象优先：走「延迟编码」路径 —— 载荷在出站时只被拷一次。
        //   没有对象（老接口直接塞字节）才走字节路径。
        //   上下文跟着走 ⇒ 它的 deadline 会被编码进帧头 flags 的 bit1~bit7。
        RpcContext ctx = request.getContext();
        Object message = request.getRequestObject();
        if (message != null) {
            response = channel.call(request.getMethodId(), request.getServiceId(),
                message, request.getTraceId(), timeoutMillis, ctx);
        } else {
            byte[] payload = request.getPayload();
            response = channel.call(request.getMethodId(), request.getServiceId(),
                payload, request.getTraceId(), timeoutMillis, ctx);
        }
        ErpcResponse resp = new ErpcResponse();
        resp.setMethodId(request.getMethodId());
        resp.setServiceId(request.getServiceId());
        // ★ 请求号是**传输层**在写帧那一刻分配的（连接内唯一、递增）⇒
        //   它被写进了 ctx（ctx.requestId()），这里回填到请求/应答上 ——
        //   客户端拦截器的 afterCall 阶段就能看到真正的 requestId。
        if (ctx != null) {
            int requestId = (int) ctx.requestId();
            request.setRequestId(requestId);
            resp.setRequestId(requestId);
        }
        resp.setTraceId(request.getTraceId());
        resp.setPayload(response);
        resp.setSuccess(true);
        return resp;
    }

    /**
     * 本地取消一次在途调用（在池里的连接上找）。
     *
     * @param requestId 请求号
     * @param cause     取消原因
     * @return true 表示确实取消掉了一次在途调用
     */
    public boolean cancel(int requestId, Throwable cause) {
        for (int i = 0; i < channels.length(); i++) {
            ErpcChannel c = channels.get(i);
            if (c != null && c.cancel(requestId, cause)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 池里当前的连接数（诊断 / 测试用）。
     */
    public int channelCount() {
        int n = 0;
        for (int i = 0; i < channels.length(); i++) {
            if (channels.get(i) != null) {
                n++;
            }
        }
        return n;
    }

    /**
     * 池里在途调用总数（诊断 / 测试用）。
     */
    public int inFlightCount() {
        int n = 0;
        for (int i = 0; i < channels.length(); i++) {
            ErpcChannel c = channels.get(i);
            if (c != null) {
                n += c.inFlightCount();
            }
        }
        return n;
    }

    /**
     * 池里已关闭的连接数（诊断 / 测试用）—— 节点被摘掉时应当等于连接数。
     */
    public int closedChannelCount() {
        int n = 0;
        for (int i = 0; i < channels.length(); i++) {
            ErpcChannel c = channels.get(i);
            if (c != null && c.isClosed()) {
                n++;
            }
        }
        return n;
    }

    @Override
    public void close() {
        for (int i = 0; i < channels.length(); i++) {
            ErpcChannel c = channels.get(i);
            if (c != null) {
                c.close();
            }
        }
    }

    @Override
    public String toString() {
        return "ErpcNodePool{" + node.id() + ", slots=" + channels.length() + "}";
    }
}
