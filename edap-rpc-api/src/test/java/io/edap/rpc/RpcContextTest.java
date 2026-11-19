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

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RpcContext} 与 {@link CancellationToken} 的行为测试。
 */
public class RpcContextTest {

    private static final RpcContext.Key<String> TENANT = RpcContext.Key.create("tenant");
    private static final RpcContext.Key<String> OTHER = RpcContext.Key.create("tenant");

    // ==================================================================
    // thread-local 绑定
    // ==================================================================

    @Test
    public void attachAndDetach() {
        assertNull(RpcContext.currentOrNull(), "测试开始时不该有绑定");

        RpcContext ctx = RpcContext.builder().protocol(RpcProtocol.GRPC).method("/a/B").build();
        try (RpcContext.Scope scope = RpcContext.attach(ctx)) {
            assertSame(ctx, RpcContext.current());
            assertSame(ctx, RpcContext.currentOrNull());
        }
        assertNull(RpcContext.currentOrNull(), "解绑后不该残留");
    }

    @Test
    public void attachRestoresPreviousContext() {
        RpcContext outer = RpcContext.builder().method("/outer").build();
        RpcContext inner = RpcContext.builder().method("/inner").build();
        try (RpcContext.Scope s1 = RpcContext.attach(outer)) {
            try (RpcContext.Scope s2 = RpcContext.attach(inner)) {
                assertSame(inner, RpcContext.current());
            }
            assertSame(outer, RpcContext.current(), "内层作用域关闭后应还原外层");
        }
        assertNull(RpcContext.currentOrNull());
    }

    @Test
    public void noLeakAcrossReusedThread() {
        // 线程池复用场景：同一个线程连续处理多个请求，上一个的上下文不能泄漏到下一个
        RpcContext first = RpcContext.builder().method("/first").build();
        RpcContext second = RpcContext.builder().method("/second").build();
        try (RpcContext.Scope s = RpcContext.attach(first)) {
            assertSame(first, RpcContext.current());
        }
        assertNull(RpcContext.currentOrNull(), "上一个请求的上下文泄漏了");
        try (RpcContext.Scope s = RpcContext.attach(second)) {
            assertSame(second, RpcContext.current());
        }
        assertNull(RpcContext.currentOrNull());
    }

    @Test
    public void currentReturnsSharedRootWhenUnbound() {
        RpcContext root = RpcContext.current();
        assertNotNull(root);
        assertSame(root, RpcContext.current(), "未绑定时返回的是同一个共享实例");
        assertNull(root.protocol());
        // ROOT 是只读的 —— 往里写会污染所有线程
        assertThrows(IllegalStateException.class, () -> root.put(TENANT, "x"));
    }

    @Test
    public void detachRemovesBinding() {
        RpcContext ctx = RpcContext.builder().build();
        RpcContext.attach(ctx);
        assertSame(ctx, RpcContext.currentOrNull());
        RpcContext.detach();
        assertNull(RpcContext.currentOrNull());
    }

    // ==================================================================
    // 跨线程传播
    // ==================================================================

    @Test
    public void wrapPropagatesToAnotherThread() throws Exception {
        RpcContext ctx = RpcContext.builder().protocol(RpcProtocol.ERPC).method("erpc:7").build();
        AtomicReference<RpcContext> seen = new AtomicReference<>();
        AtomicReference<RpcContext> leftover = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Runnable task;
        try (RpcContext.Scope scope = RpcContext.attach(ctx)) {
            task = RpcContext.wrap(() -> {
                seen.set(RpcContext.currentOrNull());
                latch.countDown();
            });
        }

        Thread t = new Thread(() -> {
            task.run();
            leftover.set(RpcContext.currentOrNull());
        }, "ctx-propagation");
        t.start();
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        t.join();

        assertSame(ctx, seen.get(), "子线程应该看到捕获的上下文");
        assertNull(leftover.get(), "执行完不该在子线程留下绑定");
        assertNull(RpcContext.currentOrNull(), "捕获线程的绑定不该被污染");
    }

    @Test
    public void wrapDoesNotReplaceExecutorThreadContext() throws Exception {
        RpcContext outer = RpcContext.builder().method("/outer").build();
        RpcContext captured = RpcContext.builder().method("/captured").build();
        AtomicReference<RpcContext> during = new AtomicReference<>();
        AtomicReference<RpcContext> after = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Runnable task;
        try (RpcContext.Scope s = RpcContext.attach(outer)) {
            try (RpcContext.Scope s2 = RpcContext.attach(captured)) {
                task = RpcContext.wrap(() -> {
                    during.set(RpcContext.currentOrNull());
                    latch.countDown();
                });
            }
            // 回到 outer —— 捕获的是 captured，但不影响当前线程的绑定
            assertSame(outer, RpcContext.current());
            Thread t = new Thread(task, "ctx-executor");
            t.start();
            assertTrue(latch.await(5, TimeUnit.SECONDS));
            t.join();
            after.set(RpcContext.currentOrNull());
        }

        assertSame(captured, during.get(), "任务里应该看到捕获的上下文");
        assertSame(outer, after.get(), "捕获线程的绑定不该被换掉");
    }

