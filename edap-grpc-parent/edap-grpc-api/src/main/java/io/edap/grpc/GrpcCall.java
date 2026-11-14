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

import io.edap.rpc.StreamObserver;

/**
 * 一次 gRPC 调用的上下文。
 *
 * <p>四种调用形态的差异只体现在「请求侧是单条消息还是消息流」「响应侧是单条消息还是消息流」，
 * 由 {@link #isClientStreaming()} 和 {@link #isServerStreaming()} 表达，因此所有形态
 * 共用同一套字节码骨架，只有调用分支不同。
 *
 * <p>响应元数据在第一次发送消息或结束调用之前可以自由修改，一旦开始发送就会被写入
 * HTTP/2 的响应头。
 */
public interface GrpcCall {

    /**
     * @return 被调用的方法，形如 {@code /pkg.Svc/Method}
     */
    String getMethod();

    /**
     * @return 请求元数据，来自 HTTP/2 的请求头
     */
    GrpcMetadata metadata();

    /**
     * @return 响应元数据，修改后会写入 HTTP/2 的响应头
     */
    GrpcMetadata responseMetadata();

    /**
     * @return true 表示请求侧是消息流（客户端流式或双向流式）
     */
    boolean isClientStreaming();

    /**
     * @return true 表示响应侧是消息流（服务端流式或双向流式）
     */
    boolean isServerStreaming();

    /**
     * @return 请求消息的完整字节内容（一元调用和服务端流式调用）；请求侧是消息流时返回 null
     */
    byte[] requestBytes();

    /**
     * 发送一条响应消息。
     *
     * <p>一元调用只能发送一条，服务端流式和双向流式可以发送多条。
     *
     * @param message 响应消息对象，类型必须与该方法的响应类型一致
     */
    void sendOne(Object message);

    /**
     * @return 响应侧的观察者，通过它发送的消息与 {@link #sendOne(Object)} 等价
     */
    StreamObserver<Object> responseObserver();

    /**
     * 交回请求侧的观察者。
     *
     * <p>客户端流式和双向流式的业务方法返回一个 {@code StreamObserver<Req>}，
     * 框架通过本方法把它拿到手，后续收到的请求消息才会被投递给它。
     *
     * @param requestObserver 请求侧的观察者
     */
    void setRequestObserver(StreamObserver<Object> requestObserver);

    /**
     * 结束本次调用，写出 gRPC 的 trailers。
     *
     * @param status  结束状态
     * @param message 状态描述，可以为 null
     */
    void close(GrpcStatus status, String message);
}
