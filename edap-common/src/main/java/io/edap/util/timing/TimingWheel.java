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

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 分层时间轮（Hierarchical Timing Wheel）。
 *
 * <p>结构：</p>
 * <pre>
 *   spanTicks[L] = wheelSize^L        // level L 的槽跨度（tick 数）
 *   cursor[L]    = 绝对跨度序号        // level L 当前所在的位置
 *   buckets[L][0..wheelSize-1]        // 各层桶
 *   overflow                         // 超出顶层覆盖范围的任务（按到期时间排序）
 *   overdue                          // 已到期、下一个 tick 无条件投递
 *   inbox                            // 注册入口（跨线程）
 * </pre>
 *
 * <p><b>线程模型</b>：桶结构只由 tick 线程访问。外部线程注册时只往
 * {@code inbox}（ConcurrentLinkedQueue）里塞，tick 线程在下一次推进时 drain 并入桶。
 * 因此<b>无锁、无竞态</b>，代价是注册的任务最多迟一个 tick 生效。</p>
 *
 * <p><b>层级下沉</b>：level L 的槽跨度是 {@code spanTicks[L]} tick，
 * 任务到期 tick 为 {@code k}、当前 tick 为 {@code t}，剩余 {@code delta = k - t}，
 * 落在 {@code [spanTicks[L], spanTicks[L+1])} 的归入 level L。
 * 放置槽位为相对游标 {@code (cursor[L] + m) % wheelSize}，
 * 其中 {@code m = (delta + u) / spanTicks[L]}、{@code u = t mod spanTicks[L]}，
 * 并把 m 夹在 {@code [1, wheelSize-1]}：
 * <ul>
 *     <li>下界 1：保证落在一个"还没被访问过"的槽（m=0 会落进当前槽，任务会被漏掉一整圈）</li>
 *     <li>上界 wheelSize-1：m=wheelSize 同样会绕回当前槽；
 *         夹到 wheelSize-1 后访问时刻 {@code (cursor[L]+m)*spanTicks[L] <= 到期时刻}，只是早不会晚</li>
 * </ul>
 *
 * <p>每个任务的<b>重插入次数与延迟的绝对值无关，只与层数同阶</b>（上界约 2×层数）——
 * 这正是分层相对于"单层 + rounds"的全部意义。</p>
 */
final class TimingWheel {

    /**
     * 每层槽数（2 的幂，用 & mask 代替 %）。
     */
    private final int wheelSize;

    private final int mask;

    /**
     * 层数：level 0..levels-1。
     */
    private final int levels;

    /**
     * spanTicks[L] = wheelSize^L，length = levels + 1。
     */
    private final long[] spanTicks;

    /**
     * cursor[L] = 绝对跨度序号 = floor(cursor[0] / spanTicks[L])。
     */
    private final long[] cursor;

    private final ArrayDeque<TimerTask>[][] buckets;

    /**
     * 各层在桶里的任务数（用于算下一次唤醒时刻）。
     */
    private final int[] levelCount;

    /**
     * 超出顶层覆盖范围的任务，按到期 tick 排序。
     */
    private final PriorityQueue<TimerTask> overflow;

    /**
     * 已经到期（下一次推进无条件投递）的任务。
     */
    private final ArrayDeque<TimerTask> overdue;

    /**
     * 注册入口。跨线程写入，tick 线程 drain。
     */
    private final ConcurrentLinkedQueue<TimerTask> inbox;

    /**
     * 基准 tick 长度（纳秒）。
     */
    private final long baseTickNanos;

    /**
     * tick 原点。
     */
    private final long startNanos;

    /**
     * 换桶时复用的空桶（避免每次推进都 new ArrayDeque）。
     */
    private ArrayDeque<TimerTask> spare;

    /**
     * 轮内任务总数（含 overdue 与 overflow）。
     */
    private int total;

