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

package io.edap.grpc.server;

import io.edap.grpc.GrpcMethod;
import io.edap.grpc.GrpcServerInterceptor;
import io.edap.http.server.http2.Http2ProtocolHandler;
import io.edap.http.server.http2.Http2Stream;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * 一条 gRPC 流上的协议处理器。
 *
 * <p>把 HTTP/2 的「字节流」翻译成 gRPC 的「消息流」：</p>
 * <ol>
 *   <li>{@link #onHeaders} —— 请求头收齐（body 还没到），建立 {@link GrpcCallImpl}；</li>
 *   <li>{@link #onBody} —— 每收到一块 DATA，喂给增量分帧器，凑齐一条消息就投递给业务；</li>
 *   <li>{@link #onEnd} —— 对端半关闭，请求消息流结束。</li>
 * </ol>
 *
 * <p><b>什么时候把控制权交给业务</b>：</p>
 * <ul>
 *   <li><b>客户端流式 / 双向流式</b>：{@code onHeaders} 里立即交出 —— 请求消息是一条条来的，
 *       而且客户端可能一条都不发就半关闭（空请求），等到第一条消息才交出去会让业务永远不被调用。</li>
 *   <li><b>一元 / 服务端流式</b>：第一条请求消息凑齐时交出 —— 服务端流式必须能在
 *       请求没收完时就开始回响应（gRPC 的服务端流式语义）。</li>
 * </ul>
 */
public class GrpcProtocolHandler implements Http2ProtocolHandler {

    private final GrpcMethod method;

    /**
     * 服务端拦截器 —— <b>活视图</b>（{@code CopyOnWriteArrayList}）。
     *
     * <p>每次 {@code select} 都从 {@code GrpcServer} 取当前列表 ⇒ 注册/卸载立刻生效，
     * 不需要重启连接。</p>
     */
    private final List<GrpcServerInterceptor> interceptors;

    private GrpcCallImpl call;

    public GrpcProtocolHandler(GrpcMethod method) {
        this(method, Collections.emptyList());
    }

    public GrpcProtocolHandler(GrpcMethod method, List<GrpcServerInterceptor> interceptors) {
        this.method = method;
        this.interceptors = interceptors == null ? Collections.emptyList() : interceptors;
    }

    @Override
    public boolean takesStreamingBody() {
        // gRPC 的请求体是消息流，必须增量处理，不能累积到流上
        return true;
    }

    @Override
    public void onHeaders(Http2Stream stream) throws IOException {
        call = new GrpcCallImpl(method, stream, GrpcCallImpl.metadataOf(stream.getRequest()),
            interceptors);
        // ★ 拦截器在「请求头到齐」时就跑 ⇒ 鉴权在读 body 之前，
        //   一元调用也能在收 body 前被拒绝。
        if (!call.runBeforeInterceptors()) {
            // 已被拒绝 —— 错误 trailers 已经写出，业务不会执行
            return;
        }
        if (method.isClientStreaming()) {
            call.invokeHandler();
        }
    }

    @Override
    public void onBody(Http2Stream stream, byte[] data, int offset, int length) throws IOException {
        call.feed(data, offset, length);
    }

    @Override
    public void onEnd(Http2Stream stream) throws IOException {
        call.endRequest();
    }
}
