/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.raft.cluster;

import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.raft.ApplyHandler;
import io.edap.raft.Node;
import io.edap.raft.Peer;
import io.edap.raft.RaftConfig;
import io.edap.raft.RaftException;
import io.edap.raft.RaftRole;
import io.edap.raft.Ready;
import io.edap.raft.SoftState;
import io.edap.raft.Status;
import io.edap.raft.protocol.ConfChange;
import io.edap.raft.protocol.ConfChangeCodec;
import io.edap.raft.protocol.ConfChangeI;
import io.edap.raft.protocol.ConfChangeSingle;
import io.edap.raft.protocol.ConfChangeType;
import io.edap.raft.protocol.ConfChangeV2;
import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.EntryType;
import io.edap.raft.protocol.Message;
import io.edap.raft.storage.FileRaftStorage;
import io.edap.raft.transport.RaftTransport;
import io.edap.util.timing.Timers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 编排器：把「一个 raft 节点」变成「一个集群里的一个节点」。
 *
 * <p><b>它做什么</b>：</p>
 * <ol>
 *   <li><b>自动成组</b>：启动时广播 CLAIM，让同时启动的节点互相看见，
 *       再用「(host, port) 最小者自举」的确定性规则决定谁当主
 *       （见 {@link RaftBootstrapArbiter}）；</li>
 *   <li><b>成员表</b>：维护 id → 地址的映射。raft 的 {@code ConfState} 只记 id，
 *       地址由这一层自己维护（见 {@link RaftMembership}）；</li>
 *   <li><b>Ready 循环</b>：把 raft 内核产出的消息按成员表翻译成 {@link Peer}
 *       发出去；</li>
 *   <li><b>提案回执</b>：客户端经 HTTP 提案时，按 requestId 匹配回执
 *       （见 {@link RaftProposalTracker}）；</li>
 *   <li><b>身份持久化</b>：nodeId 跨重启不变（{@code dataDir/node.json}）。</li>
 * </ol>
 *
 * <p><b>★ 边界</b>：发现机制只解决「谁先起来谁当主」，
 * <b>网络分区下的主唯一性仍然靠 raft 的 quorum</b> ——
 * 与 etcd 同理。仲裁规则是「我看到的最小者胜」，
 * 所以「较大的节点看不见较小的节点」时可能出现两个自举出来的主；
 * 同网段互相可见的部署不会遇到。</p>
 */
public class RaftClusterNode implements RaftClusterDirectory {

    private static final Logger log = LoggerManager.getLogger(RaftClusterNode.class);

    /** 本地提案的 requestId 高位标记：本地的一律最高位为 1。 */
    private static final long LOCAL_REQUEST_FLAG = 0x8000000000000000L;

    /** 成员变更提案的最长等待（毫秒）。 */
    private static final long CONF_CHANGE_TIMEOUT_MILLIS = 5000;

    /** 已交付给业务状态机的最大日志 index（重启后用它去重）。 */
    private final AtomicLong lastDeliveredIndex = new AtomicLong();

    /** 已经落盘的 applied index。 */
    private volatile long appliedPersisted;

    /** applied index 的落盘节流时间戳。 */
    private volatile long appliedPersistMillis;

    private final RaftClusterConfig    config;
    private final RaftClusterConnector connector;
    private final RaftTransportFactory transportFactory;

    /** 业务状态机。收到的永远是裸 payload（信封已拆）。 */
    private final ApplyHandler         businessHandler;

    private final RaftMembership       membership = new RaftMembership();
    private final RaftProposalTracker  tracker = new RaftProposalTracker();

    /** 启动过程中发现的同伴（管理地址）。 */
    private final Map<String, Boolean> knownPeers = new ConcurrentHashMap<>();

    /** 本次启动广播的候选地址（加入失败重试时重新找主用）。 */
    private volatile List<String> selfCandidates = Collections.emptyList();

    /** 本地提案的 requestId 序号。 */
    private final AtomicLong localRequestSeq = new AtomicLong();

    /** join 串行化：成员数上限的检查与分配必须是一个临界区。 */
    private final Object joinLock = new Object();

    /** 成员变更提案的执行线程（提案可能阻塞，不能占用 HTTP 线程）。 */
    private final ExecutorService confChangeExecutor;

    /**
     * 已提出、但<b>还没被 apply</b> 的成员 id。
     *
     * <p>这些成员还不算真成员：视图里不出现（否则客户端会以为它们已经进集群了）。</p>
     */
    private final Set<Long> pendingMemberIds = ConcurrentHashMap.newKeySet();

    /**
     * 实例 id → 已经分到的成员。
     *
     * <p><b>加入是幂等的</b>：同一个实例重试加入拿到<b>同一个</b> nodeId，
     * 不会在集群里留下两个身份。</p>
     */
    private final ConcurrentHashMap<String, RaftMember> joinedInstances = new ConcurrentHashMap<>();

    /**
     * 已经被 apply 的成员变更条目里的 nodeId。
     *
     * <p><b>★ 它是「成员真的进了集群」的判据</b>：内核在提案时如果发现
     * 「上一条成员变更还没 apply」，会把这条成员变更<b>降级成普通条目</b>
     * （{@code Raft#handleProposal}），而且不告诉调用方 ——
     * 表面上提案成功了，成员却永远进不了集群。
     * 只有真的 apply 了成员变更条目，才算落地。</p>
     */
    private final ConcurrentHashMap<Long, Boolean> appliedConfIds = new ConcurrentHashMap<>();

    /** 只做客户端时的 HTTP 客户端（发现主 + 转发提案）。 */
    private final ExecutorService clientExecutor;

    /** 只做客户端时的 HTTP 客户端；不是 client-only 时是 null。 */
    private volatile RaftClient client;

