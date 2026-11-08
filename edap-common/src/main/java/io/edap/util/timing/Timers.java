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

/**
 * 平台共享的定时服务入口。
 *
 * <p>全平台<b>一个</b>定时服务：统一时钟口径、统一线程模型、统一生命周期。
 * 消费方（Container 的延迟释放、NIO 会话空闲回收、Raft tick 驱动）都从这里拿，
 * 不再各自开 {@code ScheduledExecutorService}。</p>
 *
 * <p><b>懒创建</b>：第一次 {@link #get()} 才建轮子和线程池 —— 不用定时器的模块
 * （比如只用 {@link io.edap.util.EdapTime} 取时间）不付这份开销。</p>
 *
 * <p><b>任务不会被静默丢弃</b>：平台实例的执行器用<b>无界</b>队列 ——
 * 定时任务被丢掉意味着"该释放的资源没释放"，这种失败模式比队列涨更难查。
 * 队列只在"任务产出速度 &gt; 执行速度"时才会涨。</p>
 *
 * <p><b>关闭</b>：{@link #close()} 停掉轮子与线程池并清空引用，之后再 {@link #get()}
 * 会重新建一个（容器重启场景可用）。关掉之后<b>已注册的任务全部作废</b> ——
 * 需要在关停前先取消自己的任务。</p>
 */
public final class Timers {

    /**
     * 平台共享实例。volatile + 双重检查锁：读多写少（读方每次拿定时任务都要访问）。
     */
    private static volatile TimerService instance;

    private Timers() {
    }

    /**
     * 平台共享的定时服务（懒创建）。
     */
    public static TimerService get() {
        TimerService svc = instance;
        if (svc == null) {
            synchronized (Timers.class) {
                svc = instance;
                if (svc == null) {
                    svc = WheelTimerService.create();
                    instance = svc;
                }
            }
        }
        return svc;
    }

    /**
     * 当前是否已经建过平台实例（诊断用 —— 不触发创建）。
     */
    public static boolean isCreated() {
        return instance != null;
    }

    /**
     * 关闭平台实例并清空引用。之后 {@link #get()} 会重新创建一个。
     *
     * <p>幂等。没建过实例时是空操作。</p>
     */
    public static void close() {
        TimerService svc;
        synchronized (Timers.class) {
            svc = instance;
            instance = null;
        }
        if (svc != null) {
            svc.close();
        }
    }
}
