/*
 * Copyright 2023 The edap Project
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

package io.edap.http.server.http2;

import io.edap.http.ValueHttpRequest;

/**
 * HTTP/2 协议路由器 —— 决定一条流走「默认 HTTP 处理」还是「某个协议处理器」。
 *
 * <p><b>为什么需要它</b>：HTTP/2 的请求头收齐之后，才知道这是不是一个
 * 「非 HTTP 语义」的协议 —— 比如 gRPC（{@code content-type: application/grpc}）、
 * WebSocket over h2（RFC 8441）。这些协议需要：</p>
 * <ul>
 *   <li><b>提前派发</b>：header 一收齐就派发，不能等 body 收完 ——
 *       服务端流式（server streaming）必须在请求收完之前就能开始回响应；</li>
 *   <li><b>增量 body</b>：body 边到边处理，不在流上累积（gRPC 的请求是消息流）；</li>
 *   <li><b>trailers</b>：响应以 trailers 结束（gRPC 的 {@code grpc-status}）。</li>
 * </ul>
 *
 * <p><b>默认行为不变</b>：路由器为 null（本接口没有实现）时，所有流都走
 * 默认的 HTTP 处理路径 —— HTTP/1.1 与 HTTP/2 的既有行为<b>逐字节不变</b>。</p>
 *
 * <p>实现由 {@code io.edap.http.server.HttpServer#setProtocolRouter} 注入，
 * {@link Http2Connection} 在构造时取一次。</p>
 */
public interface Http2ProtocolRouter {

    /**
     * 选这条流该走哪个协议处理器。
     *
     * @param request 已解完 HPACK 的请求（path / method / headers 都齐了）
     * @return 协议处理器；返回 {@code null} 表示走默认的 HTTP 处理路径
     */
    Http2ProtocolHandler select(ValueHttpRequest request);
}
