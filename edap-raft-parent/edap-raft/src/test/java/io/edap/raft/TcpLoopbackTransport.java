package io.edap.raft;

import io.edap.raft.protocol.Message;
import io.edap.raft.protocol.RaftCodec;
import io.edap.raft.transport.RaftFrameReader;
import io.edap.raft.transport.RaftMessageHandler;
import io.edap.raft.transport.RaftTransport;
import io.edap.raft.transport.TransportStats;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基准用的 TCP 回环传输层。
 *
 * <p>目的是让 edap-raft 与 dragonboat 的传输条件对等：dragonboat 走
 * TCP 回环 + protobuf 序列化，edap-raft 之前在基准里是进程内直投
 * （不序列化、不走网络）。用这个传输层把 edap-raft 也拉到同样的条件下。</p>
 *
 * <p>线格式仍然是 edap-raft 自己的 {@link RaftCodec}：
 * {@code [4B length][1B type][payload]}，一个 {@code Message} 一帧。
 * 多个帧可以在一次 socket write 里拼在一起发出去 —— 这跟 dragonboat 的
 * {@code pb.MessageBatch} 是同一个意思（那边是一个批次里装多条消息），
 * 差别只在批次外壳的编码方式。</p>
 *
 * <p>出站模型照抄 dragonboat 的 {@code internal/transport/transport.go}：</p>
 * <ul>
 *     <li>每个对端一条 TCP 长连接，连接上跑一个写线程</li>
 *     <li>写线程从队列里取到第一条消息后，把队列里现有的消息全部取出来
 *         拼成一批，一次 write 出去（批次上限 64MB，与 dragonboat 的
 *         {@code MaxMessageBatchSize} 一致）</li>
 *     <li>连接断了就重连，连不上时这一批丢掉 —— raft 协议允许消息丢失</li>
 * </ul>
 */
public class TcpLoopbackTransport implements RaftTransport, TransportStats {

    /** 单个对端的发送队列长度，与 dragonboat 的 SendQueueLength(2048) 同量级 */
    private static final int QUEUE_SIZE = 2048;

    /** 一个批次最多拼多少字节，与 dragonboat 的 MaxMessageBatchSize 一致 */
    private static final int MAX_BATCH_BYTES = 64 * 1024 * 1024;

    /** 建连超时 */
    private static final int CONNECT_TIMEOUT = 3000;

    private final Map<Long, PeerLink> links = new ConcurrentHashMap<>();

    /** 发出去的帧数 */
    private final AtomicLong framesSent = new AtomicLong();
    /** 发出去的字节数 */
    private final AtomicLong bytesSent = new AtomicLong();
    /** 收进来的帧数 */
    private final AtomicLong framesReceived = new AtomicLong();
    /** 因为队列满或者连不上而丢掉的帧数 */
    private final AtomicLong framesDropped = new AtomicLong();

    private volatile ServerSocket      server;
    private volatile int               port;
    private volatile RaftMessageHandler handler;
    private volatile boolean           running;
    private Thread                     acceptThread;

