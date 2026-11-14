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
import io.edap.http.http2.Http2FrameType;
import io.edap.protobuf.annotation.ProtoGrpc;
import io.edap.rpc.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 「提前派发」的端到端测试。
 *
 * <p><b>双向流式必须能在请求还没发完时就开始回响应</b>：gRPC 的双向流式
 * 允许服务端先推送消息 —— 如果框架非要等请求体收齐才派发，双向流式就退化成
 * 「先收完再回」，业务上完全不可用。</p>
 */
public class GrpcDispatchTimingEndToEndTest extends GrpcTestBase {

    private static final String PATH = "/test.Svc/Bidi";

    @BeforeEach
    void start() throws Exception {
        GrpcHandler handler = call -> {
            // 业务一上来就推一条消息 —— 此时客户端还没发任何请求消息
            TestMessages.Resp hello = new TestMessages.Resp();
            hello.name = "hello";
            hello.value = -1;
            call.sendOne(hello);

            call.setRequestObserver(new StreamObserver<Object>() {
                @Override
                public void onNext(Object value) {
                    TestMessages.Resp resp = new TestMessages.Resp();
                    resp.value = ((TestMessages.Req) value).value;
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

    /**
     * 只发请求头，不发任何请求消息 —— 服务端必须立刻回响应头。
     */
    @Test
    public void testRespondBeforeRequestBody() throws Exception {
        try (GrpcTestClient client = new GrpcTestClient(port)) {
            client.start();
            client.sendHeaders(1, PATH, "localhost");

            GrpcTestClient.Frame frame = client.readFrameFor(1, 3000);
            assertNotNull(frame, "请求头收齐后服务端必须立刻回响应");
            assertEquals(Http2FrameType.HEADERS, frame.type,
                    "第一个响应帧必须是 HEADERS，实际是 type=" + frame.type);

            GrpcTestClient.Frame data = client.readFrameFor(1, 3000);
            assertNotNull(data, "业务推的第一条消息必须立刻到");
            assertEquals(Http2FrameType.DATA, data.type, "第二个响应帧应该是 DATA");

            byte[] payload = GrpcTestClient.splitMessages(data.payload).get(0);
            TestMessages.Resp resp = GrpcCodec.decode(TestMessages.Resp.class, payload, 0, payload.length);
            assertEquals("hello", resp.name);
        }
    }
}
