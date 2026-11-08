package io.edap.nio.bench;

import java.io.File;

/** 基准公用小工具。 */
public final class BenchUtil {

    private BenchUtil() {
    }

    /** 当前进程打开的 fd 数（读 /proc/self/fd）。 */
    public static int fdCount() {
        String[] fds = new File("/proc/self/fd").list();
        return fds == null ? -1 : fds.length;
    }

    /** 堆已用字节。 */
    public static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    /** 进程 CPU 时间（user+sys），纳秒。 */
    public static long processCpuNanos() {
        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory
                        .getOperatingSystemMXBean();
        return os.getProcessCpuTime();
    }

    public static void memInfo() {
        long used = usedHeap();
        Runtime rt = Runtime.getRuntime();
        System.out.printf("  堆: 已用 %d MB / 提交 %d MB / 堆上限 %d MB%n",
                used / (1024 * 1024),
                rt.totalMemory() / (1024 * 1024),
                rt.maxMemory() / (1024 * 1024));
    }
}
