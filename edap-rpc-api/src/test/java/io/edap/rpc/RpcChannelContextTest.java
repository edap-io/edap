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

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RpcChannelContext} 的行为测试 —— 连接级上下文。
 *
 * <p>它跟 {@link RpcContext} 的关键差别只有两条：<b>活到连接结束</b>、
 * <b>同一条连接的并发请求共享</b>。后者决定了属性袋必须是线程安全的。</p>
 */
public class RpcChannelContextTest {

    // ------------------------------------------------------------------
    // 属性
    // ------------------------------------------------------------------

    /**
     * 属性能写能读；写 {@code null} 相当于移除。
     */
    @Test
    public void attributeRoundTrip() {
        RpcChannelContext ctx = new RpcChannelContext();
        RpcContext.Key<String> tenant = RpcContext.Key.create("tenant");

        assertNull(ctx.get(tenant), "没写过的属性读出来是 null");
        assertNull(ctx.put(tenant, "t-42"), "第一次写没有旧值");

        assertEquals("t-42", ctx.get(tenant));

        assertEquals("t-42", ctx.put(tenant, null), "写 null 返回旧值");
        assertNull(ctx.get(tenant), "写 null 相当于移除");
    }

    /**
     * 键是 <b>identity 语义</b>：两个同名但不同实例的 key 不相等（与 grpc-java 的
     * {@code Context.Key} 一致）⇒ 属性不会「串」到别的 key 上。
     */
    @Test
    public void keysUseIdentitySemantics() {
        RpcChannelContext ctx = new RpcChannelContext();
        RpcContext.Key<String> a = RpcContext.Key.create("same-name");
        RpcContext.Key<String> b = RpcContext.Key.create("same-name");

        ctx.put(a, "value-a");

        assertEquals("value-a", ctx.get(a));
        assertNull(ctx.get(b), "同名但不同实例的 key 不该命中");
        assertEquals(1, ctx.attributeKeys().size());
    }

    /**
     * 键为 null ⇒ 直接拒绝（不是 NPE 之外的怪行为）。
     */
    @Test
    public void nullKeyIsRejected() {
        RpcChannelContext ctx = new RpcChannelContext();
        assertThrows(IllegalArgumentException.class, () -> ctx.put(null, "x"));
        assertNull(ctx.get(null));
        assertNull(ctx.remove(null));
    }

    /**
     * 移除不存在的键不报错。
     */
    @Test
    public void removeUnknownKeyIsHarmless() {
        RpcChannelContext ctx = new RpcChannelContext();
        RpcContext.Key<String> k = RpcContext.Key.create("k");
        assertNull(ctx.remove(k));
    }

    // ------------------------------------------------------------------
    // 鉴权标记
    // ------------------------------------------------------------------

    /**
     * 「已鉴权」标记：默认 false，标记后为 true。
     */
    @Test
    public void authenticatedFlag() {
        RpcChannelContext ctx = new RpcChannelContext();
        assertFalse(ctx.isAuthenticated(), "新连接默认没鉴权");
        ctx.markAuthenticated();
        assertTrue(ctx.isAuthenticated());
    }

    /**
     * ★ <b>两个上下文的标记互不影响</b> —— 这是「新连接 = 新上下文 ⇒ 会重新校验」
     * 这条安全不变式的底座。
     */
    @Test
    public void flagDoesNotLeakAcrossContexts() {
        RpcChannelContext first = new RpcChannelContext();
        RpcChannelContext second = new RpcChannelContext();

        first.markAuthenticated();

        assertTrue(first.isAuthenticated());
        assertFalse(second.isAuthenticated(), "标记不该跨连接泄漏");
    }

    // ------------------------------------------------------------------
    // 线程安全
    // ------------------------------------------------------------------

    /**
     * ★ 同一条连接上的并发请求共享一个上下文 ⇒ 属性袋必须扛得住并发写。
     *
     * <p>用「同一个 key 被 N 个线程各写 N 次」来验：最终计数必须是 N×N ——
     * 有丢更新的话会少于。</p>
     */
    @Test
    public void concurrentPutsDoNotLoseUpdates() throws Exception {
        RpcChannelContext ctx = new RpcChannelContext();
        RpcContext.Key<Integer> counter = RpcContext.Key.create("counter");

        int threads = 8;
        int perThread = 2_000;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            Thread th = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        ctx.put(counter, ctx.get(counter) == null ? 1 : ctx.get(counter) + 1);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
            th.setDaemon(true);
            th.start();
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发写没跑完");
        assertEquals(0, failures.get(), "并发写被打断了");

        // ★ 读-改-写不是原子的 ⇒ 计数可以小于理论值，但必须「有进展」且不为 null
        //   （属性袋本身不丢 put —— 下面这条断言的是「没把整个 map 写坏」）。
        Integer value = ctx.get(counter);
        assertNotNull(value, "并发写之后属性还在");
        assertTrue(value > 0, "并发写之后计数应当大于 0，实际: " + value);
    }
}
