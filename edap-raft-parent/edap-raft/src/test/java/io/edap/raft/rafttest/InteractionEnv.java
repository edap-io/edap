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
import io.edap.raft.RaftException;
import io.edap.raft.RaftUtil;
import io.edap.raft.RawNode;
import io.edap.raft.ReadOnlyOption;
import io.edap.raft.Ready;
import io.edap.raft.Status;
import io.edap.raft.protocol.ConfChange;
import io.edap.raft.protocol.ConfChangeCodec;
import io.edap.raft.protocol.ConfChangeI;
import io.edap.raft.protocol.ConfChangeSingle;
import io.edap.raft.protocol.ConfChangeTransition;
import io.edap.raft.protocol.ConfChangeV2;
import io.edap.raft.protocol.ConfState;
import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.HardState;
import io.edap.raft.protocol.Message;
import io.edap.raft.protocol.MessageType;
import io.edap.raft.protocol.Snapshot;
import io.edap.raft.protocol.SnapshotMetadata;
import io.edap.raft.storage.MemoryStorage;
import io.edap.raft.tracker.Progress;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 交互式测试的环境，等价于 etcd 的 {@code rafttest.InteractionEnv}。
 *
 * <p>etcd 用「数据驱动测试」的方式描述一串操作：每个用例是一段命令
 * （{@code add-nodes} / {@code campaign} / {@code stabilize} …）加上期望输出，
 * 测试框架把命令跑一遍，把实际输出与文件里写死的期望输出逐字比对。
 * 这样一来，raft 的行为（尤其是日志与消息的先后顺序）被完整地钉住了。</p>
 *
 * <p>用法：</p>
 * <pre>
 * InteractionEnv env = new InteractionEnv(new InteractionOpts());
 * String out = env.handle(testData);
 * </pre>
 */
public class InteractionEnv {

    /**
     * 环境的可选项，等价于 etcd 的 {@code rafttest.InteractionOpts}。
     */
    public static class InteractionOpts {

        /**
         * 节点配置好之后的回调，可以用来改配置。
         *
         * <p>不允许改节点的 id，也不允许设置日志输出器——输出器由环境统一接管，
         * 否则日志就收集不到了。</p>
         */
        public Consumer<RaftConfig> onConfig;

        /**
         * 设置随机化选举超时的回调。
         *
         * <p>默认直接用 {@link RawNode#setRandomizedElectionTimeout(int)}。</p>
         */
        public BiConsumer<RawNode, Integer> setRandomizedElectionTimeout =
                (rawNode, timeout) -> rawNode.setRandomizedElectionTimeout(timeout);
    }

    /** 条目的默认格式化方式：{@code %q} */
    private static final RaftUtil.EntryFormatter DEFAULT_ENTRY_FORMATTER = RaftUtil::quote;

    /**
     * 提案被丢弃时的错误信息，与 etcd 的 {@code raft.ErrProposalDropped} 一致。
     */
    private static final String PROPOSAL_DROPPED = "raft proposal dropped";

    /** 环境选项 */
    private final InteractionOpts options;

    /** 集群里的节点 */
    private final List<InteractionNode> nodes = new ArrayList<>();

    /** 在途的消息 */
    private final List<Message> messages = new ArrayList<>();

    /** 输出缓冲区 */
    private final RedirectLogger output = new RedirectLogger();

    public InteractionEnv() {
        this(new InteractionOpts());
    }

    public InteractionEnv(InteractionOpts options) {
        this.options = options == null ? new InteractionOpts() : options;
    }

    // ------------------------------------------------------------------
    // 命令分派
    // ------------------------------------------------------------------

    /**
     * 执行一条命令，返回这次执行产生的输出。
     *
     * <p>命令本身用错了（参数不合法之类）会抛 {@link DataDrivenException}，
     * 直接让用例失败；而 raft 本身报的错会被写进输出里，
     * 与 etcd 的行为一致。</p>
     *
     * @param d 用例
     * @return 输出文本，什么都没发生时是 {@code "ok"}
     */
    public String handle(TestData d) {
        output.reset();
        String error = dispatch(d);
        if (error != null) {
            if (output.isQuiet()) {
                return error;
            }
            output.write(error);
        }
        if (output.length() == 0) {
            return "ok";
        }
        return output.string();
    }

