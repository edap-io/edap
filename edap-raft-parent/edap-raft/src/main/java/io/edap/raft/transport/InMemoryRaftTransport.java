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

import io.edap.raft.Peer;
import io.edap.raft.protocol.Message;

/**
 * 单 JVM 内的传输层，多个节点共享一个 {@link InMemoryNetwork}。
 *
 * <p>没有 socket，也没有序列化，消息直接投递给对端。适合开发与单元测试。</p>
 */
public class InMemoryRaftTransport implements RaftTransport {

    private final InMemoryNetwork   network;
    private final long              nodeId;

    private volatile RaftMessageHandler handler;

    public InMemoryRaftTransport(InMemoryNetwork network, long nodeId) {
        this.network = network;
        this.nodeId = nodeId;
        network.register(this);
    }

    public long getNodeId() {
        return nodeId;
    }

    @Override
    public void setHandler(RaftMessageHandler handler) {
        this.handler = handler;
    }

    @Override
    public void start() {
        // 重新注册到网络上，节点重启之后还能收到消息
        network.register(this);
    }

    @Override
    public void stop() {
        network.unregister(nodeId);
    }

    @Override
    public void send(Peer peer, Message message) {
        if (peer == null) {
            return;
        }
        network.deliver(nodeId, peer.getNodeId(), message);
    }

    /**
     * 收到网络投来的消息
     */
    void onMessage(Message message) {
        RaftMessageHandler h = handler;
        if (h != null) {
            h.onMessage(message);
        }
    }
}
