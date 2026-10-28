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

import io.edap.raft.confchange.Changer;
import io.edap.raft.confchange.Restore;
import io.edap.raft.log.EntryId;
import io.edap.raft.log.LogSlice;
import io.edap.raft.log.RaftLog;
import io.edap.raft.protocol.ConfChangeCodec;
import io.edap.raft.protocol.ConfChangeI;
import io.edap.raft.protocol.ConfChangeSingle;
import io.edap.raft.protocol.ConfChangeV2;
import io.edap.raft.protocol.ConfState;
import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.EntryType;
import io.edap.raft.protocol.HardState;
import io.edap.raft.protocol.Message;
import io.edap.raft.protocol.MessageType;
import io.edap.raft.protocol.RaftCodec;
import io.edap.raft.protocol.Snapshot;
import io.edap.raft.quorum.Index;
import io.edap.raft.quorum.JointConfig;
import io.edap.raft.quorum.MajorityConfig;
import io.edap.raft.quorum.VoteResult;
import io.edap.raft.tracker.Config;
import io.edap.raft.tracker.Inflights;
import io.edap.raft.tracker.Progress;
import io.edap.raft.tracker.ProgressMap;
import io.edap.raft.tracker.ProgressTracker;
import io.edap.raft.tracker.State;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Raft 核心状态机，等价于 etcd 的 {@code raft} 结构体。
 *
 * <p>它是<b>单写者</b>的纯状态机：不碰存储、不发网络包、不起线程。
 * 所有外部交互都通过三个口子：</p>
 * <ul>
 *     <li>{@link #step(Message)}：喂进一条消息，推进状态机；</li>
 *     <li>{@link #tick()}：时间前进一个单位，可能触发选举或心跳；</li>
 *     <li>{@link #getMessages()} / {@link #getMessagesAfterAppend()}：
 *         取走本轮要发出的网络消息。</li>
 * </ul>
 *
 * <p>状态机本身不负责把日志落盘，产出的 {@link #getUnstableEntries()} 等由驱动层
 * （{@code RawNode}）在处理 Ready 时完成，落盘完成后调用 {@link #appliedTo(long)}
 * 与 {@link #stableTo(long, long)} 推进指针。</p>
 *
 * <p>移植自 etcd raft v3.6 系的 {@code raft.go}，方法名与语义尽量与上游一致，
 * 便于逐条对照。</p>
 */
public class Raft {

    /** 没有 leader / 没有投票时的占位节点 id */
    public static final long NONE = 0;

    /** 本节点 id */
    private long id;

    /** 当前任期 */
    private long term;

    /** 本任期投给了谁，0 表示还没投 */
    private long vote;

    /** 已经确认的只读请求状态 */
    private final List<ReadState> readStates = new ArrayList<>();

    /** 日志视图 */
    private final RaftLog raftLog;

    /** 日志输出器 */
    private final RaftLogger log;

    /** 单条追加消息的字节上限 */
    private final long maxMsgSize;

    /** 未提交日志尾部的字节上限 */
    private final long maxUncommittedSize;

    /** 成员配置与复制进度 */
    private final ProgressTracker trk;

    /** 当前角色 */
    private RaftRole state;

    /** 要立刻发出去的消息 */
    private final List<Message> msgs = new ArrayList<>();

    /**
     * 要等未落盘状态持久化之后再发出去的消息。
     *
     * <p>只有三类消息需要等：{@code MsgAppResp} / {@code MsgVoteResp} /
     * {@code MsgPreVoteResp}。按 Raft 论文 §3.8，投票与日志追加的确认必须
     * 先持久化再回复，否则重启后可能出现「投过两次票」或「确认了没落盘的日志」。</p>
     */
    private final List<Message> msgsAfterAppend = new ArrayList<>();

    /** 当前 leader 的 id */
    private long lead;

    /** 领导权转移的目标节点 id，0 表示没有在转移 */
    private long leadTransferee;

    /**
     * 尚未应用的成员变更所在的日志位置。
     *
     * <p>同一时刻只允许有一个成员变更处于「已写入日志但尚未应用」的状态，
     * 因此提案时要检查这个值。</p>
     */
    private long pendingConfIndex;

    /** 是否关闭提案时的成员变更合法性校验 */
    private final boolean disableConfChangeValidation;

    /** 未提交日志尾部的大小估计，只有 leader 维护 */
    private long uncommittedSize;

    /** 只读请求跟踪器 */
    private ReadOnly readOnly;

    /**
     * 距离上次选举超时过去了多少个 tick。
     *
     * <p>etcd 里这个字段是包内可见的，测试可以直接赋值；Java 侧保持包内可见，
     * 只对同包的测试脚手架开放。</p>
     */
    int electionElapsed;

    /** 距离上次心跳超时过去了多少个 tick */
    private int heartbeatElapsed;

    /**
     * leader 是否检查多数派是否活跃。
     *
     * <p>etcd 里这个字段是包内可见的普通可变字段，测试可以在运行途中切换；
     * Java 侧保持同样的入口，只对同包的测试使用。</p>
     */
    private boolean checkQuorum;

    /**
     * 是否启用预投票。
     *
     * <p>etcd 里这个字段是包内可见的，测试可以在运行途中切换；
     * Java 侧保持同样的入口，只对同包的测试使用。</p>
     */
    private boolean preVote;

    /** 心跳间隔（tick 数） */
    private final int heartbeatTimeout;

    /** 选举超时（tick 数） */
    private final int electionTimeout;

    /** 带随机化的选举超时，取值范围 [electionTimeout, 2 * electionTimeout - 1] */
    private int randomizedElectionTimeout;

    /** follower 是否转发提案 */
    private final boolean disableProposalForwarding;

    /** leader 被移出集群或降级时是否退位 */
    private final boolean stepDownOnRemoval;

    /**
     * 因为 leader 还没在本任期内提交过日志而暂时无法回答的读请求。
     *
     * <p>本任期第一条日志提交后立刻处理掉。</p>
     */
    private final List<Message> pendingReadIndexMessages = new ArrayList<>();

    /**
     * 用给定的配置创建一个 raft 状态机。
     *
     * @param cfg 配置，非法时抛出 {@link RaftException}
     */
    public Raft(RaftConfig cfg) {
        if (cfg == null) {
            throw new RaftException("raft config must not be null");
        }
        cfg.validate();

        this.id = cfg.id;
        this.log = cfg.logger == null ? new DefaultRaftLogger() : cfg.logger;
        this.raftLog = new RaftLog(cfg.storage, this.log);
        this.raftLog.setMaxApplyingEntsSize(cfg.maxCommittedSizePerReady);
        this.maxMsgSize = cfg.maxSizePerMsg;
        this.maxUncommittedSize = cfg.maxUncommittedEntriesSize;
        this.trk = ProgressTracker.makeProgressTracker(cfg.maxInflightMsgs, cfg.maxInflightBytes);
        this.electionTimeout = cfg.electionTick;
        this.heartbeatTimeout = cfg.heartbeatTick;
        this.checkQuorum = cfg.checkQuorum;
        this.preVote = cfg.preVote;
        this.readOnly = new ReadOnly(cfg.readOnlyOption);
        this.disableProposalForwarding = cfg.disableProposalForwarding;
        this.disableConfChangeValidation = cfg.disableConfChangeValidation;
        this.stepDownOnRemoval = cfg.stepDownOnRemoval;
        this.lead = NONE;
        this.state = RaftRole.FOLLOWER;

        // 从存储里读出上次的硬状态与成员配置
        HardState hs = cfg.storage.initialState();
        Snapshot snap = cfg.storage.snapshot();
        ConfState cs = snap == null ? null : snap.getConfState();

        // 用存储里的成员配置重建跟踪器
        Config restored = Restore.restore(new Changer(id, new Config()), cs == null ? emptyConfState() : cs);
        switchToConfig(restored);

        if (hs != null) {
            loadState(hs);
        }
        if (cfg.applied > 0) {
            raftLog.appliedTo(cfg.applied, 0);
        }
        becomeFollower(term, NONE);

        log.info("newRaft %x [peers: [%s], term: %d, commit: %d, applied: %d, lastindex: %d, lastterm: %d]",
                id, goPeers(trk.voterNodes()), term, raftLog.committed(), raftLog.applied(),
                raftLog.lastIndex(), raftLog.lastTerm());
    }

    /**
     * 把一组节点 id 渲染成 etcd 打印初始成员列表的形式。
     *
     * <p>etcd 里每个 id 先按十六进制格式化，再用逗号连接，得到 {@code 1,2,3}。</p>
     */
    static String goPeers(List<Long> ids) {
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                buf.append(',');
            }
            buf.append(Long.toHexString(ids.get(i)));
        }
        return buf.toString();
    }

    // ------------------------------------------------------------------
    // 只读访问器
    // ------------------------------------------------------------------

    /**
     * 本节点 id
     */
    public long getId() {
        return id;
    }

    /**
     * 本节点使用的日志输出器。
     *
     * <p>{@link Node} 也需要用它输出 leader 变化之类的日志，
     * 这样应用层只要在 {@link RaftConfig#logger} 里装一个收集器，
     * 就能收齐 raft 的全部日志。</p>
     */
    public RaftLogger getLogger() {
        return log;
    }

    /**
     * 当前任期
     */
    public long getTerm() {
        return term;
    }

    /**
     * 选举超时（tick 数）。
     */
    public int getElectionTimeout() {
        return electionTimeout;
    }

    /**
     * 心跳间隔（tick 数）。
     */
    public int getHeartbeatTimeout() {
        return heartbeatTimeout;
    }

    /**
     * 测试专用：直接设置当前任期。
     *
     * <p>Go 里任期是包内可见字段，测试可以直接赋值；Java 里它是私有的，
     * 这里开一个包内可见的入口，只对同包的测试使用。</p>
     */
    void setTermForTest(long newTerm) {
        this.term = newTerm;
    }

    /**
     * 测试专用：重新设置本节点 id。
     *
     * <p>Go 里节点 id 是包内可见字段，{@code newNetworkWithConfig} 会把已有
     * 状态机的 id 改成它在网络里的位置；Java 里 id 原本是 final 的，
     * 这里放开一个包内可见的入口，只对同包的测试使用。</p>
     */
    void setIdForTest(long newId) {
        this.id = newId;
    }

    /**
     * 测试专用：直接设置当前角色。
     *
     * <p>Go 里状态是包内可见字段，测试可以跳过状态机转换直接赋值；
     * Java 侧保持同样的入口，只对同包的测试使用。</p>
     */
    void setStateForTest(RaftRole newState) {
        this.state = newState;
    }

    /**
     * 测试专用：直接设置本任期投给了谁。
     *
     * <p>Go 里投票是包内可见字段，测试可以只改投票不改任期；
     * Java 侧保持同样的入口，只对同包的测试使用。</p>
     */
    void setVoteForTest(long newVote) {
        this.vote = newVote;
    }

    /**
     * 测试专用：切换是否启用预投票。
     *
     * <p>Go 里预投票开关是包内可见字段，测试可以在运行途中切换；
     * Java 侧保持同样的入口，只对同包的测试使用。</p>
     */
    void setPreVoteForTest(boolean newPreVote) {
        this.preVote = newPreVote;
    }

    /**
     * 测试专用：切换是否检查多数派是否活跃。
     *
     * <p>Go 里 {@code checkQuorum} 是包内可见字段，测试可以在运行途中切换；
     * Java 侧保持同样的入口，只对同包的测试使用。</p>
     */
    void setCheckQuorumForTest(boolean newCheckQuorum) {
        this.checkQuorum = newCheckQuorum;
    }

    /**
     * 本任期投给了谁
     */
    public long getVote() {
        return vote;
    }

    /**
     * 当前角色
     */
    public RaftRole getState() {
        return state;
    }

    /**
     * 当前 leader 的 id，没有 leader 时是 0
     */
    public long getLead() {
        return lead;
    }

    /**
     * 当前是否已知 leader
     */
    public boolean hasLeader() {
        return lead != NONE;
    }

    /**
     * 正在把领导权转移给谁，没有转移时是 0
     */
    public long getLeadTransferee() {
        return leadTransferee;
    }

    /**
     * 本节点是否是 learner
     *
     * <p>与 etcd 一致，learner 身份由跟踪器里本节点的 {@link Progress} 决定，
     * 不单独缓存：从快照恢复成员配置变化后，本节点可能从 learner 变成 voter。</p>
     */
    public boolean isLearner() {
        Progress pr = trk.getProgress().get(id);
        return pr != null && pr.isLearner();
    }

    /**
     * 日志视图
     */
    public RaftLog getRaftLog() {
        return raftLog;
    }

    /**
     * 成员配置与复制进度
     */
    public ProgressTracker getTracker() {
        return trk;
    }

    /**
     * 当前成员配置
     */
    public Config getConfig() {
        return trk.getConfig();
    }

    /**
     * 不需要持久化的状态（角色与 leader）
     */
    public SoftState softState() {
        return new SoftState(lead, state);
    }

    /**
     * 需要持久化的状态（任期、投票、提交位置）
     */
    public HardState hardState() {
        return new HardState(term, vote, raftLog.committed());
    }

    /**
     * 本轮要发出去的消息
     */
    public List<Message> getMessages() {
        return msgs;
    }

    /**
     * 本轮要发出去的消息，但要先等未落盘状态持久化
     */
    public List<Message> getMessagesAfterAppend() {
        return msgsAfterAppend;
    }

    /**
     * 清空本轮要发出去的消息。
     *
     * <p>驱动层处理完一个 Ready 之后调用，表示这批消息已经被应用层接收。</p>
     */
    public void clearMessages() {
        msgs.clear();
    }

    /**
     * 清空「等落盘之后再发」的消息。
     */
    public void clearMessagesAfterAppend() {
        msgsAfterAppend.clear();
    }

    /**
     * 还没有被应用的成员变更条目的位置，0 表示没有。
     *
     * <p>用于校验成员变更是否已经落到了状态机上。</p>
     */
    public long getPendingConfIndex() {
        return pendingConfIndex;
    }

    /**
     * leader 未提交日志尾部的总字节数。
     */
    public long getUncommittedSize() {
        return uncommittedSize;
    }

    /**
     * 已确认的只读请求状态
     */
    public List<ReadState> getReadStates() {
        return readStates;
    }

    /**
     * 清空已确认的只读请求状态。
     */
    public void clearReadStates() {
        readStates.clear();
    }

    /**
     * 尚未落盘的日志条目
     */
    public List<Entry> getUnstableEntries() {
        return raftLog.unstableEntries();
    }

    /**
     * 待应用的已提交条目。
     *
     * <p>同步落盘模式下未落盘的条目也可以应用，因此这里允许 unstable。</p>
     */
    public List<Entry> getCommittedEntries() {
        return raftLog.nextCommittedEnts(true);
    }

    /**
     * 待落盘的快照，没有返回 null
     */
    public Snapshot getUnstableSnapshot() {
        return raftLog.getUnstable().getSnapshot();
    }

    /**
     * 只读请求跟踪器
     */
    public ReadOnly getReadOnly() {
        return readOnly;
    }

    // ------------------------------------------------------------------
    // 时间推进
    // ------------------------------------------------------------------

    /**
     * 时间前进一个单位，按当前角色决定是走选举计时还是心跳计时。
     */
    public void tick() {
        if (state == RaftRole.LEADER) {
            tickHeartbeat();
        } else {
            tickElection();
        }
    }

    /**
     * 时间前进一个单位，但不执行任何状态机处理。
     *
     * <p>当所有节点都处于已知状态时，可以用它代替 {@link #tick()}，
     * 省掉周期性的心跳与选举。慎用：它绕过了 raft 状态机。</p>
     */
    public void tickQuiesced() {
        electionElapsed++;
    }

    /**
     * follower / candidate 的计时：超过选举超时就发起选举。
     */
    public void tickElection() {
        electionElapsed++;
        if (promotable() && pastElectionTimeout()) {
            electionElapsed = 0;
            step(new Message().setFrom(id).setType(MessageType.MSG_HUP));
        }
    }

    /**
     * leader 的计时：到点发心跳，顺便检查多数派是否还活跃。
     */
    public void tickHeartbeat() {
        heartbeatElapsed++;
        electionElapsed++;

        if (electionElapsed >= electionTimeout) {
            electionElapsed = 0;
            if (checkQuorum) {
                step(new Message().setFrom(id).setType(MessageType.MSG_CHECK_QUORUM));
            }
            // 一个选举超时内没能把领导权转移出去，就自己收回领导权
            if (state == RaftRole.LEADER && leadTransferee != NONE) {
                abortLeaderTransfer();
            }
        }

        if (state != RaftRole.LEADER) {
            return;
        }

        if (heartbeatElapsed >= heartbeatTimeout) {
            heartbeatElapsed = 0;
            step(new Message().setFrom(id).setType(MessageType.MSG_BEAT));
        }
    }

    // ------------------------------------------------------------------
    // 角色切换
    // ------------------------------------------------------------------

    /**
     * 变成 follower。
     *
     * @param term 新的任期
     * @param lead 已知的 leader，0 表示不知道
     */
    public void becomeFollower(long term, long lead) {
        reset(term);
        this.lead = lead;
        this.state = RaftRole.FOLLOWER;
        log.info("%x became follower at term %d", id, this.term);
    }

    /**
     * 变成 candidate：任期加一，投自己一票。
     */
    public void becomeCandidate() {
        if (state == RaftRole.LEADER) {
            throw new RaftException("invalid transition [leader -> candidate]");
        }
        reset(term + 1);
        this.vote = id;
        this.state = RaftRole.CANDIDATE;
        log.info("%x became candidate at term %d", id, this.term);
    }

    /**
     * 变成预候选者：任期与投票都不变，只是开始收集选票。
     */
    public void becomePreCandidate() {
        if (state == RaftRole.LEADER) {
            throw new RaftException("invalid transition [leader -> pre-candidate]");
        }
        // 预候选只改 step 函数与状态，任期与投票都不动
        trk.resetVotes();
        this.lead = NONE;
        this.state = RaftRole.PRE_CANDIDATE;
        log.info("%x became pre-candidate at term %d", id, this.term);
    }

    /**
     * 变成 leader：追加一条空日志，并把自己切到复制状态。
     */
    public void becomeLeader() {
        if (state == RaftRole.FOLLOWER) {
            throw new RaftException("invalid transition [follower -> leader]");
        }
        reset(term);
        this.lead = id;
        this.state = RaftRole.LEADER;

        // 其它 follower 要先被探测成功才会进入复制状态，leader 自己天然就在这个状态
        Progress pr = trk.getProgress().get(id);
        if (pr != null) {
            pr.becomeReplicate();
            // leader 永远是活跃的，MsgCheckQuorum 会保证这一点
            pr.setRecentActive(true);
        }

        // 保守地把待处理的成员变更位置设成日志末尾，
        // 这样在提交完已有日志之前不会再提出新的成员变更
        pendingConfIndex = raftLog.lastIndex();

        // 新 leader 的第一条空日志，用来确认自己在本任期内拥有多数派
        List<Entry> emptyEnt = Collections.singletonList(new Entry(0, 0, EntryType.NORMAL, null));
        if (!appendEntry(emptyEnt)) {
            throw new RaftException("empty entry was dropped");
        }
        log.info("%x became leader at term %d", id, this.term);
    }

    /**
     * 发起选举。
     *
     * @param t 选举类型
     */
    public void hup(CampaignType t) {
        if (state == RaftRole.LEADER) {
            log.debug("%x ignoring MsgHup because already leader", id);
            return;
        }
        if (!promotable()) {
            log.warn("%x is unpromotable and can not campaign", id);
            return;
        }
        if (hasUnappliedConfChanges()) {
            log.warn("%x cannot campaign at term %d since there are still pending configuration changes to apply",
                    id, term);
            return;
        }
        log.info("%x is starting a new election at term %d", id, term);
        campaign(t);
    }

    /**
     * 真正发起竞选：给自己和所有投票节点发拉票消息。
     *
     * @param t 选举类型
     */
    public void campaign(CampaignType t) {
        if (!promotable()) {
            log.warn("%x is unpromotable; campaign() should have been called", id);
        }
        long voteTerm;
        MessageType voteMsg;
        if (t == CampaignType.CAMPAIGN_PRE_ELECTION) {
            becomePreCandidate();
            voteMsg = MessageType.MSG_PRE_VOTE;
            // 预投票消息带的是「下一个任期」，此时本节点任期还没加一
            voteTerm = term + 1;
        } else {
            becomeCandidate();
            voteMsg = MessageType.MSG_VOTE;
            voteTerm = term;
        }

        for (Long id : trk.getConfig().getVoters().ids()) {
            if (id == this.id) {
                // 候选者给自己投票，等投票持久化后由自己确认
                send(new Message().setTo(id).setTerm(voteTerm)
                        .setType(voteRespMsgType(voteMsg)));
                continue;
            }
            long lastIndex = raftLog.lastIndex();
            long lastTerm = raftLog.lastTerm();
            log.info("%x [logterm: %d, index: %d] sent %s request to %x at term %d",
                    this.id, lastTerm, lastIndex, voteMsg, id, this.term);

            byte[] ctx = null;
            if (t == CampaignType.CAMPAIGN_TRANSFER) {
                ctx = t.getBytes();
            }
            send(new Message().setTo(id).setTerm(voteTerm).setType(voteMsg)
                    .setIndex(lastIndex).setLogTerm(lastTerm).setContext(ctx));
        }
    }

    /**
     * 统计某个节点对当前竞选的投票。
     *
     * @return 赞成票数、反对票数与投票结果
     */
    private ProgressTracker.TallyResult poll(long id, MessageType t, boolean v) {
        if (v) {
            log.info("%x received %s from %x at term %d", this.id, t, id, this.term);
        } else {
            log.info("%x received %s rejection from %x at term %d", this.id, t, id, this.term);
        }
        trk.recordVote(id, v);
        return trk.tallyVotes();
    }

    // ------------------------------------------------------------------
    // 消息处理
    // ------------------------------------------------------------------

    /**
     * 推进状态机。
     *
     * <p>调用方传入的消息之后不应再修改。</p>
     *
     * @param m 要处理的消息
     * @return true 表示消息被接受；false 表示提案被丢弃（没有 leader、正在转移领导权等）
     */
    public boolean step(Message m) {
        if (m == null) {
            throw new RaftException("nil message");
        }

        // 先处理任期，这可能让我们退位成 follower
        long mTerm = m.getTerm();
        if (mTerm != 0) {
            if (mTerm > term) {
                if (m.getType() == MessageType.MSG_VOTE || m.getType() == MessageType.MSG_PRE_VOTE) {
                    boolean force = m.getContext() != null
                            && java.util.Arrays.equals(m.getContext(), CampaignType.CAMPAIGN_TRANSFER.getBytes());
                    boolean inLease = checkQuorum && lead != NONE && electionElapsed < electionTimeout;
                    if (!force && inLease) {
                        // 在收到 leader 消息后的最小选举超时内收到拉票请求，
                        // 不更新任期也不投票
                        log.info("%x [logterm: %d, index: %d, vote: %x] ignored %s from %x "
                                        + "[logterm: %d, index: %d] at term %d: lease is not expired "
                                        + "(remaining ticks: %d)",
                                id, raftLog.lastTerm(), raftLog.lastIndex(), vote, m.getType(), m.getFrom(),
                                m.getLogTerm(), m.getIndex(), this.term, electionTimeout - electionElapsed);
                        return true;
                    }
                }
                if (m.getType() == MessageType.MSG_PRE_VOTE) {
                    // 预投票绝不改变自己的任期
                } else if (m.getType() == MessageType.MSG_PRE_VOTE_RESP && !m.getReject()) {
                    // 预投票通过时不改任期，等拿到多数票再自己加一
                } else {
                    log.info("%x [term: %d] received a %s message with higher term from %x [term: %d]",
                            id, this.term, m.getType(), m.getFrom(), mTerm);
                    if (m.getType() == MessageType.MSG_APP
                            || m.getType() == MessageType.MSG_HEARTBEAT
                            || m.getType() == MessageType.MSG_SNAP) {
                        becomeFollower(mTerm, m.getFrom());
                    } else {
                        becomeFollower(mTerm, NONE);
                    }
                }
            } else if (mTerm < term) {
                if ((checkQuorum || preVote)
                        && (m.getType() == MessageType.MSG_HEARTBEAT || m.getType() == MessageType.MSG_APP)) {
                    // 收到旧 leader 的消息，回一个确认把当前任期告诉对方
                    send(new Message().setTo(m.getFrom()).setType(MessageType.MSG_APP_RESP));
                } else if (m.getType() == MessageType.MSG_PRE_VOTE) {
                    // 预投票消息的任期比本地低，直接拒绝
                    send(new Message().setTo(m.getFrom()).setTerm(term)
                            .setType(MessageType.MSG_PRE_VOTE_RESP).setReject(true));
                } else if (m.getType() == MessageType.MSG_STORAGE_APPEND_RESP) {
                    if (m.getIndex() != 0) {
                        log.info("%x [term: %d] ignored entry appends from a %s message with lower term [term: %d]",
                                id, this.term, m.getType(), mTerm);
                    }
                    if (m.getSnapshot() != null) {
                        // 快照里装的是已提交状态，与任期无关，照样能应用
                        appliedSnap(m.getSnapshot());
                    }
                } else {
                    log.info("%x [term: %d] ignored a %s message with lower term from %x [term: %d]",
                            id, this.term, m.getType(), m.getFrom(), mTerm);
                }
                return true;
            }
        }

        switch (m.getType()) {
            case MSG_HUP:
                if (preVote) {
                    hup(CampaignType.CAMPAIGN_PRE_ELECTION);
                } else {
                    hup(CampaignType.CAMPAIGN_ELECTION);
                }
                return true;

            case MSG_STORAGE_APPEND_RESP:
                if (m.getIndex() != 0) {
                    raftLog.stableTo(m.getIndex(), m.getLogTerm());
                }
                if (m.getSnapshot() != null) {
                    appliedSnap(m.getSnapshot());
                }
                return true;

            case MSG_STORAGE_APPLY_RESP:
                if (m.getEntries() != null && !m.getEntries().isEmpty()) {
                    List<Entry> ents = m.getEntries();
                    long index = ents.get(ents.size() - 1).getIndex();
                    appliedTo(index, entsSize(ents));
                    reduceUncommittedSize(payloadsSize(ents));
                }
                return true;

            case MSG_VOTE:
            case MSG_PRE_VOTE:
                return handleVoteRequest(m);

            default:
                break;
        }

        return stepStateMachine(m);
    }

    /**
     * 按当前状态把消息交给对应的处理函数。
     *
     * <p>etcd 里 {@code raft.step} 是一个可替换的函数字段，
     * {@code TestStepIgnoreOldTermMsg} 靠替换它来验证旧任期的消息不会走到这一步；
     * Java 没有函数字段，这里拆成一个可重写的方法。</p>
     */
    boolean stepStateMachine(Message m) {
        switch (state) {
            case LEADER:
                return stepLeader(m);
            case CANDIDATE:
            case PRE_CANDIDATE:
                return stepCandidate(m);
            case FOLLOWER:
            default:
                return stepFollower(m);
        }
    }

    /**
     * 处理拉票请求。
     *
     * @return true 表示消息被接受
     */
    private boolean handleVoteRequest(Message m) {
        // 可以投票的三种情况
        boolean canVote = vote == m.getFrom()
                // 还没投过票，并且本任期还不知道有 leader
                || (vote == NONE && lead == NONE)
                // 这是针对更高任期的预投票
                || (m.getType() == MessageType.MSG_PRE_VOTE && m.getTerm() > term);

        long lastIndex = raftLog.lastIndex();
        long lastTerm = raftLog.lastTerm();
        long candLastIndex = m.getIndex();
        long candLastTerm = m.getLogTerm();

        if (canVote && raftLog.isUpToDate(candLastIndex, candLastTerm)) {
            log.info("%x [logterm: %d, index: %d, vote: %x] cast %s for %x [logterm: %d, index: %d] at term %d",
                    id, lastTerm, lastIndex, vote, m.getType(), m.getFrom(), candLastTerm, candLastIndex, this.term);
            // 回复里带的是对方消息里的任期，不是本地任期：
            // 预投票时两者不同，带本地任期会让对方直接忽略这条回复
            send(new Message().setTo(m.getFrom()).setTerm(m.getTerm())
                    .setType(voteRespMsgType(m.getType())));
            if (m.getType() == MessageType.MSG_VOTE) {
                // 只有真正的投票才记录
                electionElapsed = 0;
                vote = m.getFrom();
            }
        } else {
            log.info("%x [logterm: %d, index: %d, vote: %x] rejected %s from %x [logterm: %d, index: %d] at term %d",
                    id, lastTerm, lastIndex, vote, m.getType(), m.getFrom(), candLastTerm, candLastIndex, this.term);
            send(new Message().setTo(m.getFrom()).setTerm(term)
                    .setType(voteRespMsgType(m.getType())).setReject(true));
        }
        return true;
    }

    /**
     * leader 状态下的消息处理。
     */
    private boolean stepLeader(Message m) {
        // 这几类消息不需要 m.From 的复制进度
        switch (m.getType()) {
            case MSG_BEAT:
                bcastHeartbeat();
                return true;

            case MSG_CHECK_QUORUM:
                if (!trk.quorumActive()) {
                    log.warn("%x stepped down to follower since quorum is not active", id);
                    becomeFollower(term, NONE);
                }
                // 把别人标记成不活跃，为下一次检查做准备
                trk.visit((pid, pr) -> {
                    if (pid != id) {
                        pr.setRecentActive(false);
                    }
                });
                return true;

            case MSG_PROP:
                return handleProposal(m);

            case MSG_READ_INDEX:
                // leader 还没在本任期内提交过日志时，暂缓处理读请求
                if (!committedEntryInCurrentTerm()) {
                    pendingReadIndexMessages.add(m);
                    return true;
                }
                sendMsgReadIndexResponse(m);
                return true;

            case MSG_FORGET_LEADER:
                // leader 上是空操作
                return true;

            default:
                break;
        }

        // 其余消息都需要 m.From 的复制进度
        Progress pr = trk.getProgress().get(m.getFrom());
        if (pr == null) {
            log.debug("%x no progress available for %x", id, m.getFrom());
            return true;
        }

        switch (m.getType()) {
            case MSG_APP_RESP:
                return handleAppendResponse(m, pr);

            case MSG_HEARTBEAT_RESP:
                return handleHeartbeatResponse(m, pr);

            case MSG_SNAP_STATUS:
                if (pr.getState() != State.SNAPSHOT) {
                    return true;
                }
                if (!m.getReject()) {
                    pr.becomeProbe();
                    log.debug("%x snapshot succeeded, resumed sending replication messages to %x [%s]",
                            id, m.getFrom(), pr);
                } else {
                    // 顺序很重要：先清掉待发快照再切状态，否则会从快照位置开始探测
                    pr.setPendingSnapshot(0);
                    pr.becomeProbe();
                    log.debug("%x snapshot failed, resumed sending replication messages to %x [%s]",
                            id, m.getFrom(), pr);
                }
                // 快照发完后要等对端的 MsgAppResp，快照失败则要等一个心跳间隔
                pr.setMsgAppFlowPaused(true);
                return true;

            case MSG_UNREACHABLE:
                // 乐观复制期间对端不可达，说明刚发的追加消息大概率丢了
                if (pr.getState() == State.REPLICATE) {
                    pr.becomeProbe();
                }
                log.debug("%x failed to send message to %x because it is unreachable [%s]",
                        id, m.getFrom(), pr);
                return true;

            case MSG_TRANSFER_LEADER:
                return handleTransferLeader(m, pr);

            default:
                return true;
        }
    }

    /**
     * leader 处理提案。
     */
    private boolean handleProposal(Message m) {
        List<Entry> entries = m.getEntries();
        if (entries == null || entries.isEmpty()) {
            throw new RaftException(Long.toHexString(id) + " stepped empty MsgProp");
        }
        if (trk.getProgress().get(id) == null) {
            // 本节点已经不在配置里了（比如当 leader 时被移出），丢弃提案
            return false;
        }
        if (leadTransferee != NONE) {
            log.debug("%x [term %d] transfer leadership to %x is in progress; dropping proposal",
                    id, term, leadTransferee);
            return false;
        }

        // 复制一份，便于把非法的成员变更条目改成普通条目
        List<Entry> proposal = new ArrayList<>(entries);
        for (int i = 0; i < proposal.size(); i++) {
            Entry entry = proposal.get(i);
            ConfChangeI cc = null;
            if (entry.getType() == EntryType.CONFIG_CHANGE) {
                cc = ConfChangeCodec.unmarshal(EntryType.CONFIG_CHANGE, entry.getData());
            } else if (entry.getType() == EntryType.CONFIG_CHANGE_V2) {
                cc = ConfChangeCodec.unmarshal(EntryType.CONFIG_CHANGE_V2, entry.getData());
            }
            if (cc == null) {
                continue;
            }
            boolean alreadyPending = pendingConfIndex > raftLog.applied();
            boolean alreadyJoint = trk.getConfig().getVoters().getOutgoing().size() > 0;
            boolean wantsLeaveJoint = cc.asV2().getChanges().isEmpty();

            String failedCheck = null;
            if (alreadyPending) {
                failedCheck = "possible unapplied conf change at index " + pendingConfIndex
                        + " (applied to " + raftLog.applied() + ")";
            } else if (alreadyJoint && !wantsLeaveJoint) {
                failedCheck = "must transition out of joint config first";
            } else if (!alreadyJoint && wantsLeaveJoint) {
                failedCheck = "not in joint state; refusing empty conf change";
            }

            if (failedCheck != null && !disableConfChangeValidation) {
                log.info("%x ignoring conf change %v at config %s: %s",
                        id, cc, trk.getConfig(), failedCheck);
                // 把这条成员变更降级成普通条目
                proposal.set(i, new Entry(entry.getIndex(), entry.getTerm(), EntryType.NORMAL, null));
            } else {
                pendingConfIndex = raftLog.lastIndex() + i + 1;
            }
        }

        if (!appendEntry(proposal)) {
            return false;
        }
        bcastAppend();
        return true;
    }

    /**
     * leader 处理追加日志的响应。
     */
    private boolean handleAppendResponse(Message m, Progress pr) {
        pr.setRecentActive(true);

        if (m.getReject()) {
            // rejectHint 是建议的下一个探测位置，
            // logTerm 是对端在该位置上的任期
            log.debug("%x received MsgAppResp(rejected, hint: (index %d, term %d)) from %x for index %d",
                    id, m.getRejectHint(), m.getLogTerm(), m.getFrom(), m.getIndex());
            long nextProbeIdx = m.getRejectHint();
            if (m.getLogTerm() > 0) {
                // 对端有未提交的日志尾巴时，逐条探测会非常慢。
                // 这里利用「同一份日志里任期只增不减」的性质，
                // 直接跳到 leader 这边任期不大于拒绝提示的那个位置
                long[] found = raftLog.findConflictByTerm(m.getRejectHint(), m.getLogTerm());
                nextProbeIdx = found[0];
            }
            if (pr.maybeDecrTo(m.getIndex(), nextProbeIdx)) {
                log.debug("%x decreased progress of %x to [%s]", id, m.getFrom(), pr);
                if (pr.getState() == State.REPLICATE) {
                    pr.becomeProbe();
                }
                sendAppend(m.getFrom());
            }
            return true;
        }

        // 确认消息推进了匹配位置，或者能把探测状态的节点拉回复制状态
        if (pr.maybeUpdate(m.getIndex()) || (pr.getMatch() == m.getIndex() && pr.getState() == State.PROBE)) {
            if (pr.getState() == State.PROBE) {
                pr.becomeReplicate();
            } else if (pr.getState() == State.SNAPSHOT && pr.getMatch() + 1 >= raftLog.firstIndex()) {
                // 快照点之后的日志够用，就不用再等快照了
                log.debug("%x recovered from needing snapshot, resumed sending replication messages to %x [%s]",
                        id, m.getFrom(), pr);
                pr.becomeProbe();
                pr.becomeReplicate();
            } else if (pr.getState() == State.REPLICATE) {
                pr.getInflights().freeLE(m.getIndex());
            }

            if (maybeCommit()) {
                // 提交位置前进了，本任期的读请求可以回答了
                releasePendingReadIndexMessages();
                bcastAppend();
            } else if (id != m.getFrom() && pr.canBumpCommit(raftLog.committed())) {
                // 对端可能还不知道最新的提交位置，主动推一次
                sendAppend(m.getFrom());
            }

            // 上面更新了流控信息，可能一次能发多条，把能发的都发掉
            if (id != m.getFrom()) {
                while (maybeSendAppend(m.getFrom(), false)) {
                    // 一直发到发不动为止
                }
            }

            // 领导权转移进行中
            if (m.getFrom() == leadTransferee && pr.getMatch() == raftLog.lastIndex()) {
                log.info("%x sent MsgTimeoutNow to %x after received MsgAppResp", id, m.getFrom());
                sendTimeoutNow(m.getFrom());
            }
        }
        return true;
    }

    /**
     * leader 处理心跳响应。
     */
    private boolean handleHeartbeatResponse(Message m, Progress pr) {
        pr.setRecentActive(true);
        pr.setMsgAppFlowPaused(false);

        // 对端落后或者还停在探测状态时，补发一次追加消息（可能是空的，只带提交位置）
        if (pr.getMatch() < raftLog.lastIndex() || pr.getState() == State.PROBE) {
            sendAppend(m.getFrom());
        }

        if (readOnly.getOption() != ReadOnlyOption.READ_ONLY_SAFE
                || m.getContext() == null || m.getContext().length == 0) {
            return true;
        }

        readOnly.recvAck(m.getFrom(), m.getContext());
        List<ReadOnly.ReadIndexRequest> rss = readOnly.maybeAdvance(trk.getConfig().getVoters());
        for (ReadOnly.ReadIndexRequest rs : rss) {
            Message resp = responseToReadIndexReq(rs.getReq(), rs.getIndex());
            if (resp.getTo() != NONE) {
                send(resp);
            }
        }
        return true;
    }

    /**
     * leader 处理领导权转移请求。
     */
    private boolean handleTransferLeader(Message m, Progress pr) {
        if (pr.isLearner()) {
            log.debug("%x is learner. Ignored transferring leadership", id);
            return true;
        }
        long transferee = m.getFrom();
        long lastTransferee = leadTransferee;
        if (lastTransferee != NONE) {
            if (lastTransferee == transferee) {
                log.info("%x [term %d] transfer leadership to %x is in progress, ignores request to same node %x",
                        id, term, transferee, transferee);
                return true;
            }
            abortLeaderTransfer();
            log.info("%x [term %d] abort previous transferring leadership to %x",
                    id, term, lastTransferee);
        }
        if (transferee == id) {
            log.debug("%x is already leader. Ignored transferring leadership to self", id);
            return true;
        }
        log.info("%x [term %d] starts to transfer leadership to %x", id, term, transferee);
        // 领导权转移要在一个选举超时内完成，重新计时
        electionElapsed = 0;
        leadTransferee = transferee;
        if (pr.getMatch() == raftLog.lastIndex()) {
            sendTimeoutNow(transferee);
            log.info("%x sends MsgTimeoutNow to %x immediately as %x already has up-to-date log",
                    id, transferee, transferee);
        } else {
            sendAppend(transferee);
        }
        return true;
    }

    /**
     * candidate / pre-candidate 状态下的消息处理。
     */
    private boolean stepCandidate(Message m) {
        // 预候选者只认预投票响应，候选者只认投票响应
        MessageType myVoteRespType = state == RaftRole.PRE_CANDIDATE
                ? MessageType.MSG_PRE_VOTE_RESP
                : MessageType.MSG_VOTE_RESP;

        switch (m.getType()) {
            case MSG_PROP:
                log.info("%x no leader at term %d; dropping proposal", id, term);
                return false;

            case MSG_APP:
                becomeFollower(m.getTerm(), m.getFrom());
                handleAppendEntries(m);
                return true;

            case MSG_HEARTBEAT:
                becomeFollower(m.getTerm(), m.getFrom());
                handleHeartbeat(m);
                return true;

            case MSG_SNAP:
                becomeFollower(m.getTerm(), m.getFrom());
                handleSnapshot(m);
                return true;

            case MSG_PRE_VOTE_RESP:
            case MSG_VOTE_RESP:
                if (m.getType() != myVoteRespType) {
                    return true;
                }
                ProgressTracker.TallyResult tally = poll(m.getFrom(), m.getType(), !m.getReject());
                log.info("%x has received %d %s votes and %d vote rejections",
                        id, tally.getGranted(), m.getType(), tally.getRejected());
                if (tally.getResult() == VoteResult.VOTE_WON) {
                    if (state == RaftRole.PRE_CANDIDATE) {
                        campaign(CampaignType.CAMPAIGN_ELECTION);
                    } else {
                        becomeLeader();
                        bcastAppend();
                    }
                } else if (tally.getResult() == VoteResult.VOTE_LOST) {
                    // 预投票响应里带的是未来的任期，这里沿用本地任期
                    becomeFollower(term, NONE);
                }
                return true;

            case MSG_TIMEOUT_NOW:
                log.debug("%x [term %d state %v] ignored MsgTimeoutNow from %x", id, term, state, m.getFrom());
                return true;

            default:
                return true;
        }
    }

    /**
     * follower 状态下的消息处理。
     */
    private boolean stepFollower(Message m) {
        switch (m.getType()) {
            case MSG_PROP:
                if (lead == NONE) {
                    log.info("%x no leader at term %d; dropping proposal", id, term);
                    return false;
                } else if (disableProposalForwarding) {
                    log.info("%x not forwarding to leader %x at term %d; dropping proposal", id, lead, term);
                    return false;
                }
                m.setTo(lead);
                send(m);
                return true;

            case MSG_APP:
                electionElapsed = 0;
                lead = m.getFrom();
                handleAppendEntries(m);
                return true;

            case MSG_HEARTBEAT:
                electionElapsed = 0;
                lead = m.getFrom();
                handleHeartbeat(m);
                return true;

            case MSG_SNAP:
                electionElapsed = 0;
                lead = m.getFrom();
                handleSnapshot(m);
                return true;

            case MSG_TRANSFER_LEADER:
                if (lead == NONE) {
                    log.info("%x no leader at term %d; dropping leader transfer msg", id, term);
                    return true;
                }
                m.setTo(lead);
                send(m);
                return true;

            case MSG_FORGET_LEADER:
                if (readOnly.getOption() == ReadOnlyOption.READ_ONLY_LEASE_BASED) {
                    log.error("ignoring MsgForgetLeader due to ReadOnlyLeaseBased");
                    return true;
                }
                if (lead != NONE) {
                    log.info("%x forgetting leader %x at term %d", id, lead, term);
                    lead = NONE;
                }
                return true;

            case MSG_TIMEOUT_NOW:
                log.info("%x [term %d] received MsgTimeoutNow from %x and starts an election to get leadership.",
                        id, term, m.getFrom());
                // 领导权转移不用预投票：我们知道没有分区，不需要多绕一圈
                hup(CampaignType.CAMPAIGN_TRANSFER);
                return true;

            case MSG_READ_INDEX:
                if (lead == NONE) {
                    log.info("%x no leader at term %d; dropping index reading msg", id, term);
                    return true;
                }
                m.setTo(lead);
                send(m);
                return true;

            case MSG_READ_INDEX_RESP:
                if (m.getEntries() == null || m.getEntries().size() != 1) {
                    log.error("%x invalid format of MsgReadIndexResp from %x, entries count: %d",
                            id, m.getFrom(), m.getEntries() == null ? 0 : m.getEntries().size());
                    return true;
                }
                readStates.add(new ReadState(m.getIndex(), m.getEntries().get(0).getData()));
                return true;

            default:
                return true;
        }
    }

    // ------------------------------------------------------------------
    // follower 侧的日志处理
    // ------------------------------------------------------------------

    /**
     * 从追加日志消息里取出这次要追加的日志切片。
     *
     * <p>Go: {@code logSliceFromMsgApp}。</p>
     */
    private static LogSlice logSliceFromMsgApp(Message m) {
        return new LogSlice(m.getTerm(), new EntryId(m.getLogTerm(), m.getIndex()), m.getEntries());
    }

    /**
     * 处理追加日志消息。
     */
    void handleAppendEntries(Message m) {
        LogSlice a = logSliceFromMsgApp(m);
        long prevIndex = a.getPrev().getIndex();
        long prevTerm = a.getPrev().getTerm();

        if (prevIndex < raftLog.committed()) {
            // 前置位置已经提交，直接告诉对方最新的提交位置
            send(new Message().setTo(m.getFrom()).setType(MessageType.MSG_APP_RESP)
                    .setIndex(raftLog.committed()));
            return;
        }

        long lastNewIndex = raftLog.maybeAppend(a, m.getCommit());
        if (lastNewIndex >= 0) {
            send(new Message().setTo(m.getFrom()).setType(MessageType.MSG_APP_RESP)
                    .setIndex(lastNewIndex));
            return;
        }

        log.debug("%x [logterm: %d, index: %d] rejected MsgApp [logterm: %d, index: %d] from %x",
                id, raftLog.zeroTermOnOutOfBounds(prevIndex), prevIndex, prevTerm, prevIndex, m.getFrom());

        // 给出回退提示：在任期不超过对方 logTerm、位置不超过对方 index 的范围内，
        // 找出自己日志里最靠后的匹配位置
        long hintIndex = Math.min(prevIndex, raftLog.lastIndex());
        long[] hint = raftLog.findConflictByTerm(hintIndex, prevTerm);
        send(new Message().setTo(m.getFrom()).setType(MessageType.MSG_APP_RESP)
                .setIndex(prevIndex).setReject(true)
                .setRejectHint(hint[0]).setLogTerm(hint[1]));
    }

    /**
     * 处理心跳消息。
     */
    void handleHeartbeat(Message m) {
        raftLog.commitTo(m.getCommit());
        send(new Message().setTo(m.getFrom()).setType(MessageType.MSG_HEARTBEAT_RESP)
                .setContext(m.getContext()));
    }

    /**
     * 处理快照消息。
     */
    private void handleSnapshot(Message m) {
        Snapshot s = m.getSnapshot();
        if (s == null) {
            s = new Snapshot(new byte[0], new io.edap.raft.protocol.SnapshotMetadata(null, 0, 0));
        }
        long sindex = s.getLastIncludedIndex();
        long sterm = s.getLastIncludedTerm();
        if (restore(s)) {
            log.info("%x [commit: %d] restored snapshot [index: %d, term: %d]",
                    id, raftLog.committed(), sindex, sterm);
            send(new Message().setTo(m.getFrom()).setType(MessageType.MSG_APP_RESP)
                    .setIndex(raftLog.lastIndex()));
        } else {
            log.info("%x [commit: %d] ignored snapshot [index: %d, term: %d]",
                    id, raftLog.committed(), sindex, sterm);
            send(new Message().setTo(m.getFrom()).setType(MessageType.MSG_APP_RESP)
                    .setIndex(raftLog.committed()));
        }
    }

    /**
     * 用快照恢复状态机（日志与成员配置）。
     *
     * @param s 要恢复的快照
     * @return true 表示快照被应用了；false 表示快照过时或本节点不在配置里
     */
    public boolean restore(Snapshot s) {
        if (s.getLastIncludedIndex() <= raftLog.committed()) {
            return false;
        }
        if (state != RaftRole.FOLLOWER) {
            // 防御性检查：leader 不应该应用快照
            log.warn("%x attempted to restore snapshot as leader; should never happen", id);
            becomeFollower(term + 1, NONE);
            return false;
        }

        // 防御性检查：本节点必须在配置里
        ConfState cs = s.getConfState() == null ? emptyConfState() : s.getConfState();
        boolean found = false;
        List<List<Long>> sets = new ArrayList<>();
        sets.add(cs.getVoters());
        sets.add(cs.getLearners());
        sets.add(cs.getVotersOutgoing());
        for (List<Long> set : sets) {
            if (set.contains(id)) {
                found = true;
                break;
            }
        }
        if (!found) {
            log.warn("%x attempted to restore snapshot but it is not in the ConfState %v; should never happen",
                    id, cs);
            return false;
        }

        long snapIndex = s.getLastIncludedIndex();
        long snapTerm = s.getLastIncludedTerm();
        if (raftLog.matchTerm(snapIndex, snapTerm)) {
            // 本地日志已经有这条了，只要把提交位置推上去
            log.info("%x [commit: %d, lastindex: %d, lastterm: %d] fast-forwarded commit to snapshot "
                            + "[index: %d, term: %d]",
                    id, raftLog.committed(), raftLog.lastIndex(), raftLog.lastTerm(), snapIndex, snapTerm);
            raftLog.commitTo(snapIndex);
            return false;
        }

        raftLog.restore(s);

        // 用快照里的成员配置重建跟踪器。快照之后旧的复制进度全部作废，
        // 所以这里的进度是全新的（与 etcd 重建整个 tracker 的做法一致）
        Config restored = Restore.restore(new Changer(id, new Config()), cs);
        resetProgress(restored);
        trk.resetVotes();
        switchToConfig(restored);

        log.info("%x [commit: %d, lastindex: %d, lastterm: %d] restored snapshot [index: %d, term: %d]",
                id, raftLog.committed(), raftLog.lastIndex(), raftLog.lastTerm(), snapIndex, snapTerm);
        return true;
    }

    // ------------------------------------------------------------------
    // 成员变更
    // ------------------------------------------------------------------

    /**
     * 应用一次成员变更，返回变更后的集群配置。
     *
     * @param cc 成员变更
     * @return 变更生效后的配置
     */
    public ConfState applyConfChange(ConfChangeV2 cc) {
        if (cc == null) {
            throw new RaftException("conf change must not be null");
        }
        Changer changer = new Changer(id, trk.getConfig());
        Config cfg;
        if (cc.leaveJoint()) {
            cfg = changer.leaveJoint();
        } else {
            boolean[] joint = cc.enterJoint();
            if (joint[1]) {
                cfg = changer.enterJoint(joint[0], cc.getChanges());
            } else {
                List<ConfChangeSingle> changes = cc.getChanges();
                cfg = changer.simple(changes.toArray(new ConfChangeSingle[0]));
            }
        }
        return switchToConfig(cfg);
    }

    /**
     * 切换到新的成员配置，并做相应的善后。
     *
     * @param cfg 新配置
     * @return 切换后的集群配置
     */
    private ConfState switchToConfig(Config cfg) {
        rebuildProgress(cfg);
        trk.config = cfg;

        log.info("%x switched to configuration %s", id, trk.getConfig());
        ConfState cs = trk.confState();
        Progress pr = trk.getProgress().get(id);

        // 自己的复制进度至少要跟上当前的提交位置
        if (pr != null) {
            pr.maybeUpdate(raftLog.committed());
        }

        // 本节点是否变成了 learner，被移出集群时恢复成 false
        boolean inConfig = pr != null;
        boolean isLearner = inConfig && pr.isLearner();

        if ((!inConfig || isLearner) && state == RaftRole.LEADER) {
            // leader 被移出集群或降级，退位
            if (stepDownOnRemoval) {
                becomeFollower(term, NONE);
            }
            return cs;
        }

        // 后面的动作只在本节点是 leader 且集群还有其它节点时才有意义
        if (state != RaftRole.LEADER || cs.getVoters().isEmpty()) {
            return cs;
        }

        if (maybeCommit()) {
            // 配置变化让更多日志变成已提交，立刻广播
            bcastAppend();
        } else {
            // 否则也探测一下新加进来的节点，没必要让它们等一个心跳周期
            trk.visit((pid, p) -> {
                if (pid != id) {
                    maybeSendAppend(pid, false);
                }
            });
        }

        // 转移目标被移出或降级，放弃转移
        if (leadTransferee != NONE && !trk.getConfig().getVoters().contains(leadTransferee)) {
            abortLeaderTransfer();
        }

        return cs;
    }

    /**
     * 按新的配置重建各节点的复制进度。
     *
     * <p>已经存在的节点沿用原来的进度（match / next / 在途窗口），
     * 新加入的节点从 {@code Next = max(lastIndex, 1)} 开始，
     * 离开配置的节点被移除。</p>
     */
    private void rebuildProgress(Config cfg) {
        Set<Long> voters = cfg.getVoters().ids();
        Set<Long> learners = cfg.getLearners();
        ProgressMap old = trk.getProgress();
        ProgressMap next = new ProgressMap();
        long lastIndex = raftLog.lastIndex();

        for (Long pid : voters) {
            Progress pr = old.get(pid);
            if (pr == null) {
                pr = new Progress(pid, 0, Math.max(lastIndex, 1),
                        new Inflights(trk.maxInflight, trk.maxInflightBytes));
                // 新加入的节点先标记成活跃，否则可能在它还没来得及通信时就退位
                pr.setRecentActive(true);
            }
            pr.setLearner(false);
            next.put(pid, pr);
        }
        for (Long pid : learners) {
            Progress pr = old.get(pid);
            if (pr == null) {
                pr = new Progress(pid, 0, Math.max(lastIndex, 1),
                        new Inflights(trk.maxInflight, trk.maxInflightBytes));
                pr.setRecentActive(true);
            }
            pr.setLearner(true);
            next.put(pid, pr);
        }
        trk.progress = next;
    }

    /**
     * 丢弃全部复制进度，按新配置重新初始化。
     *
     * <p>用于从快照恢复：快照之后各节点的匹配位置全部作废，
     * 与 etcd 重建整个 {@code ProgressTracker} 的效果一致。</p>
     */
    private void resetProgress(Config cfg) {
        Set<Long> voters = cfg.getVoters().ids();
        Set<Long> learners = cfg.getLearners();
        ProgressMap next = new ProgressMap();
        long lastIndex = raftLog.lastIndex();

        for (Long pid : voters) {
            Progress pr = new Progress(pid, 0, Math.max(lastIndex, 1),
                    new Inflights(trk.maxInflight, trk.maxInflightBytes));
            pr.setRecentActive(true);
            pr.setLearner(false);
            next.put(pid, pr);
        }
        for (Long pid : learners) {
            Progress pr = new Progress(pid, 0, Math.max(lastIndex, 1),
                    new Inflights(trk.maxInflight, trk.maxInflightBytes));
            pr.setRecentActive(true);
            pr.setLearner(true);
            next.put(pid, pr);
        }
        trk.progress = next;
    }

    // ------------------------------------------------------------------
    // 发送消息
    // ------------------------------------------------------------------

    /**
     * 排入一条要发出的消息。
     *
     * <p>投票类消息要求消息里已经带上了任期；其余消息要求任期为空
     * （由本方法填上当前任期）。投票与追加的<b>响应</b>要等未落盘状态
     * 持久化之后才能发出去。</p>
     */
    void send(Message m) {
        if (m.getFrom() == NONE) {
            m.setFrom(id);
        }
        MessageType type = m.getType();
        if (type == MessageType.MSG_VOTE || type == MessageType.MSG_VOTE_RESP
                || type == MessageType.MSG_PRE_VOTE || type == MessageType.MSG_PRE_VOTE_RESP) {
            if (m.getTerm() == 0) {
                throw new RaftException("term should be set when sending " + type);
            }
        } else {
            if (m.getTerm() != 0) {
                throw new RaftException("term should not be set when sending " + type
                        + " (was " + m.getTerm() + ")");
            }
            // 提案与读请求是转发给 leader 的本地消息，不带任期
            if (type != MessageType.MSG_PROP && type != MessageType.MSG_READ_INDEX) {
                m.setTerm(term);
            }
        }
        if (type == MessageType.MSG_APP_RESP || type == MessageType.MSG_VOTE_RESP
                || type == MessageType.MSG_PRE_VOTE_RESP) {
            msgsAfterAppend.add(m);
        } else {
            if (m.getTo() == id) {
                throw new RaftException("message should not be self-addressed when sending " + type);
            }
            msgs.add(m);
        }
    }

    /**
     * 给指定节点发送带日志的追加消息。
     */
    void sendAppend(long to) {
        maybeSendAppend(to, true);
    }

    /**
     * 给指定节点发送带日志的追加消息（可能不带日志）。
     *
     * @param to          目标节点
     * @param sendIfEmpty 没有日志时是否也要发（空消息用来传递提交位置，
     *                    但批量发送时不应该发空消息）
     * @return true 表示确实发出了消息
     */
    private boolean maybeSendAppend(long to, boolean sendIfEmpty) {
        Progress pr = trk.getProgress().get(to);
        if (pr == null || pr.isPaused()) {
            return false;
        }

        long prevIndex = pr.getNext() - 1;
        long prevTerm;
        try {
            prevTerm = raftLog.term(prevIndex);
        } catch (RaftException e) {
            // 日志在 pr.Next 之后被截断了，没法靠日志追上了，发快照
            return maybeSendSnapshot(to, pr);
        }

        List<Entry> ents;
        if (pr.getState() != State.REPLICATE || !pr.getInflights().full()) {
            ents = raftLog.entries(pr.getNext(), raftLog.lastIndex() + 1, maxMsgSize);
        } else {
            // 在途窗口满了，先发空消息保活
            ents = Collections.emptyList();
        }
        if (ents.isEmpty() && !sendIfEmpty) {
            return false;
        }

        send(new Message().setTo(to).setType(MessageType.MSG_APP)
                .setIndex(prevIndex).setLogTerm(prevTerm)
                .setEntries(ents).setCommit(raftLog.committed()));
        pr.sentEntries(ents.size(), payloadsSize(ents));
        pr.sentCommit(raftLog.committed());
        return true;
    }

    /**
     * 给指定节点发送快照。
     *
     * @return true 表示确实发出了快照
     */
    private boolean maybeSendSnapshot(long to, Progress pr) {
        if (!pr.isRecentActive()) {
            log.debug("ignore sending snapshot to %x since it is not recently active", to);
            return false;
        }
        Snapshot snapshot = raftLog.snapshot();
        if (snapshot == null) {
            // etcd 里存储层用 ErrSnapshotTemporarilyUnavailable 表示「暂时拿不到快照」，
            // 这里存储层返回 null，语义相同
            log.debug("%x failed to send snapshot to %x because snapshot is temporarily unavailable", id, to);
            return false;
        }
        if (RaftUtil.isEmptySnap(snapshot)) {
            throw new RaftException("need non-empty snapshot");
        }
        long sindex = snapshot.getLastIncludedIndex();
        long sterm = snapshot.getLastIncludedTerm();
        log.debug("%x [firstindex: %d, commit: %d] sent snapshot[index: %d, term: %d] to %x [%s]",
                id, raftLog.firstIndex(), raftLog.committed(), sindex, sterm, to, pr);
        pr.becomeSnapshot(sindex);
        log.debug("%x paused sending replication messages to %x [%s]", id, to, pr);

        send(new Message().setTo(to).setType(MessageType.MSG_SNAP).setSnapshot(snapshot));
        return true;
    }

    /**
     * 给指定节点发送心跳。
     */
    private void sendHeartbeat(long to, byte[] ctx) {
        Progress pr = trk.getProgress().get(to);
        if (pr == null) {
            return;
        }
        // 提交位置不能超过对方已经匹配的位置
        long commit = Math.min(pr.getMatch(), raftLog.committed());
        send(new Message().setTo(to).setType(MessageType.MSG_HEARTBEAT)
                .setCommit(commit).setContext(ctx));
        pr.sentCommit(commit);
    }

    /**
     * 广播带日志的追加消息。
     */
    /**
     * 把待复制的日志广播给所有 follower。
     *
     * <p>包级可见：etcd 的论文测试里 {@code commitNoopEntry} 需要显式触发一次广播，
     * 与 {@link #appendEntry(List)} 一样属于「测试要用得到」的状态机操作。</p>
     */
    void bcastAppend() {
        trk.visit((pid, pr) -> {
            if (pid != id) {
                sendAppend(pid);
            }
        });
    }

    /**
     * 广播心跳。
     */
    private void bcastHeartbeat() {
        bcastHeartbeatWithCtx(readOnly.heartbeatCtx());
    }

    /**
     * 带上下文地广播心跳。
     */
    private void bcastHeartbeatWithCtx(byte[] ctx) {
        trk.visit((pid, pr) -> {
            if (pid != id) {
                sendHeartbeat(pid, ctx);
            }
        });
    }

    /**
     * 让指定节点立即发起选举（用于领导权转移）。
     */
    private void sendTimeoutNow(long to) {
        send(new Message().setTo(to).setType(MessageType.MSG_TIMEOUT_NOW));
    }

    /**
     * 放弃正在进行的领导权转移。
     */
    private void abortLeaderTransfer() {
        leadTransferee = NONE;
    }

    // ------------------------------------------------------------------
    // 状态推进
    // ------------------------------------------------------------------

    /**
     * 重置状态机（角色切换时调用）。
     *
     * @param newTerm 新的任期
     */
    void reset(long newTerm) {
        if (term != newTerm) {
            term = newTerm;
            vote = NONE;
        }
        lead = NONE;

        electionElapsed = 0;
        heartbeatElapsed = 0;
        resetRandomizedElectionTimeout();

        abortLeaderTransfer();

        trk.resetVotes();

        // 所有节点的复制进度回到起点
        List<Long> ids = new ArrayList<>();
        trk.getProgress().visit((pid, pr) -> ids.add(pid));
        long lastIndex = raftLog.lastIndex();
        for (Long pid : ids) {
            Progress old = trk.getProgress().get(pid);
            Progress pr = new Progress(pid, 0, Math.max(lastIndex + 1, 1),
                    new Inflights(trk.maxInflight, trk.maxInflightBytes));
            pr.setLearner(old.isLearner());
            if (pid == id) {
                pr.setMatch(lastIndex);
            }
            trk.getProgress().put(pid, pr);
        }

        pendingConfIndex = 0;
        uncommittedSize = 0;
        readOnly = new ReadOnly(readOnly.getOption());
    }

    /**
     * 追加日志条目。
     *
     * @param es 要追加的条目（index/term 会被重新赋值）
     * @return true 表示追加成功；false 表示因为超出未提交日志上限而被丢弃
     */
    boolean appendEntry(List<Entry> es) {
        long li = raftLog.lastIndex();
        List<Entry> cloned = new ArrayList<>(es.size());
        for (int i = 0; i < es.size(); i++) {
            Entry e = es.get(i);
            cloned.add(new Entry(li + 1 + i, term, e.getType(), e.getData()));
        }

        // 记账未提交日志的大小
        if (!increaseUncommittedSize(cloned)) {
            log.warn("%x appending new entries to log would exceed uncommitted entry size limit; dropping proposal", id);
            return false;
        }

        raftLog.append(cloned);

        // leader 不给自己发 MsgApp，等日志落盘后由自己确认
        send(new Message().setTo(id).setType(MessageType.MSG_APP_RESP).setIndex(raftLog.lastIndex()));
        return true;
    }

    /**
     * 提交位置能否推进。
     *
     * @return true 表示提交位置前进了
     */
    boolean maybeCommit() {
        Index idx = trk.committed();
        return raftLog.maybeCommit(term, idx.value());
    }

    /**
     * 应用层已把日志应用到状态机。
     *
     * @param index 已应用到的位置
     * @param size  本次确认的条目编码字节数，用来归还应用层的字节配额
     */
    public void appliedTo(long index, long size) {
        long oldApplied = raftLog.applied();
        long newApplied = Math.max(index, oldApplied);
        raftLog.appliedTo(newApplied, size);

        // 联合配置在条件允许时会自动退出
        if (trk.getConfig().isAutoLeave() && newApplied >= pendingConfIndex && state == RaftRole.LEADER) {
            // 空的 ConfChangeV2 表示退出联合配置，它的大小是 0，不会被大小限制拒绝
            Message m = new Message().setType(MessageType.MSG_PROP)
                    .setEntries(Collections.singletonList(
                            new Entry(0, 0, EntryType.CONFIG_CHANGE_V2, new byte[0])));
            try {
                step(m);
                log.info("initiating automatic transition out of joint configuration %s", trk.getConfig());
            } catch (RaftException e) {
                log.debug("not initiating automatic transition out of joint configuration %s: %v",
                        trk.getConfig(), e.getMessage());
            }
        }
    }

    /**
     * 应用层已把快照持久化。
     */
    public void appliedSnap(Snapshot snap) {
        long index = snap.getLastIncludedIndex();
        raftLog.stableSnapTo(index);
        appliedTo(index, 0);
    }

    /**
     * 标记日志已经落盘。
     *
     * @param index 落盘到的位置
     * @param term  该位置的任期
     */
    public void stableTo(long index, long term) {
        raftLog.stableTo(index, term);
    }

    /**
     * 载入持久化的硬状态。
     */
    public void loadState(HardState state) {
        if (state.getCommit() < raftLog.committed() || state.getCommit() > raftLog.lastIndex()) {
            throw new RaftException(Long.toHexString(id) + " state.commit " + state.getCommit()
                    + " is out of range [" + raftLog.committed() + ", " + raftLog.lastIndex() + "]");
        }
        raftLog.commitTo(state.getCommit());
        this.term = state.getTerm();
        this.vote = state.getVote();
    }

    /**
     * 本节点是否可以竞选 leader。
     */
    public boolean promotable() {
        Progress pr = trk.getProgress().get(id);
        return pr != null && !pr.isLearner() && !raftLog.hasNextOrInProgressSnapshot();
    }

    /**
     * 是否还有已提交但未应用的成员变更。
     */
    public boolean hasUnappliedConfChanges() {
        if (raftLog.applied() >= raftLog.committed()) {
            return false;
        }
        // 分页扫描，避免一次性把整个未应用区间读进内存
        long lo = raftLog.applied() + 1;
        long hi = raftLog.committed() + 1;
        // 复用应用层的字节上限，它也是用来限制未应用区间读取量的
        long pageSize = raftLog.getMaxApplyingEntsSize();
        boolean[] found = new boolean[1];
        raftLog.scan(lo, hi, pageSize, ents -> {
            for (Entry e : ents) {
                if (e.getType() == EntryType.CONFIG_CHANGE || e.getType() == EntryType.CONFIG_CHANGE_V2) {
                    found[0] = true;
                    return false;
                }
            }
            return true;
        });
        return found[0];
    }

    /**
     * 是否已经超过带随机化的选举超时。
     */
    public boolean pastElectionTimeout() {
        return electionElapsed >= randomizedElectionTimeout;
    }

    /**
     * 重新计算带随机化的选举超时。
     */
    void resetRandomizedElectionTimeout() {
        randomizedElectionTimeout = electionTimeout
                + (int) ThreadLocalRandom.current().nextInt(electionTimeout);
    }

    /**
     * 直接设置带随机化的选举超时，绕过随机计算。
     *
     * <p>测试里需要确定性的超时值时使用，等价于 etcd 的
     * {@code setRandomizedElectionTimeout}。</p>
     */
    public void setRandomizedElectionTimeout(int v) {
        randomizedElectionTimeout = v;
    }

    /**
     * 测试专用：取当前带随机化的选举超时。
     *
     * <p>Go 里这个字段是包内可见的，测试需要按它来 tick；
     * Java 侧保持同样的入口，只对同包的测试使用。</p>
     */
    int randomizedElectionTimeout() {
        return randomizedElectionTimeout;
    }

    /**
     * 测试专用：按给定的成员集合重建跟踪器，并重置状态机。
     *
     * <p>等价于 etcd {@code raft_test.go} 里 {@code newNetworkWithConfig} 对已存在的
     * {@code *raft} 的处理：整个换掉 {@code ProgressTracker}，把网络里的节点按
     * voter / learner 重新登记（复制进度清零），最后调用 {@code reset}。</p>
     *
     * <p>Go 里这些字段是包内可见，测试可以直接改；Java 没有「测试专用包」的概念，
     * 因此把这段逻辑收敛成包内可见的方法，只对同包的测试脚手架开放。</p>
     *
     * @param peerIds 网络里的全部节点 id
     * @param learners 其中的 learner 节点
     */
    void resetTrackerForTest(long[] peerIds, Set<Long> learners) {
        List<Long> voters = new ArrayList<>();
        for (long pid : peerIds) {
            if (!learners.contains(pid)) {
                voters.add(pid);
            }
        }
        JointConfig joint = JointConfig.of(MajorityConfig.of(voters), MajorityConfig.empty());
        trk.config = new Config(joint, new LinkedHashSet<>(learners), false);

        long lastIndex = raftLog.lastIndex();
        ProgressMap pm = new ProgressMap();
        for (long pid : peerIds) {
            Progress pr = new Progress(pid, 0, Math.max(lastIndex + 1, 1),
                    new Inflights(trk.maxInflight, trk.maxInflightBytes));
            pr.setLearner(learners.contains(pid));
            pm.put(pid, pr);
        }
        trk.progress = pm;

        reset(term);
    }

    /**
     * 本任期是否已经提交过日志。
     */
    public boolean committedEntryInCurrentTerm() {
        return raftLog.zeroTermOnOutOfBounds(raftLog.committed()) == term;
    }

    /**
     * 构造一个读请求的回复。
     *
     * <p>请求来自本节点时只记录状态，不产生网络消息。</p>
     */
    private Message responseToReadIndexReq(Message req, long readIndex) {
        if (req.getFrom() == NONE || req.getFrom() == id) {
            List<Entry> entries = req.getEntries();
            byte[] ctx = entries == null || entries.isEmpty() ? new byte[0] : entries.get(0).getData();
            readStates.add(new ReadState(readIndex, ctx));
            return new Message();
        }
        return new Message().setType(MessageType.MSG_READ_INDEX_RESP)
                .setTo(req.getFrom()).setIndex(readIndex).setEntries(req.getEntries());
    }

    /**
     * 记账未提交日志的大小，判断这次提案会不会超限。
     *
     * @return true 表示可以接受
     */
    private boolean increaseUncommittedSize(List<Entry> ents) {
        long s = payloadsSize(ents);
        if (uncommittedSize > 0 && s > 0 && uncommittedSize + s > maxUncommittedSize) {
            // 未提交尾巴为空时允许任意大小的提案；
            // 另外 s > 0 的条件保证单条空条目永远能被追加
            return false;
        }
        uncommittedSize += s;
        return true;
    }

    /**
     * 提交之后相应地减少未提交日志的大小。
     */
    void reduceUncommittedSize(long s) {
        if (s > uncommittedSize) {
            // 未提交大小可能算少了但不会算多，饱和到 0 防止下溢
            uncommittedSize = 0;
        } else {
            uncommittedSize -= s;
        }
    }

    /**
     * 释放之前挂起的读请求（本任期第一条日志提交后调用）。
     */
    private void releasePendingReadIndexMessages() {
        if (pendingReadIndexMessages.isEmpty()) {
            return;
        }
        if (!committedEntryInCurrentTerm()) {
            log.error("pending MsgReadIndex should be released only after first commit in current term");
            return;
        }
        List<Message> msgs = new ArrayList<>(pendingReadIndexMessages);
        pendingReadIndexMessages.clear();
        for (Message m : msgs) {
            sendMsgReadIndexResponse(m);
        }
    }

    /**
     * 处理一个读请求。
     */
    private void sendMsgReadIndexResponse(Message m) {
        // 只有一个投票成员时可以直接回答：它已经在本任期内提交过日志，
        // 期间不可能选出别的 leader
        if (trk.isVoter(id) && trk.isSingleton()) {
            Message resp = responseToReadIndexReq(m, raftLog.committed());
            if (resp.getTo() != NONE) {
                send(resp);
            }
            return;
        }

        switch (readOnly.getOption()) {
            case READ_ONLY_SAFE:
                // 需要更多确认，走一轮心跳广播
                readOnly.addRequest(raftLog.committed(), m);
                // 本节点自己确认
                readOnly.recvAck(id, readOnly.heartbeatCtx());
                bcastHeartbeat();
                break;
            case READ_ONLY_LEASE_BASED:
            default:
                Message resp = responseToReadIndexReq(m, raftLog.committed());
                if (resp.getTo() != NONE) {
                    send(resp);
                }
                break;
        }
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /**
     * 拉票请求对应的响应类型。
     */
    private static MessageType voteRespMsgType(MessageType voteMsgType) {
        switch (voteMsgType) {
            case MSG_VOTE:
                return MessageType.MSG_VOTE_RESP;
            case MSG_PRE_VOTE:
                return MessageType.MSG_PRE_VOTE_RESP;
            default:
                throw new RaftException("not a vote message: " + voteMsgType);
        }
    }

    /**
     * 一组日志条目的负载总字节数。
     */
    public static long payloadsSize(List<Entry> ents) {
        if (ents == null) {
            return 0;
        }
        long size = 0;
        for (Entry e : ents) {
            size += e.getData().length;
        }
        return size;
    }

    /**
     * 一组日志条目的编码后总字节数，等价于 etcd 的 {@code entsSize}。
     */
    public static long entsSize(List<Entry> ents) {
        return RaftCodec.entriesSize(ents);
    }

    /**
     * 空配置。
     */
    private static ConfState emptyConfState() {
        return new ConfState(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), false);
    }

    @Override
    public String toString() {
        return "Raft{id=" + Long.toHexString(id) + ", term=" + term + ", state=" + state
                + ", lead=" + Long.toHexString(lead) + ", commit=" + raftLog.committed()
                + ", applied=" + raftLog.applied() + "}";
    }
}
