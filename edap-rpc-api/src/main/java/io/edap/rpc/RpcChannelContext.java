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

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一条<b>连接</b>的上下文 —— 放在共用的 {@code edap-rpc-api} 里，
 * <b>目前只有 eRPC 两端在用</b>（gRPC 确定不接入：它的连接对象在 HTTP/2 层，不在 gRPC 模块内）。
 *
 * <p>★ <b>它与 {@link RpcContext} 的分工</b>：</p>
 * <table border="1">
 *   <caption>两级上下文</caption>
 *   <tr><th></th><th>{@link RpcContext}</th><th>本类</th></tr>
 *   <tr><td>生命周期</td><td>一次调用</td><td>一条连接</td></tr>
 *   <tr><td>装什么</td><td>身份 / deadline / 取消信号</td>
 *       <td>连接级状态（已鉴权、租户、协商结果……）</td></tr>
 *   <tr><td>怎么拿到</td><td>{@code RpcContext.current()}</td>
 *       <td>拦截器钩子的参数 / 连接对象上的 getter</td></tr>
 * </table>
 *
 * <p><b>典型用途</b>：<b>权限校验只做一次</b> —— 校验通过后把「已鉴权」标记记在这条连接上，
 * 后续同一连接的请求直接放行：</p>
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
 *
 * <p>★ <b>安全不变量：新连接 = 新上下文</b>：连接断了（或连接池重建了连接），
 * 上下文随之丢弃 ⇒ 标记不会跨连接泄漏 ⇒ 新连接上的第一个请求会重新校验。
 * 反过来说，<b>只要连接不断就不必重复校验</b>。</p>
 *
 * <p>★ <b>线程安全</b>：同一条连接上的并发请求共享同一个实例 ⇒ 属性袋是
 * {@link ConcurrentHashMap}，鉴权标记是 {@code volatile} 布尔。</p>
 *
 * <p>★ <b>属性键用 {@link RpcContext.Key}</b>（identity 语义，同 grpc-java 的
 * {@code Context.Key}）：同一个 {@code Key} 实例在任何地方都是同一个键。
 * 应用要更细的粒度（按应用、按租户）就自己定义 {@code static final} 键：</p>
 * <pre>{@code
 *   static final RpcContext.Key<String> TENANT = RpcContext.Key.create("tenant");
 *   channel.put(TENANT, "t-42");
 * }</pre>
 */
public final class RpcChannelContext {

    /**
     * 认证主体的键 —— 握手校验通过后，认证主体（用户名 / 租户 / 令牌指纹……）记在这里。
     *
     * <p>用 {@link RpcContext.Key} 而不是裸 {@code String}：键是 identity 语义，
     * 同一个 {@code Key} 实例在任何地方都是同一个键。</p>
     */
    public static final RpcContext.Key<String> PRINCIPAL = RpcContext.Key.create("principal");

    /** 属性表 —— 键是 {@link RpcContext.Key}（identity 语义），值任意。 */
    private final ConcurrentHashMap<RpcContext.Key<?>, Object> attributes = new ConcurrentHashMap<>();

    /**
     * 「本连接已通过鉴权」的标记。
     *
     * ★ <b>它是进程级的一个布尔</b>：容器里 gRPC / eRPC Server 是进程级共享的，
     * 多个应用跑在同一条连接上时，这个标记对它们是同一个。
     * 需要按应用区分 ⇒ 各自用自己的 {@link RpcContext.Key} 存标记，别用这个。
     */
    private volatile boolean authenticated;

    // ------------------------------------------------------------------
    // 属性
    // ------------------------------------------------------------------

    /**
     * 取属性。
     *
     * @param key 键
     * @return 属性值；没有则返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    public <T> T get(RpcContext.Key<T> key) {
        if (key == null) {
            return null;
        }
        return (T) attributes.get(key);
    }

    /**
     * 设置属性。
     *
     * @param key   键
     * @param value 值（{@code null} 相当于移除）
     * @return 旧值；没有则返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    public <T> T put(RpcContext.Key<T> key, T value) {
        if (key == null) {
            throw new IllegalArgumentException("属性键不能为 null");
        }
        if (value == null) {
            return (T) attributes.remove(key);
        }
        return (T) attributes.put(key, value);
    }

    /**
     * 移除属性。
     *
     * @param key 键
     * @return 被移除的值；没有则返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    public <T> T remove(RpcContext.Key<T> key) {
        if (key == null) {
            return null;
        }
        return (T) attributes.remove(key);
    }

    /**
     * 当前所有属性的键。
     */
    public Set<RpcContext.Key<?>> attributeKeys() {
        return attributes.isEmpty() ? Collections.emptySet() : attributes.keySet();
    }

    // ------------------------------------------------------------------
    // 鉴权标记
    // ------------------------------------------------------------------

    /**
     * 本连接是否已经通过鉴权。
     *
     * @return true 表示已鉴权
     */
    public boolean isAuthenticated() {
        return authenticated;
    }

    /**
     * 标记「本连接已通过鉴权」。
     *
     * <p>★ <b>只在真的校验通过之后才调</b>：调了它，同一条连接的后续请求
     * 就不再走校验流程了。</p>
     */
    public void markAuthenticated() {
        this.authenticated = true;
    }

    @Override
    public String toString() {
        return "RpcChannelContext{authenticated=" + authenticated
            + ", attributes=" + attributes.size() + '}';
    }
}
