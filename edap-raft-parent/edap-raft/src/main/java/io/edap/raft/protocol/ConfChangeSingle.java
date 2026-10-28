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

package io.edap.raft.protocol;

/**
 * 单条成员变更操作，等价于 etcd 的 {@code raftpb.ConfChangeSingle}。
 */
public class ConfChangeSingle {

    private final ConfChangeType type;
    private final long          nodeId;

    public ConfChangeSingle(ConfChangeType type, long nodeId) {
        this.type = type;
        this.nodeId = nodeId;
    }

    public ConfChangeType getType() {
        return type;
    }

    public long getNodeId() {
        return nodeId;
    }

    /**
     * 与 etcd 一致的可读描述。
     *
     * <p>etcd 的 {@code pb.ConfChangeSingle} 的 {@code String()} 定义在指针接收者上，
     * 所以用 {@code %v} 打印值类型时走的是默认的结构体格式，
     * 得到 {@code {ConfChangeAddNode 3}}，这里保持一致。</p>
     */
    @Override
    public String toString() {
        return "{" + type + " " + nodeId + "}";
    }
}
