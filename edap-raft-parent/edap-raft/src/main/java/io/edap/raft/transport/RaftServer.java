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

/**
 * raft 的入站服务器。
 *
 * <p>复用 edap-nio-server 的 Server / NioServerSession / Decoder 体系，
 * 端口的 bind 由 {@code Edap} 与 {@code ServerGroup} 完成。</p>
 */
public class RaftServer extends Server<Message, RaftServerSession> {

    private final RaftDecoder decoder = new RaftDecoder();

    private volatile RaftMessageHandler handler;

    @Override
    public void init() {
        super.init();
        setDecoder(decoder);
        // raft 的流量很小（心跳 + 复制），一个 IO 线程足够，避免每个端口都起一整套 reactor
        setIoThreadCount(1);
    }

    @Override
    public RaftServerSession createNioSession() {
        RaftServerSession session = new RaftServerSession();
        // 框架不会替我们设置 server 与 decoder
        session.setServer(this);
        session.setDecoder(decoder);
        session.setBufPool(getBufPool());
        session.setHandler(handler);
        return session;
    }

    /**
     * 设置入站消息的处理器，新建的连接会拿到它
     */
    public void setHandler(RaftMessageHandler handler) {
        this.handler = handler;
    }
}
