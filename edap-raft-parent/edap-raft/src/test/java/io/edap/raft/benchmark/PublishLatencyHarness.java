package io.edap.raft.benchmark;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 生产者侧（publish）的延迟压测。
 *
 * <p>前面两个 harness 测的都是「入队 → 出队」的端到端延迟，
 * 没单独量过 {@code publish} 这一步本身要花多久。MPSC 队列里多个生产者
 * 争同一把锁，publish 是会互相阻塞的 —— 这个 harness 量的是
 * 「一个生产者线程在 publish 里被卡住多久」。</p>
 *
 * <p>跑法：</p>
 * <pre>
 * java -cp ... io.edap.raft.benchmark.PublishLatencyHarness [生产者数] [每生产者 msg/s] [时长秒]
 * </pre>
 *
 * <p>每生产者 msg/s 传 0 表示不限速（打满）。</p>
 */
public final class PublishLatencyHarness {

    /** 采样上限，超出的样本丢弃 */
    private static final int MAX_SAMPLES = 4_000_000;

    public static void main(String[] args) throws Exception {
        int producers = args.length > 0 ? Integer.parseInt(args[0]) : 5;
        int ratePerProducer = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        int durationSec = args.length > 2 ? Integer.parseInt(args[2]) : 10;

        System.out.printf("producers=%d  rate=%s  duration=%ds%n",
                producers, ratePerProducer == 0 ? "unlimited" : ratePerProducer + " msg/s/producer",
                durationSec);
        System.out.println("--------------------------------------------------------------------------------------");
        System.out.printf("%-14s %10s %10s %10s %10s %10s %8s%n",
                "variant", "p50(us)", "p90(us)", "p99(us)", "p999(us)", "max(us)", "cpu/wall");
        System.out.println("--------------------------------------------------------------------------------------");

        for (MessageBus bus : MessageBus.allVariants(producers, false, 8192, 1024)) {
            Result r = run(bus, producers, ratePerProducer, durationSec);
            System.out.printf("%-14s %10.1f %10.1f %10.1f %10.1f %10.1f %8.2f%n",
                    r.name, r.p50 / 1000.0, r.p90 / 1000.0, r.p99 / 1000.0,
                    r.p999 / 1000.0, r.max / 1000.0, r.cpuRatio);
        }
    }

    static final class Result {
        String name;
        long p50;
        long p90;
        long p99;
        long p999;
        long max;
        double cpuRatio;
        long samples;
        long droppedSamples;
    }

    static Result run(MessageBus bus, int producers, int ratePerProducer, int durationSec)
            throws Exception {
        bus.start();
        final long[] samples = new long[MAX_SAMPLES];
        final int[] count = {0};
        final long[] dropped = {0};
        final boolean[] stop = {false};
        final CountDownLatch ready = new CountDownLatch(producers);

        // 消费者：不停地取，把队列排空，避免生产者被「队列满」卡住
        Thread consumer = new Thread(() -> {
            while (!stop[0]) {
                if (bus.take() == null) {
                    Thread.onSpinWait();
                }
            }
        }, "publish-consumer");
        consumer.setDaemon(true);
        consumer.start();

        List<Thread> ps = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            final int idx = p;
            Thread t = new Thread(() -> {
                ready.countDown();
                long next = System.nanoTime();
                long interval = ratePerProducer > 0 ? 1_000_000_000L / ratePerProducer : 0;
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
                    bus.publish(idx, Long.valueOf(t0));
                    long cost = System.nanoTime() - t0;
                    int i = count[0];
                    if (i < samples.length) {
                        samples[i] = cost;
                        count[0] = i + 1;
                    } else {
                        dropped[0]++;
                    }
                }
            }, "publish-producer-" + p);
            t.setDaemon(true);
            ps.add(t);
        }

        long cpu0 = LatencyHarness.processCpuTime();
        long wall0 = System.nanoTime();
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
        long wall = System.nanoTime() - wall0;
        long cpu = LatencyHarness.processCpuTime() - cpu0;
        bus.stop();

        long[] data = Arrays.copyOf(samples, count[0]);
        Arrays.sort(data);

        Result r = new Result();
        r.name = bus.getClass().getSimpleName();
        r.samples = data.length;
        r.droppedSamples = dropped[0];
        r.p50 = LatencyHarness.pct(data, 50);
        r.p90 = LatencyHarness.pct(data, 90);
        r.p99 = LatencyHarness.pct(data, 99);
        r.p999 = LatencyHarness.pct(data, 99.9);
        r.max = data.length == 0 ? 0 : data[data.length - 1];
        r.cpuRatio = (double) cpu / (double) wall;
        return r;
    }
}
