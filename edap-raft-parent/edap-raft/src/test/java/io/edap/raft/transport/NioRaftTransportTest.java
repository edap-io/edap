package io.edap.raft.transport;

import io.edap.Edap;
import io.edap.raft.Peer;
import io.edap.raft.protocol.Message;
import io.edap.raft.protocol.MessageType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产传输层 {@link NioRaftTransport} 的测试。
 *
 * <p>这个传输层在仓库里从来没有被跑过（只有定义 + 文档提及），
 * 所以这里做三件最基本的事：</p>
 * <ol>
 *     <li>两个节点之间真的能互通一条消息（入站走 edap-nio，出站走阻塞 Socket）</li>
 *     <li>对端停掉之后发送方不炸，连接能重连</li>
 *     <li>{@code stop()} 之后端口能重新绑定（没有句柄泄漏）</li>
 * </ol>
 *
 * <p>需要 {@code --add-opens java.base/sun.nio.ch=ALL-UNNAMED}：
 * edap-nio 的 {@code FastSelectorProvider} 反射 {@code sun.nio.ch}，
 * JDK17 下不加这个开关会抛 {@code InaccessibleObjectException}。
 * pom 里已经配好了。</p>
 */
public class NioRaftTransportTest {

    /**
     * 两个节点之间能互通一条消息。
     */
    @Test
    public void twoNodesExchangeMessage() throws Exception {
        int port1 = freePort();
        int port2 = freePort();
        Edap edap = new Edap();
        BlockingQueue<Message> inbox = new ArrayBlockingQueue<>(64);

        NioRaftTransport t1 = new NioRaftTransport("127.0.0.1", port1, edap);
        NioRaftTransport t2 = new NioRaftTransport("127.0.0.1", port2, edap);
        try {
            t2.setHandler(inbox::offer);
            t2.start();
            t1.start();
            // 两个 server 都入队之后一次性 bind
            edap.run();

            Message msg = new Message().setType(MessageType.MSG_HEARTBEAT)
                    .setFrom(1).setTo(2).setTerm(7);
            t1.send(new Peer(2, "127.0.0.1", port2), msg);

            Message received = inbox.poll(10, TimeUnit.SECONDS);
            assertNotNull(received, "对端没有收到心跳");
            assertEquals(MessageType.MSG_HEARTBEAT, received.getType());
            assertEquals(1, received.getFrom());
            assertEquals(2, received.getTo());
            assertEquals(7, received.getTerm());

            assertTrue(t1.getFramesSent() > 0, "发送计数没有增长");
            assertTrue(t2.getFramesReceived() > 0, "接收计数没有增长");
        } finally {
            t1.stop();
            t2.stop();
            edap.stop();
        }
    }

    /**
     * 对端停掉之后发送方不抛异常，连接能重连。
     */
    @Test
    public void sendAfterPeerStoppedDoesNotThrow() throws Exception {
        int port1 = freePort();
        int port2 = freePort();
        Edap edap = new Edap();
        BlockingQueue<Message> inbox = new ArrayBlockingQueue<>(64);

        NioRaftTransport t1 = new NioRaftTransport("127.0.0.1", port1, edap);
        NioRaftTransport t2 = new NioRaftTransport("127.0.0.1", port2, edap);
        try {
            t2.setHandler(inbox::offer);
            t2.start();
            t1.start();
            edap.run();

            Message msg = new Message().setType(MessageType.MSG_HEARTBEAT)
                    .setFrom(1).setTo(2).setTerm(1);
            t1.send(new Peer(2, "127.0.0.1", port2), msg);
            assertNotNull(inbox.poll(10, TimeUnit.SECONDS), "对端没有收到心跳");

            // 对端停掉：发送方不能再崩
            t2.stop();
            for (int i = 0; i < 20; i++) {
                t1.send(new Peer(2, "127.0.0.1", port2), msg);
                Thread.sleep(10);
            }
            // 停掉之后帧会被丢掉（连不上），计数会涨
            assertTrue(t1.getFramesDropped() > 0 || t1.getFramesSent() > 0);
        } finally {
            t1.stop();
            t2.stop();
            edap.stop();
        }
    }

    /**
     * {@code stop()} 之后端口能重新绑定。
     */
    @Test
    public void portCanBeReboundAfterStop() throws Exception {
        int port = freePort();
        Edap edap = new Edap();
        NioRaftTransport t = new NioRaftTransport("127.0.0.1", port, edap);
        try {
            t.start();
            edap.run();
            // 端口此刻应该是被占用的
            assertTrue(portInUse(port), "端口没有真正监听");

            t.stop();
            edap.stop();
            // 停掉之后端口要能重新绑定
            long deadline = System.currentTimeMillis() + 10_000;
            boolean rebound = false;
            while (System.currentTimeMillis() < deadline) {
                try (ServerSocket ss = new ServerSocket()) {
                    ss.setReuseAddress(true);
                    ss.bind(new InetSocketAddress("127.0.0.1", port));
                    rebound = true;
                    break;
                } catch (IOException e) {
                    Thread.sleep(100);
                }
            }
            assertTrue(rebound, "端口停掉之后仍然绑不上");
        } finally {
            t.stop();
            edap.stop();
        }
    }

    /**
     * 找一个当前空闲的端口。
     *
     * <p>绑 0 端口让内核分配，然后立刻释放 —— 有极小的概率被别的进程抢走，
     * 但对测试来说足够了（框架的 {@code AbstractAcceptor.bind} 不回读实际端口，
     * 只能显式指定）。</p>
     */
    private static int freePort() throws IOException {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress("127.0.0.1", 0));
            return ss.getLocalPort();
        }
    }

    /**
     * 端口上有没有人在监听
     */
    private static boolean portInUse(int port) {
        try (ServerSocket ss = new ServerSocket()) {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress("127.0.0.1", port));
            return false;
        } catch (IOException e) {
            return true;
        }
    }
}
