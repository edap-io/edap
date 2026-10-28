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

package io.edap.raft.tracker;

import io.edap.raft.RaftException;

/**
 * leader 眼中某个 follower 的复制进度，等价于 etcd 的 {@code tracker.Progress}。
 *
 * <p>leader 为每个 follower 维护一份 Progress，并根据它决定发多少日志、
 * 什么时候发快照、什么时候暂停发送。各字段的语义：</p>
 * <ul>
 *     <li>{@code match}：已知与 leader 日志一致的最大 index</li>
 *     <li>{@code next}：下一条要发给该 follower 的日志 index，
 *     区间 (match, next) 里的日志都已经在途</li>
 *     <li>{@code sentCommit}：已经发给该 follower 的最大 commit index，
 *     一般单调递增，但在切换到探测状态或收到拒绝时会回退</li>
 *     <li>{@code state}：当前复制状态，决定 leader 与 follower 的交互方式</li>
 *     <li>{@code pendingSnapshot}：处于快照状态时待发送快照的 index</li>
 *     <li>{@code recentActive}：最近是否收到过该节点的消息，用于判断节点是否掉线</li>
 *     <li>{@code msgAppFlowPaused}：发往该节点的追加消息流是否被暂停</li>
 *     <li>{@code inflights}：在途消息窗口</li>
 *     <li>{@code learner}：该节点是否是 learner（非投票成员）</li>
 * </ul>
 *
 * <p>约定：正常运行时 {@code 0 <= match < next}，因此 next 至少为 1。
 * etcd 的 {@code Progress} 只是一个纯数据结构，不做校验，这里也保持一致，
 * 只拒绝负数（Go 用 uint64，负数无法表达）。</p>
 */
public class Progress {

    /** 节点 id */
    private final long     id;
    /** 已知与 leader 一致的最大日志 index */
    private long           match;
    /** 下一条要发给该 follower 的日志 index */
    private long           next;
    /** 已经发给该节点的最大 commit index */
    private long           sentCommit;
    /** 当前复制状态 */
    private State          state = State.PROBE;
    /** 快照状态下待发送快照的 index */
    private long           pendingSnapshot;
    /** 最近是否收到过该节点的消息，用于判断节点是否掉线 */
    private boolean        recentActive;
    /** 发往该节点的追加消息流是否被暂停 */
    private boolean        msgAppFlowPaused;
    /** 在途消息窗口 */
    private Inflights      inflights;
    /** 该节点是否是 learner（非投票成员） */
    private boolean        learner;

    /**
     * 创建一个复制进度，在途窗口用默认大小。
     *
     * @param id    节点 id
     * @param match 已知一致的最大日志 index
     * @param next  下一条要发送的日志 index
     * @throws RaftException match 或 next 为负数时抛出
     */
    public Progress(long id, long match, long next) {
        this(id, match, next, Inflights.DEFAULT_SIZE);
    }

    /**
     * 创建一个复制进度，并指定在途窗口大小。
     *
     * @param id               节点 id
     * @param match            已知一致的最大日志 index
     * @param next             下一条要发送的日志 index
     * @param maxInflightSize  在途消息窗口大小
     * @throws RaftException match 或 next 为负数时抛出
     */
    public Progress(long id, long match, long next, int maxInflightSize) {
        this(id, match, next, new Inflights(maxInflightSize, 0));
    }

    /**
     * 创建一个复制进度，并指定在途窗口。
     *
     * @param id        节点 id
     * @param match     已知一致的最大日志 index
     * @param next      下一条要发送的日志 index
     * @param inflights 在途消息窗口，不能为 null
     * @throws RaftException match 或 next 为负数，或 inflights 为 null 时抛出
     */
    public Progress(long id, long match, long next, Inflights inflights) {
        if (match < 0 || next < 0) {
            throw new RaftException("invalid progress: id=" + id + ", match=" + match + ", next=" + next);
        }
        if (inflights == null) {
            throw new RaftException("inflights must not be null");
        }
        this.id = id;
        this.match = match;
        this.next = next;
        this.inflights = inflights;
    }

    /**
     * 该进度对应的节点 id。
     */
    public long getId() {
        return id;
    }

    /**
     * 已知与 leader 一致的最大日志 index。
     */
    public long getMatch() {
        return match;
    }

    /**
     * 设置已知一致的最大日志 index。
     */
    public void setMatch(long match) {
        this.match = match;
    }

    /**
     * 下一条要发给该 follower 的日志 index。
     */
    public long getNext() {
        return next;
    }