    TimingWheel(long baseTickNanos, int levels, int wheelSize) {
        if (baseTickNanos <= 0) {
            throw new IllegalArgumentException("baseTickNanos must be positive");
        }
        if (levels < 1) {
            throw new IllegalArgumentException("levels must be at least 1");
        }
        if (wheelSize < 2 || (wheelSize & (wheelSize - 1)) != 0) {
            throw new IllegalArgumentException("wheelSize must be a power of 2 and at least 2");
        }
        this.baseTickNanos = baseTickNanos;
        this.levels = levels;
        this.wheelSize = wheelSize;
        this.mask = wheelSize - 1;
        this.spanTicks = new long[levels + 1];
        this.spanTicks[0] = 1;
        for (int i = 1; i <= levels; i++) {
            if (this.spanTicks[i - 1] > Long.MAX_VALUE / wheelSize) {
                throw new IllegalArgumentException("wheel size too large for " + levels + " levels");
            }
            this.spanTicks[i] = this.spanTicks[i - 1] * wheelSize;
        }
        this.cursor = new long[levels];
        this.buckets = new ArrayDeque[levels][wheelSize];
        for (int L = 0; L < levels; L++) {
            for (int s = 0; s < wheelSize; s++) {
                this.buckets[L][s] = new ArrayDeque<>();
            }
        }
        this.levelCount = new int[levels];
        this.overflow = new PriorityQueue<>(Comparator.comparingLong(TimerTask::dueTick));
        this.overdue = new ArrayDeque<>();
        this.inbox = new ConcurrentLinkedQueue<>();
        this.spare = new ArrayDeque<>();
        this.startNanos = System.nanoTime();
    }

    long baseTickNanos() {
        return baseTickNanos;
    }

    long startNanos() {
        return startNanos;
    }

    /**
     * 当前 tick（向下取整）：轮子已经推进到的位置。
     */
    long currentTick() {
        return (System.nanoTime() - startNanos) / baseTickNanos;
    }

    /**
     * 把单调纳秒时刻换算成到期 tick（向上取整）—— 保证不早于该时刻触发。
     */
    long tickOf(long deadlineNanos) {
        long diff = saturatedSubtract(deadlineNanos, startNanos);
        if (diff <= 0) {
            return 0;
        }
        long ticks = diff / baseTickNanos;
        if (diff % baseTickNanos != 0) {
            ticks++;
        }
        return ticks;
    }

