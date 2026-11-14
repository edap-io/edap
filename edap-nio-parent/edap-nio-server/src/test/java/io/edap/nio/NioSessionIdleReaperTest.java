/*
 * Copyright 2023 The edap Project
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.edap.nio;

import io.edap.Edap;
import io.edap.NioServerSession;
import io.edap.Server;
import io.edap.ServerGroup;
import io.edap.buffer.FastBuf;
import io.edap.nio.enums.ThreadType;
import io.edap.nio.impl.FastSelectorProvider;
import io.edap.pool.impl.ThreadLocalPool;
import io.edap.util.EdapTime;
import io.edap.util.timing.TimerService;
import io.edap.util.timing.WheelTimerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NioSessionIdleReaper} —— NIO 会话空闲回收。
 *
 * <p>覆盖三件事：空闲判定（真的关连接）、活跃连接不被误杀、默认关闭（不配就不启用）。</p>
 */
public class NioSessionIdleReaperTest {

    private TimerService timerService;
    private final List<java.io.Closeable> closeables = new ArrayList<>();

    @BeforeEach
    public void setUp() {
        timerService = WheelTimerService.create();
    }

    @AfterEach
    public void tearDown() {
        for (java.io.Closeable c : closeables) {
            try {
                c.close();
            } catch (Exception ignore) {
                // 测试清理，忽略
            }
        }
        closeables.clear();
        timerService.close();
        System.clearProperty(Edap.SESSION_IDLE_TIMEOUT_KEY);
    }

    // ------------------------------------------------------------------
    // 扫描周期推导
    // ------------------------------------------------------------------

    @Test
    public void scanIntervalIsDerivedFromTimeout() {
        assertEquals(1_000L, NioSessionIdleReaper.deriveScanInterval(1_000L));
        assertEquals(1_000L, NioSessionIdleReaper.deriveScanInterval(4_000L));
        assertEquals(5_000L, NioSessionIdleReaper.deriveScanInterval(20_000L));
        assertEquals(60_000L, NioSessionIdleReaper.deriveScanInterval(10_000_000L));
    }

    // ------------------------------------------------------------------
    // 默认关闭
    // ------------------------------------------------------------------

    @Test
    public void idleReaperIsOffByDefault() {
        Edap edap = new Edap();
        assertEquals(-1L, edap.parseSessionIdleTimeoutMs(),
                "没配 idleTimeoutMs 时不应该启用空闲回收");
    }

    @Test
    public void idleTimeoutIsReadFromSystemProperty() {
        System.setProperty(Edap.SESSION_IDLE_TIMEOUT_KEY, "30000");
        Edap edap = new Edap();
        assertEquals(30_000L, edap.parseSessionIdleTimeoutMs());
    }

    @Test
    public void invalidIdleTimeoutIsIgnored() {
        System.setProperty(Edap.SESSION_IDLE_TIMEOUT_KEY, "not-a-number");
        Edap edap = new Edap();
        assertEquals(-1L, edap.parseSessionIdleTimeoutMs());
    }

    // ------------------------------------------------------------------
    // 空闲回收
    // ------------------------------------------------------------------

    @Test
    public void idleSessionIsClosedActiveSessionIsKept() throws Exception {
        TestServer server = new TestServer();
        server.setThreadType(ThreadType.EDAP);
        server.setSelectorProvider(new FastSelectorProvider());
        server.setIoThreadCount(1);
        server.setNioSesionPooled(true);
        server.setNioSessionPool(new ThreadLocalPool<>());

        IoSelectorManager manager = new IoSelectorManager(server, new Server.Addr());
        server.addIoSelectorManager(new Server.Addr(), manager);

        ServerGroup group = new ServerGroup();
        group.setName("reaper-test");
        group.addServer(server);
        Edap edap = new Edap();
        edap.addServerGroup(group);

        // 一条空闲连接（很久没读也没写）
        Connection idle = connect(server, manager);
        idle.session.setLastReadTime(EdapTime.instance().currentTimeMillis() - 60_000L);
        // 一条活跃连接
        Connection active = connect(server, manager);
        active.session.setLastReadTime(EdapTime.instance().currentTimeMillis());
        active.session.setLastWriteTime(EdapTime.instance().currentTimeMillis());

        NioSessionIdleReaper reaper = new NioSessionIdleReaper(edap, 1_000L, timerService);
        int closed = reaper.scan();

        assertEquals(1, closed, "只应该关掉那条空闲连接");
        assertFalse(idle.channel.isOpen(), "空闲连接应被关闭");
        assertTrue(active.channel.isOpen(), "活跃连接不该被关");
    }