    /**
     * 绑定回环端口，端口号由内核分配，返回实际监听的端口
     */
    public int bind() throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 256);
        server = ss;
        port = ss.getLocalPort();
        return port;
    }

    public int getPort() {
        return port;
    }

    @Override
    public void setHandler(RaftMessageHandler handler) {
        this.handler = handler;
    }

    /**
     * 启动传输层。出站连接在第一次给某个对端发消息时才建立，
     * 建立连接期间消息会排在队列里，不会丢。
     */
    public void start() {
        running = true;
        acceptThread = new Thread(this::acceptLoop, "raft-accept-" + port);
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    @Override
    public void stop() {
        running = false;
        for (PeerLink link : links.values()) {
            link.close();
        }
        links.clear();
        ServerSocket ss = server;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException e) {
                // 忽略
            }
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
    }

    @Override
    public void send(Peer peer, Message message) {
        if (!running || peer == null) {
            return;
        }
        PeerLink link = links.get(peer.getNodeId());
        if (link == null) {
            link = links.computeIfAbsent(peer.getNodeId(), id -> new PeerLink(peer));
        }
        link.enqueue(RaftCodec.encode(message));
    }

    public long getFramesSent() {
        return framesSent.get();
    }

    public long getBytesSent() {
        return bytesSent.get();
    }

    public long getFramesReceived() {
        return framesReceived.get();
    }

    public long getFramesDropped() {
        return framesDropped.get();
    }

    // ------------------------------------------------------------------
    // 入站
    // ------------------------------------------------------------------

    private void acceptLoop() {
        while (running) {
            Socket socket;
            try {
                socket = server.accept();
            } catch (IOException e) {
                if (!running) {
                    return;
                }
                continue;
            }
            try {
                socket.setTcpNoDelay(true);
            } catch (IOException e) {
                // 忽略，用默认的 Nagle 行为
            }
            Thread t = new Thread(() -> readLoop(socket), "raft-recv-" + socket.getPort());
            t.setDaemon(true);
            t.start();
        }
    }

    private void readLoop(Socket socket) {
        RaftFrameReader reader = new RaftFrameReader();
        byte[] buf = new byte[8192];
        try {
            InputStream in = socket.getInputStream();
            while (running) {
                int n = in.read(buf);
                if (n < 0) {
                    return;
                }
                if (n == 0) {
                    continue;
                }
                reader.append(buf, 0, n);
                Message msg;
                while ((msg = reader.next()) != null) {
                    framesReceived.incrementAndGet();
                    RaftMessageHandler h = handler;
                    if (h != null) {
                        h.onMessage(msg);
                    }
                }
            }
        } catch (IOException e) {
            // 连接断了，正常退出
        } catch (RuntimeException e) {
            // 协议错误，丢掉这条连接
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                // 忽略
            }
        }
    }

    // ------------------------------------------------------------------
    // 出站
    // ------------------------------------------------------------------

    /**
     * 一个对端的连接：一条 TCP 长连接 + 一个写线程
     */
    private class PeerLink {

        private final Peer                   peer;
        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(QUEUE_SIZE);
        private final Thread                 writerThread;

        private volatile Socket              socket;
        private volatile OutputStream        out;

        PeerLink(Peer peer) {
            this.peer = peer;
            this.writerThread = new Thread(this::writeLoop, "raft-writer-" + peer.getNodeId());
            this.writerThread.setDaemon(true);
            this.writerThread.start();
        }

        void enqueue(byte[] frame) {
            if (!queue.offer(frame)) {
                framesDropped.incrementAndGet();
            }
        }

        void close() {
            closeSocket();
            writerThread.interrupt();
        }

        private void writeLoop() {
            // 先把连接建立起来，连不上就一直重试：
            // 否则最初的几条消息会因为连不上被丢掉，选主要多花几个心跳周期
            while (running && out == null) {
                try {
                    connect();
                } catch (IOException e) {
                    sleep(20);
                }
            }
            while (running) {
                byte[] first;
                try {
                    first = queue.poll(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (first == null) {
                    continue;
                }
                OutputStream stream = out;
                if (stream == null) {
                    framesDropped.incrementAndGet();
                    continue;
                }
                // 把队列里现有的消息全取出来，拼成一批一次发出去
                int total = first.length;
                List<byte[]> batch = null;
                while (total < MAX_BATCH_BYTES) {
                    byte[] next = queue.poll();
                    if (next == null) {
                        break;
                    }
                    if (batch == null) {
                        batch = new ArrayList<>(4);
                        batch.add(first);
                    }
                    batch.add(next);
                    total += next.length;
                }
                byte[] payload;
                if (batch == null) {
                    payload = first;
                } else {
                    payload = new byte[total];
                    int off = 0;
                    for (byte[] frame : batch) {
                        System.arraycopy(frame, 0, payload, off, frame.length);
                        off += frame.length;
                    }
                }
                try {
                    stream.write(payload);
                    stream.flush();
                    framesSent.addAndGet(batch == null ? 1 : batch.size());
                    bytesSent.addAndGet(total);
                } catch (IOException e) {
                    closeSocket();
                    framesDropped.incrementAndGet();
                }
            }
        }

        private synchronized void connect() throws IOException {
            Socket s = new Socket();
            try {
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress(peer.getHost(), peer.getPort()), CONNECT_TIMEOUT);
            } catch (IOException e) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // 忽略
                }
                throw e;
            }
            socket = s;
            out = s.getOutputStream();
        }

        private synchronized void closeSocket() {
            Socket s = socket;
            socket = null;
            out = null;
            if (s != null) {
                try {
                    s.close();
                } catch (IOException e) {
                    // 忽略
                }
            }
        }

        private static void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
