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
import io.edap.grpc.GrpcCodec;
import io.edap.grpc.GrpcMethod;
import io.edap.grpc.GrpcStatus;
import io.edap.grpc.server.GrpcServer;
import io.edap.pool.SimpleFastBufPool;
import io.edap.protobuf.annotation.ProtoGrpc;
import io.edap.rpc.StreamObserver;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * edap 侧的基准服务端。
 *
 * <p>复用 edap 自己的 gRPC 服务端（{@link GrpcServer}）与 NIO 运行时（{@link Edap}），
 * 业务逻辑与 {@link GoogleBenchServer} <b>逐行对齐</b>：name 加前缀、value ×2、blob 原样回显。</p>
 */
public final class EdapBenchServer implements AutoCloseable {

    private final GrpcServer server;
    private final Edap edap;
    private final int port;

    /**
     * 是否把请求里的 {@link BenchConstants#ECHO_HEADER} 原样回显到响应头。
     *
     * <p>默认关 —— 性能基准的热路径上不要多做事；兼容性测试才打开。</p>
     */
    private final boolean echoMetadata;

    public EdapBenchServer(int port) {
        this(port, false);
    }

    public EdapBenchServer(int port, boolean echoMetadata) {
        this.port = port;
        this.echoMetadata = echoMetadata;

        Map<String, GrpcMethod> methods = new HashMap<>();
        methods.put(BenchConstants.PATH, new GrpcMethod(BenchConstants.PATH, call -> {
            byte[] raw = call.requestBytes();
            Payload req = GrpcCodec.decode(Payload.class, raw, 0, raw.length);
            Payload resp = new Payload();
            resp.setName(BenchConstants.NAME_PREFIX + req.getName());
            resp.setValue(req.getValue() * 2);
            // blob 原样回显：edap 的解码器（ByteArrayReader.readBytes）每次都会复制出新的数组，
            // 所以这里直接复用，不再多拷一次 —— 与 google 侧共享 ByteString 的做法等价。
            resp.setBlob(req.getBlob());
            if (echoMetadata) {
                String echo = call.metadata().get(BenchConstants.ECHO_HEADER);
                if (echo != null) {
                    call.responseMetadata().set(BenchConstants.ECHO_HEADER, echo);
                }
            }
            call.sendOne(resp);
            call.close(GrpcStatus.OK, null);
        }, ProtoGrpc.MethodType.UNARY, Payload.class, Payload.class));

        // ---- 客户端流式：多个请求消息 → 一个响应消息（求和 + 回显最后一条） ----
        methods.put(BenchConstants.PATH_CLIENT_STREAM, new GrpcMethod(BenchConstants.PATH_CLIENT_STREAM,
                call -> call.setRequestObserver(new StreamObserver<Object>() {
                    private int sum;
                    private String lastName;
                    private byte[] lastBlob;

                    @Override
                    public void onNext(Object value) {
                        Payload req = (Payload) value;
                        sum += req.getValue();
                        lastName = req.getName();
                        lastBlob = req.getBlob();
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                        Payload resp = new Payload();
                        resp.setName(BenchConstants.NAME_PREFIX + (lastName == null ? "" : lastName));
                        resp.setValue(sum);
                        resp.setBlob(lastBlob);
                        call.sendOne(resp);
                        call.close(GrpcStatus.OK, null);
                    }
                }), ProtoGrpc.MethodType.CLIENT_STREAM, Payload.class, Payload.class));

        // ---- 服务端流式：一个请求消息 → 多个响应消息 ----
        methods.put(BenchConstants.PATH_SERVER_STREAM, new GrpcMethod(BenchConstants.PATH_SERVER_STREAM,
                call -> {
                    byte[] raw = call.requestBytes();
                    Payload req = GrpcCodec.decode(Payload.class, raw, 0, raw.length);
                    for (int i = 0; i < BenchConstants.SERVER_STREAM_COUNT; i++) {
                        Payload resp = new Payload();
                        resp.setName(BenchConstants.NAME_PREFIX + req.getName());
                        resp.setValue(req.getValue() * 2 + i);
                        resp.setBlob(req.getBlob());
                        call.sendOne(resp);
                    }
                    call.close(GrpcStatus.OK, null);
                }, ProtoGrpc.MethodType.SERVER_STREAM, Payload.class, Payload.class));

        // ---- 双向流式：收到一条就回一条 ----
        methods.put(BenchConstants.PATH_BIDI, new GrpcMethod(BenchConstants.PATH_BIDI,
                call -> call.setRequestObserver(new StreamObserver<Object>() {
                    @Override
                    public void onNext(Object value) {
                        Payload req = (Payload) value;
                        Payload resp = new Payload();
                        resp.setName(BenchConstants.NAME_PREFIX + req.getName());
                        resp.setValue(req.getValue() * BenchConstants.BIDI_MULTIPLIER);
                        resp.setBlob(req.getBlob());
                        call.sendOne(resp);
                    }

                    @Override
                    public void onError(Throwable t) {
                    }

                    @Override
                    public void onCompleted() {
                        call.close(GrpcStatus.OK, null);
                    }
                }), ProtoGrpc.MethodType.BIDIRECTIONAL, Payload.class, Payload.class));

        server = new GrpcServer();
        server.setBufPool(new SimpleFastBufPool());
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
        awaitListening(port);
    }

    public int port() {
        return port;
    }

    @Override
    public void close() {
        edap.stop();
    }

    static void awaitListening(int port) throws IOException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException e) {
                try {
                    TimeUnit.MILLISECONDS.sleep(20);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("等待端口监听被中断", ie);
                }
            }
        }
        throw new IllegalStateException("端口 " + port + " 未在 5s 内开始监听");
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
