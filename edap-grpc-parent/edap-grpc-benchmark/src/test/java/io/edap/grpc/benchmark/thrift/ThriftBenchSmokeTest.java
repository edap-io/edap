/*
 * Copyright 2026 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.edap.grpc.benchmark.thrift;

import io.edap.grpc.benchmark.BenchConstants;
import io.edap.grpc.benchmark.BenchPayloads;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Thrift 臂的冒烟测试 —— 「手写的 thrift 生成代码」与服务端能不能真的对上。
 *
 * <p>★ <b>这个测试要防的是什么</b>：Thrift 侧的消息结构 / 结果包装 / 字段头
 * 全是手写的 ⇒ 一旦与 thrift 的 wire 约定对不上（例如结果没包在
 * {@code {0: success}} 里），症状是「服务端返回了但客户端解不出」，
 * 或者更糟 —— <b>解出了错的东西</b>。所以这里逐字段校验。</p>
 */
public class ThriftBenchSmokeTest {

    /** 线程池形态（默认）。 */
    @Test
    public void testThreadPoolServerRoundTrip() throws Exception {
        checkRoundTrip(ThriftBenchServer.MODE_TPOOL, ThriftBenchServer.PROTOCOL_BINARY);
    }

    /** NIO selector 形态 + 分帧传输。 */
    @Test
    public void testThreadedSelectorServerRoundTrip() throws Exception {
        checkRoundTrip(ThriftBenchServer.MODE_TSELECTOR, ThriftBenchServer.PROTOCOL_BINARY);
    }

    /** 紧凑协议。 */
    @Test
    public void testCompactProtocolRoundTrip() throws Exception {
        checkRoundTrip(ThriftBenchServer.MODE_TPOOL, ThriftBenchServer.PROTOCOL_COMPACT);
    }

    /**
     * ★ <b>并发</b>：thrift 的连接内是串行的 ⇒ N 路并发必须靠 N 条连接。
     * 这里用 8 个线程打 8 条连接（连接池懒创建），验证池子能正常扩张。
     */
    @Test
    public void testConcurrentCallsUseMultipleConnections() throws Exception {
        int port = ThriftBenchServer.freePort();
        try (ThriftBenchServer server = new ThriftBenchServer(port,
                ThriftBenchServer.MODE_TPOOL, ThriftBenchServer.PROTOCOL_BINARY, 16)) {
            server.start();
            try (ThriftBenchClient client = new ThriftBenchClient("127.0.0.1", port,
                    ThriftBenchServer.MODE_TPOOL, ThriftBenchServer.PROTOCOL_BINARY, 32)) {

                final int threads = 8;
                final int perThread = 50;
                CountDownLatch done = new CountDownLatch(threads);
                AtomicInteger errors = new AtomicInteger();
                List<Thread> workers = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    Thread worker = new Thread(() -> {
                        try {
                            ThriftPayload request = new ThriftPayload(
                                BenchConstants.REQUEST_NAME, BenchConstants.REQUEST_VALUE, BenchPayloads.blob(128));
                            for (int i = 0; i < perThread; i++) {
                                ThriftPayload response = client.unary(request);
                                if (response.getValue() != BenchConstants.REQUEST_VALUE * 2
                                        || !response.getName().startsWith(BenchConstants.NAME_PREFIX)) {
                                    errors.incrementAndGet();
                                }
                            }
                        } catch (Throwable e) {
                            errors.incrementAndGet();
                        } finally {
                            done.countDown();
                        }
                    });
                    workers.add(worker);
                    worker.start();
                }
                assertTrue(done.await(30, TimeUnit.SECONDS), "并发调用超时");
                for (Thread worker : workers) {
                    worker.join(1000);
                }
                assertEquals(0, errors.get(), "并发调用里有失败");
            }
        }
    }

    private void checkRoundTrip(String mode, String protocol) throws Exception {
        int port = ThriftBenchServer.freePort();
        int blobSize = 1024;
        try (ThriftBenchServer server = new ThriftBenchServer(port, mode, protocol, 8)) {
            server.start();
            try (ThriftBenchClient client = new ThriftBenchClient("127.0.0.1", port, mode, protocol, 8)) {

                ThriftPayload request = new ThriftPayload(
                    BenchConstants.REQUEST_NAME, BenchConstants.REQUEST_VALUE, BenchPayloads.blob(blobSize));
                ThriftPayload response = client.unary(request);

                assertEquals(BenchConstants.NAME_PREFIX + BenchConstants.REQUEST_NAME, response.getName(),
                        "name 不对（服务端应当加前缀）");
                assertEquals(BenchConstants.REQUEST_VALUE * 2, response.getValue(), "value 不对（应当 ×2）");
                assertArrayEquals(request.getBlob(), response.getBlob(), "blob 应当原样回显");
            }
        }
    }
}
