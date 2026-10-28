package io.edap.raft.benchmark;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MPSC 争用压测：N 个生产线程不节流地往队列里塞，单个消费线程尽量快地取。
 *
 * <p>目的是看「多生产者同时打同一个队列」时各方案的单次操作成本。
 * 队列容量取得足够大（2^20），正常情况下不会触发丢弃快路径；
 * 报告里的 dropped 用来确认这一点。</p>
 *
 * <p>跑法：</p>
 * <pre>
 * java -cp ... io.edap.raft.bench.MpscHarness [生产者数] [时长秒]
 * </pre>
 */
public final class MpscHarness {

    private static final Object PAYLOAD = new Object();
    private static final int CAPACITY = 1 << 20;

    public static void main(String[] args) throws Exception {
        int producers = args.length > 0 ? Integer.parseInt(args[0]) : 10;
        int durationSec = args.length > 1 ? Integer.parseInt(args[1]) : 2;

        System.out.printf("producers=%d  duration=%ds  capacity=%d%n", producers, durationSec, CAPACITY);
        System.out.println("-------------------------------------------------------------------------------------");
        System.out.printf("%-18s %14s %14s %12s %10s%n",
                "variant", "enqueue(M/s)", "dequeue(M/s)", "dropped", "cpu/wall");
        System.out.println("-------------------------------------------------------------------------------------");

        for (MessageBus bus : MessageBus.allVariants(producers, true, CAPACITY, CAPACITY)) {
            Result r = run(bus, producers, durationSec);
            System.out.printf("%-18s %14.2f %14.2f %12d %10.2f%n",
                    r.name, r.enqueued / 1e6 / durationSec, r.dequeued / 1e6 / durationSec,
                    r.dropped, r.cpuRatio);
        }
    }

    static final class Result {
        String name;
        long enqueued;
        long dequeued;
        long dropped;
        double cpuRatio;
    }

    static Result run(MessageBus bus, int producers, int durationSec) throws Exception {
        bus.start();
        final AtomicLong enqueued = new AtomicLong();
        final AtomicLong dequeued = new AtomicLong();
        final boolean[] stop = {false};
        final CountDownLatch ready = new CountDownLatch(producers);

        Thread consumer = new Thread(() -> {
            while (!stop[0]) {
                Object o = bus.take();
                if (o != null) {
                    dequeued.incrementAndGet();
                } else {
                    Thread.onSpinWait();
                }
            }
        }, "mpsc-consumer");
        consumer.setDaemon(true);
        consumer.start();

        List<Thread> ps = new ArrayList<>();
        for (int p = 0; p < producers; p++) {
            final int idx = p;
            Thread t = new Thread(() -> {
                ready.countDown();
                long n = 0;
                while (!stop[0]) {
                    bus.publish(idx, PAYLOAD);
                    n++;
                }
                enqueued.addAndGet(n);
            }, "mpsc-producer-" + p);
            t.setDaemon(true);
            ps.add(t);
        }

        long cpu0 = LatencyHarness.processCpuTime();
        long wall0 = System.nanoTime();
        for (Thread t : ps) {
            t.start();
        }
        Thread.sleep(durationSec * 1000L);
        stop[0] = true;
        for (Thread t : ps) {
            t.join(3000);
        }
        consumer.join(3000);
        long wall = System.nanoTime() - wall0;
        long cpu = LatencyHarness.processCpuTime() - cpu0;
        bus.stop();

        Result r = new Result();
        r.name = bus.getClass().getSimpleName();
        r.enqueued = enqueued.get();
        r.dequeued = dequeued.get();
        r.dropped = bus.dropped();
        r.cpuRatio = (double) cpu / (double) wall;
        return r;
    }
}
