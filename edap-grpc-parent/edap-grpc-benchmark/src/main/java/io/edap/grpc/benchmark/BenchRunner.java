/*
 * Copyright 2026 The edap Project
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

package io.edap.grpc.benchmark;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 压测循环 —— N 个 worker 线程共享一条连接，各自循环发起一元调用直到测量窗口结束。
 *
 * <p><b>测量口径</b>：</p>
 * <ul>
 *   <li>预热一段固定时间（不记录延迟），让 JIT 把编解码器、HPACK 表这些跑热；</li>
 *   <li>测量窗口也是时间制 —— 不同负载的耗时可控；</li>
 *   <li>每个 worker 用<b>自己的</b>延迟缓冲（不共享容器），避免容器争抢污染测量；</li>
 *   <li>样本超过上限时按 2 的幂抽稀（保留偶数下标的样本），内存有界、样本仍均匀分布在时间轴上。</li>
 * </ul>
 *
 * <p><b>为什么正反顺序各跑一轮</b>：JIT 预热与 GC 的顺序偏差会系统性地影响先跑的那一方。
 * 外部（{@link BenchMain}）负责把「edap 先跑」和「google 先跑」各做一轮，取中位数。</p>
 */
public final class BenchRunner {

    /**
     * 一次一元调用。返回值累加到校验和里，避免结果被 JIT 当成无用计算优化掉。
     */
    public interface Op {
        long call() throws Exception;
    }

    /** 每个 worker 的延迟样本上限（超过就抽稀）。 */
    private static final int MAX_SAMPLES = 100_000;

    private BenchRunner() {
    }

    /**
     * 跑一轮压测。
     *
     * @param framework     框架名（写进结果里）
     * @param blobSize      blob 的字节数
     * @param concurrency   worker 线程数（共享一条连接）
     * @param warmupMillis  预热时长
     * @param measureMillis 测量时长
     * @param op            一次调用的动作
     */
    public static BenchResult run(String framework, int blobSize, int concurrency,
                                  long warmupMillis, long measureMillis, Op op) throws Exception {
        RunState state = new RunState(concurrency);
        Worker[] workers = new Worker[concurrency];
        Thread[] threads = new Thread[concurrency];
        for (int i = 0; i < concurrency; i++) {
            workers[i] = new Worker(op, state);
            threads[i] = new Thread(workers[i], "bench-" + framework + "-" + blobSize + "-" + i);
            threads[i].setDaemon(true);
            threads[i].start();
        }
        state.workers = workers;
        state.ready.await();

        // 预热（不记录延迟）
        runPhase(state, warmupMillis, false);

        // 测量前清零
        for (Worker w : workers) {
            w.reset();
        }

        long start = System.nanoTime();
        runPhase(state, measureMillis, true);
        double elapsedMillis = (System.nanoTime() - start) / 1_000_000.0;

        // 收工
        state.shutdown();
        for (Thread t : threads) {
            t.join(2000);
        }

        // 汇总
        long ops = 0;
        long errors = 0;
        String firstError = null;
        int sampleCount = 0;
        long[] merged = new long[1024];
        for (Worker w : workers) {
            ops += w.ops;
            errors += w.errors;
            if (firstError == null && w.firstError != null) {
                firstError = w.firstError;
            }
            long[] s = w.latencies.snapshot();
            if (sampleCount + s.length > merged.length) {
                long[] bigger = new long[Math.max(merged.length * 2, sampleCount + s.length)];
                System.arraycopy(merged, 0, bigger, 0, sampleCount);
                merged = bigger;
            }
            System.arraycopy(s, 0, merged, sampleCount, s.length);
            sampleCount += s.length;
        }
        long[] samples = Arrays.copyOf(merged, sampleCount);
        Arrays.sort(samples);
        long sum = 0;
        for (long s : samples) {
            sum += s;
        }

        return new BenchResult(framework, blobSize, concurrency, ops, errors, elapsedMillis,
                percentile(samples, 50), percentile(samples, 90), percentile(samples, 99),
                samples.length == 0 ? 0 : samples[samples.length - 1] / 1000.0,
                samples.length == 0 ? 0 : (double) sum / samples.length / 1000.0,
                firstError);
    }

    /**
     * 投递一个阶段并等所有 worker 做完。
     *
     * <p>★ 阶段必须<b>广播</b>给每一个 worker —— 如果只放在一个共享槽位里，
     * 第一个取到它的 worker 会把槽位清空，其余 worker 永远等不到这个阶段，
     * {@code phase.done} 的计数永远降不到 0（死锁）。</p>
     */
    private static void runPhase(RunState state, long millis, boolean recording) throws InterruptedException {
        Phase phase = new Phase(System.nanoTime() + millis * 1_000_000L, recording,
                new CountDownLatch(state.concurrency));
        synchronized (state.lock) {
            for (Worker w : state.workers) {
                w.current = phase;
            }
            state.lock.notifyAll();
        }
        phase.done.await();
    }

