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
 * 服务端流式的端到端测试 —— 一个请求消息，多个响应消息。
 */
public class GrpcServerStreamEndToEndTest extends GrpcTestBase {

    private static final String PATH = "/test.Svc/ServerStream";

    @BeforeEach
    void start() throws Exception {
        GrpcHandler handler = call -> {
            byte[] raw = call.requestBytes();
            TestMessages.Req req = GrpcCodec.decode(TestMessages.Req.class, raw, 0, raw.length);
            for (int i = 0; i < 3; i++) {
                TestMessages.Resp resp = new TestMessages.Resp();
                resp.name = req.name;
                resp.value = i;
                call.sendOne(resp);
            }
            call.close(GrpcStatus.OK, null);
        };
        startServer(Collections.singletonMap(PATH, new GrpcMethod(PATH, handler,
                ProtoGrpc.MethodType.SERVER_STREAM, TestMessages.Req.class, TestMessages.Resp.class)));
    }

    @Test
    public void testServerStream() throws Exception {
        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.start();
            client.sendHeaders(1, PATH, "localhost");

            TestMessages.Req req = new TestMessages.Req();
            req.name = "stream";
            client.sendMessage(1, GrpcCodec.encode(TestMessages.Req.class, req), true);

            GrpcTestClient.Response response = client.readResponse(1);

            assertEquals("200", response.status());
            assertEquals("application/grpc", response.headerValue("content-type"));
            assertEquals(3, response.messages.size(), "服务端流式应该回 3 条消息");
            assertEquals("0", response.trailerValue("grpc-status"));
            assertTrue(response.endStream, "响应必须以 END_STREAM 结束");

            for (int i = 0; i < 3; i++) {
                byte[] payload = response.messages.get(i);
                TestMessages.Resp resp = GrpcCodec.decode(TestMessages.Resp.class, payload, 0, payload.length);
                assertEquals("stream", resp.name);
                assertEquals(i, resp.value);
            }
        }
    }
}
