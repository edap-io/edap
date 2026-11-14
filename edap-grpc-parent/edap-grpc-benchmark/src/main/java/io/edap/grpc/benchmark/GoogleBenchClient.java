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

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * google（grpc-java）侧的基准客户端 —— 一条 {@link ManagedChannel}，所有 worker 线程共享。
 *
 * <p>blocking stub 是线程安全的（每次 RPC 内部各自建 {@code ClientCall}），
 * 与 edap 侧「一条连接 + 多线程发起调用」的口径一致。</p>
 */
public final class GoogleBenchClient implements AutoCloseable {

    /** 调用的默认超时（毫秒） */
    private static final int TIMEOUT_MILLIS = 30_000;

    /**
     * 本端通告的<b>流控窗口</b>（流级 + 连接级）。
     *
     * <p>★ grpc-java 的默认是 1MB，而 edap 客户端默认给 8MB ——
     * 不对齐的话两边给服务端的流控额度差 8 倍，2×2 矩阵就不是同一口径了。
     * 用 {@code -Dbench.google.connWindow=<字节>} 覆盖。</p>
     */
    private static final int FLOW_CONTROL_WINDOW =
            Integer.getInteger("bench.google.connWindow", 1024 * 1024);

    private final ManagedChannel channel;
    private final BenchServiceGrpc.BenchServiceBlockingStub stub;
    private final BenchServiceGrpc.BenchServiceStub asyncStub;

    public GoogleBenchClient(String host, int port) throws IOException {
        // ★ 必须用 NettyChannelBuilder —— ManagedChannelBuilder 没有流控窗口的设置入口。
        //   flowControlWindow 同时设置流级与连接级窗口（grpc-java 的语义）。
        channel = io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
                .forAddress(host, port)
                .usePlaintext()
                .flowControlWindow(FLOW_CONTROL_WINDOW)
                .build();
        stub = BenchServiceGrpc.newBlockingStub(channel);
        asyncStub = BenchServiceGrpc.newStub(channel);
    }

    /**
     * 一次 unary 调用。
     *
     * @return 服务端的响应（用于校验和）
     */
    public Bench.Payload unary(Bench.Payload request) {
        return stub.unary(request);
    }

    /**
     * 一次 unary 调用，<b>保留响应头与 trailers</b> —— 兼容性测试要读它们。
     *
     * @param headers         额外的请求头（null 表示不带）
     * @param capturedHeaders 响应头的落点（null 表示不捕获）
     * @param capturedTrailers trailers 的落点（null 表示不捕获）
     */
    public Bench.Payload unaryCall(Bench.Payload request, Map<String, String> headers,
                                   AtomicReference<Metadata> capturedHeaders,
                                   AtomicReference<Metadata> capturedTrailers) {
        BenchServiceGrpc.BenchServiceBlockingStub withCapture = stub;
        if (capturedHeaders != null || capturedTrailers != null) {
            withCapture = withCapture.withInterceptors(MetadataUtils.newCaptureMetadataInterceptor(
                    capturedHeaders, capturedTrailers));
        }
        if (headers != null && !headers.isEmpty()) {
            withCapture = withCapture.withInterceptors(
                    MetadataUtils.newAttachHeadersInterceptor(toMetadata(headers)));
        }
        return withCapture.unary(request);
    }

    /**
     * 服务端流式 —— 一个请求，多条响应。
     */
    public List<Bench.Payload> serverStream(Bench.Payload request) {
        Iterator<Bench.Payload> iterator = stub.serverStream(request);
        List<Bench.Payload> responses = new ArrayList<>();
        while (iterator.hasNext()) {
            responses.add(iterator.next());
        }
        return responses;
    }

    /**
     * 客户端流式 —— 多条请求，一条响应。
     *
     * <p>★ grpc-java 的<b>阻塞</b> stub 没有 {@code clientStream}（阻塞 stub 只支持一元与服务端流式），
     * 所以客户端流式与双向流式都必须走异步 stub。</p>
     */
    public Bench.Payload clientStream(List<Bench.Payload> requests) {
        List<Bench.Payload> responses = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        StreamObserver<Bench.Payload> responseObserver = collector(responses, done);
        StreamObserver<Bench.Payload> requestObserver = asyncStub.clientStream(responseObserver);
        for (Bench.Payload request : requests) {
            requestObserver.onNext(request);
        }
        requestObserver.onCompleted();
        awaitQuietly(done);
        if (responses.isEmpty()) {
            throw new IllegalStateException("客户端流式没有收到响应消息");
        }
        return responses.get(0);
    }

    /**
     * 双向流式 —— 多条请求，多条响应。
     *
     * <p>★ grpc-java 的<b>阻塞</b> stub 做不了 bidi（拿不到请求观察者），
     * 所以这里用异步 stub + {@link ClientResponseObserver} ——
     * {@code beforeStart} 会把请求观察者交给调用方。</p>
     */
    public List<Bench.Payload> bidi(List<Bench.Payload> requests) {
        List<Bench.Payload> responses = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ClientCallStreamObserver<Bench.Payload>> requestStream = new AtomicReference<>();
        StreamObserver<Bench.Payload> responseObserver = new ClientResponseObserver<Bench.Payload, Bench.Payload>() {
            @Override
            public void beforeStart(ClientCallStreamObserver<Bench.Payload> rs) {
                requestStream.set(rs);
            }

            @Override
            public void onNext(Bench.Payload value) {
                responses.add(value);
            }

            @Override
            public void onError(Throwable t) {
                done.countDown();
            }

            @Override
            public void onCompleted() {
                done.countDown();
            }
        };
        asyncStub.bidi(responseObserver);
        ClientCallStreamObserver<Bench.Payload> rs = requestStream.get();
        if (rs == null) {
            throw new IllegalStateException("grpc-java 的 bidi 没有拿到请求观察者");
        }
        for (Bench.Payload request : requests) {
            rs.onNext(request);
        }
        rs.onCompleted();
        awaitQuietly(done);
        return responses;
    }

    /**
     * 把响应收进列表，并在流结束时放信号量。
     */
    private static StreamObserver<Bench.Payload> collector(List<Bench.Payload> responses, CountDownLatch done) {
        return new StreamObserver<Bench.Payload>() {
            @Override
            public void onNext(Bench.Payload value) {
                responses.add(value);
            }

            @Override
            public void onError(Throwable t) {
                done.countDown();
            }

            @Override
            public void onCompleted() {
                done.countDown();
            }
        };
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 把普通 map 转成 grpc-java 的 {@link Metadata}。
     */
    static Metadata toMetadata(Map<String, String> headers) {
        Metadata metadata = new Metadata();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            Metadata.Key<String> key = Metadata.Key.of(entry.getKey().toLowerCase(),
                    Metadata.ASCII_STRING_MARSHALLER);
            metadata.put(key, entry.getValue());
        }
        return metadata;
    }

    @Override
    public void close() {
        channel.shutdownNow();
        try {
            channel.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