    /**
     * 分位数（样本单位是纳秒，返回微秒）。
     */
    private static double percentile(long[] sorted, int p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= sorted.length) {
            idx = sorted.length - 1;
        }
        return sorted[idx] / 1000.0;
    }

    // ------------------------------------------------------------------
    // 内部结构
    // ------------------------------------------------------------------

    /**
     * 一个测量阶段。
     */
    private static final class Phase {
        final long deadlineNanos;
        final boolean recording;
        final CountDownLatch done;

        Phase(long deadlineNanos, boolean recording, CountDownLatch done) {
            this.deadlineNanos = deadlineNanos;
            this.recording = recording;
            this.done = done;
        }
    }

    /**
     * worker 之间的共享状态（阶段的投递 + 停止信号）。
     */
    private static final class RunState {
        final Object lock = new Object();
        final int concurrency;
        final CountDownLatch ready;
        /** 所有 worker —— 阶段要广播给它们每一个 */
        Worker[] workers = new Worker[0];
        boolean stopping;

        RunState(int concurrency) {
            this.concurrency = concurrency;
            this.ready = new CountDownLatch(concurrency);
        }

        void shutdown() {
            synchronized (lock) {
                stopping = true;
                lock.notifyAll();
            }
        }
    }

    /**
     * 一个 worker 线程。
     */
    private static final class Worker implements Runnable {

        private final Op op;
        private final RunState state;
        private final Latencies latencies = new Latencies();

        /** 当前阶段 —— 由主线程广播下发（每个 worker 各一份） */
        volatile Phase current;

        long ops;
        long errors;
        long sink;
        String firstError;

        Worker(Op op, RunState state) {
            this.op = op;
            this.state = state;
        }

        void reset() {
            ops = 0;
            errors = 0;
            sink = 0;
            firstError = null;
            latencies.reset();
        }

        @Override
        public void run() {
            state.ready.countDown();
            try {
                while (true) {
                    Phase phase;
                    synchronized (state.lock) {
                        while (current == null && !state.stopping) {
                            state.lock.wait(100);
                        }
                        if (current == null) {
                            return;                                 // 收到停止信号
                        }
                        phase = current;
                        current = null;
                    }
                    runPhaseLoop(phase);
                    phase.done.countDown();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void runPhaseLoop(Phase phase) {
            while (System.nanoTime() < phase.deadlineNanos) {
                long t0 = System.nanoTime();
                try {
                    sink += op.call();
                    ops++;
                } catch (Throwable e) {
                    errors++;
                    if (firstError == null) {
                        firstError = describe(e);
                    }
                }
                if (phase.recording) {
                    latencies.add(System.nanoTime() - t0);
                }
            }
        }
    }

    /**
     * 把异常链压成一行 —— 报告里要能看到根因（而不是只有最外层的包装消息）。
     */
    static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 5) {
            if (depth > 0) {
                sb.append(" <- ");
            }
            sb.append(cur.getClass().getSimpleName()).append(": ").append(cur.getMessage());
            cur = cur.getCause();
            depth++;
        }
        return sb.toString();
    }

    /**
     * 有界的延迟样本缓冲 —— 满了就按 2 的幂抽稀（只保留偶数下标）。
     */
    private static final class Latencies {

        private long[] buf = new long[4096];
        private int count;

        void add(long v) {
            if (count == buf.length) {
                if (buf.length < MAX_SAMPLES) {
                    long[] bigger = new long[Math.min(MAX_SAMPLES, buf.length * 2)];
                    System.arraycopy(buf, 0, bigger, 0, count);
                    buf = bigger;
                } else {
                    thin();
                }
            }
            buf[count++] = v;
        }

        void reset() {
            count = 0;
        }

        private void thin() {
            int n = count / 2;
            for (int i = 0; i < n; i++) {
                buf[i] = buf[i * 2];
            }
            count = n;
        }

        long[] snapshot() {
            return Arrays.copyOf(buf, count);
        }
    }

    /**
     * 供报告使用的辅助方法：把两轮结果取中位数（两轮时取算术平均）。
     */
    public static double median(double a, double b) {
        return (a + b) / 2.0;
    }

    /**
     * 多个样本的中位数 —— 偶数个时取中间两个的平均。
     *
     * <p>★ 为什么用中位数而不是平均值：机器负载高时单轮数字会整体漂移，
     * 中位数对个别离群轮次不敏感（与 compare-reports.py 跨批次取中位数同一口径）。</p>
     */
    public static double medianOf(double... values) {
        if (values.length == 0) {
            return 0;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        if ((n & 1) == 1) {
            return sorted[n / 2];
        }
        return (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }

    /**
     * 等待线程结束（工具方法，避免各处重复写 try/catch）。
     */
    static void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
