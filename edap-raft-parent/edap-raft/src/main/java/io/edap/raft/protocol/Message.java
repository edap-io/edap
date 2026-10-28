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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Raft 节点之间传递的消息，等价于 etcd 的 {@code raftpb.Message}。
 *
 * <p>字段语义与 etcd 完全一致，便于逐条对照上游实现：</p>
 * <ul>
 *     <li>{@code logTerm}：复制日志时用来做一致性检查的前置条目任期</li>
 *     <li>{@code index}：复制日志时的前置条目位置，或读确认号</li>
 *     <li>{@code reject} + {@code rejectHint}：复制失败时的回退提示</li>
 *     <li>{@code context}：读索引的上下文，跟随心跳原样回传</li>
 * </ul>
 *
 * <p>与 etcd 不同的是这里不用 protobuf，而是手写的扁平编码，
 * 布局见 {@link RaftCodec}。</p>
 */
public class Message {

    private MessageType type;
    /** 接收方节点 id，0 表示广播 */
    private long        to;
    /** 发送方节点 id */
    private long        from;
    /** 发送方的任期 */
    private long        term;
    /**
     * 复制日志时前置条目的任期。
     * (MsgApp, index=100, logTerm=5) 表示 leader 要从 index=101 开始追加，index=100 处的任期是 5。
     */
    private long        logTerm;
    /**
     * 复制日志时前置条目的位置；也用于承载读确认号。
     */
    private long        index;
    /** 待复制的日志条目 */
    private List<Entry> entries;
    /** leader 已经提交到的位置 */
    private long        commit;
    /** 投票给谁，0 表示没有投票 */
    private long        vote;
    /** 快照，仅 MsgSnap 使用 */
    private Snapshot    snapshot;
    /** 是否拒绝 */
    private boolean     reject;
    /** 拒绝时的回退提示 */
    private long        rejectHint;
    /** 读索引上下文 */
    private byte[]      context;
    /** 异步存储的响应描述 */
    private List<Message> responses;

    public MessageType getType() {
        return type;
    }

    public Message setType(MessageType type) {
        this.type = type;
        return this;
    }

    public long getTo() {
        return to;
    }

    public Message setTo(long to) {
        this.to = to;
        return this;
    }

    public long getFrom() {
        return from;
    }

    public Message setFrom(long from) {
        this.from = from;
        return this;
    }

    public long getTerm() {
        return term;
    }

    public Message setTerm(long term) {
        this.term = term;
        return this;
    }

    public long getLogTerm() {
        return logTerm;
    }

    public Message setLogTerm(long logTerm) {
        this.logTerm = logTerm;
        return this;
    }

    public long getIndex() {
        return index;
    }

    public Message setIndex(long index) {
        this.index = index;
        return this;
    }

    public List<Entry> getEntries() {
        return entries;
    }

    public Message setEntries(List<Entry> entries) {
        this.entries = entries;
        return this;
    }

    public long getCommit() {
        return commit;
    }

    public Message setCommit(long commit) {
        this.commit = commit;
        return this;
    }

    public long getVote() {
        return vote;
    }

    public Message setVote(long vote) {
        this.vote = vote;
        return this;
    }

    public Snapshot getSnapshot() {
        return snapshot;
    }

    public Message setSnapshot(Snapshot snapshot) {
        this.snapshot = snapshot;
        return this;
    }

    public boolean getReject() {
        return reject;
    }

    public Message setReject(boolean reject) {
        this.reject = reject;
        return this;
    }

    public long getRejectHint() {
        return rejectHint;
    }

    public Message setRejectHint(long rejectHint) {
        this.rejectHint = rejectHint;
        return this;
    }

    public byte[] getContext() {
        return context;
    }

    public Message setContext(byte[] context) {
        this.context = context;
        return this;
    }

    public List<Message> getResponses() {
        return responses;
    }

    public Message setResponses(List<Message> responses) {
        this.responses = responses;
        return this;
    }

    /**
     * 复制一份消息。leader 复用同一个 AppendEntries 模板发给多个 peer 时使用，
     * 修改副本不影响原消息。
     */
    public Message copy() {
        Message copy = new Message();
        copy.type = type;
        copy.to = to;
        copy.from = from;
        copy.term = term;
        copy.logTerm = logTerm;
        copy.index = index;
        copy.entries = entries == null ? null : new ArrayList<>(entries);
        copy.commit = commit;
        copy.vote = vote;
        copy.snapshot = snapshot;
        copy.reject = reject;
        copy.rejectHint = rejectHint;
        copy.context = context == null ? null : Arrays.copyOf(context, context.length);
        copy.responses = responses == null ? null : new ArrayList<>(responses);
        return copy;
    }

    @Override
    public String toString() {
        return "Message{" + type + ", from=" + from + ", to=" + to + ", term=" + term
                + ", index=" + index + ", logTerm=" + logTerm + ", commit=" + commit
                + ", entries=" + (entries == null ? 0 : entries.size())
                + ", reject=" + reject + ", rejectHint=" + rejectHint + "}";
    }

    /**
     * 按值比较，等价于 etcd 里 protobuf 消息的 {@code require.Equal} 语义。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Message)) {
            return false;
        }
        Message other = (Message) o;
        return to == other.to
                && from == other.from
                && term == other.term
                && logTerm == other.logTerm
                && index == other.index
                && commit == other.commit
                && vote == other.vote
                && reject == other.reject
                && rejectHint == other.rejectHint
                && type == other.type
                && Objects.equals(entries, other.entries)
                && Objects.equals(snapshot, other.snapshot)
                && Arrays.equals(context, other.context)
                && Objects.equals(responses, other.responses);
    }

    @Override
    public int hashCode() {
        int result = type == null ? 0 : type.hashCode();
        result = 31 * result + (int) (to * 31 + from);
        result = 31 * result + (int) (term * 31 + logTerm);
        result = 31 * result + (int) (index * 31 + commit);
        result = 31 * result + (int) vote;
        result = 31 * result + (reject ? 1 : 0);
        result = 31 * result + (int) rejectHint;
        result = 31 * result + (entries == null ? 0 : entries.hashCode());
        result = 31 * result + (snapshot == null ? 0 : snapshot.hashCode());
        result = 31 * result + Arrays.hashCode(context);
        result = 31 * result + (responses == null ? 0 : responses.hashCode());
        return result;
    }
}
