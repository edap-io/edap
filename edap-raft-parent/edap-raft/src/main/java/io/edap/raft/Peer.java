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

package io.edap.raft;

import java.util.Objects;

/**
 * 集群中的一个节点标识。
 *
 * <p>nodeId 是集群内唯一的逻辑标识，在节点重启、换 IP 之后保持不变；
 * host/port 是该节点 raft 传输层的监听地址。</p>
 */
public class Peer {

    private final long   nodeId;
    private final String host;
    private final int    port;
    private final byte[] context;

    public Peer(long nodeId, String host, int port) {
        this(nodeId, host, port, new byte[0]);
    }

    /**
     * 带上下文地创建一个节点标识。
     *
     * <p>上下文会写进引导时生成的成员变更条目里，集群成员可以据此
     * 知道这个节点是怎么加入的。</p>
     */
    public Peer(long nodeId, String host, int port, byte[] context) {
        this.nodeId = nodeId;
        this.host = host;
        this.port = port;
        this.context = context == null ? new byte[0] : context;
    }

    public long getNodeId() {
        return nodeId;
    }

    /**
     * 加入集群时携带的上下文，没有时是空数组
     */
    public byte[] getContext() {
        return context;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    /**
     * 节点对外可访问的地址，形如 host:port
     */
    public String getAddress() {
        return host + ":" + port;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Peer)) {
            return false;
        }
        return nodeId == ((Peer) o).nodeId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId);
    }

    @Override
    public String toString() {
        return "Peer{" + nodeId + "@" + getAddress() + "}";
    }
}
