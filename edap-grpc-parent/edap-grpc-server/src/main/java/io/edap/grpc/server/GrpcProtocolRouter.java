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
import io.edap.http.ValueHttpRequest;
import io.edap.http.server.http2.Http2ProtocolHandler;
import io.edap.http.server.http2.Http2ProtocolRouter;

/**
 * gRPC 的协议路由器 —— 决定一条 HTTP/2 流走 gRPC 语义还是普通 HTTP 语义。
 *
 * <p><b>判定依据是 {@code content-type}</b>：gRPC 规范要求请求的 content-type
 * 是 {@code application/grpc} 或它的子类型（{@code application/grpc+proto} 等）。
 * 不是 gRPC 的请求返回 null —— 走默认的 HTTP 处理路径，行为与改造前完全一致。</p>
 */
public class GrpcProtocolRouter implements Http2ProtocolRouter {

    /**
     * gRPC 的 content-type 前缀。
     */
    private static final String GRPC_CONTENT_TYPE_PREFIX = "application/grpc";

    private final GrpcServer server;

    public GrpcProtocolRouter(GrpcServer server) {
        this.server = server;
    }

    @Override
    public Http2ProtocolHandler select(ValueHttpRequest request) {
        String contentType = request.getHeader("content-type");
        if (contentType == null || !contentType.startsWith(GRPC_CONTENT_TYPE_PREFIX)) {
            return null;
        }
        String path = request.getPath();
        if (path == null || path.isEmpty()) {
            return null;
        }
        GrpcMethod method = server.lookup(path);
        if (method == null) {
            // gRPC 规范：未知的方法要回 UNIMPLEMENTED，而不是 HTTP 404
            return new GrpcUnimplementedHandler(path);
        }
        return new GrpcProtocolHandler(method);
    }
}
