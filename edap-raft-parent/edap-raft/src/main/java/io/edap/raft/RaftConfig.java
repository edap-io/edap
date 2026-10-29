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

import io.edap.raft.storage.PersistentStorage;

/**
 * 启动一个 raft 节点的参数，等价于 etcd 的 {@code raft.Config}。
 *
 * <p>etcd 里用「tick 数」而不是时间间隔来表达超时（ElectionTick / HeartbeatTick），
 * 由上层按固定节奏调用 {@code Tick}。这里保持同样的模型：一次 tick 是一个抽象时间单位，
 * 具体多长时间由驱动层决定。</p>
 */
public class RaftConfig {

    /** 不限制 */
    public static final long NO_LIMIT = Long.MAX_VALUE;

    /**
     * 本节点的 id，不能为 0。
     */
    public long id;

    /**
     * 两次选举之间的 tick 数，必须大于 {@link #heartbeatTick}。
     *
     * <p>follower 在这段时间内没有收到 leader 的消息就会发起选举。
     * 建议取 {@code 10 * heartbeatTick}，避免 leader 频繁切换。</p>
     */
    public int electionTick = 10;

    /**
     * 两次心跳之间的 tick 数，必须大于 0。
     */
    public int heartbeatTick = 1;

    /**
     * 日志存储，不能为 null。
     */
    public PersistentStorage storage;

    /**
     * 是否由框架（{@link Node}）接管落盘。
     *
     * <p>false（默认）时与 etcd raft 的模型一致：应用层从 Ready 里取出
     * 硬状态与日志条目，自己写进 {@link #storage}，写完再调 {@code advance()}。</p>
     *
     * <p>true 时框架在交付 Ready 之前先把硬状态与整批日志一次性落盘
     * （只做一次 fsync），Ready 里不再带日志条目，应用层不要重复落盘。</p>
     */
    public boolean frameworkPersist;

    /**
     * 已提交条目的处理器，非 null 时启用独立 apply 线程。
     *
     * <p>非 null 时框架把已提交条目交给这个处理器，Ready 里不再带
     * committedEntries。处理器在独立线程上被调用，实现要自己保证线程安全。</p>
     */
    public ApplyHandler applyHandler;

    /**
     * 重启时最后已应用的位置，只能在重启时设置。
     *
     * <p>设置之后 raft 不会再把不大于它的条目交给应用层。不设置的话
     * 重启后可能重复应用已经应用过的条目。</p>
     */
    public long applied;

    /**
     * 单条追加消息的字节上限，{@link #NO_LIMIT} 表示不限制。
     *
     * <p>值越小，节点恢复时的探测成本越低（初始探测 + 正常运行时丢包），
     * 但会影响正常复制时的吞吐。</p>
     */
    public long maxSizePerMsg = NO_LIMIT;

    /**
     * 单次 Ready 里允许应用（apply）的已提交条目总字节上限。
     */
    public long maxCommittedSizePerReady = NO_LIMIT;

    /**
     * leader 未提交日志尾部的总字节上限，0 表示不限制。
     *
     * <p>超过上限后新的提案会被丢弃，防止日志无限增长。</p>
     */
    public long maxUncommittedEntriesSize = NO_LIMIT;

    /**
     * 提案队列的容量上限，0 表示不限制。
     *
     * <p>默认 2048，与 dragonboat 的 {@code IncomingProposalQueueLength} 一致。</p>
     *
     * <p>注意这个上限只对<b>异步</b>提案路径有实际约束力：同步提案
     * {@link Node#propose(byte[])} 阻塞到状态机 step 完，每个调用方最多只有
     * 一条提案排在队列里，队列长度约等于并发调用方数。</p>
     */
    public int maxPendingProposals = 2048;

    /**
     * 提案队列满时的行为，默认 {@link ProposalFullPolicy#BLOCK}。
     *
     * <p>只影响同步提案路径。异步提案路径永远立刻拒绝
     * （{@link RaftBusyException}），传输层回调路径永远丢弃。</p>
     */
    public ProposalFullPolicy proposalFullPolicy = ProposalFullPolicy.BLOCK;

