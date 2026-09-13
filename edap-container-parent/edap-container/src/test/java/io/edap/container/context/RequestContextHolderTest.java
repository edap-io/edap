package io.edap.container.context;

import io.edap.mw.context.RequestContext;
import io.edap.mw.context.RequestContextHolder;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RequestContextHolder 单测:set/get/clear + 多线程隔离。
 */
class RequestContextHolderTest {

    @Test
    void currentReturnsNullBeforeSet() {
        // 每个测试方法前清一下,避免其它测试的 ctx 残留
        RequestContextHolder.clear();
        assertNull(RequestContextHolder.current());
    }

    @Test
    void setThenGetReturnsSameInstance() {
        RequestContextHolder.clear();
        RequestContext ctx = new RequestContext("u1", "alice", Set.of("admin"), "t-1");
        RequestContextHolder.set(ctx);
        assertSame(ctx, RequestContextHolder.current());
    }

    @Test
    void clearRemovesCtx() {
        RequestContextHolder.set(new RequestContext("u1", "alice", Set.of(), "t-1"));
        RequestContextHolder.clear();
        assertNull(RequestContextHolder.current());
    }

    @Test
    void anonymousProducesEmptyRolesAndNullUser() {
        RequestContext ctx = RequestContext.anonymous("trace-x");
        assertNull(ctx.userId());
        assertNull(ctx.userName());
        assertTrue(ctx.roles().isEmpty());
        assertEquals("trace-x", ctx.traceId());
    }

    @Test
    void rolesIsImmutable() {
        RequestContext ctx = new RequestContext("u1", "alice", Set.of("admin"), "t-1");
        assertThrows(UnsupportedOperationException.class,
                () -> ctx.roles().add("hacker"));
    }

    @Test
    void threadIsolation() throws Exception {
        RequestContextHolder.clear();
        RequestContext main = new RequestContext("main", null, Set.of(), "t-main");
        RequestContextHolder.set(main);

        AtomicReference<RequestContext> seen = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            // 子线程默认看不到父线程 ctx(ThreadLocal,不是 InheritableThreadLocal)
            seen.set(RequestContextHolder.current());
            done.countDown();
        });
        t.start();
        done.await(2, TimeUnit.SECONDS);

        assertNull(seen.get(), "子线程应看不到父线程的 ctx");
        assertSame(main, RequestContextHolder.current(), "父线程 ctx 不受影响");
        RequestContextHolder.clear();
    }

    @Test
    void concurrentThreadsDoNotLeak() throws Exception {
        RequestContextHolder.clear();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(8);
        for (int i = 0; i < 8; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    RequestContext ctx = new RequestContext("u" + idx, null, Set.of(), "t-" + idx);
                    RequestContextHolder.set(ctx);
                    assertSame(ctx, RequestContextHolder.current());
                    RequestContextHolder.clear();
                    assertNull(RequestContextHolder.current());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        pool.shutdown();
    }
}