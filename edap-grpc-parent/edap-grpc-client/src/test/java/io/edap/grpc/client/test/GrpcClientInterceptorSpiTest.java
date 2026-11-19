/*
 * Copyright 2023 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.edap.grpc.client.test;

import io.edap.Edap;
import io.edap.grpc.GrpcHandler;
import io.edap.grpc.GrpcMethod;
import io.edap.grpc.GrpcStatus;
import io.edap.grpc.client.GrpcChannel;
import io.edap.grpc.client.GrpcClientInterceptor;
import io.edap.grpc.server.GrpcServer;
import io.edap.pool.SimpleFastBufPool;
import io.edap.protobuf.annotation.ProtoGrpc;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * gRPC 客户端拦截器的 SPI 装载测试。
 *
 * <p>{@code META-INF/services/io.edap.grpc.client.GrpcClientInterceptor} 里的实现
 * 必须在 {@code GrpcChannel} 构造时就被装上 —— 不需要使用者写任何代码。</p>
 */
public class GrpcClientInterceptorSpiTest {

    private static final String PATH = "/demo.Svc/Spi";

    private Edap edap;
    private GrpcChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.close();
            channel = null;
        }
        if (edap != null) {
            edap.stop();
            edap = null;
        }
    }

    /**
     * 构造 {@code GrpcChannel} 时就装上了（不用等第一次调用）。
     */
    @Test
    public void spiInterceptorIsRegisteredAtConstruction() throws Exception {
        startServer();
        channel = new GrpcChannel("127.0.0.1", port, 10_000, 5_000);

        boolean found = false;
        for (GrpcClientInterceptor interceptor : channel.interceptors()) {
            if (interceptor instanceof SpiClientInterceptor) {
                found = true;
                break;
            }
        }
        assertTrue(found, "SPI 拦截器没有在构造时装上: " + channel.interceptors());
    }

    // ---- 服务端 ---------------------------------------------------------

    private int port;

    private void startServer() throws Exception {
        port = freePort();

        GrpcHandler handler = call -> call.close(GrpcStatus.OK, null);
        GrpcServer server = new GrpcServer();
        server.setBufPool(new SimpleFastBufPool());
        server.setMethods(Collections.singletonMap(PATH, new GrpcMethod(PATH, handler,
            ProtoGrpc.MethodType.UNARY,
            GrpcClientInterceptorTest.InterceptorReq.class,
            GrpcClientInterceptorTest.InterceptorResp.class)));
        server.listen(port);

        edap = new Edap();
        edap.addServer(server);
        edap.run();
        awaitListening(port);
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static void awaitListening(int port) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                return;
            } catch (IOException e) {
                TimeUnit.MILLISECONDS.sleep(20);
            }
        }
        throw new IllegalStateException("端口 " + port + " 未在 5s 内开始监听");
    }
}
