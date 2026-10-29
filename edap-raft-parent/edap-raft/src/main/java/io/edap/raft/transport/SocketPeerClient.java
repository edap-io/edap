/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.raft.transport;

import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.raft.Peer;
import io.edap.raft.protocol.MessageType;
import io.edap.raft.protocol.RaftCodec;
import io.edap.raft.protocol.Message;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 出站客户端：主动连到对端，把 raft 帧发过去。
 *
 * <p>每个对端一个连接，连接上跑一个写线程与一个读线程：</p>
 * <ul>
 *     <li>写线程从队列里取帧，连接可用就写出去，不可用就丢弃这一帧
 *         （raft 允许消息丢失，重传由上层的心跳与重试负责）</li>
 *     <li>读线程负责解析对端回来的帧，交给 {@link RaftMessageHandler}</li>
 * </ul>
 *
 * <p>之所以把写放在独立线程上，是因为调用方是 raft 状态机线程，
 * 不能被 connect 的耗时拖住。</p>
 *
 * <p><b>出站是批量写的</b>：写线程把队列里现有的帧拼成一批一次 write 出去
 * （上限 64MB，与 dragonboat 的 {@code MaxMessageBatchSize} 一致）。
 * 一条一条写的话，每次 write 都要一次系统调用，吞吐会差一个数量级。</p>
 *
 * <p><b>丢帧是分级的</b>：复制类消息（{@code MSG_APP} / {@code MSG_APP_RESP} /
 * {@code MSG_SNAP}）与控制类消息（心跳、投票）走<b>同一条</b>队列，但用<b>互相独立的</b>
 * 预算判定丢不丢：</p>
 * <ul>
 *     <li>复制类：按排队字节数限额（{@link #MAX_CRITICAL_BYTES}），
 *         只有极端过载才会丢。丢了要等下一个心跳对端才知道自己落后，
 *         复制会停滞一整个心跳周期</li>
 *     <li>控制类：按排队帧数限额（{@link #MAX_CONTROL_FRAMES}），
 *         丢掉只影响一个心跳周期</li>
 * </ul>
 *
 * <p>为什么是同一条队列：分成两条再「复制类优先」取，会<b>饿死控制类</b> ——
 * leader 一直在复制时复制队列永远不为空，心跳与投票就永远发不出去，
 * follower 收不到心跳就会超时 campaigning，集群被选举风暴反复打断
 * （实测吞吐掉到 60%，延迟涨到 1.7 倍）。同一条 FIFO 队列没有饿死问题，
 * 而「复制类不受控制类挤掉」这个目标由上面的独立预算保证。</p>
 */
public class SocketPeerClient implements TransportStats {

    private static final Logger log = LoggerManager.getLogger(SocketPeerClient.class);

    /** 控制类消息（心跳、投票）的排队帧数上限，超了就丢新的控制类帧 */
    private static final int MAX_CONTROL_FRAMES = 1024;
    /** 单次连接的等待时间 */
    private static final int CONNECT_TIMEOUT = 3000;
    /** 一个批次最多拼多少字节，与 dragonboat 的 MaxMessageBatchSize 一致 */
    private static final int MAX_BATCH_BYTES = 64 * 1024 * 1024;
    /** 复制类消息的排队字节上限，超了就丢（并单独计数） */
    private static final long MAX_CRITICAL_BYTES = 64L * 1024 * 1024;

    private final Map<Long, PeerConnection> connections = new ConcurrentHashMap<>();

    private final RaftMessageHandler         inbound;
    private final int                        connectTimeout;

    /** 发出去的帧数 */
    private final AtomicLong framesSent = new AtomicLong();
    /** 发出去的字节数 */
    private final AtomicLong bytesSent = new AtomicLong();
    /** 收到的帧数 */
    private final AtomicLong framesReceived = new AtomicLong();
    /** 丢掉的帧数 */
    private final AtomicLong framesDropped = new AtomicLong();
    /** 丢掉的复制类帧数 */
    private final AtomicLong criticalFramesDropped = new AtomicLong();

    private volatile boolean                 running;

    public SocketPeerClient(RaftMessageHandler inbound) {
        this(inbound, CONNECT_TIMEOUT);
    }

    public SocketPeerClient(RaftMessageHandler inbound, int connectTimeout) {
        this.inbound = inbound;
        this.connectTimeout = connectTimeout;
    }

    public void start() {
        running = true;
    }

    public void stop() {
        running = false;
        for (PeerConnection connection : connections.values()) {
            connection.close();
        }
        connections.clear();
    }

    /**
     * 给对端发一条消息，方法不会阻塞
     */
    public void send(Peer peer, Message message) {
        if (!running || peer == null) {
            return;
        }
        PeerConnection connection = connections.get(peer.getNodeId());
        if (connection == null) {
            connection = connections.computeIfAbsent(peer.getNodeId(), id -> new PeerConnection(peer));
        }
        connection.enqueue(RaftCodec.encode(message), isCritical(message.getType()));
    }

    @Override
    public long getFramesSent() {
        return framesSent.get();
    }

    @Override
    public long getBytesSent() {
        return bytesSent.get();
    }

    @Override
    public long getFramesReceived() {
        return framesReceived.get();
    }

    @Override
    public long getFramesDropped() {
        return framesDropped.get();
    }

    @Override
    public long getCriticalFramesDropped() {
        return criticalFramesDropped.get();
    }

    /**
     * 复制类消息：丢了之后对端要等下一个心跳才知道自己落后，
     * 复制会停滞一整个心跳周期。控制类消息（心跳、投票）丢掉只影响一个心跳周期。
     */
    private static boolean isCritical(MessageType type) {
        switch (type) {
            case MSG_APP:
            case MSG_APP_RESP:
            case MSG_SNAP:
                return true;
            default:
                return false;
        }
    }

    /**
     * 一帧待发送的数据。
     *
     * <p>带上「是不是复制类」的标记：出队时要按标记归还复制类的字节预算。</p>
     */
    private static final class Frame {

        final byte[]  bytes;
        final boolean critical;

        Frame(byte[] bytes, boolean critical) {
            this.bytes = bytes;
            this.critical = critical;
        }
    }

    /**
     * 一个对端的连接：写线程 + 读线程
     */
    private class PeerConnection {

        private final Peer                        peer;
        /** 待发队列：复制类与控制类共用一条 FIFO，靠独立的预算判定丢不丢 */
        private final LinkedBlockingQueue<Frame>  queue = new LinkedBlockingQueue<>();
        /** 复制类消息排队占用的字节数 */
        private final AtomicLong                  criticalBytes = new AtomicLong();
        /** 控制类消息排队的帧数 */
        private final AtomicInteger               controlFrames = new AtomicInteger();
        private final RaftFrameReader             frameReader  = new RaftFrameReader();
        private final Thread                      writerThread;
        private final Thread                      readerThread;

        private volatile Socket                   socket;
        private volatile OutputStream             out;
        private volatile InputStream              in;

        PeerConnection(Peer peer) {
            this.peer = peer;
            this.writerThread = new Thread(this::writeLoop, "raft-writer-" + peer.getNodeId());
            this.readerThread = new Thread(this::readLoop, "raft-reader-" + peer.getNodeId());
            writerThread.setDaemon(true);
            readerThread.setDaemon(true);
            writerThread.start();
            readerThread.start();
        }

        void enqueue(byte[] bytes, boolean critical) {
            if (critical) {
                // 复制类：只受字节预算约束，不会被控制类消息挤掉
                long size = criticalBytes.addAndGet(bytes.length);
                if (size > MAX_CRITICAL_BYTES) {
                    criticalBytes.addAndGet(-bytes.length);
                    criticalFramesDropped.incrementAndGet();
                    framesDropped.incrementAndGet();
                    log.error("Raft critical send queue to {} is full, drop message",
                            l -> l.arg(peer.getNodeId()));
                    return;
                }
                queue.offer(new Frame(bytes, true));
                return;
            }
            // 控制类：只看控制类自己排了多少帧。
            // 不能拿整个队列的长度当判据 —— 复制类堆积也会把队列撑长，
            // 那时把心跳丢掉反而会引发选举风暴。
            if (controlFrames.incrementAndGet() > MAX_CONTROL_FRAMES) {
                controlFrames.decrementAndGet();
                framesDropped.incrementAndGet();
                log.warn("Raft control send queue to {} is full, drop message",
                        l -> l.arg(peer.getNodeId()));
                return;
            }
            queue.offer(new Frame(bytes, false));
        }

        private void writeLoop() {
            while (running) {
                Frame first = pollFirst();
                if (first == null) {
                    if (Thread.currentThread().isInterrupted()) {
                        return;
                    }
                    continue;
                }
                releaseCriticalBytes(first);
                OutputStream stream = ensureConnected();
                if (stream == null) {
                    // 连不上就丢掉这一帧，raft 协议允许消息丢失
                    framesDropped.incrementAndGet();
                    continue;
                }
                // 把队列里现有的帧全取出来，拼成一批一次发出去
                int total = first.bytes.length;
                List<byte[]> batch = null;
                while (total < MAX_BATCH_BYTES) {
                    Frame next = pollNext();
                    if (next == null) {
                        break;
                    }
                    releaseCriticalBytes(next);
                    if (batch == null) {
                        batch = new ArrayList<>(4);
                        batch.add(first.bytes);
                    }
                    batch.add(next.bytes);
                    total += next.bytes.length;
                }
                byte[] payload;
                if (batch == null) {
                    payload = first.bytes;
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

        /**
         * 归还复制类的字节预算、控制类的帧数
         */
        private void releaseCriticalBytes(Frame frame) {
            if (frame.critical) {
                criticalBytes.addAndGet(-frame.bytes.length);
            } else {
                controlFrames.decrementAndGet();
            }
        }

        /**
         * 取下一条要发的帧。
         *
         * @return null 表示暂时没有消息，或者线程被中断了
         */
        private Frame pollFirst() {
            try {
                return queue.poll(50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        private Frame pollNext() {
            return queue.poll();
        }

        private void readLoop() {
            byte[] data = new byte[8192];
            while (running) {
                InputStream stream = in;
                if (stream == null) {
                    sleep();
                    continue;
                }
                try {
                    int n = stream.read(data);
                    if (n < 0) {
                        closeSocket();
                        continue;
                    }
                    if (n == 0) {
                        continue;
                    }
                    frameReader.append(data, 0, n);
                    Message message;
                    while ((message = frameReader.next()) != null) {
                        framesReceived.incrementAndGet();
                        inbound.onMessage(message);
                    }
                } catch (IOException e) {
                    closeSocket();
                } catch (RuntimeException e) {
                    // 协议错误，丢掉这条连接
                    log.warn("Raft peer {} protocol error", l -> l.arg(peer.getNodeId()).threw(e));
                    closeSocket();
                }
            }
        }

        private synchronized OutputStream ensureConnected() {
            OutputStream stream = out;
            if (stream != null) {
                return stream;
            }
            closeSocket();
            try {
                Socket newSocket = new Socket();
                newSocket.setTcpNoDelay(true);
                newSocket.connect(new InetSocketAddress(peer.getHost(), peer.getPort()), connectTimeout);
                socket = newSocket;
                out = newSocket.getOutputStream();
                in = newSocket.getInputStream();
                return out;
            } catch (IOException e) {
                log.warn("Raft connect to {} failed", l -> l.arg(peer.getAddress()).threw(e));
                closeSocket();
                return null;
            }
        }

        private synchronized void closeSocket() {
            Socket s = socket;
            socket = null;
            out = null;
            in = null;
            if (s != null) {
                try {
                    s.close();
                } catch (IOException e) {
                    // 忽略
                }
            }
        }

        void close() {
            closeSocket();
            writerThread.interrupt();
            readerThread.interrupt();
        }

        private static void sleep() {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
