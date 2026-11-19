/*
 * Copyright 2023 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.edap.erpc.client;

import io.edap.erpc.ErpcRequest;
import io.edap.erpc.ErpcResponse;

/**
 * eRPC 客户端的拦截器 —— 请求发出之前 / 调用结束之后各插一刀。
 *
 * <p>典型用途：改 traceId、收紧超时、metrics / 审计日志。</p>
 *
 * <p>★ <b>装上去的方式</b>：</p>
 * <ol>
 *   <li>编程式：{@code client.addInterceptor(this)}；</li>
 *   <li>SPI：{@code META-INF/services/io.edap.erpc.client.ErpcClientInterceptor}；</li>
 *   <li>容器 Bean：<b>不适用</b> —— 容器只自动装<b>服务端</b>拦截器
 *       （{@link io.edap.erpc.ErpcServerInterceptor}）；客户端拦截器由使用者自己建客户端时
 *       {@code addInterceptor}。</li>
 * </ol>
 *
 * <p>★ <b>顺序</b>：{@link #beforeCall} 正序（先注册先进）、{@link #afterCall} 逆序
 * （后注册的先出）—— 与 Servlet Filter / Netty Pipeline 一致。</p>
 *
 * <p>★ <b>eRPC 没有请求头通道</b>：32 字节帧头字段全占满 ⇒ 钩子里碰不到请求头，
 * 只能碰 traceId / 超时 / 上下文属性。</p>
 *
 * <p>★ <b>连接级状态</b>：客户端这边也有连接级上下文
 * （{@link io.edap.rpc.RpcChannelContext}，每条连接一个）。但
 * <b>{@code beforeCall} 跑在「选节点 / 选连接」之前</b> ⇒ 那时还不知道走哪条连接 ⇒
 * 拿不到它；<b>{@code afterCall} 阶段可以</b>（{@code request.getChannelContext()}）。
 * 客户端要按连接存状态，直接用 {@code ErpcChannel.channelContext()}。</p>
 */
public interface ErpcClientInterceptor {

    /**
     * 请求发出之前（选节点之前）。
     *
     * <p>★ <b>抛异常 ⇒ 本次调用失败</b>：不选节点、不发帧，框架抛
     * {@link io.edap.erpc.ErpcException}。</p>
     *
     * @param request 本次请求（可改 traceId / 超时 / 上下文属性）
     * @throws Exception 拒绝本次调用
     */
    default void beforeCall(ErpcRequest request) throws Exception {
    }

    /**
     * 调用结束（收到响应 / 出错 / 超时 / 被取消）。
     *
     * <p>★ <b>只跑一次</b>。★ <b>抛出的异常被吞掉</b>（框架记一条 warn 日志）——
     * 收尾钩子不该影响调用结果。</p>
     *
     * @param request  本次请求
     * @param response 收到的响应；调用失败时可能是 null
     * @param error    出错原因；{@code null} 表示正常结束
     */
    default void afterCall(ErpcRequest request, ErpcResponse response, Throwable error) {
    }
}
