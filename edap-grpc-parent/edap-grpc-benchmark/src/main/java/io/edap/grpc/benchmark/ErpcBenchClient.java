/*
 * Copyright 2026 The edap Project
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

package io.edap.grpc.benchmark;

import io.edap.erpc.client.ErpcClient;
import io.edap.erpc.client.ErpcClientOptions;
import io.edap.erpc.client.ErpcNode;
import io.edap.erpc.client.ErpcNodeRegistry;
import io.edap.erpc.client.StaticErpcNodeProvider;
import io.edap.erpc.client.loadbalance.RoundRobinLoadBalancer;
import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.codec.protobuf.ProtobufErpcCodec;

import java.io.IOException;
import java.util.Collections;

/**
 * eRPC 侧的基准客户端 —— 一个节点、<b>一条连接</b>，所有 worker 线程共享。
 *
 * <p>★ <b>连接数必须与 gRPC 侧对齐</b>：gRPC 侧是「一条 {@code GrpcChannel} 被 N 个
 * worker 共享」，所以 eRPC 侧的连接池也设成 {@code poolSize=1}。
 * 池开大了就变成「每 worker 一条连接」，那比的是连接数而不是协议。</p>
 *
 * <p>节点级均衡用轮询（只有一个节点时与随机等价，但轮询是确定的，便于复现）。</p>
 */
public final class ErpcBenchClient implements AutoCloseable {

    /** 调用的默认超时（毫秒）—— 与 gRPC 侧一致。 */
    private static final int TIMEOUT_MILLIS = 30_000;

    private final ErpcClient client;

    public ErpcBenchClient(String host, int port) {
        this(host, port, new ProtobufErpcCodec());
    }

    /**
     * ★ <b>指定序列化器的构造器</b> —— 用来对比同一套 eRPC 传输下的不同载荷格式。
     *
     * <p>序列化器决定线上载荷的格式（帧头里的 codec 字节）⇒ 服务端按帧协商，
     * <b>服务端一行都不用改</b>。</p>
     *
     * <p>★ <b>同一个实例</b>必须同时交给「节点池」与「客户端」：
     * 前者建连接时用它填帧头的 codec，后者用它解响应帧。</p>
     *
     * @param codec 载荷编解码器（如 {@code ProtobufErpcCodec} / {@code FastProtobufCodec}）
     */
    public ErpcBenchClient(String host, int port, ErpcCodec codec) {
        ErpcClientOptions options = ErpcClientOptions.defaults()
            .poolSize(1)
            .connectTimeoutMillis(TIMEOUT_MILLIS)
            .callTimeoutMillis(TIMEOUT_MILLIS);
        ErpcNode node = new ErpcNode(host, port);
        StaticErpcNodeProvider provider = new StaticErpcNodeProvider(Collections.singletonList(node));
        ErpcNodeRegistry registry = new ErpcNodeRegistry(provider, codec, options);
        this.client = new ErpcClient(registry, codec,
            new RoundRobinLoadBalancer(registry::pools), options);
    }

    /**
     * 一次一元调用。
     *
     * @return 服务端的响应（用于校验和）
     */
    public Payload unary(Payload request) {
        return client.invoke(BenchConstants.ERPC_METHOD_ID, request, Payload.class, TIMEOUT_MILLIS);
    }

    @Override
    public void close() {
        client.close();
    }
}