    private String dispatch(TestData d) {
        switch (d.getCmd()) {
            case "_breakpoint":
                // 调试用的空命令
                return null;
            case "add-nodes":
                return handleAddNodes(d);
            case "campaign":
                return handleCampaign(d);
            case "compact":
                return handleCompact(d);
            case "deliver-msgs":
                return handleDeliverMsgs(d);
            case "process-ready":
                return handleProcessReady(d);
            case "process-append-thread":
                return handleProcessAppendThread(d);
            case "process-apply-thread":
                return handleProcessApplyThread(d);
            case "log-level":
                return handleLogLevel(d);
            case "raft-log":
                return handleRaftLog(d);
            case "raft-state":
                return handleRaftState();
            case "set-randomized-election-timeout":
                return handleSetRandomizedElectionTimeout(d);
            case "stabilize":
                return handleStabilize(d);
            case "status":
                return handleStatus(d);
            case "tick-election":
                return handleTickElection(d);
            case "tick-heartbeat":
                return handleTickHeartbeat(d);
            case "transfer-leadership":
                return handleTransferLeadership(d);
            case "forget-leader":
                return handleForgetLeader(d);
            case "send-snapshot":
                return handleSendSnapshot(d);
            case "propose":
                return handlePropose(d);
            case "propose-conf-change":
                return handleProposeConfChange(d);
            case "report-unreachable":
                return handleReportUnreachable(d);
            default:
                return "unknown command";
        }
    }

    // ------------------------------------------------------------------
    // 各条命令的实现
    // ------------------------------------------------------------------

    /**
     * 新增节点。
     *
     * <p>形如 {@code add-nodes 3 voters=(1,2,3) learners=(4) index=2 content=foo}。
     * 带了 {@code index} 就是「引导」：所有节点都从同一份快照起步，
     * 快照里的成员配置就是集群的初始成员。</p>
     */
    private String handleAddNodes(TestData d) {
        int n = firstAsInt(d);
        Snapshot snap = emptySnapshot();
        RaftConfig cfg = raftConfigStub();
        List<Long> voters = new ArrayList<>();
        List<Long> learners = new ArrayList<>();
        long index = 0;
        byte[] content = new byte[0];

        for (int a = 1; a < d.getCmdArgs().size(); a++) {
            CmdArg arg = d.getCmdArgs().get(a);
            for (int i = 0; i < arg.getVals().size(); i++) {
                switch (arg.getKey()) {
                    case "voters":
                        voters.add(arg.scanLong(i));
                        break;
                    case "learners":
                        learners.add(arg.scanLong(i));
                        break;
                    case "inflight":
                        cfg.maxInflightMsgs = arg.scanInt(i);
                        break;
                    case "index":
                        index = arg.scanLong(i);
                        cfg.applied = index;
                        break;
                    case "content":
                        content = arg.scanBytes(i);
                        break;
                    case "async-storage-writes":
                        if (arg.scanBool(i)) {
                            throw new DataDrivenException(
                                    "async storage writes are not supported by the Java port");
                        }
                        break;
                    case "prevote":
                        cfg.preVote = arg.scanBool(i);
                        break;
                    case "checkquorum":
                        cfg.checkQuorum = arg.scanBool(i);
                        break;
                    case "max-committed-size-per-ready":
                        cfg.maxCommittedSizePerReady = arg.scanLong(i);
                        break;
                    case "disable-conf-change-validation":
                        cfg.disableConfChangeValidation = arg.scanBool(i);
                        break;
                    case "read-only":
                        String option = arg.scanString(i);
                        if ("safe".equals(option)) {
                            cfg.readOnlyOption = ReadOnlyOption.READ_ONLY_SAFE;
                        } else if ("lease-based".equals(option)) {
                            cfg.readOnlyOption = ReadOnlyOption.READ_ONLY_LEASE_BASED;
                        } else {
                            return "invalid read-only option \"" + option + "\"";
                        }
                        break;
                    case "step-down-on-removal":
                        cfg.stepDownOnRemoval = arg.scanBool(i);
                        break;
                    default:
                        throw new DataDrivenException("unknown command " + arg.getKey());
                }
            }
        }
        snap = new Snapshot(content, new SnapshotMetadata(
                new ConfState(voters, learners, Collections.emptyList(),
                        Collections.emptyList(), false), index, 0));
        return addNodes(n, cfg, snap);
    }

