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

import io.edap.raft.log.RaftLog;
import io.edap.raft.protocol.ConfChange;
import io.edap.raft.protocol.ConfChangeCodec;
import io.edap.raft.protocol.ConfChangeI;
import io.edap.raft.protocol.ConfChangeSingle;
import io.edap.raft.protocol.ConfChangeTransition;
import io.edap.raft.protocol.ConfChangeType;
import io.edap.raft.protocol.ConfChangeV2;
import io.edap.raft.protocol.ConfState;
import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.EntryType;
import io.edap.raft.protocol.HardState;
import io.edap.raft.protocol.Message;
import io.edap.raft.protocol.MessageType;
import io.edap.raft.protocol.Snapshot;
import io.edap.raft.tracker.Config;
import io.edap.raft.tracker.Progress;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 非线程安全的 raft 驱动层，等价于 etcd 的 {@code raft.RawNode}。
 *
 * <p>{@link Raft} 只负责状态机，产出的日志、消息要由应用层落盘、发出去、应用到状态机。
 * 这个类把「产出什么、什么时候产出」的时机管起来：应用层调用 {@link #ready()} 拿到一批
 * 工作，处理完之后调用 {@link #advance(Ready)} 通知 raft 可以继续。</p>
 *
 * <p>用法：</p>
 * <pre>
 * RawNode rn = new RawNode(config);
 * while (true) {
 *     if (!rn.hasReady()) {
 *         // 等一会儿再看
 *         continue;
 *     }
 *     Ready rd = rn.ready();
 *     saveToDisk(rd.getHardState(), rd.getEntries(), rd.getSnapshot());
 *     sendMessages(rd.getMessages());
 *     applyToStateMachine(rd.getCommittedEntries());
 *     rn.advance(rd);
 * }
 * </pre>
 *
 * <p>与 etcd 不同的是这里只支持同步落盘：etcd v3.6 的
 * {@code AsyncStorageWrites}（把落盘与状态机应用拆到独立线程）没有移植。</p>
 */
public class RawNode {

    /** raft 状态机 */
    private final Raft      raft;

    /** 上一次交给应用层的软状态 */
    private SoftState        prevSoftSt;

    /** 上一次交给应用层的硬状态 */
    private HardState        prevHardSt;

    /**
     * {@link #advance(Ready)} 时要回灌给状态机的消息。
     *
     * <p>同步模式下，落在自己身上的消息（投票确认、日志落盘确认）要等落盘完成
     * 之后才交给状态机，这里先记下来。</p>
     */
    private final List<Message> stepsOnAdvance = new ArrayList<>();

    /**
     * 框架落盘模式下，落盘完成之后要回灌给状态机的消息。
     *
     * <p>与 {@link #stepsOnAdvance} 的区别只在时机：这一批消息必须等到
     * 硬状态与日志真的落盘之后才能交给状态机 —— 落在自己身上的投票确认
     * 与日志落盘确认都属于这一批。</p>
     */
    private final List<Message> stepsOnPersist = new ArrayList<>();

    /**
     * 已经通过落盘门控、可以发出去的消息。
     *
     * <p>框架落盘模式下，确认类消息（投票确认、日志追加确认）不能在
     * 第一个 Ready 里就带出去 —— 它们的意思是「我已经持久化了这些日志」。
     * {@link #takePersistGatedMessages()} 把它们摘下来，等落盘完成之后
     * 用 {@link #releasePersistGatedMessages(List)} 放回来，
     * 下一个 Ready 再带出去。</p>
     */
    private final List<Message> gatedReady = new ArrayList<>();

    /** 是否由框架接管落盘 */
    private final boolean frameworkPersist;

    /** 已提交条目是否由独立的 apply 线程处理 */
    private final boolean separateApplyThread;

    /**
     * 用给定配置创建一个 RawNode
     *
     * @param config 配置，非法时抛出 {@link RaftException}
     */
    public RawNode(RaftConfig config) {
        this.raft = new Raft(config);
        this.prevSoftSt = raft.softState();
        this.prevHardSt = raft.hardState();
        this.frameworkPersist = config.frameworkPersist;
        this.separateApplyThread = config.applyHandler != null;
    }

    /**
     * 用给定的状态机创建一个 RawNode。
     *
     * <p>测试专用：{@link Raft#stepStateMachine(Message)} 是可重写的方法，
     * 测试可以继承 {@link Raft} 重写它，观察状态机实际处理了哪些消息。</p>
     */
    RawNode(Raft raft) {
        this.raft = raft;
        this.prevSoftSt = raft.softState();
        this.prevHardSt = raft.hardState();
        RaftConfig cfg = raft.getRaftConfig();
        this.frameworkPersist = cfg.frameworkPersist;
        this.separateApplyThread = cfg.applyHandler != null;
    }

    /**
     * 是否由框架接管落盘
     */
    public boolean isFrameworkPersist() {
        return frameworkPersist;
    }

    /**
     * 已提交条目的处理器，没有则返回 null
     */
    public ApplyHandler getApplyHandler() {
        return raft.getRaftConfig().applyHandler;
    }

    /**
     * 日志存储
     */
    public io.edap.raft.storage.PersistentStorage getStorage() {
        return raft.getRaftConfig().storage;
    }

    /**
     * 为首次使用初始化状态机：把初始成员作为成员变更条目追加到日志里。
     *
     * <p>等价于 etcd 的 {@code RawNode.Bootstrap}：节点先变成 term 1 的 follower，
     * 再为每个成员追加一条「加入节点」的成员变更，并把提交位置推到这些条目之后。
     * 这些条目仍然留在未落盘区，应用层能从 {@link Ready#getCommittedEntries()}
     * 里看到它们。</p>
     *
     * @throws RaftException 存储里已经有日志，或没有给出任何成员
     */
    public void bootstrap(Peer... peers) {
        if (peers == null || peers.length == 0) {
            throw new RaftException("must provide at least one peer to Bootstrap");
        }
        RaftLog raftLog = raft.getRaftLog();
        if (raftLog.lastIndex() != 0) {
            throw new RaftException("can't bootstrap a nonempty Storage");
        }
        // 还没有落盘任何东西：第一个 Ready 会带上硬状态让应用层持久化
        prevHardSt = new HardState(0, 0, 0);

        raft.becomeFollower(1, Raft.NONE);

        List<Entry> ents = new ArrayList<>(peers.length);
        for (int i = 0; i < peers.length; i++) {
            Peer peer = peers[i];
            ConfChange cc = new ConfChange(ConfChangeType.ADD_NODE, peer.getNodeId(),
                    peer.getContext());
            byte[] data = ConfChangeCodec.marshal(cc).getData();
            ents.add(new Entry(i + 1, 1, EntryType.CONFIG_CHANGE, data));
        }
        raftLog.append(ents);
        raftLog.commitTo(peers.length);

        // 先应用这些变更，应用层调用 campaign 时集群成员就已经就位。
        // 这些节点会被加两次：一次在这里，一次是应用层的 Ready 循环里
        // 调用 applyConfChange。加节点的调用必须都在追加日志之后，
        // 这样复制进度里的 next 才落在这些引导条目之后。
        for (Peer peer : peers) {
            raft.applyConfChange(new ConfChangeV2(ConfChangeTransition.AUTO,
                    Collections.singletonList(
                            new ConfChangeSingle(ConfChangeType.ADD_NODE, peer.getNodeId())),
                    new byte[0]));
        }
    }

    /**
     * 底层的 raft 状态机
     */
    public Raft getRaft() {
        return raft;
    }

    /**
     * 推进内部逻辑时钟一个 tick
     */
    public void tick() {
        raft.tick();
    }

    /**
     * 推进内部逻辑时钟一个 tick，但不执行任何状态机处理。
     *
     * <p>当所有节点都处于已知状态时，可以用它代替 {@link #tick()}，
     * 省掉周期性的心跳与选举。慎用：它绕过了 raft 状态机。</p>
     */
    public void tickQuiesced() {
        raft.tickQuiesced();
    }

    /**
     * 发起选举，把自己变成候选者
     */
    public boolean campaign() {
        return raft.step(new Message().setType(MessageType.MSG_HUP));
    }

    /**
     * 提议一条数据
     */
    public boolean propose(byte[] data) {
        return raft.step(new Message()
                .setType(MessageType.MSG_PROP)
                .setFrom(raft.getId())
                .setEntries(Collections.singletonList(
                        new Entry(0, 0, EntryType.NORMAL, data))));
    }

    /**
     * 提议一次成员变更
     */
    public boolean proposeConfChange(ConfChangeI cc) {
        return raft.step(confChangeToMsg(cc));
    }

    /**
     * 应用一次成员变更。
     *
     * <p>应用层在 {@link Ready#getCommittedEntries()} 里看到成员变更条目时调用。
     * 如果决定拒绝这次变更，就不要调用本方法。</p>
     *
     * @return 变更之后的集群配置，需要记录到快照里
     */
    public ConfState applyConfChange(ConfChangeI cc) {
        return raft.applyConfChange(cc.asV2());
    }

    /**
     * 用消息推进状态机
     *
     * @throws RaftException 消息是本地消息但来源不对，或者响应消息来自未知节点时抛出
     */
    public boolean step(Message m) {
        if (m == null) {
            throw new RaftException("cannot step with nil message");
        }
        // 忽略从网络上收到的、只应该在本节点内部产生的消息
        if (RaftUtil.isLocalMsg(m.getType()) && !RaftUtil.isLocalMsgTarget(m.getFrom())) {
            throw new RaftException("raft: cannot step raft local message");
        }
        // 响应消息必须来自已知节点
        if (RaftUtil.isResponseMsg(m.getType())
                && !RaftUtil.isLocalMsgTarget(m.getFrom())
                && raft.getTracker().getProgress().get(m.getFrom()) == null) {
            throw new RaftException("raft: cannot step as peer not found");
        }
        return raft.step(m);
    }

    /**
     * 取出应用层需要处理的一批工作，并接管这批工作的「所有权」。
     *
     * <p>返回的 Ready 必须被处理，处理完之后调用 {@link #advance(Ready)}。</p>
     */
    public Ready ready() {
        Ready rd = readyWithoutAccept();
        acceptReady(rd);
        return rd;
    }

    /**
     * 只读地组装一个 Ready，不接管它的所有权。
     *
     * <p>调用方没有义务处理这个 Ready，可以先看一眼再决定要不要
     * {@link #acceptReady(Ready)}。</p>
     */
    public Ready readyWithoutAccept() {
        RaftLog raftLog = raft.getRaftLog();

        Ready rd = new Ready();
        rd.setEntries(raftLog.nextUnstableEnts());
        rd.setCommittedEntries(raftLog.nextCommittedEnts(applyUnstableEntries()));
        rd.setMessages(new ArrayList<>(raft.getMessages()));

        SoftState softSt = raft.softState();
        if (!isSoftStateEqual(softSt, prevSoftSt)) {
            // 只在软状态变化时才分配
            rd.setSoftState(softSt);
        }
        HardState hardSt = raft.hardState();
        if (!RaftUtil.isHardStateEqual(hardSt, prevHardSt)) {
            rd.setHardState(hardSt);
        }
        if (raftLog.hasNextUnstableSnapshot()) {
            rd.setSnapshot(raftLog.nextUnstableSnapshot());
        }
        if (!raft.getReadStates().isEmpty()) {
            rd.setReadStates(new ArrayList<>(raft.getReadStates()));
        }
        rd.setMustSync(mustSync(raft.hardState(), prevHardSt, rd.getEntries().size()));

        // 同步落盘模式下，落在自己身上的消息要等未落盘状态持久化之后再处理，
        // 因此这里不把它们放进 Messages，而是记到 stepsOnAdvance 里。
        if (frameworkPersist) {
            // 框架落盘：确认类消息等落盘完成之后再带出去，
            // 先带出去的是已经通过门控的那些。
            rd.getMessages().addAll(gatedReady);
        } else {
            for (Message m : raft.getMessagesAfterAppend()) {
                if (m.getTo() != raft.getId()) {
                    rd.getMessages().add(m);
                }
            }
        }

        return rd;
    }

    /**
     * 取出这一批「必须等落盘才能发出去」的确认消息。
     *
     * <p>框架落盘模式下，确认类消息（投票确认、日志追加确认）不能跟着
     * 第一个 Ready 一起发出去：它们的意思是「我已经持久化了这些日志」。
     * 这个方法把它们从状态机里摘出来，等落盘完成之后用
     * {@link #releasePersistGatedMessages(List)} 放回去，
     * 下一个 Ready 会自动带上它们。</p>
     *
     * <p>其余消息（复制、心跳）不带任何持久化承诺，先发出去可以让对端
     * 立刻开始落盘，与本节点的落盘重叠 —— 两个 fsync 不串行，
     * 端到端延迟少一个落盘周期。</p>
     *
     * @return 摘出来的确认消息（发给别人的那些；落在自己身上的由
     *         {@link #acceptReady(Ready, List)} 处理）
     */
    public List<Message> takePersistGatedMessages() {
        List<Message> gated = new ArrayList<>();
        for (Message m : raft.getMessagesAfterAppend()) {
            if (m.getTo() != raft.getId()) {
                gated.add(m);
            }
        }
        return gated;
    }

    /**
     * 落盘完成之后，把之前摘出来的确认消息放回去。
     *
     * <p>它们会在下一个 Ready 里被带出去 —— 那时候对应的日志已经落盘，
     * 「我已经持久化了这些日志」这句话才算成立。</p>
     */
    public void releasePersistGatedMessages(List<Message> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return;
        }
        gatedReady.addAll(msgs);
    }

    /**
     * 接管一个 Ready：清空已经交付出去的状态，记录 Advance 时要回灌的消息。
     *
     * <p>从本方法调用到下一次 {@link #ready()} 之间，RawNode 的状态不允许再被改动。</p>
     */
    public void acceptReady(Ready rd) {
        acceptReady(rd, rd.getCommittedEntries());
    }

    /**
     * 接管一个 Ready，并指定本次要交给应用层的已提交条目。
     *
     * <p>与 {@link #acceptReady(Ready)} 的区别：已提交条目可以与 Ready 里的分开传。
     * 独立 apply 线程模式下，Ready 里不再带已提交条目（应用层不需要它们），
     * 但 {@code acceptApplying} 仍然要用它们推进 applying 指针，
     * 所以由调用方单独传进来。</p>
     *
     * @param committedEnts 本次交给应用层的已提交条目
     */
    public void acceptReady(Ready rd, List<Entry> committedEnts) {
        if (rd.getSoftState() != null) {
            prevSoftSt = rd.getSoftState();
        }
        if (!RaftUtil.isEmptyHardState(rd.getHardState())) {
            prevHardSt = rd.getHardState();
        }
        if (!rd.getReadStates().isEmpty()) {
            raft.clearReadStates();
        }
        if (!stepsOnAdvance.isEmpty() || !stepsOnPersist.isEmpty()) {
            throw new RaftException("two accepted Ready structs without call to Advance");
        }
        // 落在自己身上的消息：投票确认、日志落盘确认。
        // 框架落盘模式下它们要等落盘完成之后才交给状态机。
        List<Message> persistMsgs = new ArrayList<>();
        for (Message m : raft.getMessagesAfterAppend()) {
            if (m.getTo() == raft.getId()) {
                persistMsgs.add(m);
            }
        }
        if (needStorageAppendRespMsg(rd)) {
            persistMsgs.add(newStorageAppendRespMsg(rd));
        }
        if (frameworkPersist) {
            stepsOnPersist.addAll(persistMsgs);
        } else {
            stepsOnAdvance.addAll(persistMsgs);
        }
        // 独立 apply 线程模式下，「已应用到状态机」的通知由 apply 线程自己发
        if (!separateApplyThread && needStorageApplyRespMsg(committedEnts)) {
            stepsOnAdvance.add(newStorageApplyRespMsg(committedEnts));
        }

        raft.clearMessages();
        raft.clearMessagesAfterAppend();
        // 已经通过落盘门控的消息随这个 Ready 交出去了
        gatedReady.clear();
        raft.getRaftLog().acceptUnstable();
        if (committedEnts != null && !committedEnts.isEmpty()) {
            long index = committedEnts.get(committedEnts.size() - 1).getIndex();
            raft.getRaftLog().acceptApplying(index, Raft.entsSize(committedEnts), applyUnstableEntries());
        }
    }

    /**
     * 通知 raft：硬状态与日志已经落盘。
     *
     * <p>只有框架落盘模式（{@link RaftConfig#frameworkPersist}）才需要调用：
     * 这一步把「落盘相关」的消息回灌给状态机 —— 落在自己身上的投票确认、
     * 日志落盘确认。它们的含义是「我现在可以安全地承认自己投的票、
     * 承认自己日志里的条目已经持久化了」，必须等到真的落盘之后才成立。</p>
     */
    public void persisted(Ready rd) {
        if (!frameworkPersist) {
            throw new RaftException("persisted() is only meaningful in framework persist mode");
        }
        for (int i = 0; i < stepsOnPersist.size(); i++) {
            raft.step(stepsOnPersist.get(i));
            stepsOnPersist.set(i, null);
        }
        stepsOnPersist.clear();
    }

    /**
     * 通知 raft：上一个 Ready 已经处理完了，可以继续。
     *
     * <p>这一步会把「落在自己身上的消息」回灌给状态机，比如自己给自己投票的确认、
     * 日志落盘完成的确认、状态机应用完成的确认。</p>
     */
    public void advance(Ready rd) {
        for (int i = 0; i < stepsOnAdvance.size(); i++) {
            raft.step(stepsOnAdvance.get(i));
            stepsOnAdvance.set(i, null);
        }
        stepsOnAdvance.clear();
    }

    /**
     * 是否还有待处理的工作
     */
    public boolean hasReady() {
        SoftState softSt = raft.softState();
        if (!isSoftStateEqual(softSt, prevSoftSt)) {
            return true;
        }
        HardState hardSt = raft.hardState();
        if (!RaftUtil.isEmptyHardState(hardSt) && !RaftUtil.isHardStateEqual(hardSt, prevHardSt)) {
            return true;
        }
        if (raft.getRaftLog().hasNextUnstableSnapshot()) {
            return true;
        }
        if (!raft.getMessages().isEmpty() || !raft.getMessagesAfterAppend().isEmpty()) {
            return true;
        }
        if (!gatedReady.isEmpty()) {
            // 已经通过落盘门控、等着发出去的消息
            return true;
        }
        if (raft.getRaftLog().hasNextUnstableEnts()
                || raft.getRaftLog().hasNextCommittedEnts(applyUnstableEntries())) {
            return true;
        }
        if (!raft.getReadStates().isEmpty()) {
            return true;
        }
        return false;
    }

    /**
     * 当前的完整状态
     */
    public Status status() {
        BasicStatus basic = basicStatus();
        Map<Long, Progress> progress = new LinkedHashMap<>();
        if (raft.getState() == RaftRole.LEADER) {
            raft.getTracker().visit((id, pr) -> progress.put(id, pr));
        }
        Config config = raft.getConfig().clone();
        return new Status(basic, config, progress);
    }

    /**
     * 当前的基本状态
     */
    public BasicStatus basicStatus() {
        return new BasicStatus(raft.getId(), raft.hardState(), raft.softState(),
                raft.getRaftLog().applied(), raft.getLeadTransferee());
    }

    /**
     * 报告某个节点不可达
     */
    public void reportUnreachable(long id) {
        raft.step(new Message().setType(MessageType.MSG_UNREACHABLE).setFrom(id));
    }

    /**
     * 报告快照的发送结果
     */
    public void reportSnapshot(long id, SnapshotStatus status) {
        boolean reject = status == SnapshotStatus.FAILURE;
        raft.step(new Message().setType(MessageType.MSG_SNAP_STATUS).setFrom(id).setReject(reject));
    }

    /**
     * 尝试把领导权转移给指定节点
     */
    public void transferLeader(long transferee) {
        raft.step(new Message().setType(MessageType.MSG_TRANSFER_LEADER).setFrom(transferee));
    }

    /**
     * 忘记当前的 leader，变回一个没有 leader 的 follower。
     *
     * <p>用于 PreVote + CheckQuorum 场景：外部已经确认 leader 挂了，
     * 立刻放弃 leader 让新的选举可以马上进行。</p>
     */
    public boolean forgetLeader() {
        return raft.step(new Message().setType(MessageType.MSG_FORGET_LEADER));
    }

    /**
     * 发起一次线性一致读请求。
     *
     * <p>结果会出现在 {@link Ready#getReadStates()} 里。</p>
     */
    public void readIndex(byte[] rctx) {
        raft.step(new Message().setType(MessageType.MSG_READ_INDEX)
                .setEntries(Collections.singletonList(new Entry(0, 0, EntryType.NORMAL, rctx))));
    }

    /**
     * 软状态是否相等
     */
    private static boolean isSoftStateEqual(SoftState a, SoftState b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.getLead() == b.getLead() && a.getRaftState() == b.getRaftState();
    }

    /**
     * 硬状态与日志条数是否意味着必须同步落盘。
     *
     * <p>所有节点都要持久化的内容：term、vote、日志条目。</p>
     */
    public static boolean mustSync(HardState st, HardState prevSt, int entsNum) {
        return entsNum != 0
                || st.getVote() != prevSt.getVote()
                || st.getTerm() != prevSt.getTerm();
    }

    /**
     * raft 是否需要知道「未落盘的日志已经落盘了」。
     *
     * <p>注意这里看的是「有没有未落盘或在途的日志」而不是「这个 Ready 里有没有日志」，
     * 原因见 {@link #newStorageAppendRespMsg(Ready)} 里的说明。</p>
     */
    private boolean needStorageAppendRespMsg(Ready rd) {
        return raft.getRaftLog().hasNextOrInProgressUnstableEnts()
                || !RaftUtil.isEmptySnap(rd.getSnapshot());
    }

    /**
     * raft 是否需要知道「已提交的日志已经应用到状态机了」
     */
    private static boolean needStorageApplyRespMsg(List<Entry> committedEnts) {
        return committedEnts != null && !committedEnts.isEmpty();
    }

    /**
     * 构造「日志已经落盘」的通知消息。
     *
     * <p>消息里带上当前日志的最后一条的 (index, term)。收到通知时，如果这两个值
     * 与未落盘区对得上，未落盘区就可以被截断。</p>
     *
     * <p>之所以带上「当前」的最后一条而不是「这个 Ready 里的最后一条」：如果这个
     * Ready 里没有日志，也要能证明当前的 (index, term) 在当前任期内是对的，
     * 否则未落盘区永远等不到被截断的机会。</p>
     */
    private Message newStorageAppendRespMsg(Ready rd) {
        Message m = new Message()
                .setType(MessageType.MSG_STORAGE_APPEND_RESP)
                .setTo(raft.getId())
                .setFrom(RaftUtil.LOCAL_APPEND_THREAD)
                .setTerm(raft.getTerm());
        if (raft.getRaftLog().hasNextOrInProgressUnstableEnts()) {
            long lastIndex = raft.getRaftLog().lastIndex();
            m.setIndex(lastIndex);
            m.setLogTerm(raft.getRaftLog().lastTerm());
        }
        if (!RaftUtil.isEmptySnap(rd.getSnapshot())) {
            m.setSnapshot(rd.getSnapshot());
        }
        return m;
    }

    /**
     * 构造「已提交的日志已经应用到状态机」的通知消息
     */
    private Message newStorageApplyRespMsg(List<Entry> ents) {
        return new Message()
                .setType(MessageType.MSG_STORAGE_APPLY_RESP)
                .setTo(raft.getId())
                .setFrom(RaftUtil.LOCAL_APPLY_THREAD)
                .setEntries(ents);
    }

    /**
     * 未落盘的条目是否可以先应用到状态机。
     *
     * <p>同步落盘模式下可以：应用层拿到 Ready 时日志已经落盘了。</p>
     */
    private boolean applyUnstableEntries() {
        return true;
    }

    /**
     * 把成员变更打包成一条提案消息
     */
    public static Message confChangeToMsg(ConfChangeI cc) {
        ConfChangeCodec.EntryTypeAndData typeAndData = ConfChangeCodec.marshal(cc);
        return new Message()
                .setType(MessageType.MSG_PROP)
                .setEntries(Collections.singletonList(
                        new Entry(0, 0, typeAndData.getType(), typeAndData.getData())));
    }

    /**
     * 便捷方法：把 v2 成员变更打包成提案消息
     */
    public static Message confChangeToMsg(ConfChangeV2 cc) {
        return confChangeToMsg((ConfChangeI) cc);
    }

    /**
     * 便捷方法：直接应用一次 v2 成员变更
     */
    public ConfState applyConfChange(ConfChangeV2 cc) {
        return raft.applyConfChange(cc);
    }

    /**
     * 快照（供测试与调试使用）
     */
    public Snapshot snapshot() {
        return raft.getRaftLog().snapshot();
    }

    /**
     * 直接设置带随机化的选举超时，绕过随机计算。
     *
     * <p>测试里需要确定性的超时值时使用。</p>
     */
    public void setRandomizedElectionTimeout(int v) {
        raft.setRandomizedElectionTimeout(v);
    }
}
