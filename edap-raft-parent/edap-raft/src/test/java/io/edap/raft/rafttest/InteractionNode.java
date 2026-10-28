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

package io.edap.raft.rafttest;

import io.edap.raft.RaftConfig;
import io.edap.raft.RawNode;
import io.edap.raft.protocol.Message;
import io.edap.raft.protocol.Snapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * 交互测试里的一个节点，等价于 etcd rafttest 的 {@code rafttest.Node}。
 *
 * <p>除了 {@link RawNode} 之外，还带着三样东西：</p>
 * <ul>
 *     <li>{@code appendWork} / {@code applyWork}：异步落盘模式下要交给
 *         本地追加线程、本地应用线程的消息。这里不支持异步落盘，这两个列表
 *         永远是空的。</li>
 *     <li>{@code history}：快照历史。每应用一条日志就追加一条，
 *         存储的 {@code snapshot()} 返回它的最后一条。</li>
 * </ul>
 */
public class InteractionNode {

    /** raft 驱动层 */
    private final RawNode rawNode;

    /** 节点自己的存储 */
    private final InteractionStorage storage;

    /** 本节点的配置 */
    private final RaftConfig config;

    /** 要交给本地追加线程的消息（Java 侧不支持异步落盘，恒为空） */
    private final List<Message> appendWork = new ArrayList<>();

    /** 要交给本地应用线程的消息（Java 侧不支持异步落盘，恒为空） */
    private final List<Message> applyWork = new ArrayList<>();

    /** 快照历史 */
    private final List<Snapshot> history = new ArrayList<>();

    public InteractionNode(RawNode rawNode, InteractionStorage storage, RaftConfig config) {
        this.rawNode = rawNode;
        this.storage = storage;
        this.config = config;
    }

    public RawNode getRawNode() {
        return rawNode;
    }

    public InteractionStorage getStorage() {
        return storage;
    }

    public RaftConfig getConfig() {
        return config;
    }

    public List<Message> getAppendWork() {
        return appendWork;
    }

    public List<Message> getApplyWork() {
        return applyWork;
    }

    public List<Snapshot> getHistory() {
        return history;
    }
}