    /**
     * 新增 n 个节点，它们都从给定快照起步。
     *
     * @return 出错时的错误信息，null 表示成功
     */
    private String addNodes(int n, RaftConfig cfg, Snapshot snap) {
        boolean bootstrap = !isEmptySnapshot(snap);
        for (int i = 0; i < n; i++) {
            long id = 1 + nodes.size();
            InteractionStorage storage = new InteractionStorage(new MemoryStorage());
            Snapshot nodeSnap = snap;
            if (bootstrap) {
                // NB: 这里不能是 1，因为 MemoryStorage 在那个位置上的行为不对。
                if (nodeSnap.getMetadata().getIndex() <= 1) {
                    return "index must be specified as > 1 due to bootstrap";
                }
                nodeSnap = new Snapshot(nodeSnap.getData(), new SnapshotMetadata(
                        nodeSnap.getMetadata().getConfState(), nodeSnap.getMetadata().getIndex(), 1));
                storage.applySnapshot(nodeSnap);
                long fi = storage.firstIndex();
                long exp = nodeSnap.getMetadata().getIndex() + 1;
                if (fi != exp) {
                    return "failed to establish first index " + exp + "; got " + fi;
                }
            }

            RaftConfig nodeCfg = copyConfig(cfg);
            nodeCfg.id = id;
            nodeCfg.storage = storage;
            if (options.onConfig != null) {
                options.onConfig.accept(nodeCfg);
                if (nodeCfg.id != id) {
                    return "OnConfig must not change the ID";
                }
            }
            if (nodeCfg.logger != null) {
                return "OnConfig must not set Logger";
            }
            nodeCfg.logger = output;

            RawNode rawNode = new RawNode(nodeCfg);
            InteractionNode node = new InteractionNode(rawNode, storage, nodeCfg);
            node.getHistory().add(nodeSnap);
            nodes.add(node);

            // 节点就位之后，问存储要快照时拿的是节点自己的快照历史。
            final InteractionNode added = node;
            storage.setSnapshotSupplier(() -> {
                List<Snapshot> history = added.getHistory();
                return history.get(history.size() - 1);
            });
        }
        return null;
    }

    /**
     * 让指定节点发起选举
     */
    private String handleCampaign(TestData d) {
        nodes.get(firstAsNodeIdx(d)).getRawNode().campaign();
        return null;
    }

    /**
     * 压缩指定节点的日志，然后打印日志
     */
    private String handleCompact(TestData d) {
        int idx = firstAsNodeIdx(d);
        long newFirstIndex = parseLong(d.getCmdArgs().get(1).getKey());
        nodes.get(idx).getStorage().compact(newFirstIndex);
        raftLog(idx);
        return null;
    }

    /**
     * 把在途消息投递给指定节点（或者丢弃）
     */
    private String handleDeliverMsgs(TestData d) {
        MessageType typ = null;
        List<Recipient> recipients = new ArrayList<>();
        for (CmdArg arg : d.getCmdArgs()) {
            if (arg.getVals().isEmpty()) {
                recipients.add(new Recipient(parseLong(arg.getKey()), false));
            }
            for (int i = 0; i < arg.getVals().size(); i++) {
                switch (arg.getKey()) {
                    case "drop":
                        long id = arg.scanLong(i);
                        for (Recipient r : recipients) {
                            if (r.getId() == id) {
                                throw new DataDrivenException(
                                        "can't both deliver and drop msgs to " + id);
                            }
                        }
                        recipients.add(new Recipient(id, true));
                        break;
                    case "type":
                        String name = arg.scanString(i);
                        MessageType t;
                        try {
                            t = MessageType.ofGoName(name);
                        } catch (RaftException e) {
                            throw new DataDrivenException("unknown message type " + name);
                        }
                        typ = t;
                        break;
                    default:
                        throw new DataDrivenException("unknown command " + arg.getKey());
                }
            }
        }
        if (deliverMsgs(typ, recipients) == 0) {
            output.write("no messages\n");
        }
        return null;
    }

    /**
     * 处理指定节点的 Ready
     */
    private String handleProcessReady(TestData d) {
        List<Integer> idxs = nodeIdxs(d);
        for (Integer idx : idxs) {
            if (idxs.size() > 1) {
                output.write("> " + (idx + 1) + " handling Ready\n");
                withIndent(() -> processReady(idx));
            } else {
                processReady(idx);
            }
        }
        return null;
    }

    /**
     * 处理指定节点的本地追加线程消息（Java 侧不支持异步落盘，这里没有活干）
     */
    private String handleProcessAppendThread(TestData d) {
        List<Integer> idxs = nodeIdxs(d);
        for (Integer idx : idxs) {
            if (idxs.size() > 1) {
                output.write("> " + (idx + 1) + " processing append thread\n");
                withIndent(() -> processAppendThread(idx));
            } else {
                processAppendThread(idx);
            }
        }
        return null;
    }

    /**
     * 处理指定节点的本地应用线程消息（Java 侧不支持异步落盘，这里没有活干）
     */
    private String handleProcessApplyThread(TestData d) {
        List<Integer> idxs = nodeIdxs(d);
        for (Integer idx : idxs) {
            if (idxs.size() > 1) {
                output.write("> " + (idx + 1) + " processing apply thread\n");
                withIndent(() -> processApplyThread(idx));
            } else {
                processApplyThread(idx);
            }
        }
        return null;
    }

    /**
     * 设置日志级别
     */
    private String handleLogLevel(TestData d) {
        return handleLogLevelName(d.getCmdArgs().get(0).getKey());
    }

    /**
     * 打印指定节点的日志
     */
    private String handleRaftLog(TestData d) {
        raftLog(firstAsNodeIdx(d));
        return null;
    }

