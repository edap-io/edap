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

package io.edap.erpc.server;

import io.edap.Edap;
import io.edap.erpc.ErpcMethod;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 测试用的 eRPC 服务端 —— 起一个真的 NIO 服务端，等端口开始监听。
 *
 * <p>启动范式照抄 gRPC 基准服务端（{@code EdapBenchServer}）：
 * {@code new Server → listen(port) → new Edap → addServer → run}。</p>
 */
public final class ErpcTestServer implements AutoCloseable {

    private final ErpcServer server;
    private final Edap       edap;
    private final int        port;

    private ErpcTestServer(ErpcServer server, Edap edap, int port) {
        this.server = server;
        this.edap = edap;
        this.port = port;
    }

    /**
     * 起一个服务端并等到端口开始监听。
     */
    public static ErpcTestServer start(Map<Integer, ErpcMethod> methods) throws IOException {
        return start(methods, null);
    }

    /**
     * 起一个服务端并等到端口开始监听（可先改服务端配置）。
     *
     * @param methods     路由表
     * @param configurator 配置回调（<b>在 {@code listen} 之前</b>调用 ——
     *                     业务线程池是 {@code init()} 时才建的）
     */
    public static ErpcTestServer start(Map<Integer, ErpcMethod> methods,
                                       Consumer<ErpcServer> configurator) throws IOException {
        int port = freePort();
        ErpcServer server = new ErpcServer();
        server.setMethods(methods);
        if (configurator != null) {
            configurator.accept(server);
        }
        server.listen(port);

        Edap edap = new Edap();
        edap.addServer(server);
        edap.run();
        awaitListening(port);
        return new ErpcTestServer(server, edap, port);
    }

    public int port() {
        return port;
    }

    public ErpcServer server() {
        return server;
    }

    @Override
    public void close() {
        edap.stop();
        server.stop();
    }

    /**
     * 等端口开始监听。
     */
    static void awaitListening(int port) throws IOException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException e) {
                sleep(20);
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

    static void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", e);
        }
    }
}
