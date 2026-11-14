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

import io.edap.grpc.client.GrpcChannel;
import io.edap.grpc.client.GrpcClientCall;
import io.edap.rpc.StreamObserver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * edap 侧的基准客户端 —— 一条 {@link GrpcChannel}，所有 worker 线程共享。
 */
public final class EdapBenchClient implements AutoCloseable {

    /** 调用的默认超时（毫秒） */
    private static final int TIMEOUT_MILLIS = 30_000;

    private final GrpcChannel channel;

    public EdapBenchClient(String host, int port) throws IOException {
        channel = new GrpcChannel(host, port, 30_000, 10_000);
    }

    /**
     * 一次 unary 调用。
     *
     * @return 服务端的响应（用于校验和）
     */
    public Payload unary(Payload request) {
        return channel.unary(BenchConstants.PATH, Payload.class, Payload.class, request);
    }

    /**
     * 一次 unary 调用，指定 {@code :path} —— 兼容性测试要打「服务端没有的方法」。
     */
    public Payload unary(String path, Payload request) {
        return channel.unary(path, Payload.class, Payload.class, request);
    }

    /**
     * 一次 unary 调用，<b>保留调用句柄</b> —— 兼容性测试要读响应头与 trailers。
     *
     * @param headers 额外的请求头（null 表示不带）
     */
    public GrpcClientCall unaryCall(Payload request, Map<String, String> headers) {
        GrpcClientCall call = channel.newCall(BenchConstants.PATH, Payload.class, Payload.class, null, headers);
        call.sendMessage(request);
        call.halfClose();
        call.awaitCompletion(TIMEOUT_MILLIS);
        return call;
    }

    /**
     * 服务端流式 —— 一个请求，多条响应。
     */
    public List<Payload> serverStream(Payload request) {
        List<Payload> responses = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        GrpcClientCall call = channel.serverStream(BenchConstants.PATH_SERVER_STREAM,
                Payload.class, Payload.class, request, collector(responses, done));
        call.awaitCompletion(TIMEOUT_MILLIS);
        awaitQuietly(done);
        return responses;
    }

    /**
     * 客户端流式 —— 多条请求，一条响应。
     */
    public Payload clientStream(List<Payload> requests) {
        List<Payload> responses = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        GrpcClientCall call = channel.clientStream(BenchConstants.PATH_CLIENT_STREAM,
                Payload.class, Payload.class, collector(responses, done));
        StreamObserver<Object> requestObserver = call.requestObserver();
        for (Payload request : requests) {
            requestObserver.onNext(request);
        }
        requestObserver.onCompleted();
        call.awaitCompletion(TIMEOUT_MILLIS);
        awaitQuietly(done);
        if (responses.isEmpty()) {
            throw new IllegalStateException("客户端流式没有收到响应消息");
        }
        return responses.get(0);
    }

    /**
     * 双向流式 —— 多条请求，多条响应。
     */
    public List<Payload> bidi(List<Payload> requests) {
        List<Payload> responses = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        GrpcClientCall call = channel.bidi(BenchConstants.PATH_BIDI,
                Payload.class, Payload.class, collector(responses, done));
        StreamObserver<Object> requestObserver = call.requestObserver();
        for (Payload request : requests) {
            requestObserver.onNext(request);
        }
        requestObserver.onCompleted();
        call.awaitCompletion(TIMEOUT_MILLIS);
        awaitQuietly(done);
        return responses;
    }

    /**
     * 把响应收进列表，并在流结束时放信号量。
     */
    private static StreamObserver<Payload> collector(List<Payload> responses, CountDownLatch done) {
        return new StreamObserver<Payload>() {
            @Override
            public void onNext(Payload value) {
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

    @Override
    public void close() {
        channel.close();
    }
}
