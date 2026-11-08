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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 定时任务的内部表示。
 *
 * <p>状态机（一次性任务）：</p>
 * <pre>
 *   PENDING --(投递)--> DISPATCHED
 *      |
 *      +--(取消)---> CANCELLED
 * </pre>
 *
 * <p>周期任务不进入 DISPATCHED：每一期都从 PENDING 走，
 * 取消标志 {@code cancelled} 阻止后续续期。</p>
 */
final class TimerTask implements TimerHandle, Runnable {

    private static final Logger LOG = Logger.getLogger(TimerTask.class.getName());

    static final int PENDING = 0;
    static final int CANCELLED = 1;
    static final int DISPATCHED = 2;

    private final Runnable delegate;
    private final Executor executor;

    /**
     * 到期时刻的墙钟毫秒快照（对外可读）。
     */
    private final long deadlineWallMillis;

    /**
     * 周期任务的间隔（tick 数）；0 表示一次性任务。
     */
    private final long periodTicks;

    private final AtomicInteger state = new AtomicInteger(PENDING);

    /**
     * 用户取消标记，幂等。
     */
    private final AtomicBoolean cancelled = new AtomicBoolean();

    /**
     * 服务级的待处理计数（注册 +1，任务离开待处理状态 -1）。
     */
    private final AtomicInteger pendingCounter;

    /**
     * 当前到期 tick（周期任务续期时会变）。
     */
    private volatile long dueTick;

    TimerTask(Runnable delegate, Executor executor, long deadlineWallMillis, long periodTicks,
              AtomicInteger pendingCounter, long dueTick) {
        this.delegate = delegate;
        this.executor = executor;
        this.deadlineWallMillis = deadlineWallMillis;
        this.periodTicks = periodTicks;
        this.pendingCounter = pendingCounter;
        this.dueTick = dueTick;
    }

    long dueTick() {
        return dueTick;
    }

    void dueTick(long tick) {
        this.dueTick = tick;
    }

    boolean isPeriodic() {
        return periodTicks > 0;
    }

    /**
     * 触发。一次性任务在此完成状态迁移并投递；周期任务在此续期。
     * 只允许 tick 线程调用。
     */
    void fire(TimingWheel wheel) {
        if (isPeriodic()) {
            if (cancelled.get()) {
                return;
            }
            dispatch();
            if (cancelled.get()) {
                // 投递与取消竞态：不再续期
                return;
            }
            long next = dueTick + periodTicks;
            // 不追赶错过的期次：tick 线程迟到时直接跳过，避免补偿性突发
            long current = wheel.cursorTick();
            while (next <= current) {
                next += periodTicks;
            }
            wheel.place(this, next);
            if (cancelled.get()) {
                // 取消与续期竞态：把刚续上的这一期也作废（桶里的条目到期时会被跳过）
                cancel();
            }
            return;
        }
        if (state.compareAndSet(PENDING, DISPATCHED)) {
            pendingCounter.decrementAndGet();
            dispatch();
        }
    }

    private void dispatch() {
        try {
            executor.execute(this);
        } catch (RejectedExecutionException e) {
            LOG.log(Level.WARNING, "edap timer task rejected by executor", e);
        } catch (Throwable t) {
            // 执行器是调用方给的，它抛什么都不能穿出到 tick 线程 ——
            // 否则整个桶的剩余任务会随异常一起丢掉（pendingCount 也永久泄漏）
            LOG.log(Level.WARNING, "edap timer task dispatch failed", t);
        }
    }

    @Override
    public void run() {
        try {
            delegate.run();
        } catch (Throwable t) {
            // 异常隔离：任务抛出的异常不影响轮子与其他任务
            LOG.log(Level.WARNING, "edap timer task execution failed", t);
        }
    }

    @Override
    public boolean cancel() {
        if (isPeriodic()) {
            if (!cancelled.compareAndSet(false, true)) {
                return false;
            }
            pendingCounter.decrementAndGet();
            return true;
        }
        // 一次性任务：以状态迁移为准。已投递（DONE）的任务迁移失败 → 返回 false，
        // 且不置 cancelled 标志 —— 否则 isCancelled() 会把"已经执行过的任务"误报成已取消。
        if (!state.compareAndSet(PENDING, CANCELLED)) {
            return false;
        }
        pendingCounter.decrementAndGet();
        return true;
    }

    @Override
    public boolean isCancelled() {
        return isPeriodic() ? cancelled.get() : state.get() == CANCELLED;
    }

    @Override
    public long deadlineWallMillis() {
        return deadlineWallMillis;
    }
}
