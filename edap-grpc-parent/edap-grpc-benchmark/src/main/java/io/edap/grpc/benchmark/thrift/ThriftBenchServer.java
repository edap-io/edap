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
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.protocol.TCompactProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.server.TThreadedSelectorServer;
import org.apache.thrift.server.TServer;
import org.apache.thrift.transport.TNonblockingServerSocket;
import org.apache.thrift.transport.TNonblockingServerTransport;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TServerTransport;
import org.apache.thrift.transport.TTransportException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Thrift 侧的基准服务端。
 *
 * <p>业务逻辑与其它三臂<b>逐行对齐</b>：name 加前缀、value ×2、blob 原样回显。</p>
 *
 * <p>★ <b>两种服务端形态</b>（都是 thrift 官方的）：</p>
 * <table border="1">
 *   <caption>服务端形态</caption>
 *   <tr><th>形态</th><th>实现</th><th>线程模型</th><th>连接内并发</th></tr>
 *   <tr><td>{@code tpool}（默认）</td>
 *       <td>{@link TThreadPoolServer}</td>
 *       <td>阻塞 socket，<b>1 连接 = 1 线程</b>（线程池）</td>
 *       <td><b>1 个在途请求</b></td></tr>
 *   <tr><td>{@code tselector}</td>
 *       <td>{@link TThreadedSelectorServer}</td>
 *       <td>NIO selector + worker 池</td>
 *       <td><b>1 个在途请求</b></td></tr>
 * </table>
 *
 * <p><b>「连接内并发 = 1」是 thrift 架构的固有约束，不是本基准的人为削弱</b>，
 * 源码依据：</p>
 * <ul>
 *   <li>{@code TThreadPoolServer.WorkerProcess.run()}：{@code while(true) processor.process(in, out);}
 *       —— 同一条连接上的请求<b>严格串行</b>；</li>
 *   <li>{@code AbstractNonblockingServer.FrameBuffer.read()}：帧读满后
 *       {@code selectionKey_.interestOps(0)}（<b>摘掉读兴趣</b>），
 *       直到 {@code responseReady()} 才重新挂上 ⇒ 同一条连接不会在响应未写出时读下一帧。</li>
 * </ul>
 *
 * <p>⇒ N 路并发在途请求 ⇒ Thrift 侧必须用 <b>N 条连接</b>（客户端侧的连接池负责）。</p>
 */
public final class ThriftBenchServer implements AutoCloseable {

    /** 服务端形态：线程池（阻塞 socket）。 */
    public static final String MODE_TPOOL = "tpool";

    /** 服务端形态：NIO selector + worker 池。 */
    public static final String MODE_TSELECTOR = "tselector";

    /** 协议：二进制。 */
    public static final String PROTOCOL_BINARY = "binary";

    /** 协议：紧凑。 */
    public static final String PROTOCOL_COMPACT = "compact";

    private final TServer server;
    private final Thread serveThread;
    private final int port;

    /**
     * @param port          监听端口
     * @param mode          服务端形态（{@link #MODE_TPOOL} / {@link #MODE_TSELECTOR}）
     * @param protocol      协议（{@link #PROTOCOL_BINARY} / {@link #PROTOCOL_COMPACT}）
     * @param workerThreads worker 线程数（&lt;=0 ⇒ 取 {@code max(8, 核数×2)}）
     */
    public ThriftBenchServer(int port, String mode, String protocol, int workerThreads)
        throws IOException, TTransportException {
        this.port = port;
        TProtocolFactory protocolFactory = protocolFactory(protocol);
        ThriftBenchService.Processor processor = new ThriftBenchService.Processor(new ThriftBenchService.Iface() {
            @Override
            public ThriftPayload unary(ThriftPayload request) {
                ThriftPayload response = new ThriftPayload();
                response.setName(BenchConstants.NAME_PREFIX + request.getName());
                response.setValue(request.getValue() * 2);
                // blob 原样回显
                response.setBlob(request.getBlob());
                return response;
            }
        });

        int workers = workerThreads > 0 ? workerThreads : Math.max(8, Runtime.getRuntime().availableProcessors() * 2);

        if (MODE_TSELECTOR.equals(mode)) {
            TNonblockingServerTransport transport = new TNonblockingServerSocket(
                new InetSocketAddress("127.0.0.1", port), 512);
            TThreadedSelectorServer.Args args = new TThreadedSelectorServer.Args(transport)
                .processor(processor)
                .protocolFactory(protocolFactory)
                .workerThreads(workers);
            server = new TThreadedSelectorServer(args);
        } else {
            TServerTransport transport = new TServerSocket(
                new InetSocketAddress("127.0.0.1", port), 512);
            TThreadPoolServer.Args args = new TThreadPoolServer.Args(transport)
                .processor(processor)
                .protocolFactory(protocolFactory)
                .minWorkerThreads(Math.min(workers, 8))
                // ★ thrift 的线程池是「1 连接 = 1 线程、线程随连接存活」⇒ 上限必须 ≥ 并发连接数
                .maxWorkerThreads(Math.max(workers, 64));
            server = new TThreadPoolServer(args);
        }

        serveThread = new Thread(() -> {
            try {
                server.serve();
            } catch (Throwable ignored) {
                // 服务端停止时抛出的异常不影响基准结果
            }
        }, "thrift-bench-server");
        serveThread.setDaemon(true);
    }

    /**
     * 协议工厂。
     */
    public static TProtocolFactory protocolFactory(String protocol) {
        if (PROTOCOL_COMPACT.equals(protocol)) {
            return new TCompactProtocol.Factory();
        }
        return new TBinaryProtocol.Factory();
    }

    /**
     * 启动服务端并等到端口开始监听。
     */
    public void start() throws IOException {
        serveThread.start();
        awaitListening(port);
    }

    public int port() {
        return port;
    }

    @Override
    public void close() {
        server.stop();
        try {
            serveThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 取一个空闲端口。
     */
    public static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    /**
     * 等端口开始监听。
     */
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
}