    /**
     * 设置下一条要发给该 follower 的日志 index。
     */
    public void setNext(long next) {
        this.next = next;
    }

    /**
     * 记录发给该节点的最大 commit index，等价于 Go 的 {@code SentCommit}。
     */
    public void sentCommit(long commit) {
        this.sentCommit = commit;
    }

    /**
     * 当前复制状态。
     */
    public State getState() {
        return state;
    }

    /**
     * 直接设置当前复制状态。
     *
     * <p>Go 里 {@code Progress.State} 是导出字段，测试可以直接赋值；
     * Java 侧用这个方法达到同样的效果。注意它不像 {@link #becomeProbe()}
     * 那样顺带重置 inflights 与待发快照。</p>
     */
    public void setState(State state) {
        this.state = state;
    }

    /**
     * 快照状态下待发送快照的 index，其它状态下为 0。
     */
    public long getPendingSnapshot() {
        return pendingSnapshot;
    }

    /**
     * 设置快照状态下待发送快照的 index。
     */
    public void setPendingSnapshot(long pendingSnapshot) {
        this.pendingSnapshot = pendingSnapshot;
    }

    /**
     * 最近是否收到过该节点的消息。
     */
    public boolean isRecentActive() {
        return recentActive;
    }

    /**
     * 设置最近是否收到过该节点的消息（选举超时后会被清掉）。
     */
    public void setRecentActive(boolean recentActive) {
        this.recentActive = recentActive;
    }

    /**
     * 发往该节点的追加消息流是否被暂停。
     */
    public boolean isMsgAppFlowPaused() {
        return msgAppFlowPaused;
    }

    /**
     * 设置发往该节点的追加消息流是否被暂停。
     */
    public void setMsgAppFlowPaused(boolean msgAppFlowPaused) {
        this.msgAppFlowPaused = msgAppFlowPaused;
    }

    /**
     * 该节点的在途消息窗口。
     */
    public Inflights getInflights() {
        return inflights;
    }

    /**
     * 该节点是否是 learner（不参与投票，只接收日志）。
     */
    public boolean isLearner() {
        return learner;
    }

    /**
     * 设置该节点是否是 learner。
     */
    public void setLearner(boolean learner) {
        this.learner = learner;
    }

    /**
     * 切换到指定状态，同时复位流控标记、待发送快照和在途窗口。
     *
     * <p>等价于 Go 的 {@code ResetState}：状态切换意味着之前的发送上下文作废。</p>
     */
    public void resetState(State state) {
        this.msgAppFlowPaused = false;
        this.pendingSnapshot = 0;
        this.state = state;
        this.inflights.reset();
    }

    /**
     * 切换到探测状态，next 重置为 match+1。
     *
     * <p>若原状态是快照状态，说明快照已经成功发出，
     * 于是从 pendingSnapshot+1 开始继续探测。</p>
     */
    public void becomeProbe() {
        if (state == State.SNAPSHOT) {
            long pendingSnapshot = this.pendingSnapshot;
            resetState(State.PROBE);
            next = Math.max(match + 1, pendingSnapshot + 1);
        } else {
            resetState(State.PROBE);
            next = match + 1;
        }
        sentCommit = Math.min(sentCommit, next - 1);
    }

    /**
     * 切换到复制状态，next 重置为 match+1。
     */
    public void becomeReplicate() {
        resetState(State.REPLICATE);
        next = match + 1;
    }

    /**
     * 切换到快照状态，next 重置为 snapshot+1。
     */
    public void becomeSnapshot(long snapshot) {
        resetState(State.SNAPSHOT);
        pendingSnapshot = snapshot;
        next = snapshot + 1;
        sentCommit = snapshot;
    }

    /**
     * 记录刚刚发出了一条追加消息，等价于 Go 的 {@code SentEntries}。
     *
     * <p>复制状态下把 next 推进 entries 条，并把最后一条日志的 index 登记为在途；
     * 探测状态下只要带了日志就认为这条消息是探测，流控被暂停；
     * 快照状态下不允许再发追加消息。</p>
     *
     * @param entries 这条消息携带的日志条数
     * @param bytes   这条消息携带的日志总字节数
     * @throws RaftException 在快照状态下调用时抛出
     */
    public void sentEntries(int entries, long bytes) {
        switch (state) {
            case REPLICATE:
                if (entries > 0) {
                    next += entries;
                    inflights.add(next - 1, bytes);
                }
                // 窗口满了说明对端消费不动了，暂停发送
                msgAppFlowPaused = inflights.full();
                break;
            case PROBE:
                if (entries > 0) {
                    msgAppFlowPaused = true;
                }
                break;
            default:
                throw new RaftException("sending append in unhandled state " + state);
        }
    }

