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

import io.edap.Edap;
import io.edap.erpc.ErpcMethod;
import io.edap.erpc.ErpcRequest;
import io.edap.erpc.ErpcResponse;
import io.edap.erpc.codec.ErpcCodec;
import io.edap.erpc.server.ErpcServer;
import io.edap.pool.SimpleFastBufPool;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;

/**
 * eRPC 侧的基准服务端。
 *
 * <p>复用 edap 自己的 eRPC 服务端（{@link ErpcServer}）与 NIO 运行时（{@link Edap}），
 * 业务逻辑与 {@link EdapBenchServer} <b>逐行对齐</b>：name 加前缀、value ×2、blob 原样回显。</p>
 *
 * <p>★ <b>为什么要对齐业务逻辑</b>：本基准比的是<b>协议与传输层</b>，
 * 不是业务实现。业务越轻，两边的差距越能干净地归到协议上。
 * 两边用的是<b>同一套</b> protobuf 编解码实现（edap 自研的 {@code ProtoBufCodecRegister}）
 * ⇒ 编解码成本在对比里互相抵消，剩下的就是帧格式、拷贝次数、流控、线程模型的差异。</p>
 */
public final class ErpcBenchServer implements AutoCloseable {

    private final ErpcServer server;
    private final Edap edap;
    private final int port;

    public ErpcBenchServer(int port) {
        this(port, 0);
    }

    public ErpcBenchServer(int port, int workerThreads) {
        this(port, workerThreads, false);
    }

    /**
     * @param workerThreads eRPC 业务线程池的线程数；0 ⇒ 用服务端默认（{@code max(4, 核数×2)}）
     * @param inlineDispatch 诊断开关：true ⇒ IO 线程直调业务（gRPC 的模型），只用于归因实验
     */
    public ErpcBenchServer(int port, int workerThreads, boolean inlineDispatch) {
        this.port = port;

        Map<Integer, ErpcMethod> methods = new HashMap<>();
        methods.put(BenchConstants.ERPC_METHOD_ID, new ErpcMethod(
            BenchConstants.ERPC_METHOD_ID,
            BenchConstants.SERVICE_NAME,
            BenchConstants.ERPC_METHOD_NAME,
            (req, resp) -> {
                byte[] raw = req.getPayload();
                // ★ 用帧头 codec 现场解析出来的序列化器解码 —— 与容器生成的 handler 同一形态
                ErpcCodec serializer = req.getSerializer();
                Payload request = serializer.decode(raw, 0, raw.length, Payload.class);
                Payload response = new Payload();
                response.setName(BenchConstants.NAME_PREFIX + request.getName());
                response.setValue(request.getValue() * 2);
                // blob 原样回显：解码器已经复制出新的数组，直接复用，不再多拷一次
                response.setBlob(request.getBlob());
                // ★ 交<b>对象</b>而不是编码好的字节 —— 编码推迟到出站写帧那一刻，
                //   载荷在网络上只被拷一次（与容器生成的 handler 同一形态）。
                resp.setResult(response);
            },
            Payload.class, Payload.class));

        server = new ErpcServer();
        server.setBufPool(new SimpleFastBufPool());
        if (workerThreads > 0) {
            server.setWorkerThreads(workerThreads);
        }
        server.setInlineDispatch(inlineDispatch);
        server.setMethods(methods);
        server.listen(port);

        edap = new Edap();
        edap.addServer(server);
    }

    /**
     * 启动并等到端口开始监听。
     */
    public void start() throws IOException {
        edap.run();
        EdapBenchServer.awaitListening(port);
    }

    public int port() {
        return port;
    }

    @Override
    public void close() {
        edap.stop();
    }

    /**
     * 取一个空闲端口。
     */
    public static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

}
