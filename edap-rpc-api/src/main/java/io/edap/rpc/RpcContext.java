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

package io.edap.rpc;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 一次 RPC 调用的上下文 —— gRPC 与 eRPC 共用。
 *
 * <p>它承载五样东西：</p>
 * <ol>
 *   <li><b>身份</b>：协议、方法、请求号、链路号；</li>
 *   <li><b>deadline</b>：还能活多久（{@code nanoTime} 基准的绝对时刻）；</li>
 *   <li><b>取消信号</b>：{@link CancellationToken}；</li>
 *   <li><b>键值属性</b>：强类型 {@link Key} 索引（<b>进程内，不上线</b>）；</li>
 *   <li><b>元数据</b>：{@link RpcMetadata}（<b>会随请求帧上线</b>，用于跨进程传播）。</li>
 * </ol>
 *
 * <p><b>★ 怎么拿到它</b>：</p>
 * <pre>{@code
 *   RpcContext ctx = RpcContext.current();          // 当前线程绑定的上下文
 *   long left = ctx.remainingMillis();               // 还剩多久
 *   if (ctx.cancellation().isCancelled()) { ... }    // 协作式取消
 * }</pre>
 *
 * <p>框架在调用业务 handler 之前 {@link #attach(RpcContext) 绑定}、之后解绑，
 * 所以业务方法体里 {@link #current()} 一定拿得到正确的上下文。</p>
 *
 * <p><b>★ 为什么不用 {@code InheritableThreadLocal}</b>：NIO 事件循环 + 线程池场景下，
 * 父线程的上下文透传到子线程会污染独立请求的语义（子线程可能处理的是另一个请求）。
 * 需要跨线程时用 {@link #wrap(Runnable)} 显式传播 —— 沿用 edap 项目
 * {@code edap-microservice-context} 的既有规约。</p>
 *
 * <p><b>★ deadline 的不变式</b>：线上只传「相对时长」，本地只存「绝对 nanoTime 时刻」
 * （见 {@link RpcTimeout}）。</p>
 */
public final class RpcContext {

    // ------------------------------------------------------------------
    // thread-local 绑定
    // ------------------------------------------------------------------

    private static final ThreadLocal<RpcContext> CURRENT = new ThreadLocal<>();

    /**
     * 未绑定时的共享空上下文。
     *
     * <p>它是<b>只读</b>的：往里写属性会抛异常 —— 否则一个线程误写就会污染
     * 所有线程（它是全局共享的同一个实例）。</p>
     */
    private static final RpcContext ROOT = new RpcContext(
        null, "", 0L, 0L, 0L, new CancellationToken(), null, null);

    // ------------------------------------------------------------------
    // deadline 定时器
    // ------------------------------------------------------------------

    /**
     * deadline 定时器 —— 单线程、守护。
     *
     * <p>只有<b>带 deadline</b> 的调用才会往这里排任务；没有 deadline 的调用零开销。</p>
     */
    private static final ScheduledThreadPoolExecutor DEADLINE_TIMER = createDeadlineTimer();

    private static ScheduledThreadPoolExecutor createDeadlineTimer() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "edap-rpc-deadline");
            t.setDaemon(true);
            return t;
        });
        // 取消掉的任务立刻从队列里移除 —— 否则长连接服务会攒下一堆僵尸任务
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    // ------------------------------------------------------------------
    // 实例字段
    // ------------------------------------------------------------------

    private final RpcProtocol protocol;
    private final String method;
    /**
     * 请求号 —— <b>可后填</b>（见 {@link #assignRequestId(long)}）。
     *
     * <p>服务端侧在解码帧头时就知道它；客户端侧它是<b>传输层分配</b>的
     * （连接内唯一、递增）⇒ 上下文先由调用方建好，请求号随后补上。</p>
     */
    private volatile long requestId;
    private final long traceId;
    private final long deadlineNanos;
    private final CancellationToken cancellation;

    /** 属性表；null 表示「还没有属性」—— 首次 put 时才创建，避免每次调用都分配 map。 */
    private volatile Map<Key<?>, Object> attributes;

    /**
     * <b>可上线</b>的元数据；null 表示「还没有」—— 首次访问 {@link #metadata()} 时才创建。
     *
     * <p>★ <b>与 {@link #attributes} 的区别</b>：属性是<b>进程内</b>的（不上线），
     * 元数据会随请求帧发给对端（跨进程传播）。</p>
     */
    private volatile RpcMetadata metadata;

    /** deadline 到期回调的定时任务句柄。 */
    private volatile ScheduledFuture<?> deadlineTask;

    private RpcContext(RpcProtocol protocol, String method, long requestId, long traceId,
                       long deadlineNanos, CancellationToken cancellation,
                       Map<Key<?>, Object> attributes, RpcMetadata metadata) {
        this.protocol = protocol;
        this.method = method;
        this.requestId = requestId;
        this.traceId = traceId;
        this.deadlineNanos = deadlineNanos;
        this.cancellation = cancellation;
        this.attributes = attributes;
        this.metadata = metadata;
    }

    // ==================================================================
    // 身份
    // ==================================================================

    /**
     * 协议。
     *
     * @return 协议；未绑定的 ROOT 上返回 {@code null}
     */
    public RpcProtocol protocol() {
        return protocol;
    }

    /**
     * 被调用的方法。
     *
     * @return gRPC 是 {@code /pkg.Svc/Method}；eRPC 是 {@code erpc:<methodId>}
     */
    public String method() {
        return method;
    }

    /**
     * 请求号 —— 同一连接内唯一。
     *
     * <p>★ <b>客户端侧它在写帧前是 0</b>：客户端的上下文由调用方创建，
     * 而请求号由传输层分配 ⇒ 由传输层在写帧前 {@link #assignRequestId(long) 补上}。</p>
     */
    public long requestId() {
        return requestId;
    }

    /**
     * 填上请求号 —— <b>由传输层在分配请求号后调用</b>。
     *
     * <p>★ <b>为什么要可写</b>：一次调用只有一个上下文对象（取消信号、deadline
     * 都得是同一个），但客户端的请求号是连接内递增分配的、只有传输层知道
     * ⇒ 只能「先建上下文、后填请求号」。</p>
     *
     * @param requestId 请求号
     * @return 本上下文（便于链式调用）
     */
    public RpcContext assignRequestId(long requestId) {
        if (this == ROOT) {
            throw new IllegalStateException(
                "当前线程没有绑定 RPC 上下文，不能填请求号 —— 请在 handler 内使用，或先 RpcContext.attach(ctx)");
        }
        this.requestId = requestId;
        return this;
    }

    /**
     * 链路号。
     */
    public long traceId() {
        return traceId;
    }

    // ==================================================================
    // deadline
    // ==================================================================

    /**
     * deadline 的绝对时刻（{@link System#nanoTime()} 基准）。
     *
     * @return 绝对时刻；0 表示「无 deadline」
     */
    public long deadlineNanos() {
        return deadlineNanos;
    }

    /**
     * 还剩多久。
     *
     * @return 剩余纳秒；无 deadline 返回 {@link Long#MAX_VALUE}；已过期返回 0
     */
    public long remainingNanos() {
        if (deadlineNanos == 0) {
            return Long.MAX_VALUE;
        }
        long remain = deadlineNanos - System.nanoTime();
        return remain > 0 ? remain : 0L;
    }

    /**
     * 还剩多少毫秒。
     *
     * @return 剩余毫秒；无 deadline 返回 {@link Long#MAX_VALUE}；已过期返回 0
     */
    public long remainingMillis() {
        if (deadlineNanos == 0) {
            return Long.MAX_VALUE;
        }
        long remain = deadlineNanos - System.nanoTime();
        return remain > 0 ? remain / 1_000_000L : 0L;
    }

    /**
     * deadline 是否已过。
     */
    public boolean isExpired() {
        // 用减法比较而不是 >= —— nanoTime 溢出时减法仍然正确
        return deadlineNanos != 0 && System.nanoTime() - deadlineNanos >= 0;
    }

    /**
     * 注册「deadline 到期」的回调。
     *
     * <p>框架用它来真正掐断调用（gRPC 写 DEADLINE_EXCEEDED trailers、
     * eRPC 写 ERROR 帧）。同一个上下文可以注册多个回调。</p>
     *
     * <p>没有 deadline 时本方法什么都不做。</p>
     *
     * @param action 到期时执行的动作
     */
    public void onDeadline(Runnable action) {
        if (action == null || deadlineNanos == 0) {
            return;
        }
        long delay = deadlineNanos - System.nanoTime();
        if (delay <= 0) {
            // 已经过期 —— 立即执行
            action.run();
            return;
        }
        ScheduledFuture<?> task = DEADLINE_TIMER.schedule(action, delay, TimeUnit.NANOSECONDS);
        ScheduledFuture<?> prev = this.deadlineTask;
        if (prev != null) {
            prev.cancel(false);
        }
        this.deadlineTask = task;
    }

    /**
     * 撤销已注册的 deadline 回调 —— 调用正常结束时调用。
     */
    public void clearDeadline() {
        ScheduledFuture<?> task = this.deadlineTask;
        if (task != null) {
            task.cancel(false);
            this.deadlineTask = null;
        }
    }

    // ==================================================================
    // 取消
    // ==================================================================

    /**
     * 取消信号。
     */
    public CancellationToken cancellation() {
        return cancellation;
    }

    // ==================================================================
    // 属性
    // ==================================================================

    /**
     * 取属性。
     *
     * @param key 键
     * @return 属性值；没有则返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    public <T> T get(Key<T> key) {
        if (key == null) {
            return null;
        }
        Map<Key<?>, Object> map = this.attributes;
        return map == null ? null : (T) map.get(key);
    }

    /**
     * 设置属性。
     *
     * @param key   键
     * @param value 值（{@code null} 相当于移除）
     * @return 旧值；没有则返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    public <T> T put(Key<T> key, T value) {
        if (key == null) {
            throw new IllegalArgumentException("属性键不能为 null");
        }
        if (this == ROOT) {
            throw new IllegalStateException(
                "当前线程没有绑定 RPC 上下文，不能写属性 —— 请在 handler 内使用，或先 RpcContext.attach(ctx)");
        }
        Map<Key<?>, Object> map = this.attributes;
        if (map == null) {
            synchronized (this) {
                map = this.attributes;
                if (map == null) {
                    map = new ConcurrentHashMap<>();
                    this.attributes = map;
                }
            }
        }
        if (value == null) {
            return (T) map.remove(key);
        }
        return (T) map.put(key, value);
    }

    /**
     * 移除属性。
     *
     * @param key 键
     * @return 被移除的值；没有则返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    public <T> T remove(Key<T> key) {
        if (key == null) {
            return null;
        }
        Map<Key<?>, Object> map = this.attributes;
        return map == null ? null : (T) map.remove(key);
    }

    /**
     * 当前所有属性的键。
     */
    public Set<Key<?>> attributeKeys() {
        Map<Key<?>, Object> map = this.attributes;
        return map == null ? Collections.emptySet() : map.keySet();
    }

    // ==================================================================
    // 元数据
    // ==================================================================

    /**
     * 本次调用的<b>可上线元数据</b>。
     *
     * <p>★ <b>与属性表的区别</b>：属性（{@link #put(Key, Object)}）是<b>进程内</b>的，
     * 不上线；元数据会随请求帧发给对端 ⇒ 用来做<b>跨进程传播</b>
     * （分布式事务的 txId、灰度标签……）。</p>
     *
     * <p>★ <b>永远不会返回 {@code null}</b>：没绑上下文时返回共享的只读空实例
     * （往里写会抛 {@link IllegalStateException}）。</p>
     *
     * @return 元数据容器
     */
    public RpcMetadata metadata() {
        if (this == ROOT) {
            return RpcMetadata.empty();
        }
        RpcMetadata m = this.metadata;
        if (m == null) {
            synchronized (this) {
                m = this.metadata;
                if (m == null) {
                    m = RpcMetadata.create();
                    this.metadata = m;
                }
            }
        }
        return m;
    }

    /**
     * 本次调用<b>有没有</b>元数据 —— <b>不触发懒创建</b>。
     *
     * <p>★ <b>传输层用它决定「这一帧要不要带上下文段」</b>：没有就不带 ⇒
     * 线上帧与「不带元数据」时<b>字节级一致</b>（默认路径零开销）。</p>
     *
     * <p>★ 为什么不能直接问 {@link #metadata()}：那个方法会<b>懒创建</b>一个容器
     * （连带一个 map）⇒ 每次调用都白分配两个对象。</p>
     *
     * @return true 表示确实带了元数据
     */
    public boolean hasMetadata() {
        RpcMetadata m = this.metadata;
        return m != null && !m.isEmpty();
    }

    // ==================================================================
    // thread-local 绑定
    // ==================================================================

    /**
     * 当前线程绑定的上下文。
     *
     * @return 上下文；未绑定时返回一个<b>共享的只读空上下文</b>（不会抛异常）
     */
    public static RpcContext current() {
        RpcContext ctx = CURRENT.get();
        return ctx == null ? ROOT : ctx;
    }

    /**
     * 当前线程绑定的上下文。
     *
     * @return 上下文；未绑定时返回 {@code null}
     */
    public static RpcContext currentOrNull() {
        return CURRENT.get();
    }

    /**
     * 把上下文绑定到当前线程。
     *
     * <p>框架在调业务 handler 前调用，返回的 {@link Scope} 在 handler 返回后
     * {@code close()} —— 还原成绑定之前的上下文。</p>
     *
     * @param ctx 要绑定的上下文；{@code null} 表示解绑
     * @return 作用域对象
     */
    public static Scope attach(RpcContext ctx) {
        RpcContext previous = CURRENT.get();
        if (ctx == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(ctx);
        }
        return new Scope(previous);
    }

    /**
     * 解绑当前线程的上下文。
     */
    public static void detach() {
        CURRENT.remove();
    }

    /**
     * 把任务包装成「携带当前上下文」的任务 —— 用于显式跨线程传播。
     *
     * <p>上下文在<b>包装时</b>捕获，在<b>执行时</b>绑定 —— 执行线程原有的上下文
     * 不受影响，执行完也会还原。</p>
     *
     * @param task 任务
     * @return 包装后的任务
     */
    public static Runnable wrap(Runnable task) {
        if (task == null) {
            return null;
        }
        RpcContext captured = CURRENT.get();
        if (captured == null) {
            return task;
        }
        return () -> {
            try (Scope scope = attach(captured)) {
                task.run();
            }
        };
    }

    /**
     * 把任务包装成「携带当前上下文」的任务 —— 用于显式跨线程传播。
     *
     * @param task 任务
     * @param <T>  返回值类型
     * @return 包装后的任务
     */
    public static <T> Callable<T> wrap(Callable<T> task) {
        if (task == null) {
            return null;
        }
        RpcContext captured = CURRENT.get();
        if (captured == null) {
            return task;
        }
        return () -> {
            try (Scope scope = attach(captured)) {
                return task.call();
            }
        };
    }

    /**
     * 绑定作用域 —— 用 try-with-resources 还原绑定前的上下文。
     */
    public static final class Scope implements AutoCloseable {

        private final RpcContext previous;
        private boolean closed;

        private Scope(RpcContext previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (previous == null) {
                // ThreadLocal.remove() 而不是 set(null) —— 避免 Entry 持有 null value 残留
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    // ==================================================================
    // 强类型键
    // ==================================================================

    /**
     * 属性的强类型键 —— <b>identity 语义</b>（两个同名但不同实例的 key 不相等），
     * 与 grpc-java 的 {@code Context.Key} 一致。
     *
     * <p>用法：把 key 定义成 {@code static final} 常量。</p>
     *
     * @param <T> 值类型
     */
    public static final class Key<T> {

        private final String name;

        private Key(String name) {
            this.name = name;
        }

        /**
         * 创建一个键。
         *
         * @param name 名字（只用于诊断信息）
         * @param <T>  值类型
         * @return 键
         */
        public static <T> Key<T> create(String name) {
            return new Key<>(name);
        }

        /**
         * 键的名字。
         */
        public String name() {
            return name;
        }

        @Override
        public String toString() {
            return "RpcContext.Key(" + name + ')';
        }
    }

    // ==================================================================
    // 构造
    // ==================================================================

    /**
     * 新建一个上下文构建器。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 上下文构建器。
     */
    public static final class Builder {

        private RpcProtocol protocol;
        private String method = "";
        private long requestId;
        private long traceId;
        private long deadlineNanos;
        private CancellationToken cancellation = new CancellationToken();
        private RpcMetadata metadata;

        private Builder() {
        }

        public Builder protocol(RpcProtocol protocol) {
            this.protocol = protocol;
            return this;
        }

        public Builder method(String method) {
            this.method = method == null ? "" : method;
            return this;
        }

        public Builder requestId(long requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder traceId(long traceId) {
            this.traceId = traceId;
            return this;
        }

        /**
         * 设置 deadline 的绝对时刻（{@code nanoTime} 基准）。
         *
         * @param deadlineNanos 绝对时刻；0 表示「无 deadline」
         */
        public Builder deadlineNanos(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
            return this;
        }

        /**
         * 设置 deadline 为「从现在起还剩 timeoutMillis 毫秒」。
         *
         * @param timeoutMillis 时长（毫秒）；{@code <= 0} 表示「无 deadline」
         */
        public Builder timeoutMillis(long timeoutMillis) {
            this.deadlineNanos = RpcTimeout.deadlineFromNow(timeoutMillis);
            return this;
        }

        public Builder cancellation(CancellationToken cancellation) {
            this.cancellation = cancellation == null ? new CancellationToken() : cancellation;
            return this;
        }

        /**
         * 预置元数据容器（可上线的那一份）。
         *
         * @param metadata 元数据；{@code null} ⇒ 调用时再懒创建
         */
        public Builder metadata(RpcMetadata metadata) {
            this.metadata = metadata;
            return this;
        }

        public RpcContext build() {
            return new RpcContext(protocol, method, requestId, traceId, deadlineNanos,
                cancellation, null, metadata);
        }
    }

    @Override
    public String toString() {
        return "RpcContext{protocol=" + protocol
            + ", method='" + method + '\''
            + ", requestId=" + requestId
            + ", traceId=" + traceId
            + ", deadlineNanos=" + deadlineNanos
            + ", cancelled=" + cancellation.isCancelled()
            + '}';
    }
}
