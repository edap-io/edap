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
import io.edap.raft.protocol.RaftCodec;
import io.edap.raft.protocol.Message;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
 */
public class SocketPeerClient {

    private static final Logger log = LoggerManager.getLogger(SocketPeerClient.class);

    /** 每个连接的发送队列长度，满了就丢弃新的帧 */
    private static final int QUEUE_SIZE      = 1024;
    /** 单次连接的等待时间 */
    private static final int CONNECT_TIMEOUT = 3000;

    private final Map<Long, PeerConnection> connections = new ConcurrentHashMap<>();

    private final RaftMessageHandler         inbound;
    private final int                        connectTimeout;

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
        connection.enqueue(RaftCodec.encode(message));
    }

    /**
     * 一个对端的连接：写线程 + 读线程
     */
    private class PeerConnection {

        private final Peer                   peer;
        private final ArrayBlockingQueue<byte[]> outQueue = new ArrayBlockingQueue<>(QUEUE_SIZE);
        private final RaftFrameReader        frameReader  = new RaftFrameReader();
        private final Thread                 writerThread;
        private final Thread                 readerThread;

        private volatile Socket              socket;
        private volatile OutputStream        out;
        private volatile InputStream         in;

        PeerConnection(Peer peer) {
            this.peer = peer;
            this.writerThread = new Thread(this::writeLoop, "raft-writer-" + peer.getNodeId());
            this.readerThread = new Thread(this::readLoop, "raft-reader-" + peer.getNodeId());
            writerThread.setDaemon(true);
            readerThread.setDaemon(true);
            writerThread.start();
            readerThread.start();
        }

        void enqueue(byte[] frame) {
            if (!outQueue.offer(frame)) {
                log.warn("Raft send queue to {} is full, drop message", l -> l.arg(peer.getNodeId()));
            }
        }

        private void writeLoop() {
            while (running) {
                byte[] frame;
                try {
                    frame = outQueue.poll(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (frame == null) {
                    continue;
                }
                OutputStream stream = ensureConnected();
                if (stream == null) {
                    // 连不上就丢掉这一帧，raft 协议允许消息丢失
                    continue;
                }
                try {
                    stream.write(frame);
                    stream.flush();
                } catch (IOException e) {
                    closeSocket();
                }
            }
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