    /**
     * 发送给定的 commit index 是否有可能推进该 follower 的 commit index，
     * 等价于 Go 的 {@code CanBumpCommit}。
     *
     * <p>正常情况下发送 commit 最多能把 follower 的 commit 推到 next-1，
     * 少数情况下可以更高。只有在还没把 commit 推满到 next-1 时，
     * 才值得提前把更大的 commit 发过去。</p>
     *
     * @param index 打算发送的 commit index
     */
    public boolean canBumpCommit(long index) {
        return index > sentCommit && sentCommit < next - 1;
    }

    /**
     * 收到 follower 的确认消息后更新进度，等价于 Go 的 {@code MaybeUpdate}。
     *
     * @param n follower 确认已收到的最大 index
     * @return true 表示这是一条有效的新确认并已更新进度；false 表示消息过时
     */
    public boolean maybeUpdate(long n) {
        if (n <= match) {
            return false;
        }
        match = n;
        // 不变量：match < next
        next = Math.max(next, n + 1);
        msgAppFlowPaused = false;
        return true;
    }

    /**
     * 收到 follower 的拒绝消息后回退进度，等价于 Go 的 {@code MaybeDecrTo}。
     *
     * <p>rejected 是被拒绝的那条追加消息的 index，matchHint 是希望回退到的位置。
     * 由于消息可能乱序或重复，拒绝有时是假的：这时返回 false 且不改动任何状态。</p>
     *
     * @param rejected  被拒绝的那条追加消息的 index
     * @param matchHint 希望回退到的 match 位置提示
     * @return true 表示进度已回退；false 表示这是一条过时或无关的拒绝
     */
    public boolean maybeDecrTo(long rejected, long matchHint) {
        if (state == State.REPLICATE) {
            // 已经匹配上的 index 再被拒绝，一定是过时消息
            if (rejected <= match) {
                return false;
            }
            // 直接退回到 match+1
            next = match + 1;
            // 之前发出去的 commit 大概率还没被应用，一起退回来
            sentCommit = Math.min(sentCommit, next - 1);
            return true;
        }

        // 非复制状态下每次只探测一条日志，只有 next-1 被拒绝才需要回退
        if (next - 1 != rejected) {
            return false;
        }
        next = Math.max(Math.min(rejected, matchHint + 1), match + 1);
        sentCommit = Math.min(sentCommit, next - 1);
        msgAppFlowPaused = false;
        return true;
    }

    /**
     * 当前是否暂停向该节点发送日志。
     *
     * <p>节点最近拒绝过追加、正在等快照，或者在途窗口已满时都会返回 true，
     * 此时 leader 应降低发送频率，等它恢复后再恢复稳定推送。</p>
     */
    public boolean isPaused() {
        switch (state) {
            case PROBE:
            case REPLICATE:
                return msgAppFlowPaused;
            case SNAPSHOT:
                return true;
            default:
                throw new RaftException("unexpected state " + state);
        }
    }

    /**
     * 两个进度是否相同。
     *
     * <p>在途消息窗口 {@link #getInflights()} 不参与比较：
     * 它是一个滑动窗口，同一个状态下两边的内容也可能不同。</p>
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Progress)) {
            return false;
        }
        Progress other = (Progress) o;
        return id == other.id
                && match == other.match
                && next == other.next
                && sentCommit == other.sentCommit
                && state == other.state
                && pendingSnapshot == other.pendingSnapshot
                && recentActive == other.recentActive
                && msgAppFlowPaused == other.msgAppFlowPaused
                && learner == other.learner;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(id, match, next, sentCommit, state,
                pendingSnapshot, recentActive, msgAppFlowPaused, learner);
    }

    /**
     * 与 etcd 一致的可读描述，便于日志排查。
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append(state).append(" match=").append(match).append(" next=").append(next);
        if (learner) {
            buf.append(" learner");
        }
        if (isPaused()) {
            buf.append(" paused");
        }
        if (pendingSnapshot > 0) {
            buf.append(" pendingSnap=").append(pendingSnapshot);
        }
        if (!recentActive) {
            buf.append(" inactive");
        }
        int n = inflights.count();
        if (n > 0) {
            buf.append(" inflight=").append(n);
            if (inflights.full()) {
                buf.append("[full]");
            }
        }
        return buf.toString();
    }
}
