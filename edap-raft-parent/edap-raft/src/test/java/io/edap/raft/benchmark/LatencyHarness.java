package io.edap.raft.benchmark;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * 队列的「raft 真实负载」延迟压测。
 *
 * <p>跟 JMH 的吞吐对比互补：JMH 测的是队列被打满时的每秒操作数，
 * 但 raft 的消息量极低（心跳 100ms 级、提案看业务），真正决定体感的是
 * 「消息从入队到被 raft 线程取走」这段延迟，以及为此付出的 CPU。</p>
 *
 * <p>跑法：</p>
 * <pre>
 * java -cp ... io.edap.raft.bench.LatencyHarness [生产者数] [每生产者 msg/s] [时长秒]
 * </pre>
 */
public final class LatencyHarness {

    /** 消息体：只带一个入队时间戳 */
    static final class Envelope {
        final long enqueueNanos;

        Envelope(long enqueueNanos) {
            this.enqueueNanos = enqueueNanos;
        }
    }

    public static void main(String[] args) throws Exception {
        int producers = args.length > 0 ? Integer.parseInt(args[0]) : 10;
        int ratePerProducer = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        int durationSec = args.length > 2 ? Integer.parseInt(args[2]) : 10;

        System.out.printf("producers=%d  rate=%d msg/s/producer  total=%d msg/s  duration=%ds%n",
                producers, ratePerProducer, producers * ratePerProducer, durationSec);
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
        long count;
    }

    static Result run(MessageBus bus, int producers, int ratePerProducer, int durationSec)
            throws Exception {
        bus.start();
        final long[] latencies = new long[producers * ratePerProducer * durationSec * 2 + 1024];
        final int[] count = {0};
        final boolean[] consumerDone = {false};

        final long intervalNanos = 1_000_000_000L / ratePerProducer;
        final CountDownLatch ready = new CountDownLatch(producers);
        final CountDownLatch done = new CountDownLatch(producers);

        Thread consumer = new Thread(() -> {
            await(ready);
            while (!consumerDone[0]) {
                Object o = bus.takeBlocking(20);
                if (o == null) {
                    continue;
                }
                long lat = System.nanoTime() - ((Envelope) o).enqueueNanos;
                int i = count[0];
                if (i < latencies.length) {
                    latencies[i] = lat;
                    count[0] = i + 1;
                }
            }
            // 收尾：把剩下的都取走
            Object o;
            while ((o = bus.take()) != null) {
                long lat = System.nanoTime() - ((Envelope) o).enqueueNanos;
                int i = count[0];
                if (i < latencies.length) {
                    latencies[i] = lat;
                    count[0] = i + 1;
                }
            }
        }, "bench-consumer");
        consumer.setDaemon(true);

        List<Thread> ps = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            final int idx = p;
            Thread t = new Thread(() -> {
                ready.countDown();
                long next = System.nanoTime();
                while (!consumerDone[0]) {
                    long now = System.nanoTime();
                    long wait = next - now;
                    if (wait > 0) {
                        LockSupport.parkNanos(wait);
                        continue;
                    }
                    bus.publish(idx, new Envelope(now));
                    next += intervalNanos;
                    // 落后太多就重新对齐，避免无限追赶
                    if (next < now - intervalNanos * 10) {
                        next = now + intervalNanos;
                    }
                }
            }, "bench-producer-" + p);
            t.setDaemon(true);
            ps.add(t);
        }

        long cpu0 = processCpuTime();
        long wall0 = System.nanoTime();
        consumer.start();
        for (Thread t : ps) {
            t.start();
        }
        Thread.sleep(durationSec * 1000L);
        consumerDone[0] = true;
        done.countDown();
        for (Thread t : ps) {
            t.join(2000);
        }
        consumer.join(2000);
        long wall = System.nanoTime() - wall0;
        long cpu = processCpuTime() - cpu0;
        bus.stop();

        long[] data = Arrays.copyOf(latencies, count[0]);
        Arrays.sort(data);

        Result r = new Result();
        r.name = bus.getClass().getSimpleName();
        r.count = data.length;
        r.p50 = pct(data, 50);
        r.p90 = pct(data, 90);
        r.p99 = pct(data, 99);
        r.p999 = pct(data, 99.9);
        r.max = data.length == 0 ? 0 : data[data.length - 1];
        r.cpuRatio = (double) cpu / (double) wall;
        return r;
    }

    static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static long pct(long[] sorted, double p) {        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.min(sorted.length - 1, Math.round(p / 100.0 * (sorted.length - 1)));
        return sorted[idx];
    }

    static long processCpuTime() {
        java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean) {
            return ((com.sun.management.OperatingSystemMXBean) os).getProcessCpuTime();
        }
        return 0L;
    }
}
