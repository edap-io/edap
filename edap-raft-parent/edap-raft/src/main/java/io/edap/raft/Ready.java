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

import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.HardState;
import io.edap.raft.protocol.Message;
import io.edap.raft.protocol.Snapshot;

import java.util.Collections;
import java.util.List;

/**
 * 应用层需要处理的一批工作，等价于 etcd 的 {@code raft.Ready}。
 *
 * <p>字段都是只读的，处理顺序有严格要求：</p>
 * <ol>
 *     <li>把 {@link #getHardState()}、{@link #getEntries()}、{@link #getSnapshot()}
 *         落到稳定存储；</li>
 *     <li>再把 {@link #getMessages()} 发出去；</li>
 *     <li>然后把 {@link #getCommittedEntries()} 应用到状态机；</li>
 *     <li>最后调用 {@link RawNode#advance(Ready)} 告诉 raft 可以继续。</li>
 * </ol>
 *
 * <p>其中 {@link #getMessages()} 必须在日志落盘之后才能发出去，
 * 否则 follower 可能先看到新 leader 的消息、后看到日志，破坏一致性。</p>
 */
public class Ready {

    /** 不需要持久化的状态，没有变化时为 null */
    private SoftState       softState;

    /** 需要持久化的状态，没有变化时为 null */
    private HardState       hardState;

    /** 已确认的只读请求状态 */
    private List<ReadState> readStates = Collections.emptyList();

    /** 要落盘的日志条目 */
    private List<Entry>     entries = Collections.emptyList();

    /** 要落盘的快照，没有则为 null */
    private Snapshot        snapshot;

    /** 要应用到状态机的已提交条目 */
    private List<Entry>     committedEntries = Collections.emptyList();

    /** 要发出去的消息 */
    private List<Message>   messages = Collections.emptyList();

    /** 是否必须同步落盘 */
    private boolean         mustSync;

    /**
     * 不需要持久化的状态，没有变化时为 null
     */
    public SoftState getSoftState() {
        return softState;
    }

    public void setSoftState(SoftState softState) {
        this.softState = softState;
    }

    /**
     * 需要持久化的状态，没有变化时为 null
     */
    public HardState getHardState() {
        return hardState;
    }

    public void setHardState(HardState hardState) {
        this.hardState = hardState;
    }

    /**
     * 已确认的只读请求状态
     */
    public List<ReadState> getReadStates() {
        return readStates;
    }

    public void setReadStates(List<ReadState> readStates) {
        this.readStates = readStates == null ? Collections.emptyList() : readStates;
    }

    /**
     * 要落盘的日志条目
     */
    public List<Entry> getEntries() {
        return entries;
    }

    public void setEntries(List<Entry> entries) {
        this.entries = entries == null ? Collections.emptyList() : entries;
    }

    /**
     * 要落盘的快照，没有则为 null
     */
    public Snapshot getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(Snapshot snapshot) {
        this.snapshot = snapshot;
    }

    /**
     * 要应用到状态机的已提交条目
     */
    public List<Entry> getCommittedEntries() {
        return committedEntries;
    }

    public void setCommittedEntries(List<Entry> committedEntries) {
        this.committedEntries = committedEntries == null ? Collections.emptyList() : committedEntries;
    }

    /**
     * 要发出去的消息
     */
    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages == null ? Collections.emptyList() : messages;
    }

    /**
     * 是否必须同步落盘
     */
    public boolean isMustSync() {
        return mustSync;
    }

    public void setMustSync(boolean mustSync) {
        this.mustSync = mustSync;
    }

    @Override
    public String toString() {
        return "Ready{softState=" + softState + ", hardState=" + hardState
                + ", readStates=" + readStates.size()
                + ", entries=" + entries.size()
                + ", snapshot=" + (snapshot == null ? "null" : snapshot.getLastIncludedIndex())
                + ", committedEntries=" + committedEntries.size()
                + ", messages=" + messages.size()
                + ", mustSync=" + mustSync + "}";
    }
}