    // ==================================================================
    // 属性
    // ==================================================================

    @Test
    public void attributesAreTypedByKey() {
        RpcContext ctx = RpcContext.builder().build();
        assertNull(ctx.get(TENANT));
        assertNull(ctx.put(TENANT, "acme"));
        assertEquals("acme", ctx.get(TENANT));
        assertEquals("acme", ctx.put(TENANT, "other"));
        assertEquals("other", ctx.get(TENANT));
        assertEquals("other", ctx.remove(TENANT));
        assertNull(ctx.get(TENANT));
    }

    @Test
    public void keysAreIdentityBased() {
        // 两个同名但不同实例的 key 不相等 —— 与 grpc-java 的 Context.Key 一致
        RpcContext ctx = RpcContext.builder().build();
        ctx.put(TENANT, "a");
        assertNull(ctx.get(OTHER));
        assertEquals(1, ctx.attributeKeys().size());
    }

    // ==================================================================
    // deadline
    // ==================================================================

    @Test
    public void noDeadlineMeansNoExpiry() {
        RpcContext ctx = RpcContext.builder().build();
        assertEquals(0L, ctx.deadlineNanos());
        assertFalse(ctx.isExpired());
        assertEquals(Long.MAX_VALUE, ctx.remainingNanos());
        assertEquals(Long.MAX_VALUE, ctx.remainingMillis());
    }

    @Test
    public void deadlineExpires() throws Exception {
        RpcContext ctx = RpcContext.builder().timeoutMillis(30).build();
        assertTrue(ctx.deadlineNanos() > 0);
        assertFalse(ctx.isExpired());
        assertTrue(ctx.remainingMillis() <= 30);
        Thread.sleep(60);
        assertTrue(ctx.isExpired());
        assertEquals(0L, ctx.remainingMillis());
    }

    @Test
    public void onDeadlineFires() throws Exception {
        RpcContext ctx = RpcContext.builder().timeoutMillis(30).build();
        CountDownLatch latch = new CountDownLatch(1);
        ctx.onDeadline(latch::countDown);
        assertTrue(latch.await(5, TimeUnit.SECONDS), "deadline 回调没触发");
    }

    @Test
    public void clearDeadlinePreventsCallback() throws Exception {
        RpcContext ctx = RpcContext.builder().timeoutMillis(40).build();
        CountDownLatch latch = new CountDownLatch(1);
        ctx.onDeadline(latch::countDown);
        ctx.clearDeadline();
        assertFalse(latch.await(200, TimeUnit.MILLISECONDS), "撤销后回调不该触发");
    }

    // ==================================================================
    // 取消信号
    // ==================================================================

    @Test
    public void cancelIsIdempotent() {
        CancellationToken token = new CancellationToken();
        assertFalse(token.isCancelled());
        assertTrue(token.cancel(new RuntimeException("boom")));
        assertTrue(token.isCancelled());
        assertFalse(token.cancel(new RuntimeException("again")), "第二次取消应该无效");
        assertEquals("boom", token.reason().getMessage());
    }

    @Test
    public void completeIsNotCancel() {
        CancellationToken token = new CancellationToken();
        token.complete();
        assertTrue(token.isCompleted());
        assertFalse(token.isCancelled());
        assertFalse(token.cancel(new RuntimeException("x")), "已完成后再取消应该无效");
    }

    @Test
    public void cancelAfterCompleteIsIgnored() {
        CancellationToken token = new CancellationToken();
        token.complete();
        token.cancel(new RuntimeException("x"));
        assertFalse(token.isCancelled());
    }

    @Test
    public void onCancelRunsOnce() {
        CancellationToken token = new CancellationToken();
        int[] count = {0};
        token.onCancel(() -> count[0]++);
        token.cancel(new RuntimeException("x"));
        token.cancel(new RuntimeException("y"));
        assertEquals(1, count[0]);
    }

    @Test
    public void onCancelAfterCancelRunsImmediately() {
        CancellationToken token = new CancellationToken();
        token.cancel(new RuntimeException("x"));
        int[] count = {0};
        token.onCancel(() -> count[0]++);
        assertEquals(1, count[0]);
    }

    @Test
    public void onCancelAfterCompleteIsIgnored() {
        CancellationToken token = new CancellationToken();
        token.complete();
        int[] count = {0};
        token.onCancel(() -> count[0]++);
        assertEquals(0, count[0]);
    }
}
