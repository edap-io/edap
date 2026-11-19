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

package io.edap.grpc;

/**
 * gRPC 服务端的拦截器 —— 请求进来之前 / 调用结束之后各插一刀。
 *
 * <p>典型用途：鉴权（读 {@link GrpcCall#metadata()} 里的 token，失败就拒绝）、
 * 审计（记录谁在什么时候调了哪个方法）、限流、metrics、traceId 透传。</p>
 *
 * <p>★ <b>装上去的三种方式</b>：</p>
 * <ol>
 *   <li>编程式：{@code grpcServer.addInterceptor(this)}；</li>
 *   <li>SPI：{@code META-INF/services/io.edap.grpc.GrpcServerInterceptor}；</li>
 *   <li>容器 Bean：应用里把它注册成 bean，容器启动时自动装上。</li>
 * </ol>
 *
 * <p>★ <b>顺序</b>：{@link #beforeCall} 正序（先注册先进）、{@link #afterCall} 逆序
 * （后注册的先出）—— 与 Servlet Filter / Netty Pipeline 一致。</p>
 *
 * <p>★ <b>线程</b>：两个钩子都跑在 <b>gRPC 的 IO 线程</b>上（业务 handler 也在这个线程）。
 * 钩子里做慢操作会拖慢这条连接上的其它请求 ⇒ 重活请丢到业务线程池。</p>
 *
 * <p>★ <b>上下文</b>：两个钩子都在 {@code RpcContext.attach(ctx)} 作用域内 ⇒
 * {@code RpcContext.current()} 拿得到本次调用的上下文（deadline / traceId / 取消信号）。</p>
 */
public interface GrpcServerInterceptor {

    /**
     * 请求进来（在业务 handler 之前）。
     *
     * <p>可以：读 {@link GrpcCall#metadata()} 里的请求头；往
     * {@link GrpcCall#responseMetadata()} 写响应头（会真的上线）。</p>
     *
     * <p>★ <b>抛异常 ⇒ 拒绝本次调用</b>：业务 handler <b>不会</b>被执行，
     * 框架用 trailers 回一个错误状态。状态码这样定：</p>
     * <ul>
     *   <li>抛 {@link GrpcStatusException} ⇒ 用它带的 {@link GrpcStatus}
     *       （鉴权失败 {@code UNAUTHENTICATED}、限流 {@code RESOURCE_EXHAUSTED}…）；</li>
     *   <li>抛别的异常 ⇒ {@link GrpcStatus#INTERNAL}。</li>
     * </ul>
     *
     * @param call 本次调用（{@code getMethod()} 是 {@code /pkg.Svc/Method}）
     * @throws Exception 拒绝本次调用
     */
    default void beforeCall(GrpcCall call) throws Exception {
    }

    /**
     * 调用结束（响应已发出 / 出错 / 被取消 / deadline 到期）。
     *
     * <p>★ <b>只跑一次</b>，挂在调用真正结束的那一刻（不是 handler 返回的那一刻 ——
     * 服务端流式下 handler 立即返回而响应稍后才发）。</p>
     *
     * <p>★ <b>抛出的异常被吞掉</b>（框架记一条 warn 日志）—— 收尾钩子不该影响调用结果。</p>
     *
     * @param call  本次调用
     * @param error 出错原因；{@code null} 表示正常结束
     */
    default void afterCall(GrpcCall call, Throwable error) {
    }
}