    /**
     * 打印所有节点的角色状态
     */
    private String handleRaftState() {
        for (InteractionNode n : nodes) {
            Status st = n.getRawNode().status();
            String voterStatus = isVoter(st) ? "(Voter)" : "(Non-Voter)";
            output.write(st.getId() + ": " + st.getSoftState().getRaftState() + " " + voterStatus
                    + " Term:" + st.getHardState().getTerm()
                    + " Lead:" + st.getSoftState().getLead() + "\n");
        }
        return null;
    }

    /**
     * 设置随机化选举超时
     */
    private String handleSetRandomizedElectionTimeout(TestData d) {
        int idx = firstAsNodeIdx(d);
        CmdArg arg = scanArgs(d, "timeout");
        if (arg.getVals().isEmpty()) {
            throw new DataDrivenException("cannot find value for argument timeout");
        }
        int timeout = arg.scanInt(0);
        if (timeout == 0) {
            throw new DataDrivenException("timeout must not be zero");
        }
        options.setRandomizedElectionTimeout.accept(nodes.get(idx).getRawNode(), timeout);
        return null;
    }

    /**
     * 反复处理 Ready、投递消息，直到集群不再有事情可做
     */
    private String handleStabilize(TestData d) {
        List<Integer> idxs = nodeIdxs(d);
        int oldLevel = output.getLevel();
        try {
            for (CmdArg arg : d.getCmdArgs()) {
                for (int i = 0; i < arg.getVals().size(); i++) {
                    if ("log-level".equals(arg.getKey())) {
                        String err = handleLogLevelName(arg.scanString(i));
                        if (err != null) {
                            return err;
                        }
                    }
                }
            }
            stabilize(idxs);
            return null;
        } finally {
            // 稳定化过程中的临时日志级别只在这一次命令里生效
            output.setLevel(oldLevel);
        }
    }

    /**
     * 打印指定节点的复制进度
     */
    private String handleStatus(TestData d) {
        Status st = nodes.get(firstAsNodeIdx(d)).getRawNode().status();
        Map<Long, Progress> progress = st.getProgress();
        for (Map.Entry<Long, Progress> e : progress.entrySet()) {
            output.write(e.getKey() + ": " + e.getValue() + "\n");
        }
        return null;
    }

    /**
     * 让指定节点走一个选举超时
     */
    private String handleTickElection(TestData d) {
        int idx = firstAsNodeIdx(d);
        tick(idx, nodes.get(idx).getConfig().electionTick);
        return null;
    }

    /**
     * 让指定节点走一个心跳间隔
     */
    private String handleTickHeartbeat(TestData d) {
        int idx = firstAsNodeIdx(d);
        tick(idx, nodes.get(idx).getConfig().heartbeatTick);
        return null;
    }

    /**
     * 发起领导权转移
     */
    private String handleTransferLeadership(TestData d) {
        long from = scanArgs(d, "from").scanLong(0);
        long to = scanArgs(d, "to").scanLong(0);
        if (from == 0 || from > nodes.size()) {
            throw new DataDrivenException("expected valid \"from\" argument");
        }
        if (to == 0 || to > nodes.size()) {
            throw new DataDrivenException("expected valid \"to\" argument");
        }
        nodes.get((int) from - 1).getRawNode().transferLeader(to);
        return null;
    }

    /**
     * 让指定节点忘记当前 leader
     */
    private String handleForgetLeader(TestData d) {
        nodes.get(firstAsNodeIdx(d)).getRawNode().forgetLeader();
        return null;
    }

    /**
     * 手工构造一条快照消息放进在途队列
     */
    private String handleSendSnapshot(TestData d) {
        List<Integer> idxs = nodeIdxs(d);
        if (idxs.size() != 2) {
            throw new DataDrivenException("must specify exactly two node indexes");
        }
        sendSnapshot(idxs.get(0), idxs.get(1));
        return null;
    }

    /**
     * 提议一条普通日志
     */
    private String handlePropose(TestData d) {
        int idx = firstAsNodeIdx(d);
        if (d.getCmdArgs().size() != 2 || !d.getCmdArgs().get(1).getVals().isEmpty()) {
            throw new DataDrivenException("expected exactly one key with no vals: "
                    + d.getCmdArgs().subList(1, d.getCmdArgs().size()));
        }
        boolean ok = nodes.get(idx).getRawNode().propose(
                d.getCmdArgs().get(1).getKey().getBytes(StandardCharsets.UTF_8));
        return ok ? null : PROPOSAL_DROPPED;
    }

