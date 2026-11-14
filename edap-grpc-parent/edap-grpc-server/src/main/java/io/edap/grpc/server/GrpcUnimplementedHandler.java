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

import io.edap.grpc.GrpcStatus;
import io.edap.http.HttpResponse;
import io.edap.http.header.ContentTypeHeader;
import io.edap.http.server.http2.Http2ProtocolHandler;
import io.edap.http.server.http2.Http2Stream;

import java.io.IOException;

/**
 * 未注册的 gRPC 方法 —— 回 {@code UNIMPLEMENTED(12)}。
 *
 * <p>gRPC 规范要求未知方法用 gRPC 的状态码表达，而不是 HTTP 的 404 ——
 * 客户端只认 {@code grpc-status}。</p>
 */
class GrpcUnimplementedHandler implements Http2ProtocolHandler {

    private final String path;

    GrpcUnimplementedHandler(String path) {
        this.path = path;
    }

    @Override
    public boolean takesStreamingBody() {
        // 请求体直接丢掉 —— 不会用到
        return true;
    }

    @Override
    public void onHeaders(Http2Stream stream) throws IOException {
        HttpResponse response = stream.getResponse();
        response.setStatusCode(200);
        response.contentType(ContentTypeHeader.GRPC);
        response.begin();
        response.putTrailer("grpc-status", Integer.toString(GrpcStatus.UNIMPLEMENTED.code()));
        response.putTrailer("grpc-message", "unknown method: " + path);
        response.end();
    }

    @Override
    public void onBody(Http2Stream stream, byte[] data, int offset, int length) {
    }

    @Override
    public void onEnd(Http2Stream stream) {
    }
}
