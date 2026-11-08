package io.edap.util.timing;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 「每连接一个超时任务」vs「扫描式」在真实 IO 活动下的 CPU 对比。
 *
 * <p>模拟 N 个连接，每个连接每 intervalMs 发生一次 IO：</p>
 * <ul>
 *   <li>方案 A（扫描式）：IO 时只更新一个 volatile long；每 scanMs 扫一遍全部连接</li>
 *   <li>方案 B（每连接定时器）：IO 时取消旧的超时任务 + 插入新的超时任务</li>
 * </ul>
 */
public class ReArmBench {

    private static final int N = 100_000;
    private static final long IO_INTERVAL_MS = 10;      // 每个连接每 10ms 一次 IO
    private static final long SCAN_INTERVAL_MS = 15_000; // 扫描周期（deriveScanInterval(60s)）
    private static final long TIMEOUT_MS = 60_000;       // 空闲超时
    private static final long SIM_SECONDS = 5;

    public static void main(String[] args) throws Exception {
        System.out.printf("模拟: %,d 个连接, 每连接每 %dms 一次 IO, 空闲超时 %ds, 扫描周期 %ds, 跑 %ds%n%n",
                N, IO_INTERVAL_MS, TIMEOUT_MS / 1000, SCAN_INTERVAL_MS / 1000, SIM_SECONDS);

        scanBased();
        perConnectionTimer();
    }

    /** 方案 A：扫描式 —— IO 只更新时间戳；周期任务扫全表。 */
    private static void scanBased() throws Exception {
        final AtomicLong[] stamps = new AtomicLong[N];
        for (int i = 0; i < N; i++) {
            stamps[i] = new AtomicLong(System.currentTimeMillis());
        }
        final long[] lastSeen = new long[N];
        AtomicLong closed = new AtomicLong();

        long cpu0 = processCpuNanos();
        long wall0 = System.nanoTime();

        long ioCount = 0;
        long nextScan = System.nanoTime();
        long nextIo = System.nanoTime();
        long scanCount = 0;
        long deadline = wall0 + SIM_SECONDS * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            long now = System.nanoTime();
            // 每 10ms 一批 IO（100k 连接 × 100 次/秒 = 1000 万次/秒）
            if (now >= nextIo) {
                nextIo = now + IO_INTERVAL_MS * 1_000_000L;
                long ms = System.currentTimeMillis();
                for (int i = 0; i < N; i++) {
                    stamps[i].set(ms);
                }
                ioCount += N;
            }
            if (now >= nextScan) {
                scanCount++;
                nextScan = now + SCAN_INTERVAL_MS * 1_000_000L;
                long nowMs = System.currentTimeMillis();
                for (int i = 0; i < N; i++) {
                    long last = Math.max(stamps[i].get(), lastSeen[i]);
                    if (nowMs - last > TIMEOUT_MS) {
                        closed.incrementAndGet();
                    }
                }
            }
        }
        long cpu = processCpuNanos() - cpu0;
        long wall = System.nanoTime() - wall0;
        report("方案A 扫描式", cpu, wall, ioCount, scanCount, closed.get());
    }

    /** 方案 B：每连接一个超时任务 —— IO 时取消旧的 + 插入新的。 */
    private static void perConnectionTimer() throws Exception {
        WheelTimerService svc = new WheelTimerService(new NanoTickSource(), Runnable::run,
                1_000_000L, 5, 128);
        final TimerHandle[] handles = new TimerHandle[N];
        final AtomicLong closed = new AtomicLong();
        for (int i = 0; i < N; i++) {
            handles[i] = svc.schedule(() -> closed.incrementAndGet(), TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        long cpu0 = processCpuNanos();
        long wall0 = System.nanoTime();
        long ioCount = 0;
        long nextIo = System.nanoTime();
        long deadline = wall0 + SIM_SECONDS * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            long now = System.nanoTime();
            if (now >= nextIo) {
                nextIo = now + IO_INTERVAL_MS * 1_000_000L;
                for (int i = 0; i < N; i++) {
                    handles[i].cancel();                                  // 取消旧超时
                    handles[i] = svc.schedule(() -> closed.incrementAndGet(),
                            TIMEOUT_MS, TimeUnit.MILLISECONDS);            // 插入新超时
                }
                ioCount += N;
            }
        }
        long cpu = processCpuNanos() - cpu0;
        long wall = System.nanoTime() - wall0;
        svc.close();
        report("方案B 每连接定时器", cpu, wall, ioCount, 0, closed.get());
    }

    private static void report(String name, long cpuNanos, long wallNanos,
                               long ioCount, long scanCount, long closed) {
        double cpuPct = cpuNanos / (double) wallNanos * 100.0;
        double wallSec = wallNanos / 1e9;
        System.out.printf("%s%n", name);
        System.out.printf("  CPU 占用: %.2f%% 单核  (%d ms CPU / %d ms 墙钟)%n",
                cpuPct, cpuNanos / 1_000_000L, wallNanos / 1_000_000L);
        System.out.printf("  IO 次数: %,d (%,.0f 次/秒)  扫描次数: %d  关闭数: %d%n",
                ioCount, ioCount / wallSec, scanCount, closed);
        System.out.println();
    }

    /** 进程 CPU 时间（user+sys），用 com.sun.management.OperatingSystemMXBean。 */
    private static long processCpuNanos() {
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory
                        .getOperatingSystemMXBean();
        return os.getProcessCpuTime();
    }
}