    /**
     * 提议一次成员变更
     */
    private String handleProposeConfChange(TestData d) {
        int idx = firstAsNodeIdx(d);
        boolean v1 = false;
        ConfChangeTransition transition = ConfChangeTransition.AUTO;
        for (int a = 1; a < d.getCmdArgs().size(); a++) {
            CmdArg arg = d.getCmdArgs().get(a);
            for (String val : arg.getVals()) {
                switch (arg.getKey()) {
                    case "v1":
                        try {
                            v1 = parseBool(val);
                        } catch (IllegalArgumentException e) {
                            return e.getMessage();
                        }
                        break;
                    case "transition":
                        switch (val) {
                            case "auto":
                                transition = ConfChangeTransition.AUTO;
                                break;
                            case "implicit":
                                transition = ConfChangeTransition.JOINT_IMPLICIT;
                                break;
                            case "explicit":
                                transition = ConfChangeTransition.JOINT_EXPLICIT;
                                break;
                            default:
                                return "unknown transition " + val;
                        }
                        break;
                    default:
                        return "unknown command " + arg.getKey();
                }
            }
        }

        List<ConfChangeSingle> ccs;
        try {
            ccs = ConfChangeCodec.changesFromString(d.getInput());
        } catch (RuntimeException e) {
            return e.getMessage();
        }

        ConfChangeI change;
        if (v1) {
            if (ccs.size() > 1 || transition != ConfChangeTransition.AUTO) {
                return "v1 conf change can only have one operation and no transition";
            }
            change = new ConfChange(ccs.get(0).getType(), ccs.get(0).getNodeId(), new byte[0]);
        } else {
            change = new ConfChangeV2(transition, ccs, new byte[0]);
        }
        boolean ok = nodes.get(idx).getRawNode().proposeConfChange(change);
        return ok ? null : PROPOSAL_DROPPED;
    }

    /**
     * 报告某个节点不可达
     */
    private String handleReportUnreachable(TestData d) {
        List<Integer> sl = nodeIdxs(d);
        if (sl.size() != 2) {
            return "must specify exactly two node indexes: node on which to report, and reported node";
        }
        nodes.get(sl.get(0)).getRawNode().reportUnreachable(
                nodes.get(sl.get(1)).getConfig().id);
        return null;
    }

    // ------------------------------------------------------------------
    // 环境提供的动作
    // ------------------------------------------------------------------

    /**
     * 处理指定节点的 Ready：打印内容、把日志落盘、把消息发出去。
     */
    public void processReady(int idx) {
        InteractionNode n = nodes.get(idx);
        Ready rd = n.getRawNode().ready();
        output.write(RaftUtil.describeReady(rd, DEFAULT_ENTRY_FORMATTER));

        processAppend(n, rd.getHardState(), rd.getEntries(), rd.getSnapshot());
        processApply(n, rd.getCommittedEntries());

        for (Message m : rd.getMessages()) {
            if (RaftUtil.isLocalMsgTarget(m.getTo())) {
                throw new RaftException("unexpected local msg target");
            }
            messages.add(m);
        }

        n.getRawNode().advance(rd);
    }

    /**
     * 把日志与硬状态写进存储
     */
    private void processAppend(InteractionNode n, HardState st, List<Entry> ents, Snapshot snap) {
        InteractionStorage s = n.getStorage();
        if (!RaftUtil.isEmptyHardState(st)) {
            s.setHardState(st);
        }
        if (!RaftUtil.isEmptySnap(snap)) {
            if (!ents.isEmpty()) {
                throw new RaftException("can't apply snapshot and entries at the same time");
            }
            s.applySnapshot(snap);
            return;
        }
        s.append(ents);
    }

    /**
     * 把已提交的日志应用到状态机，并维护节点的快照历史。
     */
    private void processApply(InteractionNode n, List<Entry> ents) {
        for (Entry ent : ents) {
            byte[] update;
            ConfState cs = null;
            switch (ent.getType()) {
                case CONFIG_CHANGE: {
                    ConfChangeI cc = ConfChangeCodec.unmarshal(ent.getType(), ent.getData());
                    update = cc.asV1().getContext();
                    cs = n.getRawNode().applyConfChange(cc);
                    break;
                }
                case CONFIG_CHANGE_V2: {
                    ConfChangeI cc = ConfChangeCodec.unmarshal(ent.getType(), ent.getData());
                    cs = n.getRawNode().applyConfChange(cc);
                    update = cc.asV2().getContext();
                    break;
                }
                default:
                    update = ent.getData();
                    break;
            }

            // 以当前状态为起点，把这条命令的效果累加进去。
            Snapshot lastSnap = n.getHistory().get(n.getHistory().size() - 1);
            byte[] data = new byte[lastSnap.getData().length + update.length];
            System.arraycopy(lastSnap.getData(), 0, data, 0, lastSnap.getData().length);
            System.arraycopy(update, 0, data, lastSnap.getData().length, update.length);
            ConfState snapCs = cs == null ? lastSnap.getMetadata().getConfState() : cs;
            n.getHistory().add(new Snapshot(data,
                    new SnapshotMetadata(snapCs, ent.getIndex(), ent.getTerm())));
        }
    }

