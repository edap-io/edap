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

package io.edap.rpc;

import java.util.List;

/**
 * 拦截器链的执行器 —— gRPC / eRPC 的客户端与服务端共用。
 *
 * <p>★ <b>顺序规约</b>（Servlet Filter / Netty Pipeline 语义）：</p>
 * <ul>
 *   <li>{@link #before} <b>正序</b>：先注册先进；</li>
 *   <li>{@link #after} <b>逆序</b>：后注册的先出（像栈一样展开）。</li>
 * </ul>
 *
 * <p>★ <b>失败语义</b>：</p>
 * <ul>
 *   <li>{@link #before} 里抛异常 ⇒ <b>立刻停下</b>并原样抛出 ⇒ 调用方据此<b>拒绝本次调用</b>，
 *       业务代码不执行；</li>
 *   <li>{@link #after} 里抛异常 ⇒ <b>吞掉</b>，不影响后面的拦截器、也不影响调用结果
 *       （记日志由调用方自己的 lambda 负责）。</li>
 * </ul>
 *
 * <p>★ <b>为什么放在 {@code edap-rpc-api}</b>：本模块刻意「零运行时依赖」（只有 JDK），
 * 两个协议模块引入它都不会带来传递依赖。拦截器链的顺序/失败语义是两个协议共有的规矩，
 * 写一份比抄四份好。</p>
 */
public final class InterceptorChain {

    /**
     * 对单个拦截器做的动作。
     *
     * @param <T> 拦截器类型
     */
    @FunctionalInterface
    public interface Action<T> {
        /**
         * 跑一个拦截器。
         *
         * @param interceptor 拦截器
         * @throws Exception 拦截器自己抛的异常
         */
        void run(T interceptor) throws Exception;
    }

    private InterceptorChain() {
    }

    /**
     * 正序跑「请求进来」这一段。
     *
     * @param interceptors 拦截器列表（{@code null} / 空 ⇒ 什么都不做）
     * @param action       对单个拦截器做的事
     * @param <T>          拦截器类型
     * @throws Exception 任何一个拦截器抛的异常（原样抛出，调用方据此拒绝调用）
     */
    public static <T> void before(List<T> interceptors, Action<T> action) throws Exception {
        if (interceptors == null || interceptors.isEmpty()) {
            return;
        }
        for (T interceptor : interceptors) {
            action.run(interceptor);
        }
    }

    /**
     * 逆序跑「调用结束」这一段。
     *
     * <p>单个拦截器抛的异常被吞掉 —— 收尾钩子不该影响调用结果，也不该挡住后面的拦截器。</p>
     *
     * @param interceptors 拦截器列表（{@code null} / 空 ⇒ 什么都不做）
     * @param action       对单个拦截器做的事
     * @param <T>          拦截器类型
     */
    public static <T> void after(List<T> interceptors, Action<T> action) {
        if (interceptors == null || interceptors.isEmpty()) {
            return;
        }
        for (int i = interceptors.size() - 1; i >= 0; i--) {
            try {
                action.run(interceptors.get(i));
            } catch (Throwable ignore) {
                // 收尾钩子抛异常不影响调用结果 —— 调用方的 lambda 自己记日志
            }
        }
    }
}
