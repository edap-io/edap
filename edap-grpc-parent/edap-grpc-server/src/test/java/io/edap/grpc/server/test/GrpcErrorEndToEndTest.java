/*
 * Copyright 2020 The edap Project
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

package io.edap.grpc.server.test;

import io.edap.grpc.GrpcCodec;
import io.edap.grpc.GrpcHandler;
import io.edap.grpc.GrpcMethod;
import io.edap.grpc.GrpcStatus;
import io.edap.protobuf.annotation.ProtoGrpc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 错误路径的端到端测试。
 *
 * <ul>
 *   <li>业务返回非 OK 的状态码 → trailers 里的 {@code grpc-status} / {@code grpc-message}；</li>
 *   <li>业务抛异常 → 框架兜成 {@code UNKNOWN(2)}；</li>
 *   <li>客户端一条消息都不发就半关闭 → 按一条空消息处理，业务不能被挂死；</li>
 *   <li>非 gRPC 的 HTTP/1.1 请求打到 gRPC 端口 → 不被劫持，走普通 HTTP 处理路径。</li>
 * </ul>
 */
public class GrpcErrorEndToEndTest extends GrpcTestBase {

    private static final String ERROR_PATH = "/test.Svc/Error";
    private static final String THROW_PATH = "/test.Svc/Throw";
    private static final String EMPTY_PATH = "/test.Svc/Empty";

    @BeforeEach
    void start() throws Exception {
        GrpcHandler errorHandler = call -> {
            call.close(GrpcStatus.INVALID_ARGUMENT, "bad request");
        };
        GrpcHandler throwHandler = call -> {
            throw new IllegalStateException("boom");
        };
        GrpcHandler emptyHandler = call -> {
            byte[] raw = call.requestBytes();
            TestMessages.Resp resp = new TestMessages.Resp();
            resp.name = "len=" + (raw == null ? -1 : raw.length);
            call.sendOne(resp);
            call.close(GrpcStatus.OK, null);
        };

        Map<String, GrpcMethod> methods = new HashMap<>();
        methods.put(ERROR_PATH, new GrpcMethod(ERROR_PATH, errorHandler,
                ProtoGrpc.MethodType.UNARY, TestMessages.Req.class, TestMessages.Resp.class));
        methods.put(THROW_PATH, new GrpcMethod(THROW_PATH, throwHandler,
                ProtoGrpc.MethodType.UNARY, TestMessages.Req.class, TestMessages.Resp.class));
        methods.put(EMPTY_PATH, new GrpcMethod(EMPTY_PATH, emptyHandler,
                ProtoGrpc.MethodType.UNARY, TestMessages.Req.class, TestMessages.Resp.class));
        startServer(methods);
    }

    @Test
    public void testErrorStatusAndMessage() throws Exception {
        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.start();
            client.sendHeaders(1, ERROR_PATH, "localhost");
            client.sendMessage(1, new byte[0], true);

            GrpcTestClient.Response response = client.readResponse(1);
            assertEquals("3", response.trailerValue("grpc-status"),
                    "应该是 INVALID_ARGUMENT: " + response.trailers);
            assertEquals("bad request", response.trailerValue("grpc-message"),
                    "grpc-message 不对: " + response.trailers);
            assertTrue(response.endStream);
        }
    }

    @Test
    public void testHandlerThrows() throws Exception {
        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.start();
            client.sendHeaders(1, THROW_PATH, "localhost");
            client.sendMessage(1, new byte[0], true);

            GrpcTestClient.Response response = client.readResponse(1);
            assertEquals("2", response.trailerValue("grpc-status"),
                    "业务异常应该兜成 UNKNOWN: " + response.trailers);
            assertEquals("boom", response.trailerValue("grpc-message"));
        }
    }

    /**
     * 客户端一条消息都不发就半关闭 —— 业务必须被调用（按空消息处理），不能挂死。
     */
    @Test
    public void testEmptyRequestStillCallsHandler() throws Exception {
        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.start();
            client.sendHeaders(1, EMPTY_PATH, "localhost");
            client.sendEndStream(1);

            GrpcTestClient.Response response = client.readResponse(1);
            assertEquals("0", response.trailerValue("grpc-status"),
                    "空请求也要正常结束: " + response.trailers);
            assertEquals(1, response.messages.size());
            byte[] payload = response.messages.get(0);
            TestMessages.Resp resp = GrpcCodec.decode(TestMessages.Resp.class, payload, 0, payload.length);
            assertEquals("len=0", resp.name);
        }
    }

    /**
     * 非 gRPC 的 HTTP/1.1 请求打到 gRPC 端口 —— 不能被 gRPC 劫持。
     */
    @Test
    public void testPlainHttpRequestNotHijacked() throws Exception {
        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.writeRaw("GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n");
            String head = client.readHttpResponseHead();
            assertTrue(head.startsWith("HTTP/1.1 404"),
                    "普通 HTTP 请求应该走默认的 404 路径，实际是: " + head);
        }
    }
}
