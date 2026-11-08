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
 * 时间轮的推进源（SPI）。
 *
 * <p>两种实现语义相同、可 drop-in 替换：</p>
 * <ul>
 *     <li>{@link NanoTickSource} —— 纯 Java，{@code LockSupport.parkNanos}，零依赖</li>
 *     <li>Linux {@code timerfd} —— 绝对武装，零漂移 + 空闲零唤醒（需 JNI，见 edap-native）</li>
 * </ul>
 */
public interface TickSource extends AutoCloseable {

    /**
     * 阻塞直到 deadline（单调纳秒）到达，或被 {@link #signal()} 提前唤醒。
     *
     * @param deadlineNanos 单调纳秒时刻；小于 0 表示无限等待
     * @return 实际返回时的单调纳秒
     */
    long awaitUntil(long deadlineNanos) throws InterruptedException;

    /**
     * 提前唤醒 {@link #awaitUntil}。用于"有新任务入队"或"需要立即处理逾期任务"。
     */
    void signal();

    @Override
    void close();
}
