package io.edap.raft.benchmark;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

/**
 * 拆解 {@code Node} 的 publish 路径到底贵在哪。
 *
 * <p>{@code Node.enqueueProposal}（{@code Node.java:496-507}）做三件事：</p>
 * <ol>
 *   <li>{@code synchronized (lifecycle)} —— 保护「停止」与「入队」的竞态</li>
 *   <li>{@code propc.add(pm)} —— 入队（{@code LinkedBlockingQueue}）</li>
 *   <li>{@code wakeup.release()} —— 唤醒状态机线程（{@code Semaphore}）</li>
 * </ol>
 *
 * <p>本 harness 把这三步拆开分别量，答案是「换更快的队列能省多少」。</p>
 *
 * <p>关键变量是第 3 步：状态机线程空闲时停在 {@code Semaphore.acquire()} 上，
 * 此时 {@code release()} 要做一次 unpark 系统调用；状态机线程忙的时候
 * {@code release()} 只是把许可数加一（纯 CAS），不产生系统调用。</p>
 *
 * <p>跑法：</p>
 * <pre>
 * java -cp ... io.edap.raft.benchmark.PublishPathCostHarness [生产者数] [每生产者 msg/s] [时长秒]
 * </pre>
 */
public final class PublishPathCostHarness {

    /** 采样上限，超出的样本丢弃 */
    private static final int MAX_SAMPLES = 4_000_000;

    /** 发布路径的四个变体 */
    enum Variant {
        /** 只入队（裸队列成本） */
        OFFER("offer only"),
        /** 加 lifecycle monitor */
        MONITOR("monitor + offer"),
        /** 加 monitor + Semaphore.release()，状态机线程忙（许可数 > 0，纯 CAS） */
        SEM_IDLE("monitor + offer + release(空闲许可)"),
        /** 加 monitor + Semaphore.release()，状态机线程停着（要 unpark 系统调用） */
        SEM_WAKE("monitor + offer + release(唤醒停着的线程)");

        final String label;

        Variant(String label) {
            this.label = label;
        }
    }

    public static void main(String[] args) throws Exception {
        int producers = args.length > 0 ? Integer.parseInt(args[0]) : 8;
        int ratePerProducer = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        int durationSec = args.length > 2 ? Integer.parseInt(args[2]) : 10;

        System.out.printf("producers=%d  rate=%s  duration=%ds%n",
                producers, ratePerProducer == 0 ? "unlimited" : ratePerProducer + " msg/s/producer",
                durationSec);
        System.out.println("--------------------------------------------------------------------------------------");
        System.out.printf("%-32s %10s %10s %10s %10s %10s%n",
                "variant", "p50(us)", "p90(us)", "p99(us)", "p999(us)", "max(us)");
        System.out.println("--------------------------------------------------------------------------------------");

        for (Variant v : Variant.values()) {
            Result r = run(v, producers, ratePerProducer, durationSec);
            System.out.printf("%-32s %10.1f %10.1f %10.1f %10.1f %10.1f%n",
                    v.label, r.p50 / 1000.0, r.p90 / 1000.0, r.p99 / 1000.0,
                    r.p999 / 1000.0, r.max / 1000.0);
        }
    }

    static final class Result {
        long p50;
        long p90;
        long p99;
        long p999;
        long max;
    }

    static Result run(Variant variant, int producers, int ratePerProducer, int durationSec)
            throws Exception {
        final LinkedBlockingQueue<Object> queue = new LinkedBlockingQueue<>(8192);
        final Semaphore wakeup = new Semaphore(0);
        final Object lifecycle = new Object();
        final long[] samples = new long[MAX_SAMPLES];
        final int[] count = {0};
        final boolean[] stop = {false};

        // 消费者：
        // SEM_WAKE  —— 停着等唤醒（跟 raft 状态机线程一样，会触发 unpark）
        // 其它      —— 一直忙，排空队列（许可数堆积，release() 只做 CAS）
        Thread consumer = new Thread(() -> {
            if (variant == Variant.SEM_WAKE) {
                while (!stop[0]) {
                    try {
                        wakeup.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    queue.poll();
                }
            } else {
                while (!stop[0]) {
                    if (queue.poll() == null) {
                        Thread.onSpinWait();
                    }
                }
            }
        }, "publish-path-consumer");
        consumer.setDaemon(true);
        consumer.start();

        // SEM_IDLE / MONITOR / OFFER：消费者不 acquire，许可数会堆积，
        // 于是 release() 退化成纯 CAS。SEM_WAKE 消费者会 acquire，许可数始终接近 0。
        if (variant == Variant.SEM_IDLE) {
            for (int i = 0; i < 1024; i++) {
                wakeup.release();
            }
        }

        List<Thread> ps = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(producers);
        for (int p = 0; p < producers; p++) {
            Thread t = new Thread(() -> {
                ready.countDown();
                long next = System.nanoTime();
                long interval = ratePerProducer > 0 ? 1_000_000_000L / ratePerProducer : 0;
                Object msg = new Object();
                while (!stop[0]) {
                    if (interval > 0) {
                        long now = System.nanoTime();
                        long wait = next - now;
                        if (wait > 0) {
                            java.util.concurrent.locks.LockSupport.parkNanos(wait);
                            continue;
                        }
                        next += interval;
                        if (next < now - interval * 10) {
                            next = now + interval;
                        }
                    }
                    long t0 = System.nanoTime();
                    switch (variant) {
                        case OFFER:
                            queue.offer(msg);
                            break;
                        case MONITOR:
                            synchronized (lifecycle) {
                                queue.offer(msg);
                            }
                            break;
                        case SEM_IDLE:
                        case SEM_WAKE:
                            synchronized (lifecycle) {
                                queue.offer(msg);
                            }
                            wakeup.release();
                            break;
                        default:
                            break;
                    }
                    long cost = System.nanoTime() - t0;
                    int i = count[0];
                    if (i < samples.length) {
                        samples[i] = cost;
                        count[0] = i + 1;
                    }
                }
            }, "publish-path-producer-" + p);
            t.setDaemon(true);
            ps.add(t);
        }

        for (Thread t : ps) {
            t.start();
        }
        ready.await();
        Thread.sleep(durationSec * 1000L);
        stop[0] = true;
        for (Thread t : ps) {
            t.join(2000);
        }
        consumer.join(2000);

        long[] data = Arrays.copyOf(samples, count[0]);
        Arrays.sort(data);
        Result r = new Result();
        r.p50 = LatencyHarness.pct(data, 50);
        r.p90 = LatencyHarness.pct(data, 90);
        r.p99 = LatencyHarness.pct(data, 99);
        r.p999 = LatencyHarness.pct(data, 99.9);
        r.max = data.length == 0 ? 0 : data[data.length - 1];
        return r;
    }
}
