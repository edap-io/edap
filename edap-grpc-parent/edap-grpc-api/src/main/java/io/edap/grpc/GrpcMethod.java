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

package io.edap.grpc;

import io.edap.protobuf.annotation.ProtoGrpc;

/**
 * 一个可以通过 gRPC 调用的方法。
 *
 * <p>由容器根据 proto 生成的服务接口 + {@code @ProtoGrpc} 注解构造，
 * 注册到 {@code GrpcServer} 的路由表上（{@code path → GrpcMethod}）。</p>
 */
public final class GrpcMethod {

    /**
     * gRPC 的 {@code :path}，形如 {@code /pkg.Svc/Method}。
     */
    private final String path;

    /**
     * 方法的实现（字节码生成）。
     */
    private final GrpcHandler handler;

    /**
     * 调用形态。
     */
    private final ProtoGrpc.MethodType type;

    /**
     * 请求消息的 DTO 类型。
     */
    private final Class<?> reqType;

    /**
     * 响应消息的 DTO 类型。
     */
    private final Class<?> respType;

    public GrpcMethod(String path, GrpcHandler handler, ProtoGrpc.MethodType type,
                      Class<?> reqType, Class<?> respType) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("gRPC 方法的 path 不能为空");
        }
        if (handler == null) {
            throw new IllegalArgumentException("gRPC 方法的 handler 不能为空: " + path);
        }
        this.path = path;
        this.handler = handler;
        this.type = type == null ? ProtoGrpc.MethodType.UNARY : type;
        this.reqType = reqType;
        this.respType = respType;
    }

    public String getPath() {
        return path;
    }

    public GrpcHandler getHandler() {
        return handler;
    }

    public ProtoGrpc.MethodType getType() {
        return type;
    }

    public Class<?> getReqType() {
        return reqType;
    }

    public Class<?> getRespType() {
        return respType;
    }

    /**
     * @return true 表示请求侧是消息流（客户端流式或双向流式）
     */
    public boolean isClientStreaming() {
        return type == ProtoGrpc.MethodType.CLIENT_STREAM || type == ProtoGrpc.MethodType.BIDIRECTIONAL;
    }

    /**
     * @return true 表示响应侧是消息流（服务端流式或双向流式）
     */
    public boolean isServerStreaming() {
        return type == ProtoGrpc.MethodType.SERVER_STREAM || type == ProtoGrpc.MethodType.BIDIRECTIONAL;
    }

    @Override
    public String toString() {
        return "GrpcMethod{" + path + ", " + type + "}";
    }
}
