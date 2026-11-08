package io.edap.nio.bench;

import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 真实路径基准的客户端：建 N 条 unix domain socket 连接并一直持有。
 *
 * <p>单独进程跑，是为了把「客户端那 N 个 fd」挪出服务端的 fd 额度 ——
 * 单进程 RLIMIT_NOFILE 上限 1048576，1M 连接双端同进程是 2M fd，撑不住。</p>
 */
public class RealPathClient {

    public static void main(String[] args) throws Exception {
        String sockPath = args[0];
        int n = Integer.parseInt(args[1]);

        UnixDomainSocketAddress addr = UnixDomainSocketAddress.of(sockPath);
        // 等服务端把监听 socket 建好
        long deadline = System.currentTimeMillis() + 60_000L;
        while (!Files.exists(Paths.get(sockPath)) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        Thread.sleep(200);

        SocketChannel[] chans = new SocketChannel[n];
        long t0 = System.nanoTime();
        int threads = Integer.parseInt(System.getProperty("bench.clientThreads", "8"));
        final java.util.concurrent.atomic.AtomicInteger done =
                new java.util.concurrent.atomic.AtomicInteger();
        Thread[] ts = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            ts[t] = new Thread(() -> {
                for (int i = tid; i < n; i += threads) {
                    try {
                        SocketChannel c = SocketChannel.open(StandardProtocolFamily.UNIX);
                        c.connect(addr);
                        chans[i] = c;
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    int d = done.incrementAndGet();
                    if (d % 100_000 == 0) {
                        System.out.printf("  客户端已连 %,d (fd=%d, %.1fs)%n",
                                d, BenchUtil.fdCount(), (System.nanoTime() - t0) / 1e9);
                    }
                }
            });
            ts[t].start();
        }
        for (Thread t : ts) {
            t.join();
        }
        System.out.printf("客户端连接完成: %d 条, %.1f s, fd=%d%n",
                n, (System.nanoTime() - t0) / 1e9, BenchUtil.fdCount());
        System.out.println("CLIENT_DONE");
        System.out.flush();

        // 持有连接不释放，等服务端跑完
        Thread.sleep(Long.parseLong(System.getProperty("bench.holdMs", "600000")));
    }
}
