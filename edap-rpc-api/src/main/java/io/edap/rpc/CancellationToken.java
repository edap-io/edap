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

import java.util.ArrayList;
import java.util.List;

/**
 * 取消信号 —— 「这次调用已经不需要继续了」的协作式通知。
 *
 * <p><b>三个终态</b>：</p>
 * <ul>
 *   <li><b>挂起</b>：还没取消也没完成。</li>
 *   <li><b>已取消</b>：{@link #cancel(Throwable)} 触发，业务应当尽快中止。</li>
 *   <li><b>已完成</b>：{@link #complete()} 触发 —— 调用正常结束，
 *       <b>不是</b>取消。否则业务在收尾阶段会误判「自己被取消了」。</li>
 * </ul>
 *
 * <p><b>谁会 trip 它</b>：</p>
 * <ul>
 *   <li>客户端调用 {@code cancel()}（gRPC 发 RST_STREAM，eRPC 本地取消）；</li>
 *   <li>服务端发现对端断开；</li>
 *   <li>调用正常结束 → {@code complete()}。</li>
 * </ul>
 *
 * <p><b>★ 协作式，不是强制式</b>：token 只是通知，真正停下来靠业务自己检查
 * （{@code ctx.cancellation().isCancelled()}）。业务可以注册
 * {@link #onCancel(Runnable)} 做清理动作（关连接、停线程）。</p>
 *
 * <p>线程安全：所有状态变更都在锁内完成；回调在锁外执行，避免业务回调里再
 * 调本类的方法造成死锁。</p>
 */
public final class CancellationToken {

    private boolean cancelled;
    private boolean completed;
    private Throwable reason;
    private final List<Runnable> callbacks = new ArrayList<>();

    /**
     * 是否已被取消。
     */
    public synchronized boolean isCancelled() {
        return cancelled;
    }

    /**
     * 是否已正常完成（调用结束但不是被取消）。
     */
    public synchronized boolean isCompleted() {
        return completed;
    }

    /**
     * 取消原因。
     *
     * @return 取消原因；未取消时为 {@code null}
     */
    public synchronized Throwable reason() {
        return reason;
    }

    /**
     * 取消。
     *
     * <p>幂等：只有第一次调用生效。已取消或已完成时返回 {@code false}。</p>
     *
     * @param cause 取消原因
     * @return true 表示这次调用真的取消了
     */
    public boolean cancel(Throwable cause) {
        List<Runnable> toRun;
        synchronized (this) {
            if (cancelled || completed) {
                return false;
            }
            cancelled = true;
            reason = cause;
            toRun = new ArrayList<>(callbacks);
            callbacks.clear();
        }
        runAll(toRun);
        return true;
    }

    /**
     * 标记调用正常结束。
     *
     * <p>与 {@link #cancel(Throwable)} 互斥：已经取消的调用再 complete 是空操作，
     * 已经完成的调用再 cancel 也是空操作。</p>
     */
    public void complete() {
        synchronized (this) {
            if (cancelled || completed) {
                return;
            }
            completed = true;
        }
    }

    /**
     * 注册一个「被取消时执行」的回调。
     *
     * <p>已经取消 ⇒ 立即执行；已经完成 ⇒ 忽略；否则登记，等取消时执行。</p>
     *
     * <p>回调最多执行一次。</p>
     *
     * @param r 回调
     */
    public void onCancel(Runnable r) {
        if (r == null) {
            return;
        }
        boolean runNow = false;
        synchronized (this) {
            if (cancelled) {
                runNow = true;
            } else if (!completed) {
                callbacks.add(r);
            }
        }
        if (runNow) {
            r.run();
        }
    }

    private static void runAll(List<Runnable> list) {
        for (Runnable r : list) {
            try {
                r.run();
            } catch (Throwable ignore) {
                // 取消回调自己的异常不能影响取消流程
            }
        }
    }

    @Override
    public String toString() {
        synchronized (this) {
            if (cancelled) {
                return "CancellationToken{cancelled, reason=" + reason + '}';
            }
            if (completed) {
                return "CancellationToken{completed}";
            }
            return "CancellationToken{pending}";
        }
    }
}
