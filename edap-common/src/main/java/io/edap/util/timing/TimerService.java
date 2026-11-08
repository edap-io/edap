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
import java.util.concurrent.TimeUnit;

/**
 * 平台级定时服务。
 *
 * <p>时间轮本体用单调时间（{@code System.nanoTime()}）做 deadline，
 * 不受 NTP 校时 / 手动改系统时间影响。</p>
 *
 * <p>投递语义：到点后由 tick 线程投递到 Executor，
 * <b>不保证</b>"到点即开始执行" —— 前面可能有排队延迟。</p>
 */
public interface TimerService extends AutoCloseable {

    /**
     * 相对延迟执行一次。
     */
    TimerHandle schedule(Runnable task, long delay, TimeUnit unit);

    /**
     * 相对延迟执行一次，指定执行器。
     */
    TimerHandle schedule(Runnable task, long delay, TimeUnit unit, Executor executor);

    /**
     * 在给定的单调时刻（{@code System.nanoTime()} 口径）执行一次。
     */
    TimerHandle scheduleAt(Runnable task, long deadlineNanos);

    /**
     * 在给定的单调时刻执行一次，指定执行器。
     */
    TimerHandle scheduleAt(Runnable task, long deadlineNanos, Executor executor);

    /**
     * 固定频率执行。首次在 initialDelay 之后，之后按 period 间隔续期。
     *
     * <p>周期从<b>计划时刻</b>计算，不从上次执行完成时刻计算，
     * 所以执行耗时不会导致漂移；执行耗时超过 period 时不堆积
     * （错过的期次直接跳过，不做追赶）。</p>
     */
    TimerHandle scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit);

    /**
     * 固定频率执行，指定执行器。
     */
    TimerHandle scheduleAtFixedRate(Runnable task, long initialDelay, long period, TimeUnit unit, Executor executor);

    /**
     * 当前待处理任务数（诊断用）。
     */
    int pendingCount();

    /**
     * 关闭：停止 tick 线程，取消所有待处理任务。
     */
    @Override
    void close();
}
