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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 未注册的方法 —— 回 {@code UNIMPLEMENTED(12)}，而不是 HTTP 404。
 *
 * <p>gRPC 客户端只认 {@code grpc-status}，所以未知方法必须用 gRPC 的状态码表达。</p>
 */
public class GrpcUnimplementedEndToEndTest extends GrpcTestBase {

    private static final String PATH = "/test.Svc/Unary";

    @BeforeEach
    void start() throws Exception {
        GrpcHandler handler = call -> {
            TestMessages.Resp resp = new TestMessages.Resp();
            resp.value = 1;
            call.sendOne(resp);
            call.close(GrpcStatus.OK, null);
        };
        startServer(Collections.singletonMap(PATH, new GrpcMethod(PATH, handler,
                ProtoGrpc.MethodType.UNARY, TestMessages.Req.class, TestMessages.Resp.class)));
    }

    @Test
    public void testUnknownMethod() throws Exception {
        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.start();
            client.sendHeaders(1, "/test.Svc/NotExist", "localhost");

            TestMessages.Req req = new TestMessages.Req();
            req.name = "x";
            client.sendMessage(1, GrpcCodec.encode(TestMessages.Req.class, req), true);

            GrpcTestClient.Response response = client.readResponse(1);

            assertEquals("200", response.status(), "gRPC 的错误也走 HTTP 200");
            assertEquals("application/grpc", response.headerValue("content-type"));
            assertEquals("12", response.trailerValue("grpc-status"),
                    "未知方法应该是 UNIMPLEMENTED: " + response.trailers);
            assertTrue(response.endStream, "响应必须以 END_STREAM 结束");
            assertEquals(0, response.messages.size(), "未知方法不应该有响应消息");
        }
    }
}