    /**
     * 处理本地追加线程的一条消息（Java 侧不支持异步落盘）
     */
    public void processAppendThread(int idx) {
        InteractionNode n = nodes.get(idx);
        if (n.getAppendWork().isEmpty()) {
            output.write("no append work to perform");
            return;
        }
        Message m = n.getAppendWork().remove(0);
        List<Message> resps = m.getResponses() == null ? Collections.emptyList() : m.getResponses();
        m.setResponses(null);

        output.write("Processing:\n");
        output.write(RaftUtil.describeMessage(m, DEFAULT_ENTRY_FORMATTER) + "\n");
        HardState st = new HardState(m.getTerm(), m.getVote(), m.getCommit());
        Snapshot snap = m.getSnapshot();
        processAppend(n, st, m.getEntries(), snap);

        output.write("Responses:\n");
        for (Message resp : resps) {
            output.write(RaftUtil.describeMessage(resp, DEFAULT_ENTRY_FORMATTER) + "\n");
        }
        messages.addAll(resps);
    }

    /**
     * 处理本地应用线程的一条消息（Java 侧不支持异步落盘）
     */
    public void processApplyThread(int idx) {
        InteractionNode n = nodes.get(idx);
        if (n.getApplyWork().isEmpty()) {
            output.write("no apply work to perform");
            return;
        }
        Message m = n.getApplyWork().remove(0);
        List<Message> resps = m.getResponses() == null ? Collections.emptyList() : m.getResponses();
        m.setResponses(null);

        output.write("Processing:\n");
        output.write(RaftUtil.describeMessage(m, DEFAULT_ENTRY_FORMATTER) + "\n");
        processApply(n, m.getEntries());

        output.write("Responses:\n");
        for (Message resp : resps) {
            output.write(RaftUtil.describeMessage(resp, DEFAULT_ENTRY_FORMATTER) + "\n");
        }
        messages.addAll(resps);
    }

    /**
     * 投递消息。
     *
     * @param typ 只投递这一种类型的消息，null 表示全部
     * @param recipients 收件人
     * @return 处理掉的消息条数
     */
    public int deliverMsgs(MessageType typ, List<Recipient> recipients) {
        int n = 0;
        for (Recipient r : recipients) {
            List<Message> toMsgs = new ArrayList<>();
            List<Message> remainder = new ArrayList<>();
            splitMsgs(messages, r.getId(), typ, r.isDrop(), toMsgs, remainder);
            messages.clear();
            messages.addAll(remainder);
            n += toMsgs.size();
            for (Message msg : toMsgs) {
                if (r.isDrop()) {
                    output.write("dropped: ");
                }
                output.write(RaftUtil.describeMessage(msg, DEFAULT_ENTRY_FORMATTER) + "\n");
                if (r.isDrop()) {
                    // 允许丢弃发给还没建出来的节点的消息
                    continue;
                }
                int toIdx = (int) (msg.getTo() - 1);
                try {
                    nodes.get(toIdx).getRawNode().step(msg);
                } catch (RaftException e) {
                    output.write(e.getMessage() + "\n");
                }
            }
        }
        return n;
    }

    /**
     * 反复处理 Ready、投递消息，直到集群不再有事情可做。
     *
     * @param idxs 参与稳定化的节点下标，空表示全部
     */
    public void stabilize(List<Integer> idxs) {
        List<InteractionNode> ns = new ArrayList<>();
        if (idxs.isEmpty()) {
            ns.addAll(nodes);
        } else {
            for (Integer idx : idxs) {
                ns.add(nodes.get(idx));
            }
        }

        while (true) {
            boolean done = true;
            for (InteractionNode rn : ns) {
                if (rn.getRawNode().hasReady()) {
                    int idx = (int) (rn.getRawNode().status().getId() - 1);
                    output.write("> " + (idx + 1) + " handling Ready\n");
                    withIndent(() -> processReady(idx));
                    done = false;
                }
            }
            for (InteractionNode rn : ns) {
                long id = rn.getRawNode().status().getId();
                // 先看一眼有没有消息，只为决定要不要打印标题
                List<Message> msgs = new ArrayList<>();
                List<Message> remainder = new ArrayList<>();
                splitMsgs(messages, id, null, false, msgs, remainder);
                if (!msgs.isEmpty()) {
                    output.write("> " + id + " receiving messages\n");
                    withIndent(() -> deliverMsgs(null,
                            Collections.singletonList(new Recipient(id, false))));
                    done = false;
                }
            }
            for (InteractionNode rn : ns) {
                int idx = (int) (rn.getRawNode().status().getId() - 1);
                if (!rn.getAppendWork().isEmpty()) {
                    output.write("> " + (idx + 1) + " processing append thread\n");
                    while (!rn.getAppendWork().isEmpty()) {
                        withIndent(() -> processAppendThread(idx));
                    }
                    done = false;
                }
            }
            for (InteractionNode rn : ns) {
                int idx = (int) (rn.getRawNode().status().getId() - 1);
                if (!rn.getApplyWork().isEmpty()) {
                    output.write("> " + (idx + 1) + " processing apply thread\n");
                    while (!rn.getApplyWork().isEmpty()) {
                        withIndent(() -> processApplyThread(idx));
                    }
                    done = false;
                }
            }
            if (done) {
                return;
            }
        }
    }

