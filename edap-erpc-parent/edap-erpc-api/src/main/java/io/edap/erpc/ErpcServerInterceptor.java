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

package io.edap.erpc;

import io.edap.rpc.RpcChannelContext;

/**
 * eRPC 服务端的拦截器 —— 请求进来之前 / 调用结束之后各插一刀。
 *
 * <p>典型用途：鉴权、审计、限流、metrics、traceId 透传。</p>
 *
 * <p>★ <b>装上去的三种方式</b>：</p>
 * <ol>
 *   <li>编程式：{@code erpcServer.addInterceptor(this)}；</li>
 *   <li>SPI：{@code META-INF/services/io.edap.erpc.ErpcServerInterceptor}；</li>
 *   <li>容器 Bean：应用里把它注册成 bean，容器启动时自动装上。</li>
 * </ol>
 *
 * <p>★ <b>顺序</b>：{@link #beforeCall} 正序（先注册先进）、{@link #afterCall} 逆序
 * （后注册的先出）—— 与 Servlet Filter / Netty Pipeline 一致。</p>
 *
 * <p>★ <b>线程</b>：两个钩子都跑在 <b>业务 worker 线程</b>上（与业务 handler 同线程）。</p>
 *
 * <p>★ <b>eRPC 没有请求头通道</b>：32 字节帧头字段全占满（{@code flags} 的 bit0 是压缩预留、
 * bit1~bit7 是超时码）⇒ 钩子里<b>碰不到请求头</b>，只能碰 traceId / 超时 / 上下文属性。
 * 要传自定义头 ⇒ 放 {@code RpcContext} 的属性里，或者用 gRPC 那边。</p>
 *
 * <p>★ <b>连接级状态</b>：钩子的 {@link RpcChannelContext} 参数是<b>这条连接</b>的上下文
 * （每连接一个）—— 权限校验这类<b>只做一次</b>的事放这里：</p>
 * <pre>{@code
 *   public class AuthInterceptor implements ErpcServerInterceptor {
 *       @Override
 *       public void beforeCall(ErpcRequest req, RpcChannelContext channel) throws Exception {
 *           if (!channel.isAuthenticated()) {
 *               verify(req);                 // 校验一次
 *               channel.markAuthenticated(); // 记在连接上
 *           }
 *           // 同一连接的后续请求直接放行
 *       }
 *   }
 * }</pre>
 * <p>★ <b>新连接 = 新上下文</b> ⇒ 连接断了标记就没了 ⇒ 下次连上来的第一个请求会重新校验。</p>
 */
public interface ErpcServerInterceptor {

    /**
     * 请求进来（在业务 handler 之前）。
     *
     * <p>★ <b>抛异常 ⇒ 拒绝本次调用</b>：业务 handler <b>不会</b>被执行，
     * 框架回一个 ERROR 帧。状态码这样定：</p>
     * <ul>
     *   <li>抛 {@link ErpcStatusException} ⇒ 用它带的状态码；</li>
     *   <li>抛别的异常 ⇒ {@link ErpcStatus#INTERNAL}。</li>
     * </ul>
     *
     * <p>★ <b>不是 {@link RpcException}</b>：那个类是线上传输的 DTO，不是
     * {@link Throwable} ⇒ 抛不出去。</p>
     *
     * @param request 本次请求（methodId / traceId / 超时 / 上下文都在里面）
     * @throws Exception 拒绝本次调用
     */
    default void beforeCall(ErpcRequest request) throws Exception {
    }

    /**
     * 请求进来（在业务 handler 之前）—— <b>带连接级上下文</b>。
     *
     * <p>★ <b>框架调的是这一档</b>；只实现了 {@link #beforeCall(ErpcRequest)} 的拦截器
     * 照旧工作（默认实现转调老钩子）。</p>
     *
     * <p><b>权限校验只做一次</b>：校验通过后 {@code channel.markAuthenticated()}，
     * 同一连接的后续请求直接放行。</p>
     *
     * @param request 本次请求（methodId / traceId / 超时 / 上下文都在里面）
     * @param channel <b>本连接</b>的上下文（每连接一个；连接断了就没了 ⇒ 会重新校验）
     * @throws Exception 拒绝本次调用
     */
    default void beforeCall(ErpcRequest request, RpcChannelContext channel) throws Exception {
        beforeCall(request);
    }

    /**
     * 调用结束（响应已回 / 出错 / deadline 到期）。
     *
     * <p>★ <b>只跑一次</b>。★ <b>抛出的异常被吞掉</b>（框架记一条 warn 日志）——
     * 收尾钩子不该影响调用结果。</p>
     *
     * @param request  本次请求
     * @param response 本次响应（业务填的结果 / 异常）；被拒绝时可能是 null
     * @param error    出错原因；{@code null} 表示正常结束
     */
    default void afterCall(ErpcRequest request, ErpcResponse response, Throwable error) {
    }

    /**
     * 调用结束（响应已回 / 出错 / deadline 到期）—— <b>带连接级上下文</b>。
     *
     * <p>★ <b>框架调的是这一档</b>；只实现了 {@link #afterCall(ErpcRequest, ErpcResponse, Throwable)}
     * 的拦截器照旧工作（默认实现转调老钩子）。</p>
     *
     * @param request  本次请求
     * @param response 本次响应（业务填的结果 / 异常）；被拒绝时可能是 null
     * @param error    出错原因；{@code null} 表示正常结束
     * @param channel  <b>本连接</b>的上下文（每连接一个）
     */
    default void afterCall(ErpcRequest request, ErpcResponse response, Throwable error,
                           RpcChannelContext channel) {
        afterCall(request, response, error);
    }
}
