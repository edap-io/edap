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

import org.apache.thrift.TException;
import org.apache.thrift.protocol.TProtocol;
import org.apache.thrift.protocol.TProtocolFactory;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;
import org.apache.thrift.transport.layered.TFramedTransport;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Thrift 侧的基准客户端 —— <b>连接池</b>。
 *
 * <p>★ <b>为什么必须有连接池</b>：thrift 的服务端<b>每条连接同一时刻只处理 1 个请求</b>
 * （{@code TThreadPoolServer} 的 worker 是串行循环；{@code TThreadedSelectorServer}
 * 在帧读满后摘掉读兴趣）⇒ 要跑 N 路并发在途请求，就必须有 N 条连接。
 * 这不是本基准的人为设置，是 thrift 架构的固有约束。</p>
 *
 * <p>其它三臂（eRPC / edap-gRPC / grpc-java）都是「1 条连接 + N 路并发复用」，
 * 所以 Thrift 臂在<b>连接数</b>这一维度上天然吃亏 —— 报告里必须把这个口径差异说清楚。</p>
 *
 * <p>连接池的行为：<b>懒创建</b>（第一次借不到就新建）、上限 {@code maxConnections}、
 * 调用结束后归还；<b>连接坏了就丢弃</b>（不把坏连接放回池里）。</p>
 */
public final class ThriftBenchClient implements AutoCloseable {

    /** 调用的默认超时（毫秒）—— 与其它臂一致。 */
    private static final int TIMEOUT_MILLIS = 30_000;

    private final String host;
    private final int port;
    private final TProtocolFactory protocolFactory;

    /** {@code tselector} 模式需要分帧传输（服务端是 NIO 的）。 */
    private final boolean framed;

    private final int maxConnections;

    /** 空闲连接。 */
    private final LinkedBlockingQueue<Connection> idle = new LinkedBlockingQueue<>();

    /** 所有活着的连接（关闭时统一关掉）。 */
    private final Set<Connection> live = ConcurrentHashMap.newKeySet();

    /** 建连接的许可（保证活着的连接数不超过上限）。 */
    private final Semaphore permits;

    public ThriftBenchClient(String host, int port, String mode, String protocol, int maxConnections) {
        this.host = host;
        this.port = port;
        this.protocolFactory = ThriftBenchServer.protocolFactory(protocol);
        this.framed = ThriftBenchServer.MODE_TSELECTOR.equals(mode);
        this.maxConnections = maxConnections <= 0 ? 64 : maxConnections;
        this.permits = new Semaphore(this.maxConnections);
    }

    /**
     * 一次一元调用。
     *
     * <p>调用期间<b>独占</b>一条连接（thrift 的连接内是串行的），
     * 调用结束后归还。</p>
     */
    public ThriftPayload unary(ThriftPayload request) throws TException {
        Connection connection = borrow();
        boolean broken = false;
        try {
            return connection.client.unary(request);
        } catch (TTransportException e) {
            // 传输层出错 ⇒ 这条连接的状态不可信 ⇒ 丢弃，不放回池里
            broken = true;
            throw e;
        } finally {
            release(connection, broken);
        }
    }

    /**
     * 借一条连接（没有空闲的就新建一条）。
     */
    private Connection borrow() throws TException {
        Connection connection = idle.poll();
        if (connection != null) {
            return connection;
        }
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TException("等待 Thrift 连接被中断", e);
        }
        try {
            // 等许可期间可能已经有别人把连接还回来了
            connection = idle.poll();
            if (connection != null) {
                permits.release();
                return connection;
            }
            connection = create();
            live.add(connection);
            return connection;
        } catch (TException e) {
            permits.release();
            throw e;
        }
    }

    /**
     * 归还连接（坏连接直接关掉）。
     */
    private void release(Connection connection, boolean broken) {
        if (broken) {
            live.remove(connection);
            connection.close();
        } else {
            idle.offer(connection);
        }
        permits.release();
    }

    /**
     * 建一条新连接。
     */
    private Connection create() throws TException {
        TSocket socket = new TSocket(host, port);
        socket.setTimeout(TIMEOUT_MILLIS);
        TTransport transport = socket;
        if (framed) {
            transport = new TFramedTransport(socket);
        }
        transport.open();
        TProtocol protocol = protocolFactory.getProtocol(transport);
        return new Connection(socket, transport, new ThriftBenchService.Client(protocol, protocol));
    }

    @Override
    public void close() {
        for (Connection connection : live) {
            connection.close();
        }
        live.clear();
        idle.clear();
    }

    /**
     * 已经建过多少条连接 —— 报告里用它说明「Thrift 臂为了 N 路并发用了多少条连接」。
     */
    public int createdConnections() {
        return live.size();
    }

    /**
     * 一条连接 —— socket / transport / thrift 客户端三者绑定在一起。
     */
    private static final class Connection {
        private final TSocket socket;
        private final TTransport transport;
        private final ThriftBenchService.Client client;

        Connection(TSocket socket, TTransport transport, ThriftBenchService.Client client) {
            this.socket = socket;
            this.transport = transport;
            this.client = client;
        }

        void close() {
            try {
                transport.close();
            } catch (Exception ignored) {
                // 关连接失败不影响基准结果
            }
            try {
                socket.close();
            } catch (Exception ignored) {
                // 同上
            }
        }
    }
}