    private static long saturatedSubtract(long a, long b) {
        long r = a - b;
        if (((a ^ b) & (a ^ r)) < 0) {
            return a >= b ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
        return r;
    }

    /**
     * 注册（任意线程可调用）：只入 inbox，实际入桶由 tick 线程完成。
     */
    void submit(TimerTask task) {
        inbox.add(task);
    }

    /**
     * 把 inbox 里的任务搬进桶（只允许 tick 线程调用）。
     *
     * @return 是否有任务被搬进来
     */
    boolean drainInbox() {
        boolean drained = false;
        TimerTask task;
        while ((task = inbox.poll()) != null) {
            place(task, task.dueTick());
            drained = true;
        }
        return drained;
    }

    /**
     * 放置任务到合适的层与槽（只允许 tick 线程调用）。
     */
    void place(TimerTask task, long dueTick) {
        task.dueTick(dueTick);
        long delta = dueTick - cursor[0];
        if (delta <= 0) {
            overdue.add(task);
            total++;
            return;
        }
        int level = 0;
        while (level < levels && delta >= spanTicks[level + 1]) {
            level++;
        }
        if (level >= levels) {
            overflow.add(task);
            total++;
            return;
        }
        long span = spanTicks[level];
        long c = cursor[0] / span;
        long u = cursor[0] - c * span;
        long m = (delta + u) / span;
        if (m < 1) {
            m = 1;
        }
        if (m > wheelSize - 1) {
            m = wheelSize - 1;
        }
        int slot = (int) ((c + m) & mask);
        buckets[level][slot].add(task);
        levelCount[level]++;
        total++;
    }

    /**
     * 推进轮子到 toTick（只允许 tick 线程调用）。
     *
     * <p>推进顺序<b>由细到粗</b>：粗层下沉到细层时，细层必须已经推进到当前 tick，
     * 否则下沉的任务会落进一个"已经被访问过"的槽里被漏掉。</p>
     */
    void advanceTo(long toTick) {
        if (toTick > cursor[0]) {
            long steps = toTick - cursor[0];
            if (steps >= wheelSize) {
                // 跨过整整一圈：level 0 里的任务全部已到期
                cursor[0] = toTick;
                for (int slot = 0; slot < wheelSize; slot++) {
                    drainBucket(0, slot);
                }
            } else {
                while (cursor[0] < toTick) {
                    cursor[0]++;
                    drainBucket(0, (int) (cursor[0] & mask));
                }
            }
        }
        for (int level = 1; level < levels; level++) {
            long span = spanTicks[level];
            long target = toTick / span;
            long cur = cursor[level];
            if (cur >= target) {
                continue;
            }
            if (target - cur >= wheelSize) {
                cursor[level] = target;
                for (int slot = 0; slot < wheelSize; slot++) {
                    drainBucket(level, slot);
                }
            } else {
                while (cursor[level] < target) {
                    cursor[level]++;
                    drainBucket(level, (int) (cursor[level] & mask));
                }
            }
        }
        while (!overflow.isEmpty() && overflow.peek().dueTick() <= cursor[0]) {
            TimerTask task = overflow.poll();
            total--;
            if (task.isCancelled()) {
                continue;
            }
            task.fire(this);
        }
        while (!overdue.isEmpty()) {
            TimerTask task = overdue.poll();
            total--;
            if (task.isCancelled()) {
                continue;
            }
            task.fire(this);
        }
    }

    /**
     * 取出并处理一个桶里的任务。
     */
    private void drainBucket(int level, int slot) {
        ArrayDeque<TimerTask> bucket = buckets[level][slot];
        if (bucket.isEmpty()) {
            return;
        }
        // 换出空桶：处理期间重新落进这个槽的任务不会污染本次遍历
        buckets[level][slot] = spare;
        spare = bucket;
        try {
            TimerTask task;
            while ((task = bucket.poll()) != null) {
                levelCount[level]--;
                total--;
                if (task.isCancelled()) {
                    continue;
                }
                if (task.dueTick() <= cursor[0]) {
                    task.fire(this);
                } else {
                    // 还没到期：下沉到更细的层（下一层跨度更小，精度更高）
                    place(task, task.dueTick());
                }
            }
        } finally {
            // 异常路径（理论上 dispatch 已兜住，但不该把整桶任务押在调用方身上）：
            // 把还没处理完的任务放回原槽，下一轮访问该槽时继续处理。
            // 放回不调整 levelCount/total —— 只有被 poll 出来的那批才减过。
            ArrayDeque<TimerTask> target = buckets[level][slot];
            TimerTask rest;
            while ((rest = bucket.poll()) != null) {
                target.add(rest);
            }
            spare = bucket;
        }
    }

    /**
     * 下一次唤醒的 tick 时刻；-1 表示当前没有任务，可以无限等待。
     *
     * <p>取各层"下一次被访问"的最小值 —— 保守（可能早醒，不会漏醒）。</p>
     */
    long nextWakeTick() {
        if (total == 0) {
            return -1;
        }
        long best = Long.MAX_VALUE;
        for (int level = 0; level < levels; level++) {
            if (levelCount[level] > 0) {
                long t = (cursor[level] + 1) * spanTicks[level];
                if (t < best) {
                    best = t;
                }
            }
        }
        if (!overflow.isEmpty()) {
            long t = overflow.peek().dueTick();
            if (t < best) {
                best = t;
            }
        }
        if (!overdue.isEmpty()) {
            best = cursor[0] + 1;
        }
        return best;
    }

    /**
     * 取消轮内所有任务（close 时调用）。
     */
    void cancelAll() {
        TimerTask task;
        while ((task = inbox.poll()) != null) {
            task.cancel();
        }
        for (int level = 0; level < levels; level++) {
            for (int slot = 0; slot < wheelSize; slot++) {
                while ((task = buckets[level][slot].poll()) != null) {
                    levelCount[level]--;
                    total--;
                    task.cancel();
                }
            }
        }
        while ((task = overflow.poll()) != null) {
            total--;
            task.cancel();
        }
        while ((task = overdue.poll()) != null) {
            total--;
            task.cancel();
        }
        // spare：换桶技巧留下的备用桶。正常路径下恒为空，
        // 异常路径下可能残留尚未处理的任务 —— 漏掉它们会导致 pendingCount 永久非零。
        while ((task = spare.poll()) != null) {
            total--;
            task.cancel();
        }
    }

    /**
     * 轮子当前推进到的 tick（测试与诊断用）。
     */
    long cursorTick() {
        return cursor[0];
    }

    /**
     * 轮内任务总数（诊断用）。
     */
    int size() {
        return total;
    }
}
