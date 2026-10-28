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

import io.edap.Edap;
import io.edap.raft.Peer;
import io.edap.raft.protocol.Message;

/**
 * 基于 edap-nio-server 的传输层实现：入站走 NIO，出站走阻塞 Socket。
 *
 * <p>入站端口由 {@link RaftServer} 提供，bind 动作交给 {@link Edap}：
 * 如果 {@code edap} 是本传输层自己创建的，{@link #start()} 会把 {@code Edap} 跑起来；
 * 如果是宿主应用共享的 {@code Edap}，则由宿主负责 run。</p>
 */
public class NioRaftTransport implements RaftTransport {

    private final RaftServer        server;
    private final Edap              edap;
    private final boolean           ownEdap;
    private final SocketPeerClient  client;

    private volatile RaftMessageHandler handler;

    /**
     * @param host    监听地址
     * @param port    监听端口
     * @param edap    共享的 Edap 实例，为 null 时自己创建一个
     */
    public NioRaftTransport(String host, int port, Edap edap) {
        this.ownEdap = edap == null;
        this.edap = ownEdap ? new Edap() : edap;
        this.server = new RaftServer();
        this.server.name("raft-server");
        this.server.listen(host, port);
        this.client = new SocketPeerClient(this::onMessage);
    }

    @Override
    public void setHandler(RaftMessageHandler handler) {
        this.handler = handler;
        server.setHandler(handler);
    }

    @Override
    public void start() {
        server.setHandler(handler);
        // run 之前只是入队，run 之后立即 bind
        edap.addServerAndBind(server);
        if (ownEdap) {
            try {
                edap.run();
            } catch (Exception e) {
                throw new io.edap.raft.RaftException("Start raft transport failed", e);
            }
        }
        client.start();
    }

    @Override
    public void stop() {
        client.stop();
        if (ownEdap) {
            edap.stop();
        }
    }

    @Override
    public void send(Peer peer, Message message) {
        client.send(peer, message);
    }

    private void onMessage(Message message) {
        RaftMessageHandler h = handler;
        if (h != null) {
            h.onMessage(message);
        }
    }
}