    @Test
    public void writeKeepsSessionAlive() throws Exception {
        TestServer server = newTestServer();
        IoSelectorManager manager = new IoSelectorManager(server, new Server.Addr());
        server.addIoSelectorManager(new Server.Addr(), manager);

        ServerGroup group = new ServerGroup();
        group.setName("reaper-test");
        group.addServer(server);
        Edap edap = new Edap();
        edap.addServerGroup(group);

        Connection conn = connect(server, manager);
        // 只写不读 —— 连接依然是活的
        conn.session.setLastReadTime(EdapTime.instance().currentTimeMillis() - 60_000L);
        conn.session.setLastWriteTime(EdapTime.instance().currentTimeMillis());

        NioSessionIdleReaper reaper = new NioSessionIdleReaper(edap, 1_000L, timerService);
        assertEquals(0, reaper.scan());
        assertTrue(conn.channel.isOpen());
    }

    @Test
    public void closeCancelsTheTimerTask() throws Exception {
        Edap edap = new Edap();
        NioSessionIdleReaper reaper = new NioSessionIdleReaper(edap, 60_000L, timerService);
        reaper.start();
        assertTrue(timerService.pendingCount() > 0, "回收任务应挂在定时服务上");

        reaper.close();
        // 撤掉之后待处理任务数会回落（轮子的下一个 tick 才真正摘除）
        long deadline = System.currentTimeMillis() + 3_000L;
        while (timerService.pendingCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, timerService.pendingCount(), "关闭后定时任务应被撤掉");
    }

    // ------------------------------------------------------------------
    // 关闭路径：池化会话只能归还一次
    // ------------------------------------------------------------------

    @Test
    public void pooledSessionIsRequitedOnlyOnce() throws Exception {
        TestServer server = newTestServer();
        server.setNioSesionPooled(true);
        ThreadLocalPool<NioServerSession> pool = new ThreadLocalPool<>();
        server.setNioSessionPool(pool);

        Connection conn = connect(server, new IoSelectorManager(server, new Server.Addr()));
        conn.session.setLastReadTime(EdapTime.instance().currentTimeMillis() - 60_000L);

        // 关两次（模拟空闲回收线程与 IO 线程同时关同一条连接）
        NioSessionCloser.close(server, conn.key(), conn.session);
        NioSessionCloser.close(server, conn.key(), conn.session);

        assertNotNull(pool.borrow(), "会话应被归还一次");
        assertNull(pool.borrow(), "池里不该有第二份同一个会话");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private TestServer newTestServer() {
        TestServer server = new TestServer();
        server.setThreadType(ThreadType.EDAP);
        server.setSelectorProvider(new FastSelectorProvider());
        server.setIoThreadCount(1);
        return server;
    }

    /**
     * 建一条真实 TCP 连接，并把服务端那一端注册进 selector。
     */
    private Connection connect(TestServer server, IoSelectorManager manager) throws IOException {
        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.bind(new InetSocketAddress("127.0.0.1", 0));
        SocketChannel client = SocketChannel.open();
        client.connect(ssc.getLocalAddress());
        SocketChannel accepted = ssc.accept();
        accepted.configureBlocking(false);

        TestSession session = new TestSession();
        session.setSocketChannel(accepted);
        session.setLastReadTime(EdapTime.instance().currentTimeMillis());
        manager.registerNioSession(session);

        closeables.add(ssc);
        closeables.add(client);
        closeables.add(accepted);
        return new Connection(session, accepted);
    }

    private static class Connection {
        final TestSession   session;
        final SocketChannel channel;

        Connection(TestSession session, SocketChannel channel) {
            this.session = session;
            this.channel = channel;
        }

        java.nio.channels.SelectionKey key() {
            return session.getSelectionKey();
        }
    }

    static class TestServer extends Server<Object, TestSession> {
        @Override
        public NioServerSession createNioSession() {
            return new TestSession();
        }
    }

    static class TestSession extends NioServerSession<Object> {
        @Override
        public boolean decode(FastBuf fastBuf, boolean threadSwitch) {
            return true;
        }

        @Override
        public void handle(Object message) {
        }
    }
}
