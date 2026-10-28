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

import io.edap.raft.GoFormat;

import java.util.Collections;

/**
 * 单条成员变更（v1），等价于 etcd 的 {@code raftpb.ConfChange}。
 *
 * <p>v1 只支持一次一条操作，且总是走联合共识。</p>
 */
public class ConfChange implements ConfChangeI {

    private final ConfChangeType type;
    private final long          nodeId;
    private final byte[]        context;
    /** etcd 用来串联唯一标识的字段，raft 本身不用 */
    private final long          id;

    public ConfChange(ConfChangeType type, long nodeId, byte[] context) {
        this(type, nodeId, context, 0);
    }

    public ConfChange(ConfChangeType type, long nodeId, byte[] context, long id) {
        this.type = type;
        this.nodeId = nodeId;
        this.context = context == null ? new byte[0] : context;
        this.id = id;
    }

    public ConfChangeType getType() {
        return type;
    }

    public long getNodeId() {
        return nodeId;
    }

    public byte[] getContext() {
        return context;
    }

    public long getId() {
        return id;
    }

    /**
     * 转成 v2 的等价形式
     */
    @Override
    public ConfChangeV2 asV2() {
        ConfChangeSingle single = new ConfChangeSingle(type, nodeId);
        return new ConfChangeV2(ConfChangeTransition.AUTO, Collections.singletonList(single), context);
    }

    /**
     * v1 自身
     */
    @Override
    public ConfChange asV1() {
        return this;
    }

    /**
     * 与 etcd 一致的可读描述。
     *
     * <p>etcd 的 {@code pb.ConfChange} 的 {@code String()} 定义在指针接收者上，
     * 所以用 {@code %v} 打印值类型时走的是默认的结构体格式，
     * 字段顺序是 type、node_id、context、id，
     * 得到 {@code {ConfChangeAddNode 3 [] 0}}，这里保持一致。</p>
     */
    @Override
    public String toString() {
        return "{" + type + " " + nodeId + " " + GoFormat.goBytes(context) + " " + id + "}";
    }
}