    /**
     * 乐观复制阶段在途追加消息的条数上限，必须大于 0。
     */
    public int maxInflightMsgs = 256;

    /**
     * 在途追加消息的总字节上限，0 表示不限制。
     */
    public long maxInflightBytes = 0;

    /**
     * leader 是否检查多数派是否活跃。
     *
     * <p>开启后，如果在一个选举超时内没有多数派活跃，leader 主动退位。</p>
     */
    public boolean checkQuorum;

    /**
     * 是否启用预投票（论文 §9.6）。
     *
     * <p>避免被网络隔离的节点重新加入集群时打断现任 leader。</p>
     */
    public boolean preVote;

    /**
     * 只读请求的处理方式，默认 {@link ReadOnlyOption#READ_ONLY_SAFE}。
     */
    public ReadOnlyOption readOnlyOption = ReadOnlyOption.READ_ONLY_SAFE;

    /**
     * follower 是否转发提案给 leader。
     *
     * <p>置为 true 表示 follower 直接丢弃提案。适用于提案内容依赖本节点时钟
     * （比如混合逻辑时钟）而不能由 leader 统一生成的场景。</p>
     */
    public boolean disableProposalForwarding;

    /**
     * 是否关闭提案时的成员变更合法性校验。
     *
     * <p>校验本身是合理的，但它比对的是「当前生效配置」，而配置是在日志应用阶段
     * 才生效的，因此存在误报。只有在上层有可靠的串行化与校验机制时才应该关掉它。</p>
     */
    public boolean disableConfChangeValidation;

    /**
     * leader 被移出集群或降级为 learner 时是否主动退位。
     */
    public boolean stepDownOnRemoval;

    /**
     * 日志输出器，null 表示用默认的 {@link DefaultRaftLogger}。
     *
     * <p>与 etcd 的 {@code raft.Config.Logger} 对应：测试可以注入自己的实现，
     * 把日志收集起来做逐字比对。</p>
     */
    public RaftLogger logger;

    /**
     * 校验参数合法性，非法时抛出 {@link RaftException}。
     */
    public void validate() {
        if (id == Raft.NONE) {
            throw new RaftException("cannot use none as id");
        }
        if (heartbeatTick <= 0) {
            throw new RaftException("heartbeat tick must be greater than 0");
        }
        if (electionTick <= heartbeatTick) {
            throw new RaftException("election tick must be greater than heartbeat tick");
        }
        if (storage == null) {
            throw new RaftException("storage cannot be nil");
        }
        if (maxUncommittedEntriesSize == 0) {
            maxUncommittedEntriesSize = NO_LIMIT;
        }
        if (maxCommittedSizePerReady == 0) {
            maxCommittedSizePerReady = maxSizePerMsg;
        }
        if (maxInflightMsgs <= 0) {
            throw new RaftException("max inflight messages must be greater than 0");
        }
        if (maxInflightBytes == 0) {
            maxInflightBytes = NO_LIMIT;
        } else if (maxInflightBytes < maxSizePerMsg) {
            throw new RaftException("max inflight bytes must be >= max message size");
        }
        if (maxPendingProposals < 0) {
            throw new RaftException("max pending proposals must be >= 0");
        }
        if (readOnlyOption == ReadOnlyOption.READ_ONLY_LEASE_BASED && !checkQuorum) {
            throw new RaftException("CheckQuorum must be enabled when ReadOnlyOption is ReadOnlyLeaseBased");
        }
    }

    @Override
    public String toString() {
        return "RaftConfig{id=" + id + ", electionTick=" + electionTick
                + ", heartbeatTick=" + heartbeatTick + ", preVote=" + preVote
                + ", checkQuorum=" + checkQuorum + ", readOnlyOption=" + readOnlyOption + "}";
    }
}
