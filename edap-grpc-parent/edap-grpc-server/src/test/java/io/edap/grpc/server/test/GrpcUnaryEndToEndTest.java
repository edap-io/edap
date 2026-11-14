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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一元调用的端到端测试。
 *
 * <p>验证响应的完整形状：{@code HEADERS(:status 200, content-type: application/grpc)}
 * → {@code DATA}(一条 gRPC 消息) → {@code HEADERS(END_STREAM)}(trailers，
 * 里面有 {@code grpc-status})。</p>
 */
public class GrpcUnaryEndToEndTest extends GrpcTestBase {

    private static final String PATH = "/test.Svc/Unary";

    /** 业务看到的请求 content-type */
    private final AtomicReference<String> contentTypeSeen = new AtomicReference<>();

    /** 业务看到的方法路径 */
    private final AtomicReference<String> methodSeen = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        GrpcHandler handler = call -> {
            contentTypeSeen.set(call.metadata().get("content-type"));
            methodSeen.set(call.getMethod());
            byte[] raw = call.requestBytes();
            assertNotNull(raw, "一元调用的请求消息必须已经到齐");
            TestMessages.Req req = GrpcCodec.decode(TestMessages.Req.class, raw, 0, raw.length);
            TestMessages.Resp resp = new TestMessages.Resp();
            resp.name = "hello " + req.name;
            resp.value = req.value * 2;
            call.sendOne(resp);
            call.close(GrpcStatus.OK, null);
        };
        startServer(Collections.singletonMap(PATH, new GrpcMethod(PATH, handler,
                ProtoGrpc.MethodType.UNARY, TestMessages.Req.class, TestMessages.Resp.class)));
    }

    @Test
    public void testUnaryCall() throws Exception {
        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.start();
            client.sendHeaders(1, PATH, "localhost");

            TestMessages.Req req = new TestMessages.Req();
            req.name = "edap";
            req.value = 21;
            client.sendMessage(1, GrpcCodec.encode(TestMessages.Req.class, req), true);

            GrpcTestClient.Response response = client.readResponse(1);

            assertEquals("200", response.status(), "响应头不对: " + response.headers);
            assertEquals("application/grpc", response.headerValue("content-type"),
                    "content-type 不对: " + response.headers);
            assertEquals(1, response.messages.size(), "一元调用只应该有一条响应消息");
            assertEquals("0", response.trailerValue("grpc-status"),
                    "trailers 里没有 grpc-status: " + response.trailers);
            assertTrue(response.endStream, "响应必须以 END_STREAM 结束");

            byte[] payload = response.messages.get(0);
            TestMessages.Resp resp = GrpcCodec.decode(TestMessages.Resp.class, payload, 0, payload.length);
            assertEquals("hello edap", resp.name);
            assertEquals(42, resp.value);
        }
    }

    /**
     * 请求元数据要能从 HTTP/2 的请求头里拿到，方法路径也要对。
     */
    @Test
    public void testRequestMetadataVisible() throws Exception {
        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.start();
            client.sendHeaders(1, PATH, "localhost");

            TestMessages.Req req = new TestMessages.Req();
            req.name = "meta";
            client.sendMessage(1, GrpcCodec.encode(TestMessages.Req.class, req), true);

            GrpcTestClient.Response response = client.readResponse(1);
            assertEquals("0", response.trailerValue("grpc-status"));
            assertEquals("application/grpc", contentTypeSeen.get(), "业务看不到请求的 content-type");
            assertEquals(PATH, methodSeen.get(), "业务看到的方法路径不对");
        }
    }
}
