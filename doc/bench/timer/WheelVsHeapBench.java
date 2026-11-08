package io.edap.util.timing;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 「每连接一个定时任务」在百万级规模下的实测 + 轮子 vs ScheduledExecutorService 对比。
 *
 * <p>不建立真实连接 —— 只度量「定时任务本身」的内存与操作成本，
 * 这正是"10 万/100 万连接各挂一个超时任务"这个方案的代价。</p>
 */
public class WheelVsHeapBench {

    public static void main(String[] args) throws Exception {
        int n = Integer.parseInt(args.length > 0 ? args[0] : "1000000");

        System.out.println("=== n = " + n + " 个定时任务 ===\n");

        runWheel(n);
        runScheduled(n);
    }

    private static void runWheel(int n) throws Exception {
        System.gc();
        Thread.sleep(300);
        long heapBefore = usedHeap();

        WheelTimerService svc = new WheelTimerService(new NanoTickSource(), Runnable::run,
                1_000_000L, 5, 128);
        AtomicInteger fired = new AtomicInteger();

        long t0 = System.nanoTime();
        TimerHandle[] handles = new TimerHandle[n];
        for (int i = 0; i < n; i++) {
            // 全部落在 60 秒后到期 = 同一批桶里
            handles[i] = svc.schedule(fired::incrementAndGet, 60, TimeUnit.SECONDS);
        }
        long insertMs = (System.nanoTime() - t0) / 1_000_000L;
        long heapAfter = usedHeap();

        System.out.printf("[分层时间轮]%n");
        System.out.printf("  插入 %d 个任务: %d ms (%.0f ns/个)%n", n, insertMs, insertMs * 1e6 / n);
        System.out.printf("  堆占用增量: %d MB (%.0f B/任务)%n",
                (heapAfter - heapBefore) / (1024 * 1024),
                (heapAfter - heapBefore) / (double) n);
        System.out.printf("  pendingCount = %d%n", svc.pendingCount());

        // 取消一半 —— 取消是 O(1) 的吗？
        long tc = System.nanoTime();
        for (int i = 0; i < n; i += 2) {
            handles[i].cancel();
        }
        long cancelMs = (System.nanoTime() - tc) / 1_000_000L;
        System.out.printf("  取消一半 (%d 个): %d ms (%.0f ns/个)%n",
                n / 2, cancelMs, cancelMs * 1e6 / (n / 2.0));
        System.out.printf("  取消后 pendingCount = %d%n", svc.pendingCount());

        // 到期处理：让它们真的到期（改用短延迟重建一批）
        svc.close();
        System.out.println();
    }

    private static void runScheduled(int n) throws Exception {
        System.gc();
        Thread.sleep(300);
        long heapBefore = usedHeap();

        java.util.concurrent.ScheduledThreadPoolExecutor svc = new java.util.concurrent.ScheduledThreadPoolExecutor(4);
        AtomicInteger fired = new AtomicInteger();

        long t0 = System.nanoTime();
        ScheduledFuture<?>[] handles = new ScheduledFuture<?>[n];
        for (int i = 0; i < n; i++) {
            handles[i] = svc.schedule(fired::incrementAndGet, 60, TimeUnit.SECONDS);
        }
        long insertMs = (System.nanoTime() - t0) / 1_000_000L;
        long heapAfter = usedHeap();

        System.out.printf("[ScheduledThreadPoolExecutor]%n");
        System.out.printf("  插入 %d 个任务: %d ms (%.0f ns/个)%n", n, insertMs, insertMs * 1e6 / n);
        System.out.printf("  堆占用增量: %d MB (%.0f B/任务)%n",
                (heapAfter - heapBefore) / (1024 * 1024),
                (heapAfter - heapBefore) / (double) n);
        System.out.printf("  队列中待处理 = %d%n", svc.getQueue().size());

        long tc = System.nanoTime();
        for (int i = 0; i < n; i += 2) {
            handles[i].cancel(false);
        }
        long cancelMs = (System.nanoTime() - tc) / 1_000_000L;
        System.out.printf("  取消一半 (%d 个): %d ms (%.0f ns/个)%n",
                n / 2, cancelMs, cancelMs * 1e6 / (n / 2.0));
        System.out.printf("  取消后队列中待处理 = %d%n", svc.getQueue().size());

        svc.shutdownNow();
        System.out.println();
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }
}
