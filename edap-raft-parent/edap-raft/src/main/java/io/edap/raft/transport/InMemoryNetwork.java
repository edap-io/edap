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

import io.edap.raft.protocol.Message;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单 JVM 内的 raft 网络，把多个节点的 {@link InMemoryRaftTransport} 连在一起。
 *
 * <p>用于开发与测试：没有 socket，也没有序列化拷贝，消息直接投递给对端的 handler。</p>
 */
public class InMemoryNetwork {

    private final Map<Long, InMemoryRaftTransport> nodes = new ConcurrentHashMap<>();

    /**
     * 把一个传输层注册到网络上
     */
    public void register(InMemoryRaftTransport transport) {
        nodes.put(transport.getNodeId(), transport);
    }

    /**
     * 把一个传输层从网络上摘掉，之后发给它的消息都会被丢弃
     */
    public void unregister(long nodeId) {
        nodes.remove(nodeId);
    }

    /**
     * 投递一条消息
     */
    public void deliver(long fromNodeId, long toNodeId, Message message) {
        InMemoryRaftTransport target = nodes.get(toNodeId);
        if (target == null) {
            return;
        }
        target.onMessage(message);
    }
}
