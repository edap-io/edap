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

import com.google.protobuf.ByteString;
import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * google（grpc-java）侧的基准服务端。
 *
 * <p>业务逻辑与 {@link EdapBenchServer} <b>逐行对齐</b>：name 加前缀、value ×2、blob 原样回显。
 * 两边返回的响应字节完全一致，互通断言才有意义。</p>
 */
public final class GoogleBenchServer implements AutoCloseable {

    /**
     * 请求元数据里的回显 key —— 兼容性测试用。
     */
    public static final Metadata.Key<String> ECHO_KEY =
            Metadata.Key.of(BenchConstants.ECHO_HEADER, Metadata.ASCII_STRING_MARSHALLER);

    private final Server server;
    private final int port;

    public GoogleBenchServer(int port) throws IOException {
        this(port, false);
    }

    /**
     * @param echoMetadata 是否把请求里的 {@link BenchConstants.ECHO_HEADER} 原样回显到响应头
     */
    public GoogleBenchServer(int port, boolean echoMetadata) throws IOException {
        this.port = port;
        ServerServiceDefinition service = new BenchServiceGrpc.BenchServiceImplBase() {
                    @Override
                    public void unary(Bench.Payload request, StreamObserver<Bench.Payload> responseObserver) {
                        Bench.Payload resp = Bench.Payload.newBuilder()
                                .setName(BenchConstants.NAME_PREFIX + request.getName())
                                .setValue(request.getValue() * 2)
                                // blob 原样回显：ByteString 不可变，直接共享，不再多拷一次
                                .setBlob(request.getBlob())
                                .build();
                        responseObserver.onNext(resp);
                        responseObserver.onCompleted();
                    }

                    /**
                     * 客户端流式 —— 语义与 {@link EdapBenchServer} 里同名方法逐条对齐：
                     * 求和 + 回显最后一条请求的 name / blob。
                     *
                     * <p>grpc-java 的客户端流式：方法<b>返回</b>请求观察者（不是 void），
                     * 框架拿到它之后才会把请求消息投递给它。</p>
                     */
                    @Override
                    public StreamObserver<Bench.Payload> clientStream(
                            StreamObserver<Bench.Payload> responseObserver) {
                        final int[] sum = {0};
                        final Bench.Payload[] last = {null};
                        return new StreamObserver<Bench.Payload>() {
                            @Override
                            public void onNext(Bench.Payload value) {
                                sum[0] += value.getValue();
                                last[0] = value;
                            }

                            @Override
                            public void onError(Throwable t) {
                            }

                            @Override
                            public void onCompleted() {
                                Bench.Payload lastReq = last[0];
                                responseObserver.onNext(Bench.Payload.newBuilder()
                                        .setName(BenchConstants.NAME_PREFIX
                                                + (lastReq == null ? "" : lastReq.getName()))
                                        .setValue(sum[0])
                                        .setBlob(lastReq == null
                                                ? ByteString.EMPTY : lastReq.getBlob())
                                        .build());
                                responseObserver.onCompleted();
                            }
                        };
                    }

                    @Override
                    public void serverStream(Bench.Payload request,
                                            StreamObserver<Bench.Payload> responseObserver) {
                        for (int i = 0; i < BenchConstants.SERVER_STREAM_COUNT; i++) {
                            responseObserver.onNext(Bench.Payload.newBuilder()
                                    .setName(BenchConstants.NAME_PREFIX + request.getName())
                                    .setValue(request.getValue() * 2 + i)
                                    .setBlob(request.getBlob())
                                    .build());
                        }
                        responseObserver.onCompleted();
                    }

                    @Override
                    public StreamObserver<Bench.Payload> bidi(StreamObserver<Bench.Payload> responseObserver) {
                        return new StreamObserver<Bench.Payload>() {
                            @Override
                            public void onNext(Bench.Payload value) {
                                responseObserver.onNext(Bench.Payload.newBuilder()
                                        .setName(BenchConstants.NAME_PREFIX + value.getName())
                                        .setValue(value.getValue() * BenchConstants.BIDI_MULTIPLIER)
                                        .setBlob(value.getBlob())
                                        .build());
                            }

                            @Override
                            public void onError(Throwable t) {
                            }

                            @Override
                            public void onCompleted() {
                                responseObserver.onCompleted();
                            }
                        };
                    }
                }.bindService();

        ServerBuilder<?> builder = ServerBuilder.forPort(port);
        builder.addService(service);
        if (echoMetadata) {
            // 服务端自定义响应头的唯一入口是 ServerCall.sendHeaders ——
            // 业务方法拿到的 StreamObserver 没有 setHeaders。
            //
            // ★ 不能在这里直接调 call.sendHeaders(...)：grpc-java 在 ServerCalls 的
            //   onNext 里还会自动发一次头，直接发会撞上 "sendHeaders has already been called"。
            //   正确做法是包一层 ForwardingServerCall，把要加的头「合并」进框架真正发的那一次。
            builder.intercept(new ServerInterceptor() {
                @Override
                public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                        ServerCall<ReqT, RespT> call, Metadata headers,
                        ServerCallHandler<ReqT, RespT> next) {
                    String echo = headers.get(ECHO_KEY);
                    if (echo == null) {
                        return next.startCall(call, headers);
                    }
                    ServerCall<ReqT, RespT> withEcho =
                            new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {
                                @Override
                                public void sendHeaders(Metadata responseHeaders) {
                                    responseHeaders.put(ECHO_KEY, echo);
                                    super.sendHeaders(responseHeaders);
                                }
                            };
                    return next.startCall(withEcho, headers);
                }
            });
        }
        this.server = builder.build().start();
    }

    public void start() {
        // grpc-java 的 ServerBuilder.start() 已经开始监听
    }

    public int port() {
        return port;
    }

    @Override
    public void close() {
        server.shutdownNow();
        try {
            server.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