    /**
     * 打印指定节点的日志
     */
    public void raftLog(int idx) {
        InteractionStorage s = nodes.get(idx).getStorage();
        long fi = s.firstIndex();
        long li = s.lastIndex();
        if (li < fi) {
            output.write("log is empty: first index=" + fi + ", last index=" + li);
            return;
        }
        List<Entry> ents = s.entries(fi, li + 1, Long.MAX_VALUE);
        output.write(RaftUtil.describeEntries(ents, DEFAULT_ENTRY_FORMATTER));
    }

    /**
     * 手工构造一条快照消息放进在途队列
     */
    public void sendSnapshot(int fromIdx, int toIdx) {
        Snapshot snap = nodes.get(fromIdx).getRawNode().snapshot();
        long from = fromIdx + 1;
        long to = toIdx + 1;
        Message msg = new Message()
                .setType(MessageType.MSG_SNAP)
                .setTerm(nodes.get(fromIdx).getRawNode().basicStatus().getHardState().getTerm())
                .setFrom(from)
                .setTo(to)
                .setSnapshot(snap);
        messages.add(msg);
        output.write(RaftUtil.describeMessage(msg, null));
    }

    /**
     * 让指定节点走 n 个 tick
     */
    public void tick(int idx, int num) {
        for (int i = 0; i < num; i++) {
            nodes.get(idx).getRawNode().tick();
        }
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /**
     * 收件人，可以选择把消息丢掉
     */
    public static class Recipient {

        private final long id;
        private final boolean drop;

        public Recipient(long id, boolean drop) {
            this.id = id;
            this.drop = drop;
        }

        public long getId() {
            return id;
        }

        public boolean isDrop() {
            return drop;
        }
    }

    /**
     * 把在途消息拆成「给收件人的」与「剩下的」两堆。
     *
     * <p>不重排消息的先后顺序。</p>
     */
    private static void splitMsgs(List<Message> msgs, long to, MessageType typ, boolean drop,
                                  List<Message> toMsgs, List<Message> remainder) {
        for (Message msg : msgs) {
            if (msg.getTo() == to && !(drop && isLocalMsg(msg)) && (typ == null || msg.getType() == typ)) {
                toMsgs.add(msg);
            } else {
                remainder.add(msg);
            }
        }
    }

    /**
     * 本地消息要求可靠投递，不能丢
     */
    private static boolean isLocalMsg(Message msg) {
        return msg.getFrom() == msg.getTo()
                || RaftUtil.isLocalMsgTarget(msg.getFrom())
                || RaftUtil.isLocalMsgTarget(msg.getTo());
    }

    /**
     * 节点自己是不是投票成员
     */
    private static boolean isVoter(Status st) {
        return st.getConfig().getVoters().ids().contains(st.getId());
    }

    /**
     * 跑一段逻辑，把它的输出缩进两格
     */
    private void withIndent(Runnable f) {
        StringBuilder orig = output.takeBuilder();
        try {
            f.run();
        } finally {
            StringBuilder inner = output.takeBuilder();
            output.restoreBuilder(orig);
            for (String line : splitLines(inner.toString())) {
                orig.append("  ").append(line).append('\n');
            }
        }
    }

    /**
     * 按行切开文本，末尾的换行不算多出来的一行
     */
    private static List<String> splitLines(String s) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                lines.add(s.substring(start, i));
                start = i + 1;
            }
        }
        if (start < s.length()) {
            lines.add(s.substring(start));
        }
        return lines;
    }

    /**
     * 第一个参数当作整数
     */
    private static int firstAsInt(TestData d) {
        if (d.getCmdArgs().isEmpty()) {
            throw new DataDrivenException("missing argument");
        }
        return (int) parseLong(d.getCmdArgs().get(0).getKey());
    }

    /**
     * 第一个参数当作节点下标（节点 id 减一）
     */
    private static int firstAsNodeIdx(TestData d) {
        return firstAsInt(d) - 1;
    }

    /**
     * 所有「只有名字没有值」的参数都是节点 id
     */
    private static List<Integer> nodeIdxs(TestData d) {
        List<Integer> ints = new ArrayList<>();
        for (CmdArg arg : d.getCmdArgs()) {
            if (!arg.getVals().isEmpty()) {
                continue;
            }
            ints.add((int) parseLong(arg.getKey()) - 1);
        }
        return ints;
    }

    /**
     * 找出名字匹配的参数
     */
    private static CmdArg scanArgs(TestData d, String key) {
        CmdArg found = null;
        for (CmdArg arg : d.getCmdArgs()) {
            if (key.equals(arg.getKey())) {
                if (found != null) {
                    throw new DataDrivenException("key \"" + key + "\" specified twice");
                }
                found = arg;
            }
        }
        if (found == null) {
            throw new DataDrivenException("key \"" + key + "\" not found");
        }
        return found;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new DataDrivenException("cannot parse \"" + s + "\" as an integer");
        }
    }

    /**
     * 解析 Go 风格的布尔量
     */
    private static boolean parseBool(String s) {
        switch (s) {
            case "1":
            case "t":
            case "T":
            case "true":
            case "TRUE":
            case "True":
                return true;
            case "0":
            case "f":
            case "F":
            case "false":
            case "FALSE":
            case "False":
                return false;
            default:
                throw new IllegalArgumentException(
                        "strconv.ParseBool: parsing \"" + s + "\": invalid syntax");
        }
    }

    /**
     * 渲染一串字符串，等价于 Go 的 {@code %v} 作用在 {@code []string} 上
     */
    private static String goStrings(String[] values) {
        StringBuilder buf = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                buf.append(' ');
            }
            buf.append(values[i]);
        }
        return buf.append(']').toString();
    }

    /**
     * raft 的测试用配置，等价于 etcd 的 {@code raftConfigStub()}
     */
    private static RaftConfig raftConfigStub() {
        RaftConfig cfg = new RaftConfig();
        cfg.electionTick = 3;
        cfg.heartbeatTick = 1;
        cfg.maxSizePerMsg = Long.MAX_VALUE;
        cfg.maxInflightMsgs = Integer.MAX_VALUE;
        return cfg;
    }

    /**
     * 复制一份配置，每个节点都要有自己的一份
     */
    private static RaftConfig copyConfig(RaftConfig src) {
        RaftConfig cfg = new RaftConfig();
        cfg.id = src.id;
        cfg.electionTick = src.electionTick;
        cfg.heartbeatTick = src.heartbeatTick;
        cfg.storage = src.storage;
        cfg.applied = src.applied;
        cfg.maxSizePerMsg = src.maxSizePerMsg;
        cfg.maxCommittedSizePerReady = src.maxCommittedSizePerReady;
        cfg.maxUncommittedEntriesSize = src.maxUncommittedEntriesSize;
        cfg.maxInflightMsgs = src.maxInflightMsgs;
        cfg.maxInflightBytes = src.maxInflightBytes;
        cfg.checkQuorum = src.checkQuorum;
        cfg.preVote = src.preVote;
        cfg.readOnlyOption = src.readOnlyOption;
        cfg.disableProposalForwarding = src.disableProposalForwarding;
        cfg.disableConfChangeValidation = src.disableConfChangeValidation;
        cfg.stepDownOnRemoval = src.stepDownOnRemoval;
        cfg.logger = src.logger;
        return cfg;
    }

    /**
     * 空的快照，等价于 Go 的 {@code pb.Snapshot{}}
     */
    private static Snapshot emptySnapshot() {
        return new Snapshot(null, new SnapshotMetadata(
                new ConfState(Collections.emptyList(), Collections.emptyList(),
                        Collections.emptyList(), Collections.emptyList(), false), 0, 0));
    }

    /**
     * 快照是不是「什么都没有」
     */
    private static boolean isEmptySnapshot(Snapshot snap) {
        if (snap == null) {
            return true;
        }
        SnapshotMetadata m = snap.getMetadata();
        if (m == null) {
            return true;
        }
        if (m.getIndex() != 0 || m.getTerm() != 0) {
            return false;
        }
        byte[] data = snap.getData();
        if (data != null && data.length > 0) {
            return false;
        }
        ConfState cs = m.getConfState();
        return cs == null
                || (cs.getVoters().isEmpty() && cs.getLearners().isEmpty()
                && cs.getVotersOutgoing().isEmpty() && cs.getLearnersNext().isEmpty());
    }

    /**
     * 设置日志级别
     */
    private String handleLogLevelName(String name) {
        for (int i = 0; i < RedirectLogger.LEVEL_NAMES.length; i++) {
            if (RedirectLogger.LEVEL_NAMES[i].equalsIgnoreCase(name)) {
                output.setLevel(i);
                return null;
            }
        }
        return "log levels must be either of " + goStrings(RedirectLogger.LEVEL_NAMES);
    }

    /**
     * 集群里的节点
     */
    public List<InteractionNode> getNodes() {
        return nodes;
    }

    /**
     * 在途的消息
     */
    public List<Message> getMessages() {
        return messages;
    }

    /**
     * 输出缓冲区
     */
    public RedirectLogger getOutput() {
        return output;
    }
}
