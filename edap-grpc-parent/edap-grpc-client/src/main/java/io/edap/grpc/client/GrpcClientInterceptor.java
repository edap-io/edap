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

package io.edap.grpc.client;

import io.edap.rpc.RpcContext;

import java.util.Map;

/**
 * gRPC 客户端的拦截器 —— 请求发出之前 / 调用结束之后各插一刀。
 *
 * <p>典型用途：加鉴权头、注入 traceId、metrics / 审计日志、失败重试（自己实现）。</p>
 *
 * <p>★ <b>装上去的方式</b>：</p>
 * <ol>
 *   <li>编程式：{@code channel.addInterceptor(this)}；</li>
 *   <li>SPI：{@code META-INF/services/io.edap.grpc.client.GrpcClientInterceptor}；</li>
 *   <li>容器 Bean：<b>不适用</b> —— 容器只自动装<b>服务端</b>拦截器
 *       （{@link io.edap.grpc.GrpcServerInterceptor}）；客户端拦截器由使用者自己建通道时
 *       {@code addInterceptor}。</li>
 * </ol>
 *
 * <p>★ <b>顺序</b>：{@link #beforeCall} 正序（先注册先进）、{@link #afterCall} 逆序
 * （后注册的先出）—— 与 Servlet Filter / Netty Pipeline 一致。</p>
 */
public interface GrpcClientInterceptor {

    /**
     * 请求发出之前（在传输层锁外、HEADERS 还没组装）。
     *
     * <p>★ <b>{@code headers} 是可改的</b>：往里 put 一个键值对，框架会把它写进
     * HTTP/2 的请求头 ⇒ 鉴权头、traceId 头就靠它。</p>
     *
     * <p>★ <b>抛异常 ⇒ 本次调用失败</b>：不建流、不上线，框架抛
     * {@link GrpcException}。</p>
     *
     * @param path    被调的方法，形如 {@code /pkg.Svc/Method}
     * @param headers 额外的请求头（键已转成小写）；<b>可改</b>
     * @param context 本次调用的上下文（method / traceId / deadline 都在里面）
     * @throws Exception 拒绝本次调用
     */
    default void beforeCall(String path, Map<String, String> headers, RpcContext context) throws Exception {
    }

    /**
     * 调用结束（收到 trailers / 出错 / 被取消 / 连接关闭）。
     *
     * <p>★ <b>只跑一次</b>。★ <b>抛出的异常被吞掉</b>（框架记一条 warn 日志）——
     * 收尾钩子不该影响调用结果。</p>
     *
     * @param call  本次调用（{@code status()} / {@code responseHeaders()} /
     *              {@code responseTrailers()} / {@code failure()} 都拿得到）
     * @param error 出错原因；{@code null} 表示正常结束
     */
    default void afterCall(GrpcClientCall call, Throwable error) {
    }
}
