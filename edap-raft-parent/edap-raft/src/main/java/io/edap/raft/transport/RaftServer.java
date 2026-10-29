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

import io.edap.Server;
import io.edap.raft.protocol.Message;

import java.util.concurrent.atomic.AtomicLong;

/**
 * raft 的入站服务器。
 *
 * <p>复用 edap-nio-server 的 Server / NioServerSession / Decoder 体系，
 * 端口的 bind 由 {@code Edap} 与 {@code ServerGroup} 完成。</p>
 */
public class RaftServer extends Server<Message, RaftServerSession> {

    /** 默认的 IO 线程数：raft 的流量很小（心跳 + 复制），一个 IO 线程足够，
     *  避免每个端口都起一整套 reactor */
    public static final int DEFAULT_IO_THREAD_COUNT = 1;

    private final RaftDecoder decoder = new RaftDecoder();

    /** 入站收到的帧数，用来给传输层的健康度计数供数 */
    private final AtomicLong receivedFrames = new AtomicLong();

    /** IO 线程数，必须在 {@link #init()} 之前设置 */
    private int raftIoThreadCount = DEFAULT_IO_THREAD_COUNT;

    private volatile RaftMessageHandler handler;

    /**
     * 设置 IO 线程数。必须在 {@link #init()} 之前调用（框架在 init 里读它）。
     */
    @Override
    public void setIoThreadCount(int ioThreadCount) {
        if (ioThreadCount <= 0) {
            throw new IllegalArgumentException("ioThreadCount must be positive");
        }
        this.raftIoThreadCount = ioThreadCount;
        super.setIoThreadCount(ioThreadCount);
    }

    @Override
    public void init() {
        super.init();
        setDecoder(decoder);
        setIoThreadCount(raftIoThreadCount);
    }

    @Override
    public RaftServerSession createNioSession() {
        RaftServerSession session = new RaftServerSession();
        // 框架不会替我们设置 server 与 decoder
        session.setServer(this);
        session.setDecoder(decoder);
        session.setBufPool(getBufPool());
        session.setHandler(handler);
        session.setReceivedCounter(receivedFrames);
        return session;
    }

    /**
     * 入站收到的帧数
     */
    public long getReceivedFrames() {
        return receivedFrames.get();
    }

    /**
     * 设置入站消息的处理器，新建的连接会拿到它
     */
    public void setHandler(RaftMessageHandler handler) {
        this.handler = handler;
    }
}
