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

package io.edap.grpc.server.test;

import io.edap.grpc.GrpcCodec;
import io.edap.grpc.GrpcHandler;
import io.edap.grpc.GrpcMethod;
import io.edap.grpc.GrpcServerInterceptor;
import io.edap.grpc.GrpcStatus;
import io.edap.protobuf.annotation.ProtoGrpc;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * gRPC 服务端拦截器的 SPI 装载测试。
 *
 * <p>{@code META-INF/services/io.edap.grpc.GrpcServerInterceptor} 里的实现
 * 必须在 {@code GrpcServer} 构造时就被装上 —— 不需要使用者写任何代码。</p>
 */
public class GrpcServerInterceptorSpiTest extends GrpcTestBase {

    private static final String PATH = "/test.Svc/Spi";

    @Test
    public void spiInterceptorIsLoadedAndRuns() throws Exception {
        GrpcHandler handler = call -> {
            TestMessages.Resp resp = new TestMessages.Resp();
            resp.name = "spi";
            resp.value = 1;
            call.sendOne(resp);
            call.close(GrpcStatus.OK, null);
        };
        startServer(Collections.singletonMap(PATH, new GrpcMethod(PATH, handler,
            ProtoGrpc.MethodType.UNARY, TestMessages.Req.class, TestMessages.Resp.class)));

        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.start();
            client.sendHeaders(1, PATH, "localhost");

            TestMessages.Req req = new TestMessages.Req();
            req.name = "spi";
            req.value = 1;
            client.sendMessage(1, GrpcCodec.encode(TestMessages.Req.class, req), true);

            GrpcTestClient.Response response = client.readResponse(1);

            assertEquals(SpiServerInterceptor.MARKER_VALUE,
                response.headerValue(SpiServerInterceptor.MARKER_HEADER),
                "SPI 装出来的拦截器没有跑: " + response.headers);
            assertEquals("0", response.trailerValue("grpc-status"));
        }
    }

    /**
     * 构造 {@code GrpcServer} 时就装上了（不用等第一次调用）。
     */
    @Test
    public void spiInterceptorIsRegisteredAtConstruction() {
        io.edap.grpc.server.GrpcServer server = new io.edap.grpc.server.GrpcServer();

        boolean found = false;
        for (GrpcServerInterceptor interceptor : server.interceptors()) {
            if (interceptor instanceof SpiServerInterceptor) {
                found = true;
                break;
            }
        }
        assertTrue(found, "SPI 拦截器没有在构造时装上: " + server.interceptors());
    }
}
