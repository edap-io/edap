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

import java.util.concurrent.locks.LockSupport;

/**
 * 基于 {@code LockSupport.parkNanos} 的推进源，纯 Java 实现。
 *
 * <p>单次唤醒成本约 10µs（内核 hrtimer + 调度器唤醒），这是操作系统地板，
 * 换实现只能省个位数百分比 —— timerfd 的价值在于<b>唤醒次数</b>（空闲零唤醒）
 * 和<b>零漂移</b>（绝对武装），不是单次成本。</p>
 */
public final class NanoTickSource implements TickSource {

    /**
     * 提前唤醒标记。只由等待方消费，等待方不会在进入等待前清零，
     * 避免"信号先到、等待方后清零"造成的丢唤醒。
     */
    private volatile boolean signalled;

    /**
     * 等待线程（第一次 awaitUntil 时绑定）。
     */
    private volatile Thread owner;

    @Override
    public long awaitUntil(long deadlineNanos) throws InterruptedException {
        if (owner == null) {
            owner = Thread.currentThread();
        }
        while (true) {
            if (signalled) {
                signalled = false;
                return System.nanoTime();
            }
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            long now = System.nanoTime();
            if (deadlineNanos >= 0 && now >= deadlineNanos) {
                return now;
            }
            long wait = deadlineNanos < 0 ? Long.MAX_VALUE : deadlineNanos - now;
            LockSupport.parkNanos(wait);
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
        }
    }

    @Override
    public void signal() {
        signalled = true;
        Thread t = owner;
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    @Override
    public void close() {
        signal();
    }
}
