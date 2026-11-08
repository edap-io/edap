package io.edap.nio.bench;

import java.net.StandardProtocolFamily;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;

/**
 * Linux epoll 下「每 key 1 个 fd」的纯遍历基准。
 *
 * <p>遍历的是 {@code selector.keys()} + attachment，与 channel 类型无关，
 * 所以用 DatagramChannel（1 个 fd/key）把 fd 额度吃满，量的是纯粹的
 * 「每个 key 一次遍历」成本 —— 这正是 {@code IoSelectorManager.forEachSession}
 * 和 {@code NioSessionIdleReaper.scan()} 的底座。</p>
 */
public class TraversalBench {

    public static void main(String[] args) throws Exception {
        int n = Integer.parseInt(args.length > 0 ? args[0] : "1000000");
        int rounds = Integer.parseInt(args.length > 1 ? args[1] : "20");

        Selector selector = Selector.open();
        System.out.printf("=== Linux epoll 遍历基准: %d key, %d 轮 ===%n", n, rounds);
        System.out.printf("  fd 上限: %d  (fs.file-max=%s, nr_open=%s)%n",
                rlimit(), readProc("/proc/sys/fs/file-max"), readProc("/proc/sys/fs/nr_open"));

        long t0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            DatagramChannel ch = DatagramChannel.open(StandardProtocolFamily.INET);
            ch.configureBlocking(false);
            ch.register(selector, SelectionKey.OP_READ, new Object());
        }
        long setupMs = (System.nanoTime() - t0) / 1_000_000L;

        System.out.printf("setup: %d key 用了 %d ms; fd=%d; 堆=%d MB (%.0f B/key)%n",
                n, setupMs, BenchUtil.fdCount(),
                BenchUtil.usedHeap() / (1024 * 1024),
                BenchUtil.usedHeap() / (double) n);

        // 预热
        for (int i = 0; i < 20; i++) {
            for (SelectionKey k : selector.keys()) {
                k.attachment();
            }
        }

        long visited = 0;
        long scanStart = System.nanoTime();
        for (int r = 0; r < rounds; r++) {
            for (SelectionKey key : selector.keys()) {
                Object att = key.attachment();
                if (att != null && key.isValid()) {
                    visited++;
                }
            }
        }
        long scanNs = System.nanoTime() - scanStart;

        double perScanMs = scanNs / 1e6 / rounds;
        double perKeyNs = (double) scanNs / rounds / n;

        System.out.printf("rounds=%d  每轮 %.3f ms  每 key %.2f ns  visited=%d%n",
                rounds, perScanMs, perKeyNs, visited);

        System.out.println("按扫描周期外推 CPU 占用（单核）：");
        for (long intervalMs : new long[]{1000, 5000, 15000, 60000}) {
            double cpu = perKeyNs * n / 1e9 * (1000.0 / intervalMs) * 100.0;
            System.out.printf("  每 %6dms 扫一次 -> %.4f%% 单核%n", intervalMs, cpu);
        }

        selector.close();
    }

    private static String readProc(String path) {
        try {
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path))).trim();
        } catch (Exception e) {
            return "?";
        }
    }

    private static long rlimit() {
        return rlimitNofile();
    }

    private static long rlimitNofile() {
        try {
            Process p = new ProcessBuilder("sh", "-c", "ulimit -n").start();
            java.io.InputStream in = p.getInputStream();
            byte[] buf = new byte[64];
            int len = in.read(buf);
            p.waitFor();
            return Long.parseLong(new String(buf, 0, len).trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
