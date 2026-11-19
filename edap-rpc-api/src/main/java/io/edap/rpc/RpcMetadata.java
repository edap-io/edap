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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一次 RPC 调用的<b>可上线元数据</b> —— 一组「键 → 字符串值」。
 *
 * <p>★ <b>它与 {@link RpcContext} 的属性表（{@code put(Key, Object)}）是两个不同的东西</b>：</p>
 * <table border="1">
 *   <caption>属性 vs 元数据</caption>
 *   <tr><th></th><th>属性（{@code RpcContext.put}）</th><th>元数据（本类）</th></tr>
 *   <tr><td>会不会上线</td><td><b>不会</b>（纯进程内）</td><td><b>会</b>（随请求帧发给对端）</td></tr>
 *   <tr><td>键</td><td>{@link RpcContext.Key}（identity 语义、强类型）</td>
 *       <td>{@link String}（小写 ASCII，线上可见）</td></tr>
 *   <tr><td>值</td><td>任意 {@code Object}</td><td>字符串</td></tr>
 *   <tr><td>典型用途</td><td>把「已经算好的东西」递给下游代码</td>
 *       <td><b>跨进程传播</b>：分布式事务的 txId、灰度标签、链路标签……</td></tr>
 * </table>
 *
 * <p><b>★ 为什么元数据不灌进属性表</b>：属性表的键是 {@link RpcContext.Key}（identity 语义），
 * 值是任意 {@code Object} —— 而「上线」要求的是<b>稳定的、可被别的进程读懂的</b>键值对。
 * 两者语义不同，各管各的。</p>
 *
 * <p><b>★ 键的约束</b>：小写 ASCII 字母开头，其余为 {@code [a-z0-9-_.]}
 * （与 gRPC 的 metadata 规矩一致）—— 大小写不敏感的对端才不会读错。
 * 违反约束时 {@link #put} <b>fail-fast 抛异常</b>，不静默丢。</p>
 *
 * <p><b>★ 线程安全</b>：内部是 {@link ConcurrentHashMap}。
 * 典型用法是「客户端拦截器在请求发出前写、传输层读」，两边不在一个线程上。</p>
 *
 * <p>用法：</p>
 * <pre>{@code
 *   RpcContext ctx = RpcContext.current();
 *   String txId = ctx.metadata().get("txid");
 *   ctx.metadata().put("txid", "0f9a-42");
 * }</pre>
 */
public final class RpcMetadata {

    /** 共享的只读空实例 —— 没有绑定上下文时用它。 */
    private static final RpcMetadata EMPTY = new RpcMetadata(Collections.emptyMap(), false);

    /** 键值表；只读实例是 {@link Collections#emptyMap()}。 */
    private final Map<String, String> entries;

    /** 是否可写 —— 只读实例写时抛异常。 */
    private final boolean writable;

    private RpcMetadata(Map<String, String> entries, boolean writable) {
        this.entries = entries;
        this.writable = writable;
    }

    /**
     * 共享的<b>只读空</b>元数据。
     *
     * <p>往里写会抛 {@link IllegalStateException} —— 它是全局共享的同一个实例，
     * 一个线程误写就会污染所有线程。</p>
     */
    public static RpcMetadata empty() {
        return EMPTY;
    }

    /**
     * 新建一个<b>可写</b>的元数据容器。
     */
    public static RpcMetadata create() {
        return new RpcMetadata(new ConcurrentHashMap<>(), true);
    }

    /**
     * 取值。
     *
     * @param key 键
     * @return 值；没有则返回 {@code null}
     */
    public String get(String key) {
        if (key == null) {
            return null;
        }
        return entries.get(key);
    }

    /**
     * 是否有这个键。
     */
    public boolean containsKey(String key) {
        return key != null && entries.containsKey(key);
    }

    /**
     * 设值（{@code value} 为 {@code null} 相当于移除）。
     *
     * @param key   键（小写 ASCII 字母开头，其余 {@code [a-z0-9-_.]}）
     * @param value 值
     * @return 本对象（便于链式调用）
     * @throws IllegalArgumentException 键不合法
     * @throws IllegalStateException    本对象是只读的
     */
    public RpcMetadata put(String key, String value) {
        if (!writable) {
            throw new IllegalStateException(
                "当前线程没有绑定 RPC 上下文，不能写元数据 —— 请在 handler 内使用，或先 RpcContext.attach(ctx)");
        }
        checkKey(key);
        if (value == null) {
            entries.remove(key);
        } else {
            entries.put(key, value);
        }
        return this;
    }

    /**
     * 移除一个键。
     *
     * @return 被移除的值；没有则返回 {@code null}
     */
    public String remove(String key) {
        if (key == null) {
            return null;
        }
        return entries.remove(key);
    }

    /**
     * 当前所有键。
     */
    public Set<String> keys() {
        return entries.isEmpty() ? Collections.emptySet() : entries.keySet();
    }

    /**
     * 当前所有键值对的一份<b>快照</b>（顺序不定）。
     *
     * <p>★ <b>要遍历全部条目就用这个</b>：{@link #keys()} 拿到的是活视图，
     * 遍历期间别人改了表会拿到不一致的结果（甚至取到 null 值）。</p>
     *
     * @return 快照（不可变）
     */
    public Map<String, String> asMap() {
        return entries.isEmpty() ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /**
     * 是否一个都没有。
     */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * 条目数。
     */
    public int size() {
        return entries.size();
    }

    /**
     * 校验键：非空、小写 ASCII 字母开头、其余为 {@code [a-z0-9-_.]}。
     */
    private static void checkKey(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("元数据键不能为空");
        }
        if (key.length() > 0xFFFF) {
            throw new IllegalArgumentException("元数据键过长: " + key.length() + " 字节");
        }
        char c0 = key.charAt(0);
        if (c0 < 'a' || c0 > 'z') {
            throw new IllegalArgumentException("元数据键必须以小写 ASCII 字母开头: " + key);
        }
        for (int i = 1; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.';
            if (!ok) {
                throw new IllegalArgumentException("元数据键含非法字符 '" + c + "': " + key);
            }
        }
    }

    @Override
    public String toString() {
        return "RpcMetadata" + entries;
    }
}
