package io.edap.nio.bench;

import io.edap.Edap;
import io.edap.NioServerSession;
import io.edap.Server;
import io.edap.ServerGroup;
import io.edap.buffer.FastBuf;
import io.edap.nio.IoSelectorManager;
import io.edap.nio.NioSessionIdleReaper;
import io.edap.nio.enums.ThreadType;
import io.edap.nio.impl.FastSelectorProvider;
import io.edap.util.timing.Timers;

import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

/**
 * 半开连接回收的端到端验证（走平台时间轮 {@link Timers#get()} 的定时器路径）。
 *
 * <p>对端进程被 SIGKILL 且 FIN 被丢在中间时，服务端这边就是「ESTABLISHED 但永远收不到数据」
 * 的半开连接 —— 老版本 edap 只有 EOF / 读异常两条关闭路径，这种连接会永久泄漏 fd。
 * 这里验证 {@link NioSessionIdleReaper} 能把它按 idleTimeout 收掉。</p>
 */
public class HalfOpenServer {

    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        int n = Integer.parseInt(args[2]);
        long idleTimeoutMs = Long.parseLong(args[3]);

        System.out.printf("=== 半开连接回收验证: %d 连接, idleTimeout=%dms ===%n", n, idleTimeoutMs);

        BenchServer server = new BenchServer();
        server.setThreadType(ThreadType.EDAP);
        server.setSelectorProvider(new FastSelectorProvider());
        server.setIoThreadCount(2);

        IoSelectorManager manager = new IoSelectorManager(server, new Server.Addr());
        server.addIoSelectorManager(new Server.Addr(), manager);

        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.bind(new InetSocketAddress(host, port));

        NioServerSession[] sessions = new NioServerSession[n];
        for (int i = 0; i < n; i++) {
            SocketChannel ch = ssc.accept();
            ch.configureBlocking(false);
            BenchSession s = new BenchSession();
            s.setSocketChannel(ch);
            s.setLastReadTime(System.currentTimeMillis());
            s.setLastWriteTime(System.currentTimeMillis());
            manager.registerNioSession(s);
            sessions[i] = s;
        }
        System.out.printf("ACCEPTED %d%n", n);
        System.out.flush();

        ServerGroup group = new ServerGroup();
        group.setName("halfopen");
        group.addServer(server);
        Edap edap = new Edap();
        edap.addServerGroup(group);

        // 走平台时间轮（和线上 Edap 用的是同一条路径）
        NioSessionIdleReaper reaper = new NioSessionIdleReaper(edap, idleTimeoutMs, Timers.get());
        System.out.printf("REAPER_STARTED scanInterval=%dms%n", reaper.getScanIntervalMs());
        System.out.flush();
        startMs = System.currentTimeMillis();
        reaper.start();

        long deadline = System.currentTimeMillis() + 120_000L;
        while (System.currentTimeMillis() < deadline) {
            int alive = 0;
            for (NioServerSession s : sessions) {
                if (s.getSocketChannel() != null && s.getSocketChannel().isOpen()) {
                    alive++;
                }
            }
            if (alive == 0) {
                System.out.printf("ALL_CLOSED elapsed_ms=%d%n", System.currentTimeMillis() - startMs);
                System.out.println("BENCH_DONE");
                System.out.flush();
                return;
            }
            Thread.sleep(250);
        }
        int alive = 0;
        for (NioServerSession s : sessions) {
            if (s.getSocketChannel() != null && s.getSocketChannel().isOpen()) {
                alive++;
            }
        }
        System.out.printf("TIMEOUT still_alive=%d%n", alive);
        System.out.flush();
    }

    static volatile long startMs = System.currentTimeMillis();

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
