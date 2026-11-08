/*
 * Copyright (c) 2019 louis.lu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.edap.util.timing;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 基于分层时间轮的定时服务实现。
 *
 * <p>一个 daemon 线程负责推进轮子（只做"检测到期 + 投递"），业务回调交给 Executor，
 * 慢回调不会阻塞整个平台的定时器。</p>
 *
 * <p>空闲时轮子<b>不空转</b>：没有任务时无限等待，有任务时只睡到"下一次可能被访问的槽"。</p>
 */
public final class WheelTimerService implements TimerService {

    private static final Logger LOG = Logger.getLogger(WheelTimerService.class.getName());

    /**
     * 基准 tick 长度（纳秒）。1ms 与 {@code System.currentTimeMillis()} 的分辨率一致。
     */
    public static final long DEFAULT_BASE_TICK_NANOS = 1_000_000L;

    /**
     * 每层槽数。
     */
    public static final int DEFAULT_WHEEL_SIZE = 128;

    /**
     * 层数。默认 5 层 = 128ms / 16.4s / 35min / 3.1天 / ~1.1年。
     */
    public static final int DEFAULT_LEVELS = 5;

    private static final String TICK_THREAD_NAME = "Edap_timer_thread";

    private final TimingWheel wheel;
    private final TickSource tickSource;
    private final Executor defaultExecutor;
    private final boolean ownsExecutor;
    private final Thread tickThread;
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 用默认参数创建（1ms tick / 128 槽 / 5 层 / 平台共享 daemon 线程池）。
     *
     * <p><b>队列无界</b>：定时任务被丢掉意味着"该释放的资源没释放"，
     * 这种失败模式比队列涨更难查，所以宁可让队列涨也不丢任务。
     * 队列只在"任务产出速度 &gt; 执行速度"时才会涨 —— 轮子的 tick 线程
     * 一次只投递一个任务，慢任务不会自我放大。</p>
     */
    public static WheelTimerService create() {
        int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        ThreadPoolExecutor executor = new ThreadPoolExecutor(threads, threads, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                new io.edap.util.ThreadUtil.DefaultThreadFactory("Edap_timer_worker", true),
                new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return new WheelTimerService(new NanoTickSource(), executor, DEFAULT_BASE_TICK_NANOS,
                DEFAULT_LEVELS, DEFAULT_WHEEL_SIZE, true);
    }

    /**
     * @param tickSource     推进源
     * @param executor       默认执行器（{@code null} 表示用 {@link #create()} 的默认池）
     * @param baseTickNanos  基准 tick 长度（纳秒）
     * @param levels         层数
     * @param wheelSize      每层槽数（2 的幂）
     */
    public WheelTimerService(TickSource tickSource, Executor executor, long baseTickNanos,
                              int levels, int wheelSize) {
        this(tickSource, executor, baseTickNanos, levels, wheelSize, false);
    }

    private WheelTimerService(TickSource tickSource, Executor executor, long baseTickNanos,
                               int levels, int wheelSize, boolean ownsExecutor) {
        this.tickSource = tickSource;
        this.defaultExecutor = executor;
        this.ownsExecutor = ownsExecutor;
        this.wheel = new TimingWheel(baseTickNanos, levels, wheelSize);
        this.tickThread = new Thread(this::tickLoop, TICK_THREAD_NAME);
        this.tickThread.setDaemon(true);
        this.tickThread.start();
    }

    @Override
    public TimerHandle schedule(Runnable task, long delay, TimeUnit unit) {
        return schedule(task, delay, unit, null);
    }

    @Override
    public TimerHandle schedule(Runnable task, long delay, TimeUnit unit, Executor executor) {
        return scheduleInternal(task, unit.toNanos(delay), 0L, executor);
    }

    @Override
    public TimerHandle scheduleAt(Runnable task, long deadlineNanos) {
        return scheduleAt(task, deadlineNanos, null);
    }

    @Override
    public TimerHandle scheduleAt(Runnable task, long deadlineNanos, Executor executor) {
        long delayNanos = deadlineNanos - System.nanoTime();
        if (delayNanos < 0) {
            delayNanos = 0L;
        }
        return scheduleInternal(task, delayNanos, 0L, executor);
    }

    @Override
    public TimerHandle scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit) {
        return scheduleAtFixedRate(task, initialDelay, period, unit, null);
    }

    @Override
    public TimerHandle scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit,
                                           Executor executor) {
        if (period <= 0) {
            throw new IllegalArgumentException("period must be positive");
        }
        return scheduleInternal(task, unit.toNanos(initialDelay), unit.toNanos(period), executor);
    }

    private TimerHandle scheduleInternal(Runnable task, long delayNanos, long periodNanos, Executor executor) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        if (closed.get()) {
            throw new IllegalStateException("timer service is closed");
        }
        long now = System.nanoTime();
        long deadlineNanos = saturatedAdd(now, delayNanos);
        long dueTick = wheel.tickOf(deadlineNanos);
        long periodTicks = periodNanos > 0 ? ticksOf(periodNanos) : 0L;
        long wallDeadline = System.currentTimeMillis() + delayNanos / 1_000_000L;
        TimerTask timerTask = new TimerTask(task, executor != null ? executor : defaultExecutor,
                wallDeadline, periodTicks, pending, dueTick);
        pending.incrementAndGet();
        wheel.submit(timerTask);
        if (closed.get()) {
            // 与 close() 的竞态：任务已经进了轮子，但轮子已被 cancelAll 清空过 ——
            // 把它撤回来，否则它会滞留在 inbox 里，pendingCount 永远回不到 0。
            timerTask.cancel();
            return timerTask;
        }
        tickSource.signal();
        return timerTask;
    }

    private long ticksOf(long nanos) {
        long tickNanos = wheel.baseTickNanos();
        long ticks = nanos / tickNanos;
        if (nanos % tickNanos != 0) {
            ticks++;
        }
        return Math.max(1L, ticks);
    }

    @Override
    public int pendingCount() {
        return pending.get();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        tickSource.close();
        tickThread.interrupt();
        try {
            tickThread.join(2000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        wheel.cancelAll();
        if (ownsExecutor && defaultExecutor instanceof ThreadPoolExecutor) {
            ((ThreadPoolExecutor) defaultExecutor).shutdownNow();
        }
    }

    /**
     * tick 线程：推进轮子 → 投递到期任务 → 睡到下一次可能有任务到期的时刻。
     */
    private void tickLoop() {
        while (!closed.get()) {
            try {
                wheel.drainInbox();
                wheel.advanceTo(wheel.currentTick());
                if (wheel.drainInbox()) {
                    // 刚放进来可能有已经到期的任务，立刻再走一轮
                    continue;
                }
                long nextTick = wheel.nextWakeTick();
                if (nextTick < 0) {
                    // 没有任务，无限等待（等 signal）
                    tickSource.awaitUntil(-1L);
                    continue;
                }
                long wakeNanos = saturatedAdd(wheel.startNanos(),
                        saturatedMultiply(nextTick, wheel.baseTickNanos()));
                long now = System.nanoTime();
                if (wakeNanos <= now) {
                    wakeNanos = now + 100_000L;
                }
                tickSource.awaitUntil(wakeNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "edap timer tick loop error", t);
            }
        }
    }

    private static long saturatedAdd(long a, long b) {
        long r = a + b;
        if (((a ^ r) & (b ^ r)) < 0) {
            return b > 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return r;
    }

    private static long saturatedMultiply(long a, long b) {
        if (a == 0 || b == 0) {
            return 0;
        }
        long r = a * b;
        if (r / b != a) {
            return (a > 0) == (b > 0) ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return r;
    }
}
