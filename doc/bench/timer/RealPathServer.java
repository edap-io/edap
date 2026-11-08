package io.edap.nio.bench;

import io.edap.Edap;
import io.edap.NioServerSession;
import io.edap.Server;
import io.edap.ServerGroup;
import io.edap.buffer.FastBuf;
import io.edap.log.LoggerManager;
import io.edap.nio.IoSelectorManager;
import io.edap.nio.NioSessionIdleReaper;
import io.edap.nio.enums.ThreadType;
import io.edap.nio.impl.FastSelectorProvider;
import io.edap.util.timing.WheelTimerService;

import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 真实代码路径的高并发基准：
 * {@code IoSelectorManager.registerNioSession} 注册 + {@code NioSessionIdleReaper.scan()} 扫描。
 *
 * <p>用 unix domain socket（不吃端口，只吃 fd），连接由另一个进程（RealPathClient）发起，
 * 这样服务端只占 N 个 fd 而不是 2N —— 单进程的 fd 上限才够撑到 100 万。</p>
 */
public class RealPathServer {

    public static void main(String[] args) throws Exception {
        String sockPath = args[0];
        int n = Integer.parseInt(args[1]);
        int ioThreads = Integer.parseInt(args.length > 2 ? args[2] : "8");
        int rounds = Integer.parseInt(args.length > 3 ? args[3] : "5");

        System.out.printf("=== 真实路径基准: %d 连接, %d 个 selector, %d 轮 ===%n", n, ioThreads, rounds);
        System.out.printf("  logger 实现: %s%n",
                LoggerManager.getLogger(RealPathServer.class).getClass().getName());
        System.out.printf("  fd 上限=%d, fs.file-max=%s%n",
                rlimitNofile(), readProc("/proc/sys/fs/file-max"));

        BenchServer server = new BenchServer();
        server.setThreadType(ThreadType.EDAP);
        server.setSelectorProvider(new FastSelectorProvider());
        server.setIoThreadCount(ioThreads);

        IoSelectorManager manager = new IoSelectorManager(server, new Server.Addr());
        server.addIoSelectorManager(new Server.Addr(), manager);

        Files.deleteIfExists(Paths.get(sockPath));
        ServerSocketChannel ssc = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        ssc.bind(UnixDomainSocketAddress.of(sockPath));

        // ---- 建连 + 注册 ----
        NioServerSession[] sessions = new NioServerSession[n];
        long t0 = System.nanoTime();
        long firstAccept = -1;
        long cpu0 = BenchUtil.processCpuNanos();
        for (int i = 0; i < n; i++) {
            SocketChannel ch = ssc.accept();
            if (firstAccept < 0) {
                firstAccept = System.nanoTime();
            }
            ch.configureBlocking(false);
            BenchSession s = new BenchSession();
            s.setSocketChannel(ch);
            s.setLastReadTime(System.currentTimeMillis());
            s.setLastWriteTime(System.currentTimeMillis());
            manager.registerNioSession(s);
            sessions[i] = s;
            if ((i + 1) % 100_000 == 0) {
                double el = (System.nanoTime() - firstAccept) / 1e9;
                System.out.printf("  已注册 %,d (fd=%d, 自首连 %.1fs, %.0f conn/s)%n",
                        i + 1, BenchUtil.fdCount(), el, (i + 1) / el);
            }
        }
        long setupMs = (System.nanoTime() - firstAccept) / 1_000_000L;
        System.out.printf("建连+注册完成: %d 条, %.1f s, fd=%d%n", n, setupMs / 1000.0, BenchUtil.fdCount());
        BenchUtil.memInfo();

        ServerGroup group = new ServerGroup();
        group.setName("bench");
        group.addServer(server);
        Edap edap = new Edap();
        edap.addServerGroup(group);

        NioSessionIdleReaper reaper = new NioSessionIdleReaper(edap, 3_600_000L, WheelTimerService.create());

        // ---- 阶段 A：全部活跃 —— 纯遍历成本 ----
        System.out.println("阶段 A：全部活跃（scan 关闭 0 条）");
        for (int r = 0; r < rounds; r++) {
            long t = System.nanoTime();
            int closed = reaper.scan();
            double ms = (System.nanoTime() - t) / 1e6;
            System.out.printf("  scan#%d: %.2f ms  (%.1f ns/key)  closed=%d%n",
                    r, ms, ms * 1e6 / n, closed);
        }

        // ---- 阶段 B：全部空闲 —— 关闭路径成本 ----
        long stale = System.currentTimeMillis() - 7_200_000L;
        for (NioServerSession s : sessions) {
            s.setLastReadTime(stale);
            s.setLastWriteTime(stale);
        }
        System.out.println("阶段 B：全部空闲（scan 要关闭全部）");
        long tb = System.nanoTime();
        int closedAll = reaper.scan();
        double msb = (System.nanoTime() - tb) / 1e6;
        System.out.printf("  scan: %.2f ms  (%.1f ns/key)  closed=%d%n",
                msb, msb * 1e6 / n, closedAll);

        // ---- 阶段 C：再扫一轮（key 已取消，isValid=false 直接跳过）----
        long tc = System.nanoTime();
        int closedC = reaper.scan();
        double msc = (System.nanoTime() - tc) / 1e6;
        System.out.printf("阶段 C：已关闭后再扫一轮 %.2f ms (closed=%d)%n", msc, closedC);

        // ---- 阶段 D：fd 到底释放了没有 ----
        int stillOpen = 0;
        for (NioServerSession s : sessions) {
            if (s.getSocketChannel() != null && s.getSocketChannel().isOpen()) {
                stillOpen++;
            }
        }
        System.out.printf("阶段 D：channel.isOpen() 仍为 true 的有 %d 条%n", stillOpen);
        for (int i = 0; i < 5; i++) {
            System.out.printf("  fd=%d (t=+%ds)%n", BenchUtil.fdCount(), i);
            Thread.sleep(1000);
        }

        long cpu = BenchUtil.processCpuNanos() - cpu0;
        System.out.printf("进程 CPU 累计: %.1f s%n", cpu / 1e9);
        BenchUtil.memInfo();
        System.out.printf("最终 fd=%d%n", BenchUtil.fdCount());

        // 不主动退出 —— 让外层脚本决定何时收工
        System.out.println("BENCH_DONE");
        System.out.flush();
        Thread.sleep(Long.parseLong(System.getProperty("bench.holdMs", "0")));
    }

    private static String readProc(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path))).trim();
        } catch (Exception e) {
            return "?";
        }
    }

    private static long rlimitNofile() {
        try {
            Process p = new ProcessBuilder("sh", "-c", "ulimit -n").start();
            byte[] buf = new byte[64];
            int len = p.getInputStream().read(buf);
            p.waitFor();
            return Long.parseLong(new String(buf, 0, len).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    static class BenchServer extends Server<Object, BenchSession> {
        @Override
        public NioServerSession createNioSession() {
            return new BenchSession();
        }
    }

    static class BenchSession extends NioServerSession<Object> {
        @Override
        public boolean decode(FastBuf fastBuf, boolean threadSwitch) {
            return true;
        }

        @Override
        public void handle(Object message) {
        }
    }
}