    private volatile RaftClusterState state = RaftClusterState.BOOTSTRAPPING;
    private volatile long               nodeId;
    private volatile String             selfHost;
    private volatile String             instanceId;
    private volatile Node               node;
    private volatile RaftTransport      transport;
    private volatile FileRaftStorage    storage;
    private volatile Thread             readyThread;
    private volatile boolean            running;

    public RaftClusterNode(RaftClusterConfig config,
                           RaftClusterConnector connector,
                           RaftTransportFactory transportFactory,
                           ApplyHandler businessHandler) {
        this.config = config;
        this.connector = connector;
        this.transportFactory = transportFactory;
        this.businessHandler = businessHandler;
        this.confChangeExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "edap-raft-confchange");
            t.setDaemon(true);
            return t;
        });
        this.clientExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "edap-raft-client");
            t.setDaemon(true);
            return t;
        });
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 启动：加入（或恢复）集群，然后拉起 raft 节点。
     *
     * <p><b>两条路径</b>：</p>
     * <ul>
     *   <li>本地已有身份（{@code dataDir/node.json}）⇒ <b>恢复</b>：
     *       不重新加入，从 raft 日志恢复成员配置；</li>
     *   <li>本地没有身份 ⇒ <b>加入</b>：广播 CLAIM → 找到主就加入，
     *       没人就按仲裁规则自举成主。</li>
     * </ul>
     */
    public void start() throws Exception {
        this.instanceId = UUID.randomUUID().toString();
        this.selfHost = RaftSeedSet.detectSelfHost(config.selfHost, config.seeds);
        RaftIdentity identity = loadIdentity();
        if (identity != null && identity.getId() > 0) {
            nodeId = identity.getId();
            log.info("raft.cluster: 已有身份 " + identity + "，走恢复路径");
            restoreMembers();
            startRaftNode(false);
            return;
        }
        discoverAndJoin();
    }

    /**
     * 停止：停掉 raft 节点与传输层，在途提案全部失败。
     */
    public void stop() {
        running = false;
        Thread rt = readyThread;
        if (rt != null) {
            rt.interrupt();
        }
        Node n = node;
        if (n != null) {
            try {
                n.stop();
            } catch (Throwable t) {
                log.warn("raft.cluster: 停止 raft 节点时出错", t);
            }
        }
        RaftTransport t = transport;
        if (t != null) {
            try {
                t.stop();
            } catch (Throwable e) {
                log.warn("raft.cluster: 停止传输层时出错", e);
            }
        }
        FileRaftStorage s = storage;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable e) {
                log.warn("raft.cluster: 关闭存储时出错", e);
            }
        }
        confChangeExecutor.shutdownNow();
        clientExecutor.shutdownNow();
        tracker.failAll(new RaftException("raft 节点已停止"));
        persistAppliedIndex(lastDeliveredIndex.get(), true);
    }

    // ------------------------------------------------------------------
    // 加入 / 自举
    // ------------------------------------------------------------------

    /**
     * 广播 CLAIM、等待仲裁窗口、决定「加入已有的主」还是「自举成主」。
     */
    private void discoverAndJoin() throws Exception {
        List<String> candidates = RaftSeedSet.expand(config.seeds, config.managerPort,
                selfHost, config.managerPort, config.maxCandidates);
        selfCandidates = candidates;
        log.info("raft.cluster: " + selfManagerAddress() + " 开始广播，候选 " + candidates.size() + " 个");

        RaftClaimMessage claim = new RaftClaimMessage(selfHost, config.selfPort,
                config.managerPort, instanceId);
        Map<String, RaftClaimReply> peers = new LinkedHashMap<>();
        RaftClaimReply leaderAck = null;
        boolean full = false;
        String fullLeader = null;

        // 扫描 #1：窗口内反复扫，尽量早发现已经成型的集群
        long deadline = System.currentTimeMillis() + config.claimWindowMillis;
        while (leaderAck == null && !full) {
            for (RaftClaimReply r : connector.broadcastClaim(claim, candidates)) {
                if (r == null || r.getType() == null) {
                    continue;
                }
                switch (r.getType()) {
                    case LEADER_ACK:
                        leaderAck = r;
                        break;
                    case REJECT:
                        full = true;
                        fullLeader = r.leaderManagerAddress();
                        break;
                    case PEER_CLAIM: {
                        String addr = r.peerManagerAddress();
                        if (addr != null && !addr.equals(selfManagerAddress())) {
                            peers.put(addr, r);
                        }
                        break;
                    }
                    default:
                        break;
                }
            }
            if (leaderAck != null || full) {
                break;
            }
            long remain = deadline - System.currentTimeMillis();
            if (remain <= 0) {
                break;
            }
            sleepQuietly(Math.min(200, remain));
        }

        // 扫描 #2：窗口末再扫一次，把窗口内才启动的同伴纳入仲裁
        if (leaderAck == null && !full) {
            for (RaftClaimReply r : connector.broadcastClaim(claim, candidates)) {
                if (r == null || r.getType() == null) {
                    continue;
                }
                switch (r.getType()) {
                    case LEADER_ACK:
                        leaderAck = r;
                        break;
                    case REJECT:
                        full = true;
                        fullLeader = r.leaderManagerAddress();
                        break;
                    case PEER_CLAIM: {
                        String addr = r.peerManagerAddress();
                        if (addr != null && !addr.equals(selfManagerAddress())) {
                            peers.put(addr, r);
                        }
                        break;
                    }
                    default:
                        break;
                }
            }
        }

        if (full) {
            becomeClientOnly(fullLeader);
            return;
        }
        if (leaderAck != null) {
            log.info("raft.cluster: 发现主 " + leaderAck.leaderManagerAddress() + "，加入");
            joinWithRetry(leaderAck.leaderManagerAddress());
            return;
        }
        if (peers.isEmpty()) {
            log.info("raft.cluster: 没有发现任何同伴，本节点自举成主");
            bootstrapAsLeader();
            return;
        }
        if (!RaftBootstrapArbiter.shouldBootstrap(selfHost, config.managerPort, peers.keySet())) {
            log.info("raft.cluster: 同伴里有地址比我小的，等它成主（同伴 " + peers.keySet() + "）");
            waitForLeader(peers, candidates);
            return;
        }
        log.info("raft.cluster: 本节点地址最小，自举成主");
        bootstrapAsLeader();
    }

    /**
     * 等同伴里的「地址最小者」成主。
     *
     * <p>窗口已经等满了，这里只等「主出现」，
     * 期间一直用<b>同一个</b> claim 反复问 —— 拿到 LEADER_ACK 就加入。</p>
     */
    private void waitForLeader(Map<String, RaftClaimReply> peers, List<String> candidates)
            throws Exception {
        RaftClaimMessage claim = new RaftClaimMessage(selfHost, config.selfPort,
                config.managerPort, instanceId);
        long deadline = System.currentTimeMillis() + config.joinTimeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            List<String> targets = new ArrayList<>(peers.keySet());
            for (RaftClaimReply r : connector.broadcastClaim(claim, targets)) {
                if (r == null || r.getType() == null) {
                    continue;
                }
                if (r.getType() == RaftClaimReply.Type.LEADER_ACK) {
                    log.info("raft.cluster: 主出现了 " + r.leaderManagerAddress() + "，加入");
                    joinWithRetry(r.leaderManagerAddress());
                    return;
                }
                if (r.getType() == RaftClaimReply.Type.REJECT) {
                    becomeClientOnly(r.leaderManagerAddress());
                    return;
                }
            }
            for (RaftClaimReply r : connector.broadcastClaim(claim, candidates)) {
                if (r != null && r.getType() == RaftClaimReply.Type.LEADER_ACK) {
                    log.info("raft.cluster: 主出现了 " + r.leaderManagerAddress() + "，加入");
                    joinWithRetry(r.leaderManagerAddress());
                    return;
                }
                if (r != null && r.getType() == RaftClaimReply.Type.REJECT) {
                    becomeClientOnly(r.leaderManagerAddress());
                    return;
                }
            }
            sleepQuietly(200);
        }
        throw new RaftException("等待主超时（" + config.joinTimeoutMillis
                + "ms）：没有同伴成主，本节点不启动 raft");
    }

    /**
     * 向主请求加入。
     *
     * @param leaderAddress 主的管理地址
     * @param depth         改投深度（防 NOT_LEADER 无限跳转）
     */
    private void joinLeader(String leaderAddress, int depth) throws Exception {
        if (leaderAddress == null || leaderAddress.isEmpty()) {
            throw new RaftException("加入失败：不知道主在哪");
        }
        if (depth > 4) {
            throw new RaftException("加入失败：主地址反复变化（" + leaderAddress + "）");
        }
        RaftJoinRequest req = new RaftJoinRequest(selfHost, config.selfPort,
                config.managerPort, instanceId);
        RaftJoinResult result = connector.join(leaderAddress, req);
        if (result == null) {
            throw new RaftException("加入失败：连不上主 " + leaderAddress);
        }
        switch (result.getStatus()) {
            case ACCEPTED:
                nodeId = result.getNodeId();
                persistIdentity();
                applyMembers(result.getMembers());
                log.info("raft.cluster: 加入成功，nodeId=" + nodeId
                        + "，成员 " + result.getMembers().size() + " 个");
                if (node == null) {
                    startRaftNode(false);
                    state = RaftClusterState.FOLLOWER;
                }
                // ★ 等主把日志复制过来：学到 leader 才算真的进了集群。
                //   主那边的成员变更要是没落地（内核把成员变更降级成普通条目），
                //   这里就永远等不到 —— 超时后加入失败、容器起不来，
                //   而不是「假装加入成功、实际是个孤儿节点」。
                awaitClusterMember();
                break;
            case REJECTED_FULL:
                log.info("raft.cluster: 集群已满（上限 " + result.getCapacity() + "），本节点只做客户端");
                becomeClientOnly(result.leaderManagerAddress());
                break;
            case NOT_LEADER:
            default: {
                String next = result.leaderManagerAddress();
                if (next == null || next.equals(leaderAddress)) {
                    throw new RaftException("加入失败：主指向无效（" + leaderAddress + "）");
                }
                joinLeader(next, depth + 1);
                break;
            }
        }
    }

    /**
     * 加入主，失败就重新发现主再试。
     *
     * <p>失败的原因通常是「主刚好换了」或者「成员变更没落地」——
     * 两种都可以重试：同一个实例重试会拿到<b>同一个</b> nodeId，
     * 不会在集群里留下两个身份。</p>
     */
    private void joinWithRetry(String leaderAddress) throws Exception {
        int maxAttempts = 3;
        for (int attempt = 1; ; attempt++) {
            try {
                joinLeader(leaderAddress, 0);
                return;
            } catch (RaftException e) {
                if (attempt >= maxAttempts) {
                    throw e;
                }
                log.warn("raft.cluster: 加入失败（第 " + attempt + " 次）：" + e.getMessage()
                        + "，重新发现主再试");
                sleepQuietly(300);
                String found = discoverLeaderAddress();
                if (found != null) {
                    leaderAddress = found;
                }
            }
        }
    }

    /**
     * 等自己真的进了集群：学到 leader 就算数。
     *
     * <p>主只有在成员变更被 apply 之后才会开始复制日志给我们；
     * 收不到主的消息 ⇒ 要么成员变更没落地，要么主挂了/换了。</p>
     */
    private void awaitClusterMember() throws Exception {
        long deadline = System.currentTimeMillis() + config.joinTimeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (currentLeaderId() != 0) {
                return;
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new RaftException("加入被中断");
            }
            sleepQuietly(50);
        }
        throw new RaftException("加入超时：没有收到主的消息，成员变更可能没有生效");
    }

    /**
     * 广播 CLAIM 找主（加入失败重试时用）。
     *
     * @return 主的管理地址；没找到时返回 null
     */
    private String discoverLeaderAddress() {
        List<String> candidates = selfCandidates;
        if (candidates.isEmpty()) {
            return null;
        }
        RaftClaimMessage claim = new RaftClaimMessage(selfHost, config.selfPort,
                config.managerPort, instanceId);
        for (RaftClaimReply r : connector.broadcastClaim(claim, candidates)) {
            if (r != null && r.getType() == RaftClaimReply.Type.LEADER_ACK) {
                return r.leaderManagerAddress();
            }
        }
        return null;
    }

    /**
     * 转成「只做客户端」：不起 raft 节点，但仍然能发现主、转发提案。
     *
     * <p><b>为什么不是「彻底躺平」</b>：集群满员之后启动的容器照样要干活的 ——
     * 它只是<b>不是集群成员</b>，业务照样通过它提交提案。
     * 所以这里建一个 {@link RaftClient}：主在加入应答里已经告诉我们了，
     * 灌进去就不用再撒网；提案失败时客户端会自己重新发现（换主也能跟上）。</p>
     *
     * @param knownLeader 主的管理地址；不知道时传 null（客户端会自己去发现）
     */
    private void becomeClientOnly(String knownLeader) {
        RaftClient c = new RaftClient(config, connector);
        if (knownLeader != null && !knownLeader.isEmpty()) {
            c.setLeaderAddress(knownLeader);
        }
        this.client = c;
        this.state = RaftClusterState.CLIENT_ONLY;
        log.info("raft.cluster: 本节点只做客户端"
                + (knownLeader == null || knownLeader.isEmpty() ? "" : "（主在 " + knownLeader + "）"));
        // 顺手拉一次主的集群视图：这样 /raft/cluster 也能答出「谁是主、成员有哪些」。
        // 拉不到不影响启动 —— 提案时会再发现一次。
        try {
            c.fetchLeaderView();
        } catch (Throwable t) {
            log.warn("raft.cluster: 拉取主的集群视图失败", l -> l.threw(t));
        }
    }

    /**
     * 自举成主：单节点集群，quorum = 1 ⇒ 选票就够 ⇒ 自然选自己为主。
     *
     * <p><b>不立刻 campaign</b>：bootstrap 追加的成员变更条目还没 apply，
     * {@code Raft.hup()} 会因为「有未 apply 的成员变更」拒绝竞选
     * （见 {@code Raft#hup}）。所以靠 tick 自然选举 ——
     * 单投票节点给自己投票，1/1 满足 quorum。</p>
     */
    private void bootstrapAsLeader() throws Exception {
        nodeId = 1L;
        persistIdentity();
        RaftMember self = new RaftMember(nodeId, selfHost, config.selfPort, config.managerPort);
        membership.put(self);
        persistMembers();
        startRaftNode(true);
        state = RaftClusterState.FOLLOWER;
    }

    // ------------------------------------------------------------------
    // raft 节点
    // ------------------------------------------------------------------

    private void startRaftNode(boolean bootstrap) throws Exception {
        RaftConfig rc = new RaftConfig();
        rc.id = nodeId;
        rc.electionTick = config.electionTick;
        rc.heartbeatTick = config.heartbeatTick;
        rc.storage = FileRaftStorage.open(config.dataDir);
        rc.frameworkPersist = true;
        rc.applyHandler = this::onApply;
        this.storage = (FileRaftStorage) rc.storage;

        if (bootstrap) {
            Peer self = new Peer(nodeId, selfHost, config.selfPort,
                    RaftPeerCodec.encode(selfHost, config.selfPort, config.managerPort));
            node = Node.startNode(rc, self);
        } else {
            node = Node.restartNode(rc);
        }
        transport = transportFactory.create(nodeId, selfHost, config.selfPort);
        transport.setHandler(node::onMessage);
        transport.start();
        node.startTicker(Timers.get(), config.tickMillis);
        running = true;
        readyThread = new Thread(this::readyLoop, "edap-raft-ready-" + nodeId);
        readyThread.setDaemon(true);
        readyThread.start();
    }

    /**
     * Ready 循环：把内核产出的消息按成员表翻译成地址发出去。
     *
     * <p>框架落盘模式下 Ready 里没有日志条目，日志由框架自己落盘、
     * 已提交条目由框架的 apply 线程交给 {@link #onApply} ——
     * 所以这个循环只剩「发消息 + advance」两件事。</p>
     */
    private void readyLoop() {
        Node n = node;
        while (running && n != null) {
            try {
                Ready rd = n.ready(100, TimeUnit.MILLISECONDS);
                if (rd != null) {
                    List<Message> msgs = rd.getMessages();
                    if (msgs != null) {
                        for (Message m : msgs) {
                            long to = m.getTo();
                            if (to == 0 || to == nodeId) {
                                // 本地消息不该出现在这里
                                continue;
                            }
                            Peer p = membership.peerOf(to);
                            if (p == null) {
                                log.warn("raft.cluster: 找不到节点 " + Long.toHexString(to)
                                        + " 的地址，消息丢弃");
                                continue;
                            }
                            transport.send(p, m);
                        }
                    }
                    n.advance();
                }
                refreshState();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                log.error("raft.cluster: Ready 循环异常", t);
            }
        }
    }

    /**
     * apply 回调：拆信封、匹配回执、把裸 payload 交给业务状态机。
     *
     * <p><b>成员变更条目不进业务状态机</b>：它们由框架先交给
     * {@code applyConfChange}，这里只用来更新成员表（地址的来源）。</p>
     */
    private void onApply(List<Entry> entries) {
        List<Entry> toDeliver = new ArrayList<>();
        long last = lastDeliveredIndex.get();
        for (Entry e : entries) {
            if (e.getIndex() <= last) {
                // 重启之后框架会把已提交的条目重新投递一遍。
                // 业务状态机不能看到两次 —— 按 index 挡掉。
                continue;
            }
            last = e.getIndex();
            if (e.getType() == EntryType.CONFIG_CHANGE || e.getType() == EntryType.CONFIG_CHANGE_V2) {
                applyConfChangeToMembership(e);
                continue;
            }
            byte[] data = e.getData();
            if (!RaftProposalEnvelope.isEnvelope(data)) {
                // 不是本层的提案：leader 上任时会追加一条空的 no-op 条目
                // （用来提交前任的日志），它不是业务载荷，不能进业务状态机。
                continue;
            }
            long requestId = RaftProposalEnvelope.requestIdOf(data);
            byte[] payload = RaftProposalEnvelope.payloadOf(data);
            boolean first = tracker.complete(requestId, payload);
            if (!first) {
                // 重复的 requestId（重试）：业务状态机只应用一次
                continue;
            }
            toDeliver.add(new Entry(e.getIndex(), e.getTerm(), e.getType(), payload));
        }
        lastDeliveredIndex.set(last);
        persistAppliedIndex(last, false);
        if (!toDeliver.isEmpty() && businessHandler != null) {
            try {
                businessHandler.apply(toDeliver);
            } catch (Throwable t) {
                log.error("raft.cluster: 业务状态机处理失败", t);
            }
        }
    }

    /**
     * 从成员变更条目里解出地址，更新成员表。
     */
    private void applyConfChangeToMembership(Entry e) {
        try {
            ConfChangeI cc = ConfChangeCodec.unmarshal(e.getType(), e.getData());
            List<ConfChangeSingle> changes;
            byte[] context;
            if (cc instanceof ConfChange) {
                ConfChange c = (ConfChange) cc;
                changes = Collections.singletonList(new ConfChangeSingle(c.getType(), c.getNodeId()));
                context = c.getContext();
            } else if (cc instanceof ConfChangeV2) {
                ConfChangeV2 c = (ConfChangeV2) cc;
                changes = c.getChanges();
                context = c.getContext();
            } else {
                return;
            }
            boolean changed = false;
            for (ConfChangeSingle single : changes) {
                long id = single.getNodeId();
                if (id == 0) {
                    continue;
                }
                ConfChangeType type = single.getType();
                if (type == ConfChangeType.REMOVE_NODE) {
                    membership.remove(id);
                    pendingMemberIds.remove(id);
                    appliedConfIds.remove(id);
                    changed = true;
                    continue;
                }
                // ★ 这条成员变更真的被 apply 了 ⇒ 该成员真的进了集群。
                //   （内核把成员变更降级成普通条目时，这里根本不会走到。）
                appliedConfIds.put(id, Boolean.TRUE);
                pendingMemberIds.remove(id);
                RaftMember m = RaftPeerCodec.decode(context);
                if (m != null) {
                    m.setId(id);
                    membership.put(m);
                    changed = true;
                }
            }
            if (changed) {
                persistMembers();
            }
        } catch (Throwable t) {
            log.warn("raft.cluster: 处理成员变更条目失败", t);
        }
    }

    // ------------------------------------------------------------------
    // 入站：CLAIM / JOIN
    // ------------------------------------------------------------------

    @Override
    public RaftClaimReply claim(RaftClaimMessage request) {
        if (request != null) {
            String addr = request.managerAddress();
            if (addr != null && !addr.equals(selfManagerAddress())) {
                knownPeers.put(addr, Boolean.TRUE);
            }
        }
        if (state == RaftClusterState.CLIENT_ONLY) {
            RaftClaimReply r = new RaftClaimReply(RaftClaimReply.Type.REJECT);
            fillLeader(r);
            return r;
        }
        long leader = currentLeaderId();
        if (leader != 0) {
            RaftMember lm = membership.member(leader);
            if (lm != null) {
                RaftClaimReply r = new RaftClaimReply(RaftClaimReply.Type.LEADER_ACK);
                r.setLeaderHost(lm.getHost());
                r.setLeaderPort(lm.getPort());
                r.setLeaderManagerPort(lm.getManagerPort());
                return r;
            }
        }
        // 我是成员但还没选出主，或者我也在启动 —— 都是「同伴」
        RaftClaimReply r = new RaftClaimReply(RaftClaimReply.Type.PEER_CLAIM);
        r.setPeerHost(selfHost);
        r.setPeerPort(config.selfPort);
        r.setPeerManagerPort(config.managerPort);
        return r;
    }

    @Override
    public RaftJoinResult join(RaftJoinRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("join request must not be null");
        }
        synchronized (joinLock) {
            if (!isLeader()) {
                RaftJoinResult r = new RaftJoinResult(RaftJoinResult.Status.NOT_LEADER);
                fillLeader(r);
                r.setCapacity(config.capacity);
                return r;
            }
            // ★ 加入是幂等的：同一个实例重试加入，拿到同一个 nodeId。
            //   （重试发生在「已经分到 id、但还没真的进集群」的窗口里。）
            String instanceId = request.getInstanceId();
            RaftMember joined = instanceId == null ? null : joinedInstances.get(instanceId);
            if (joined != null) {
                RaftJoinResult r = new RaftJoinResult(RaftJoinResult.Status.ACCEPTED);
                r.setNodeId(joined.getId());
                r.setCapacity(config.capacity);
                r.setMembers(appliedMembers());
                fillLeader(r);
                return r;
            }
            if (membership.size() >= config.capacity) {
                log.info("raft.cluster: 集群已满（" + membership.size() + "/" + config.capacity
                        + "），拒绝 " + request.managerAddress());
                RaftJoinResult r = new RaftJoinResult(RaftJoinResult.Status.REJECTED_FULL);
                r.setCapacity(config.capacity);
                r.setMembers(appliedMembers());
                fillLeader(r);
                return r;
            }
            long newId = membership.nextMemberId();
            byte[] context = RaftPeerCodec.encode(request.getHost(), request.getPort(),
                    request.getManagerPort());
            // 先把地址放进成员表：主复制日志时要靠它找到对端，
            // 而成员变更要等提交之后才生效。
            RaftMember m = new RaftMember(newId, request.getHost(), request.getPort(),
                    request.getManagerPort());
            membership.put(m);
            pendingMemberIds.add(newId);
            if (instanceId != null) {
                joinedInstances.put(instanceId, m);
            }
            persistMembers();
            // ★ 成员变更交给单线程执行器：它「提一条 → 等这条被 apply → 再提下一条」。
            //   内核要求「上一条成员变更已 apply」才接受下一条，
            //   不等就会把下一条降级成普通条目（成员永远加不进集群，而且没人告诉你）。
            //   这里不能阻塞 HTTP 线程：edap 的 HTTP handler 跑在 NIO 的 IO 线程上，
            //   一阻塞整个容器的 IO 都停了（连 raft 自己的心跳都发不出去）。
            confChangeExecutor.submit(() -> runConfChange(newId, context));
            RaftJoinResult r = new RaftJoinResult(RaftJoinResult.Status.ACCEPTED);
            r.setNodeId(newId);
            r.setCapacity(config.capacity);
            r.setMembers(appliedMembers());
            fillLeader(r);
            return r;
        }
    }

    /**
     * 提一条成员变更，并等它真的被 apply。
     *
     * <p><b>★ 为什么必须等</b>：内核在提案时如果发现「上一条成员变更还没 apply」，
     * 会把这条成员变更<b>降级成普通条目</b>（{@code Raft#handleProposal}），
     * 而且不告诉调用方 —— 表面上提案成功了，成员却永远进不了集群。
     * 并发加入时最容易踩：第一个成员变更还没 apply，第二个就被降级了。</p>
     *
     * <p>这个方法跑在 {@link #confChangeExecutor} 上（单线程 ⇒ 天然串行）。</p>
     */
    private void runConfChange(long newId, byte[] context) {
        Node n = node;
        if (n == null) {
            removePendingJoin(newId);
            return;
        }
        try {
            // 只等到「提案被状态机取走」：真正的落地由下面的等待来确认。
            n.proposeConfChange(new ConfChange(ConfChangeType.ADD_NODE, newId, context));
        } catch (Throwable t) {
            log.warn("raft.cluster: 提交成员变更失败", l -> l.threw(t));
            removePendingJoin(newId);
            return;
        }
        if (!awaitConfChangeApplied(newId, CONF_CHANGE_TIMEOUT_MILLIS)) {
            log.warn("raft.cluster: 成员变更没有被应用（nodeId=" + newId
                    + "），该成员没有进集群");
            removePendingJoin(newId);
        }
    }

    /**
     * 等一条成员变更条目被 apply。
     *
     * @return true 表示这条成员变更真的以「成员变更」的身份被应用了
     */
    private boolean awaitConfChangeApplied(long memberId, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (appliedConfIds.containsKey(memberId)) {
                return true;
            }
            if (Thread.currentThread().isInterrupted()) {
                return false;
            }
            sleepQuietly(20);
        }
        return appliedConfIds.containsKey(memberId);
    }

    /**
     * 成员变更没落地：把成员从地址表里撤掉。
     *
     * <p>已经 apply 的不动 —— 那说明它真的进了集群，只是我们等超时了。</p>
     */
    private void removePendingJoin(long memberId) {
        pendingMemberIds.remove(memberId);
        if (!appliedConfIds.containsKey(memberId)) {
            membership.remove(memberId);
            persistMembers();
        }
        joinedInstances.values().removeIf(m -> m.getId() == memberId);
    }

    /**
     * 视图里的成员：<b>只算已经落地的</b>。
     *
     * <p>提案中但还没 apply 的成员不算 —— 它们还没进集群，
     * 报出去会让客户端以为「成员已经齐了」。</p>
     */
    private List<RaftMember> appliedMembers() {
        List<RaftMember> all = membership.all();
        if (pendingMemberIds.isEmpty()) {
            return all;
        }
        List<RaftMember> out = new ArrayList<>(all.size());
        for (RaftMember m : all) {
            if (!pendingMemberIds.contains(m.getId())) {
                out.add(m);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 出站：提案与查询
    // ------------------------------------------------------------------

    /**
     * 本地提案。
     *
     * <p>返回的 future 在提案<b>被提交</b>时完成（多数派已持久化）。</p>
     *
     * <p>只做客户端的节点没有本地 raft 节点，提案会转给主。</p>
     */
    public CompletableFuture<Void> propose(byte[] payload) {
        Node n = node;
        if (n == null) {
            if (state == RaftClusterState.CLIENT_ONLY) {
                CompletableFuture<Void> f = new CompletableFuture<>();
                clientPropose(nextLocalRequestId(), payload).whenComplete((v, t) -> {
                    if (t != null) {
                        f.completeExceptionally(t);
                    } else {
                        f.complete(null);
                    }
                });
                return f;
            }
            throw new IllegalStateException("raft 节点还没起来");
        }
        long requestId = nextLocalRequestId();
        byte[] envelope = RaftProposalEnvelope.wrap(requestId, payload);
        return n.proposeAsync(envelope, config.proposalTimeoutMillis);
    }

    /**
     * 客户端提案：带 requestId 的回执。
     *
     * <p>返回的 future 在提案<b>被本节点应用</b>时完成 ——
     * 应用意味着已经提交（leader 上是「已提交并已应用」）。</p>
     *
     * <p><b>幂等</b>：同一个 requestId 重复调用时，
     * 第二次直接返回已完成的结果，<b>不会</b>再提案一次。</p>
     */
    public CompletableFuture<byte[]> proposeWithAck(long requestId, byte[] payload) {
        Node n = node;
        if (n == null) {
            if (state == RaftClusterState.CLIENT_ONLY) {
                // 只做客户端：把提案转给主（HTTP）。同一个 requestId 传过去 ⇒ 幂等。
                return clientPropose(requestId, payload);
            }
            throw new IllegalStateException("raft 节点还没起来");
        }
        RaftProposalTracker.Registration reg = tracker.register(requestId);
        if (reg.isDuplicate()) {
            // 已经应用过了：直接回执，不再提案
            return reg.getFuture();
        }
        byte[] envelope = RaftProposalEnvelope.wrap(requestId, payload);
        n.proposeAsync(envelope, config.proposalTimeoutMillis).whenComplete((v, t) -> {
            if (t != null) {
                tracker.fail(requestId, t);
            }
        });
        return reg.getFuture();
    }

    /**
     * 只做客户端时的提案：转发给主。
     *
     * <p>放在单独线程上：HTTP 请求要等主提交 + 应用，可能好几秒，
     * 不能占着调用者（管理端口的 HTTP 线程）。</p>
     */
    private CompletableFuture<byte[]> clientPropose(long requestId, byte[] payload) {
        RaftClient c = client;
        if (c == null) {
            throw new IllegalStateException("raft 节点还没起来");
        }
        CompletableFuture<byte[]> f = new CompletableFuture<>();
        clientExecutor.submit(() -> {
            try {
                RaftProposalResult r = c.propose(requestId, payload);
                // 主说「这个 requestId 早先就应用过了」⇒ 如实回给自己的客户端。
                if (r != null && r.isDuplicate()) {
                    tracker.markDuplicateReply(requestId);
                }
                f.complete(payload);
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f;
    }

    /**
     * 最近一次关于这个 requestId 的回执是不是「重复应答」。
     *
     * <p>用于应答里区分「这次是新应用」与「早先就应用过了（重试）」：
     * 成员节点本地提案时由去重表给出；只做客户端的节点由主给出。</p>
     */
    public boolean isDuplicateProposal(long requestId) {
        return tracker.isDuplicateReply(requestId);
    }

    /**
     * 当前集群视图（谁是主、有哪些成员）。
     *
     * <p><b>只做客户端的节点</b>：它不在成员表里，视图来自主 ——
     * 缓存的是最近一次拉到的（转 client-only 时拉一次，之后由提案失败触发重拉）。
     * 拿不到就只报上限，不报错。</p>
     */
    public RaftClusterView view() {
        RaftClusterView v = new RaftClusterView();
        if (state == RaftClusterState.CLIENT_ONLY) {
            RaftClient c = client;
            RaftClusterView lv = c == null ? null : c.leaderView();
            if (lv != null) {
                v.setLeaderId(lv.getLeaderId());
                v.setLeaderHost(lv.getLeaderHost());
                v.setLeaderPort(lv.getLeaderPort());
                v.setLeaderManagerHost(lv.getLeaderManagerHost());
                v.setLeaderManagerPort(lv.getLeaderManagerPort());
                v.setMembers(lv.getMembers());
            }
            v.setCapacity(config.capacity);
            return v;
        }
        long leader = currentLeaderId();
        v.setLeaderId(leader);
        RaftMember lm = membership.member(leader);
        if (lm != null) {
            v.setLeaderHost(lm.getHost());
            v.setLeaderPort(lm.getPort());
            v.setLeaderManagerHost(lm.getHost());
            v.setLeaderManagerPort(lm.getManagerPort());
        }
        v.setMembers(appliedMembers());
        v.setCapacity(config.capacity);
        return v;
    }

    /**
     * 我是不是主。
     */
    public boolean isLeader() {
        Node n = node;
        if (n == null) {
            return false;
        }
        Status st = n.status();
        if (st == null) {
            return false;
        }
        SoftState ss = st.getSoftState();
        return ss != null && ss.getRaftState() == RaftRole.LEADER && ss.getLead() == nodeId;
    }

    /**
     * 当前角色。
     */
    public RaftClusterState state() {
        return state;
    }

    /**
     * 本节点的 raft 节点 id；还没有身份时是 0。
     */
    public long nodeId() {
        return nodeId;
    }

    /**
     * 本节点的 raft 传输地址。
     */
    public String raftAddress() {
        return selfHost + ":" + config.selfPort;
    }

    /**
     * 本节点的 HTTP 管理地址。
     */
    public String managerAddress() {
        return selfManagerAddress();
    }

    /**
     * 成员表。
     */
    public RaftMembership membership() {
        return membership;
    }

    /**
     * 提案的超时时间（毫秒）。
     *
     * <p>调用方等回执时应当比这个值稍长一点 ——
     * 让节点自己的超时先触发，错误信息才准确。</p>
     */
    public long proposalTimeoutMillis() {
        return config.proposalTimeoutMillis;
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private String selfManagerAddress() {
        return selfHost + ":" + config.managerPort;
    }

    /**
     * raft 内核当前的状态（成员配置、复制进度、已应用位置）。
     *
     * <p>节点还没起来时返回 null。</p>
     */
    public Status status() {
        Node n = node;
        return n == null ? null : n.status();
    }

    /**
     * 当前 leader 的 id；没有 leader 时是 0。
     */
    private long currentLeaderId() {
        Node n = node;
        if (n == null) {
            return 0L;
        }
        Status st = n.status();
        if (st == null) {
            return 0L;
        }
        SoftState ss = st.getSoftState();
        return ss == null ? 0L : ss.getLead();
    }

    private void refreshState() {
        if (state == RaftClusterState.CLIENT_ONLY) {
            return;
        }
        long leader = currentLeaderId();
        membership.setLeaderId(leader);
        if (leader == nodeId && leader != 0) {
            state = RaftClusterState.LEADER;
        } else if (state != RaftClusterState.BOOTSTRAPPING) {
            state = RaftClusterState.FOLLOWER;
        }
    }

    private void fillLeader(RaftClaimReply reply) {
        if (state == RaftClusterState.CLIENT_ONLY) {
            // 只做客户端：不在成员表里，主的信息来自缓存的视图
            RaftClient c = client;
            RaftClusterView lv = c == null ? null : c.leaderView();
            if (lv != null) {
                reply.setLeaderHost(lv.getLeaderHost());
                reply.setLeaderPort(lv.getLeaderPort());
                reply.setLeaderManagerPort(lv.getLeaderManagerPort());
            }
            return;
        }
        long leader = currentLeaderId();
        RaftMember lm = membership.member(leader);
        if (lm != null) {
            reply.setLeaderHost(lm.getHost());
            reply.setLeaderPort(lm.getPort());
            reply.setLeaderManagerPort(lm.getManagerPort());
        }
    }

    private void fillLeader(RaftJoinResult result) {
        long leader = currentLeaderId();
        RaftMember lm = membership.member(leader);
        if (lm != null) {
            result.setLeaderHost(lm.getHost());
            result.setLeaderPort(lm.getPort());
            result.setLeaderManagerPort(lm.getManagerPort());
        }
    }

    /**
     * 本地提案的 requestId：最高位标记为 1，与客户端的 requestId 空间隔开。
     */
    private long nextLocalRequestId() {
        return LOCAL_REQUEST_FLAG | (localRequestSeq.getAndIncrement() & 0x7FFFFFFFFFFFFFFFL);
    }

    private void applyMembers(List<RaftMember> members) {
        if (members == null) {
            return;
        }
        for (RaftMember m : members) {
            if (m != null && m.getId() > 0) {
                membership.put(m);
            }
        }
        persistMembers();
    }

    private void restoreMembers() {
        RaftMemberList list = readJson(membersPath(), RaftMemberList.class);
        if (list != null) {
            applyMembers(list.getMembers());
        }
        long applied = readAppliedIndex();
        lastDeliveredIndex.set(applied);
        appliedPersisted = applied;
    }

    private void persistIdentity() {
        RaftIdentity identity = new RaftIdentity(nodeId, selfHost, config.selfPort,
                config.managerPort, instanceId);
        writeJson(identityPath(), identity);
    }

    private void persistMembers() {
        writeJson(membersPath(), new RaftMemberList(membership.all()));
    }

    private RaftIdentity loadIdentity() {
        return readJson(identityPath(), RaftIdentity.class);
    }

    /**
     * 落盘 applied index（节流）。
     *
     * <p>apply 线程每批都会调，不能每批都写一次盘 ——
     * 一秒一次足够：崩了最多重放一秒的条目，
     * 而重放的部分会被 {@link #onApply} 的 index 检查挡在业务状态机之外。</p>
     */
    private void persistAppliedIndex(long index, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - appliedPersistMillis < 1000) {
            return;
        }
        appliedPersistMillis = now;
        appliedPersisted = index;
        try {
            writeFile(appliedPath(), Long.toString(index));
        } catch (Exception e) {
            log.warn("raft.cluster: 落盘 applied index 失败", e);
        }
    }

    private long readAppliedIndex() {
        try {
            Path p = appliedPath();
            if (!Files.exists(p)) {
                return 0L;
            }
            String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? 0L : Long.parseLong(s);
        } catch (Exception e) {
            log.warn("raft.cluster: 读 applied index 失败", e);
            return 0L;
        }
    }

    private Path identityPath() {
        return Paths.get(config.dataDir, "node.json");
    }

    private Path membersPath() {
        return Paths.get(config.dataDir, "members.json");
    }

    private Path appliedPath() {
        return Paths.get(config.dataDir, "applied.id");
    }

    private <T> void writeJson(Path path, T value) {
        try {
            writeFile(path, new String(io.edap.json.Eson.toJsonBytes(value), StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("raft.cluster: 写 " + path + " 失败", e);
        }
    }

    private <T> T readJson(Path path, Class<T> type) {
        try {
            if (!Files.exists(path)) {
                return null;
            }
            byte[] data = Files.readAllBytes(path);
            if (data.length == 0) {
                return null;
            }
            return io.edap.json.Eson.parseObject(data, type);
        } catch (Exception e) {
            log.warn("raft.cluster: 读 " + path + " 失败", e);
            return null;
        }
    }

    /**
     * 原子写：先写临时文件再改名，避免半个文件。
     */
    private void writeFile(Path path, String content) throws IOException {
        Path dir = path.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(tmp, content.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // 某些文件系统不支持原子改名，退化成普通改名
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
