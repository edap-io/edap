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
import io.edap.rpc.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双向流式的端到端测试 —— 多个请求消息，多个响应消息。
 */
public class GrpcBidiEndToEndTest extends GrpcTestBase {

    private static final String PATH = "/test.Svc/Bidi";

    /** 业务收到的请求消息条数 */
    private final AtomicInteger received = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        GrpcHandler handler = call -> {
            call.setRequestObserver(new StreamObserver<Object>() {
                @Override
                public void onNext(Object value) {
                    received.incrementAndGet();
                    TestMessages.Resp resp = new TestMessages.Resp();
                    resp.value = ((TestMessages.Req) value).value * 3;
                    call.sendOne(resp);
                }

                @Override
                public void onError(Throwable t) {
                }

                @Override
                public void onCompleted() {
                    call.close(GrpcStatus.OK, null);
                }
            });
        };
        startServer(Collections.singletonMap(PATH, new GrpcMethod(PATH, handler,
                ProtoGrpc.MethodType.BIDIRECTIONAL, TestMessages.Req.class, TestMessages.Resp.class)));
    }

    @Test
    public void testBidiStream() throws Exception {
        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.start();
            client.sendHeaders(1, PATH, "localhost");

            for (int i = 1; i <= 3; i++) {
                TestMessages.Req req = new TestMessages.Req();
                req.name = "b" + i;
                req.value = i;
                client.sendMessage(1, GrpcCodec.encode(TestMessages.Req.class, req), false);
            }
            client.sendEndStream(1);

            GrpcTestClient.Response response = client.readResponse(1);

            assertEquals("200", response.status());
            assertEquals(3, response.messages.size(), "双向流式应该回 3 条消息");
            assertEquals("0", response.trailerValue("grpc-status"));
            assertTrue(response.endStream, "响应必须以 END_STREAM 结束");
            assertEquals(3, received.get(), "业务应该收到 3 条请求消息");

            for (int i = 0; i < 3; i++) {
                byte[] payload = response.messages.get(i);
                TestMessages.Resp resp = GrpcCodec.decode(TestMessages.Resp.class, payload, 0, payload.length);
                assertEquals((i + 1) * 3, resp.value);
            }
        }
    }
}
