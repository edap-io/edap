package io.edap.raft;

import io.edap.Edap;
import io.edap.raft.protocol.ConfChangeCodec;
import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.EntryType;
import io.edap.raft.protocol.Message;
import io.edap.raft.protocol.MessageType;
import io.edap.raft.storage.FileRaftStorage;
import io.edap.raft.transport.NioRaftTransport;
import io.edap.raft.transport.RaftTransport;
import io.edap.raft.transport.TransportStats;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * 多节点集群 + 真实磁盘的吞吐基线。
 *
 * <p>与 {@link AsyncProposalHarness} 的区别：这里是真的 3 节点集群，
 * 每个节点一份 {@link FileRaftStorage}（{@code append()} 末尾有
 * {@code force(true)}，即真 fsync），日志要复制到多数派才能提交。</p>
 *
 * <p>每个节点一个应用线程，驱动流程与 etcd 的 {@code raftNode.start()} 一致：</p>
 * <pre>
 *   Ready rd = node.ready(timeout);
 *   storage.setHardState(rd.getHardState());   // 硬状态
 *   storage.append(rd.getEntries());           // 日志（真 fsync）
 *   sendMessages(rd.getMessages());            // 发给对端
 *   applyCommittedEntries(rd.getCommittedEntries());
 *   node.advance();
 * </pre>
 *
 * <p>跑法：</p>
 * <pre>
 * java -cp ... io.edap.raft.ClusterProposalHarness \
 *      [节点数] [客户端数] [时长秒] [sync|sync-commit|async|async-commit|async-apply] [每客户端在途上限] [tick毫秒] \
 *      [数据目录] [落盘模式] [传输方式] [提案队列容量] [队列满策略]
 * </pre>
 *
 * <p>参数说明：</p>
 * <ul>
 *   <li>{@code sync}：提案阻塞到状态机 step 完，不等提交</li>
 *   <li>{@code sync-commit}：提案阻塞到状态机 step 完，再等自己的提案被应用</li>
 *   <li>{@code async}：异步提案（{@code proposeAsync}），不等提交</li>
 *   <li>{@code async-commit}：异步提案，再等自己的提案被应用。
 *       「每客户端在途上限」是允许的未确认提案条数，窗口=1 时退化成同步语义</li>
 *   <li>{@code async-apply}：异步提案（{@code proposeAsync}，入队即返回，不阻塞），
 *       但完成点是「应用」。与 {@code sync-commit} 只差「提案入口是否阻塞」一项，
 *       用来把「完成点」和「提案入口阻塞与否」两个因素分开</li>
 *   <li>{@code 数据目录}：留空则用临时目录并在结束后删除；非空则保留数据</li>
 *   <li>{@code 传输方式}：
 *     <ul>
 *       <li>{@code direct}（默认）：进程内直投，不序列化不走网络</li>
 *       <li>{@code tcp}：TCP 回环 + {@code RaftCodec} 序列化，
 *           与 dragonboat 的传输条件对等（它那边是 TCP 回环 + protobuf）</li>
 *       <li>{@code nio}：生产传输层（入站 edap-nio / 出站阻塞 Socket），
 *           三个节点共享一个 {@code Edap} 实例</li>
 *     </ul>
 *   </li>
 *   <li>{@code 落盘模式}：
 *     <ul>
 *       <li>{@code app}（默认）：应用层落盘，调用
 *           {@code storage.save(hardState, entries)}，一次 Ready 一次 fsync；
 *           已提交条目由应用层应用</li>
 *       <li>{@code no-meta}：应用层落盘但跳过硬状态，只写日志</li>
 *       <li>{@code app+apply}：应用层落盘，已提交条目交给独立的 apply 线程</li>
 *       <li>{@code framework}：框架（Node）接管落盘，应用层不再落盘；
 *           已提交条目仍由应用层应用</li>
 *       <li>{@code framework+apply}：框架接管落盘，已提交条目交给独立的 apply 线程</li>
 *     </ul>
 *   </li>
 *   <li>{@code 提案队列容量}：提案队列的容量上限，0 表示不限制（默认 2048）</li>
 *   <li>{@code 队列满策略}：{@code block}（默认）阻塞等待，{@code reject} 立刻拒绝</li>
 * </ul>
 *
 * <p>注意：{@code 数据目录} 传空串会被当成当前目录（{@code Paths.get("")}），
 * 会在仓库根留下 {@code node-1/} 等目录，要留空就别传这个参数。</p>
 */
public final class ClusterProposalHarness {

    /** 载荷：4 字节客户端 id + 8 字节序号 + 8 字节发布时间，共 20 字节 */
    private static final int PAYLOAD_LEN = 20;

    /**
     * 延迟样本容量。
     *
     * <p><b>不要为了「覆盖整轮」而调大容量</b> —— 实测（4 模式 A/B，w=1024）：
     * 把数组从 400,000 涨到 4,000,000（3.2MB → 32MB），
     * 提交/s 从 245,118 掉到 233,407（−4.8%），而且<b>批次变碎</b>
     * （落盘 116/s、6,386 条/次 → 130/s、5,200 条/次）——
     * 原因是采样数组的内存占用把 raft 的工作集挤出 L2，
     * 落盘闸门里的「忙时占比」判据因此变了。</p>
     *
     * <p>所以容量保持 400,000，靠<b>蓄水池抽样</b>而不是加大数组来覆盖整轮：
     * 两者内存占用完全一样（都是 400,000 × 8B），行为也就一样。</p>
     */
    private static final int LATENCY_CAPACITY = 400_000;

    /** propose() 的往返耗时采样（发布到状态机 step 完） */
    private static final LatencyRecorder PROP_RT_NANOS = new LatencyRecorder(200_000);

    /** 异步提案的等待超时，与 dragonboat 基准的 30 秒同量级 */
    private static final long ASYNC_TIMEOUT_MILLIS = 30_000;

    /** nio 传输模式的起始端口 */
    private static final int NIO_BASE_PORT = 9300;

    /**
     * leader 发出复制消息（MSG_APP）的时刻，按 (客户端 id, 序号) 记。
     *
     * <p>用来把「发布 → 提交」的 RTT 拆成两段：发出去之前的一段，
     * 和发出去之后的一段。与 Go 侧的 {@code /tmp/etcdbench} 用同一个口径，
     * 两边才能直接比。</p>
     */
    private static final java.util.concurrent.ConcurrentHashMap<Long, Long> SEND_MARKS
            = new java.util.concurrent.ConcurrentHashMap<>();

    /** leader 发出的、带条目的 MSG_APP 次数 */
    private static final AtomicLong LEADER_APP_SENDS = new AtomicLong();

    /** leader 发出的 MSG_APP 携带的条目总数 */
    private static final AtomicLong LEADER_APP_ENTRIES = new AtomicLong();

    /** 客户端提交时没找到发送时刻的次数（用来发现采样偏差） */
    private static final AtomicLong MISSING_MARKS = new AtomicLong();

    /**
     * 诊断开关：{@code -Dedap.rtt.probe=true} 打开。
     *
     * <p>打开后额外记录三样东西，用来定位「发布 → MSG_APP 发出」这一段：
     * leader 每条复制消息里<b>所有</b>条目的年龄分布（不只客户端等的那条）、
     * leader 复制消息的发送间隔、每个节点自己的落盘占用率。
     * 默认关闭 —— 基准跑批用同一个二进制，开着探针会多几次 nanoTime。</p>
     */
    private static final boolean RTT_PROBE = Boolean.getBoolean("edap.rtt.probe");

    /** 探针：leader 复制消息里所有条目的年龄（发送时刻 - 发布时刻） */
    private static final LatencyRecorder PROBE_ENTRY_AGE = new LatencyRecorder(400_000);

    /** 探针：leader 复制消息的发送间隔（按目标节点分开记） */
    private static final java.util.concurrent.ConcurrentHashMap<Long, LatencyRecorder> PROBE_GAPS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 探针：leader 上一条复制消息的发送时刻（按目标节点分开记） */
    private static final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicLong>
            PROBE_LAST_SEND = new java.util.concurrent.ConcurrentHashMap<>();

    /** 探针：发布 → 状态机接收的耗时（同步 propose 的往返） */
    private static final LatencyRecorder PROBE_SM_ACCEPT = new LatencyRecorder(100_000);

    /**
     * 探针的「同步 propose 线程」单独开关。
     *
     * <p>这个线程会在任意时刻唤醒状态机线程，把提案队列提前排空，
     * 打破「客户端发布 ↔ 状态机组装 Ready」的相位锁定，
     * 属于对被测系统的主动扰动，默认关掉。</p>
     */
    private static final boolean RTT_PROBE_SYNC = Boolean.getBoolean("edap.rtt.probe.sync");

    /**
     * 诊断开关：{@code -Dedap.raft.diag=true} 打开。
     *
     * <p>打开后额外做两件事：
     * ① {@link Node} 的主循环记录分段计时（持久化周期、提交通知耗时、提案排空批大小）；
     * ② 测量窗口两端采样各 raft 线程的 CPU 时间。</p>
     *
     * <p>用来回答「一个持久化周期里时间花在哪」——
     * 是花在 fsync 上，还是花在「把 future 完成掉、把客户端唤醒」上。</p>
     */
    private static final boolean DIAG = Boolean.getBoolean("edap.raft.diag");

    /** 诊断：测量窗口开始时各节点线程的 CPU 时间（纳秒），{状态机, 落盘, apply} */
    private static final Map<Long, long[]> CPU_BEFORE = new LinkedHashMap<>();

    /** 诊断：测量窗口结束时各节点线程的 CPU 时间（纳秒），{状态机, 落盘, apply} */
    private static final Map<Long, long[]> CPU_AFTER = new LinkedHashMap<>();

    /**
     * 诊断：采样各节点三个线程的累计 CPU 时间。
     *
     * @param out 采样结果，按节点 id 存
     */
    private static void sampleRaftCpu(Map<Long, Member> members, Map<Long, long[]> out) {
        ThreadMXBean tb = ManagementFactory.getThreadMXBean();
        for (Member m : members.values()) {
            long w = tb.getThreadCpuTime(m.node.workerThreadId());
            long p = m.node.persistThreadId() < 0 ? -1L : tb.getThreadCpuTime(m.node.persistThreadId());
            long a = m.node.applyThreadId() < 0 ? -1L : tb.getThreadCpuTime(m.node.applyThreadId());
            out.put(m.id, new long[] {w, p, a});
        }
    }

    /** SEND_MARKS 的 key：把 (客户端 id, 序号) 压成一个 long */
    private static long markKey(int cid, long seq) {
        return ((long) cid << 40) | (seq & 0xFFFFFFFFFFL);
    }

    /**
     * 一个集群成员。
     */
    static final class Member {

        /** 节点 id */
        final long                 id;

        /** 本节点在集群里的地址 */
        final Peer                 peer;

        /** 真实磁盘存储 */
        final FileRaftStorage      storage;

        /** 节点，节点创建之后才填 */
        volatile Node              node;

        /** 传输层，direct 模式下为 null */
        volatile RaftTransport     transport;

        /** 传输层的健康度计数，direct 模式下为 null */
        volatile TransportStats    stats;

        /** 已应用的条目总数 */
        final AtomicLong           applied = new AtomicLong();

        /** 转发出去的消息数 */
        final AtomicLong           sentMessages = new AtomicLong();

        Member(long id, Peer peer, FileRaftStorage storage) {
            this.id = id;
            this.peer = peer;
            this.storage = storage;
        }
    }

    /**
     * 应用侧的计数与延迟采样。
     *
     * <p>延迟的测量点在<b>应用</b>，不是提交：dragonboat 的
     * {@code RequestResult.Completed()} 是「已提交并且已在本地节点应用」，
     * 两边的口径必须一致。</p>
     *
     * <p>发布时间随载荷一起进日志，应用侧直接就能算出「发布 → 应用」的耗时，
     * 不需要客户端线程去配对序号 —— 有提案被拒绝时序号会开洞，
     * 按序号配对会错位。</p>
     */
    static final class ApplyStats {

        /** 每个客户端已应用的条目数（只有 leader 上累加） */
        final AtomicLong[] committed;

        /** 每个客户端的锁 */
        final Object[] commitLocks;

        /** 延迟样本（发布 → 应用） */
        final LatencyRecorder latencies;

        /** leader 上应用的条目总数 */
        final AtomicLong leaderCommitted = new AtomicLong();

        /** 当前在途（已发布未提交）的提案数 —— 用来判断流水线是不是填满了 */
        final AtomicInteger inFlight = new AtomicInteger();

        /** 在途深度的采样（每 100ms 一个点） */
        final List<Long> inFlightSamples = Collections.synchronizedList(new ArrayList<>());

        /**
         * 客户端可见的「发布 → 提交」延迟样本。
         *
         * <p>与 {@link #latencies}（发布 → 应用）分开记：应用点受
         * 「apply 是否等落盘」影响，两边架构不同，不能直接比；
         * 而「提案被提交」是客户端真正等到的那个点（异步提案的 future
         * 在提交时完成），口径一致，可以跨系统比。</p>
         */
        final LatencyRecorder commitLatencies;

        /**
         * RTT 分解：把「发布 → 提交」拆成
         * ① 发布 → leader 把这批条目发出去（MSG_APP）
         * ② MSG_APP 发出 → 提案被提交
         *
         * <p>用来定位 RTT 到底耗在哪一段 —— 只看总延迟说不清是谁的问题。</p>
         */
        final LatencyRecorder propToSendLatencies;

        /** RTT 分解②的样本 */
        final LatencyRecorder sendToCommitLatencies;

        ApplyStats(int clients, int latencyCapacity) {
            committed = new AtomicLong[clients];
            commitLocks = new Object[clients];
            for (int i = 0; i < clients; i++) {
                committed[i] = new AtomicLong();
                commitLocks[i] = new Object();
            }
            latencies = new LatencyRecorder(latencyCapacity);
            commitLatencies = new LatencyRecorder(latencyCapacity);
            propToSendLatencies = new LatencyRecorder(latencyCapacity);
            sendToCommitLatencies = new LatencyRecorder(latencyCapacity);
        }

        /** 记一条「发布 → 提交」的客户端可见延迟 */
        void recordCommit(long latency) {
            commitLatencies.add(latency);
        }

        /** 记一条 RTT 分解样本（两段） */
        void recordSplit(long propToSend, long sendToCommit) {
            propToSendLatencies.add(propToSend);
            sendToCommitLatencies.add(sendToCommit);
        }

        /**
         * 记录一条已提交条目。
         *
         * <p>只统计 leader 上的 —— 同一个条目在每个节点上都会应用一次，
         * 全算就重复了。</p>
         */
        void record(long memberId, AtomicLong leaderId, Entry e) {
            byte[] data = e.getData();
            if (data == null || data.length != PAYLOAD_LEN) {
                return;
            }
            ByteBuffer buf = ByteBuffer.wrap(data);
            int cid = buf.getInt();
            buf.getLong();          // 序号，用不上
            long publishedAt = buf.getLong();
            if (cid < 0 || cid >= committed.length) {
                return;
            }
            if (memberId != leaderId.get()) {
                return;
            }
            long latency = System.nanoTime() - publishedAt;
            latencies.add(latency);
            leaderCommitted.incrementAndGet();
            synchronized (commitLocks[cid]) {
                committed[cid].incrementAndGet();
                commitLocks[cid].notifyAll();
            }
        }
    }

    /**
     * 独立 apply 线程用的处理器：统计提交数并唤醒等待的客户端。
     */
    static final class CountingApplyHandler implements ApplyHandler {

        private final Member                member;
        private final AtomicLong            leaderId;
        private final ApplyStats            stats;

        CountingApplyHandler(Member member, AtomicLong leaderId, ApplyStats stats) {
            this.member = member;
            this.leaderId = leaderId;
            this.stats = stats;
        }

        @Override
        public void apply(List<Entry> entries) {
            for (Entry e : entries) {
                member.applied.incrementAndGet();
                stats.record(member.id, leaderId, e);
            }
        }
    }

    /**
     * 一个在途的异步提案。
     */
    static final class Pending {

        final long                     seq;
        final CompletableFuture<Void> future;

        /** 发布时刻，用来算客户端可见的「发布 → 提交」延迟 */
        final long                     publishedAt;

        Pending(long seq, CompletableFuture<Void> future, long publishedAt) {
            this.seq = seq;
            this.future = future;
            this.publishedAt = publishedAt;
        }
    }

    public static void main(String[] args) throws Exception {
        int nodeCount = args.length > 0 ? Integer.parseInt(args[0]) : 3;
        int clients = args.length > 1 ? Integer.parseInt(args[1]) : 16;
        int durationSec = args.length > 2 ? Integer.parseInt(args[2]) : 10;
        // 模式：
        //   sync         提案阻塞到状态机 step 完，不等提交
        //   sync-commit  提案阻塞到状态机 step 完，再等自己的提案被应用
        //   async        异步提案（proposeAsync），不等提交
        //   async-commit 异步提案，再等自己的提案被应用
        String mode = args.length > 3 ? args[3] : "sync-commit";
        int maxInflight = args.length > 4 ? Integer.parseInt(args[4]) : 1;
        int tickMillis = args.length > 5 ? Integer.parseInt(args[5]) : 50;
        Path baseDir = args.length > 6 && !args[6].isEmpty()
                ? java.nio.file.Paths.get(args[6]) : null;
        // 落盘与应用模式：
        //   app            应用层落盘（一次 save），应用层应用已提交条目
        //   no-meta        应用层落盘但跳过硬状态
        //   app+apply      应用层落盘，已提交条目交给独立的 apply 线程
        //   framework      框架（Node）接管落盘，应用层应用已提交条目
        //   framework+apply 框架接管落盘，已提交条目交给独立的 apply 线程
        String persistMode = args.length > 7 && !args[7].isEmpty() ? args[7] : "app";
        boolean frameworkPersist = persistMode.startsWith("framework");
        boolean separateApply = persistMode.endsWith("+apply");
        boolean persistHardState = !"no-meta".equals(persistMode);
        // 传输方式：
        //   direct 进程内直投，不序列化不走网络（默认）
        //   tcp    TCP 回环 + RaftCodec 序列化，与 dragonboat 的传输条件对等
        //   nio    生产传输层：入站 edap-nio / 出站阻塞 Socket
        String transportMode = args.length > 8 && !args[8].isEmpty() ? args[8] : "direct";
        boolean tcpTransport = "tcp".equals(transportMode);
        boolean nioTransport = "nio".equals(transportMode);
        if (!tcpTransport && !nioTransport && !"direct".equals(transportMode)) {
            throw new IllegalArgumentException("unknown transport mode: " + transportMode);
        }
        // 提案队列容量（0 = 不限制）与队列满时的策略
        int maxPendingProposals = args.length > 9 && !args[9].isEmpty()
                ? Integer.parseInt(args[9]) : 2048;
        String policyName = args.length > 10 && !args[10].isEmpty() ? args[10] : "block";
        ProposalFullPolicy fullPolicy = "reject".equalsIgnoreCase(policyName)
                ? ProposalFullPolicy.REJECT : ProposalFullPolicy.BLOCK;

        // 异步路径：提案走 proposeAsync，不阻塞调用方
        // 「async-apply」是隔离实验用的模式：提案走 proposeAsync（不阻塞），
        // 但完成点是「应用」—— 与 sync-commit 只差「提案入口是否阻塞」这一项。
        boolean fireAndForget = mode.startsWith("async") && !mode.endsWith("-apply");
        boolean waitCommit = mode.endsWith("-commit") || mode.endsWith("-apply");
        boolean nonBlockingPropose = mode.endsWith("-apply");
        if (!fireAndForget && !waitCommit && !"sync".equals(mode)) {
            throw new IllegalArgumentException("unknown mode: " + mode);
        }

        Path root = baseDir != null ? baseDir : Files.createTempDirectory("edap-raft-cluster");
        Files.createDirectories(root);

        Map<Long, Member> members = new LinkedHashMap<>();
        List<Peer> peers = new ArrayList<>(nodeCount);
        List<TcpLoopbackTransport> transports = new ArrayList<>(nodeCount);
        // nio 模式：三个节点共享一个 Edap，避免起三套 Selector/Disruptor
        Edap sharedEdap = nioTransport ? new Edap() : null;
        if (tcpTransport) {
            // TCP 模式：先绑定回环端口（端口由内核分配），再用实际端口组成成员列表
            for (int i = 0; i < nodeCount; i++) {
                TcpLoopbackTransport transport = new TcpLoopbackTransport();
                int port = transport.bind();
                transports.add(transport);
                peers.add(new Peer(i + 1, "127.0.0.1", port));
            }
        } else if (nioTransport) {
            // nio 模式：端口必须显式指定（AbstractAcceptor.bind 不回读实际端口）
            for (int i = 0; i < nodeCount; i++) {
                peers.add(new Peer(i + 1, "127.0.0.1", NIO_BASE_PORT + i));
            }
        } else {
            for (int i = 0; i < nodeCount; i++) {
                peers.add(new Peer(i + 1, "127.0.0.1", 9000 + i));
            }
        }

        AtomicBoolean stop = new AtomicBoolean();
        ApplyStats stats = new ApplyStats(clients, LATENCY_CAPACITY);
        AtomicLong[] committed = stats.committed;
        Object[] commitLocks = stats.commitLocks;
        // 当前 leader，由追踪线程维护
        AtomicLong leaderId = new AtomicLong(Raft.NONE);
        // 拒绝统计：队列满 vs raft 丢弃
        AtomicLong published = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        AtomicLong rejectedBusy = new AtomicLong();
        AtomicLong rejectedDropped = new AtomicLong();

        try {
            for (int i = 0; i < peers.size(); i++) {
                Peer peer = peers.get(i);
                FileRaftStorage storage = new FileRaftStorage(root.resolve("node-" + peer.getNodeId()));
                RaftConfig cfg = new RaftConfig();
                cfg.id = peer.getNodeId();
                cfg.electionTick = 10;
                cfg.heartbeatTick = 1;
                cfg.storage = storage;
                cfg.maxSizePerMsg = RaftConfig.NO_LIMIT;
                cfg.maxInflightMsgs = 256;
                cfg.frameworkPersist = frameworkPersist;
                cfg.maxPendingProposals = maxPendingProposals;
                cfg.proposalFullPolicy = fullPolicy;
                Member m = new Member(peer.getNodeId(), peer, storage);
                if (separateApply) {
                    // apply 线程要用到成员信息，节点建好之前先把成员对象准备好
                    cfg.applyHandler = new CountingApplyHandler(m, leaderId, stats);
                }
                if (tcpTransport) {
                    m.transport = transports.get(i);
                    m.stats = transports.get(i);
                } else if (nioTransport) {
                    NioRaftTransport transport = new NioRaftTransport("127.0.0.1", peer.getPort(), sharedEdap);
                    m.transport = transport;
                    m.stats = transport;
                }
                m.node = Node.startNode(cfg, peers.toArray(new Peer[0]));
                members.put(m.id, m);
            }

            // 传输层：节点都建好之后，再把入站回调挂上并启动传输层
            for (Member m : members.values()) {
                if (m.transport != null) {
                    m.transport.setHandler(m.node::onMessage);
                    m.transport.start();
                }
            }
            if (nioTransport) {
                // 三个 server 都入队之后一次性 bind：run 之前只是入队
                sharedEdap.run();
            }

            // 每个节点一个应用线程
            List<Thread> appThreads = new ArrayList<>();
            for (Member m : members.values()) {
                Thread t = new Thread(() -> runApp(m, members, leaderId, stats, stop,
                                frameworkPersist, separateApply, persistHardState),
                        "raft-app-" + m.id);
                t.setDaemon(true);
                t.start();
                appThreads.add(t);
            }

            // tick 驱动：所有节点共用一个线程，按固定节奏推进逻辑时钟
            Thread ticker = new Thread(() -> {
                long interval = tickMillis;
                while (!stop.get()) {
                    long start = System.currentTimeMillis();
                    for (Member m : members.values()) {
                        m.node.tick();
                    }
                    long spent = System.currentTimeMillis() - start;
                    if (spent < interval) {
                        try {
                            Thread.sleep(interval - spent);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }, "raft-ticker");
            ticker.setDaemon(true);
            ticker.start();

            // leader 追踪
            Thread leaderTracker = new Thread(() -> {
                while (!stop.get()) {
                    for (Member m : members.values()) {
                        try {
                            long lead = m.node.status().getSoftState().getLead();
                            if (lead != Raft.NONE) {
                                leaderId.set(lead);
                                break;
                            }
                        } catch (RuntimeException e) {
                            // 节点停止时会抛，忽略
                        }
                    }
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "raft-leader-tracker");
            leaderTracker.setDaemon(true);
            leaderTracker.start();

            // 在途深度采样：判断流水线是不是被「每客户端在途窗口」填满
            Thread gauge = new Thread(() -> {
                while (!stop.get()) {
                    stats.inFlightSamples.add((long) stats.inFlight.get());
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "raft-inflight-gauge");
            gauge.setDaemon(true);
            gauge.start();

            // 等选主完成
            long deadline = System.currentTimeMillis() + 15000;
            while (leaderId.get() == Raft.NONE && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            if (leaderId.get() == Raft.NONE) {
                throw new IllegalStateException("cluster has no leader");
            }

            CountDownLatch done = new CountDownLatch(clients);
            if (RTT_PROBE && RTT_PROBE_SYNC) {
                // 探针线程：用同步 propose 测「发布 → 状态机接收」的耗时。
                // 同步 propose 返回的时刻就是状态机 step 完的时刻，
                // 这一点异步接口不暴露，只能用同步接口测。
                Thread probe = new Thread(() -> {
                    long seq = 0;
                    while (!stop.get()) {
                        long lead = leaderId.get();
                        Member leader = lead == Raft.NONE ? null : members.get(lead);
                        if (leader == null) {
                            sleep(10);
                            continue;
                        }
                        try {
                            seq++;
                            byte[] data = payload(-1, seq, System.nanoTime());
                            long t0 = System.nanoTime();
                            leader.node.propose(data);
                            PROBE_SM_ACCEPT.add(System.nanoTime() - t0);
                        } catch (RaftException e) {
                            // 节点停了
                        }
                        sleep(5);
                    }
                }, "rtt-probe");
                probe.setDaemon(true);
                probe.start();
            }
            for (int i = 0; i < clients; i++) {
                final int cid = i;
                Thread t = new Thread(() -> {
                    try {
                        if (fireAndForget) {
                            runAsyncClient(cid, maxInflight, members, leaderId, stop,
                                    published, failed, rejectedBusy, rejectedDropped, stats);
                        } else {
                            runSyncClient(cid, waitCommit, maxInflight, members, leaderId, stats,
                                    stop, published, failed, rejectedBusy, rejectedDropped,
                                    nonBlockingPropose);
                        }
                    } catch (RaftException e) {
                        // 节点停止
                    } finally {
                        done.countDown();
                    }
                }, "producer-" + i);
                t.setDaemon(true);
                t.start();
            }

            // 诊断：测量窗口开始前把分段计时清零，并在窗口两端采样各线程的 CPU 时间
            if (DIAG) {
                for (Member m : members.values()) {
                    m.node.diagReset();
                }
                CPU_BEFORE.clear();
                CPU_AFTER.clear();
                sampleRaftCpu(members, CPU_BEFORE);
            }
            Thread.sleep(durationSec * 1000L);
            if (DIAG) {
                sampleRaftCpu(members, CPU_AFTER);
            }
            stop.set(true);
            done.await(10, TimeUnit.SECONDS);
            for (Member m : members.values()) {
                m.node.stop();
                m.storage.close();
                if (m.transport != null) {
                    m.transport.stop();
                }
            }
            if (sharedEdap != null) {
                sharedEdap.stop();
            }

            report(members, clients, durationSec, mode,
                    published.get(), failed.get(), rejectedBusy.get(), rejectedDropped.get(),
                    stats, root, baseDir != null, transportMode, maxInflight, leaderId.get());
            System.out.flush();
            if (sharedEdap != null) {
                // edap-nio 的 acceptor 线程是非 daemon，JVM 自己不会退出。
                // （EdapTime 的刷新线程现在是 daemon，不再是这个原因。）
                // 基准跑完必须显式退出，否则 shell 会一直等。
                System.exit(0);
            }
        } finally {
            stop.set(true);
        }
    }

    /**
     * 应用线程：取 Ready → 落盘 → 发消息 → 应用已提交条目 → advance。
     */
    private static void runApp(Member m, Map<Long, Member> members, AtomicLong leaderId,
            ApplyStats stats, AtomicBoolean stop, boolean frameworkPersist, boolean separateApply,
            boolean persistHardState) {
        while (!stop.get()) {
            Ready rd;
            try {
                rd = m.node.ready(20, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (rd == null) {
                continue;
            }
            try {
                if (!frameworkPersist) {
                    // 1. 先把复制类消息发出去：它们不带任何持久化承诺，
                    //    对端可以立刻开始落盘，与本节点的落盘重叠，
                    //    两个 fsync 不串行，端到端延迟少一个落盘周期。
                    sendMessages(m, rd, members, false, leaderId);
                    // 2. 硬状态与日志一次落盘（一次 fsync）
                    if ((persistHardState && rd.getHardState() != null) || !rd.getEntries().isEmpty()) {
                        m.storage.save(persistHardState ? rd.getHardState() : null, rd.getEntries());
                    }
                    // 3. 快照
                    if (rd.getSnapshot() != null) {
                        m.storage.applySnapshot(rd.getSnapshot());
                    }
                    // 4. 确认类消息等落盘之后再发：它们的意思是
                    //    「我已经持久化了这些日志」
                    sendMessages(m, rd, members, true, leaderId);
                } else {
                    // 框架接管落盘：框架保证确认类消息在落盘完成之后才放进
                    // Ready，所以这里一次性全发出去
                    sendAllMessages(m, rd, members, leaderId);
                }
                // 5. 应用已提交条目（独立 apply 线程模式下由 apply 线程负责）
                if (!separateApply) {
                    for (Entry e : rd.getCommittedEntries()) {
                        m.applied.incrementAndGet();
                        if (e.getType() == EntryType.CONFIG_CHANGE
                                || e.getType() == EntryType.CONFIG_CHANGE_V2) {
                            m.node.applyConfChange(ConfChangeCodec.unmarshal(e.getType(), e.getData()));
                            continue;
                        }
                        stats.record(m.id, leaderId, e);
                    }
                }
                // 6. 告诉 raft 可以继续
                m.node.advance();
            } catch (RaftException e) {
                if (stop.get()) {
                    return;
                }
            }
        }
    }

    /**
     * 把 Ready 里的消息发给对应的节点。
     *
     * @param gated true 表示只发确认类消息（投票确认、日志追加确认），
     *              false 表示只发复制类消息。确认类消息必须等日志落盘之后才发。
     */
    private static void sendMessages(Member m, Ready rd, Map<Long, Member> members, boolean gated,
            AtomicLong leaderId) {
        for (Message msg : rd.getMessages()) {
            if (Raft.isPersistGated(msg.getType()) != gated) {
                continue;
            }
            deliver(m, msg, members, leaderId);
        }
    }

    /**
     * 把 Ready 里的所有消息发给对应的节点。
     *
     * <p>框架落盘模式下用这个：框架已经保证确认类消息是在落盘完成之后
     * 才被放进 Ready 的，应用层不用再区分。</p>
     */
    private static void sendAllMessages(Member m, Ready rd, Map<Long, Member> members,
            AtomicLong leaderId) {
        for (Message msg : rd.getMessages()) {
            deliver(m, msg, members, leaderId);
        }
    }

    /**
     * 把一条消息投递给目标节点。
     *
     * <p>direct 模式进程内直投；tcp / nio 模式走各自的传输层。</p>
     */
    private static void deliver(Member m, Message msg, Map<Long, Member> members,
            AtomicLong leaderId) {
        Member target = members.get(msg.getTo());
        if (target == null) {
            return;
        }
        // leader 发出复制消息的时刻：记下来，用来分解 RTT
        if (m.id == leaderId.get() && msg.getType() == MessageType.MSG_APP
                && !msg.getEntries().isEmpty()) {
            long now = System.nanoTime();
            LEADER_APP_SENDS.incrementAndGet();
            LEADER_APP_ENTRIES.addAndGet(msg.getEntries().size());
            if (RTT_PROBE) {
                // 复制消息的发送间隔：按目标节点分开记（同一批会发给两个对端，
                // 不分开的话两条会挨在一起，间隔就成了 0）
                java.util.concurrent.atomic.AtomicLong last =
                        PROBE_LAST_SEND.computeIfAbsent(msg.getTo(),
                                k -> new java.util.concurrent.atomic.AtomicLong());
                long prev = last.getAndSet(now);
                if (prev != 0) {
                    PROBE_GAPS.computeIfAbsent(msg.getTo(),
                            k -> new LatencyRecorder(100_000)).add(now - prev);
                }
            }
            for (Entry e : msg.getEntries()) {
                byte[] data = e.getData();
                if (data == null || data.length != PAYLOAD_LEN) {
                    continue;
                }
                ByteBuffer buf = ByteBuffer.wrap(data);
                int cid = buf.getInt();
                long seq = buf.getLong();
                if (RTT_PROBE) {
                    // 这一批里所有条目的年龄：发布时刻随载荷一起走
                    long publishedAt = buf.getLong();
                    PROBE_ENTRY_AGE.add(now - publishedAt);
                }
                // 只记第一次发送的时刻：重发会把时刻推后，
                // 那样量出来的「发布 → 发出」会被高估
                SEND_MARKS.putIfAbsent(markKey(cid, seq), now);
            }
        }
        RaftTransport transport = m.transport;
        if (transport != null) {
            transport.send(target.peer, msg);
        } else {
            target.node.onMessage(msg);
        }
        m.sentMessages.incrementAndGet();
    }

    /**
     * 同步客户端：提案阻塞到状态机 step 完。
     *
     * <p>在途窗口限制的是「已发布但还没被应用」的提案数。</p>
     */
    private static void runSyncClient(int cid, boolean waitCommit, int maxInflight,
            Map<Long, Member> members, AtomicLong leaderId, ApplyStats stats, AtomicBoolean stop,
            AtomicLong published, AtomicLong failed, AtomicLong rejectedBusy,
            AtomicLong rejectedDropped, boolean nonBlockingPropose) {
        long seq = 0;
        long publishedLocal = 0;
        long failedLocal = 0;
        long lastApplied = 0;
        int window = waitCommit ? maxInflight : Integer.MAX_VALUE;

        while (!stop.get()) {
            long lead = leaderId.get();
            Member leader = lead == Raft.NONE ? null : members.get(lead);
            if (leader == null) {
                sleep(10);
                continue;
            }
            // 把在途窗口填满
            while (publishedLocal - lastApplied - failedLocal < window && !stop.get()) {
                seq++;
                byte[] data = payload(cid, seq, System.nanoTime());
                try {
                    long t0 = System.nanoTime();
                    if (nonBlockingPropose) {
                        // 提案入口不阻塞：入队即返回，完成点仍然是「应用」
                        CompletableFuture<Void> f = leader.node.proposeAsync(data, ASYNC_TIMEOUT_MILLIS);
                        if (f.isDone() && f.isCompletedExceptionally()) {
                            // 提案根本没进去（队列满 / 节点已停止）
                            failedLocal++;
                            failed.incrementAndGet();
                            if (causeOf(f) instanceof RaftBusyException) {
                                rejectedBusy.incrementAndGet();
                            } else {
                                rejectedDropped.incrementAndGet();
                            }
                            continue;
                        }
                    } else {
                        leader.node.propose(data);
                    }
                    PROP_RT_NANOS.add(System.nanoTime() - t0);
                    publishedLocal++;
                    published.incrementAndGet();
                } catch (RaftBusyException e) {
                    // 队列满，按策略拒绝
                    failedLocal++;
                    failed.incrementAndGet();
                    rejectedBusy.incrementAndGet();
                } catch (RaftException e) {
                    // 被 raft 丢弃
                    failedLocal++;
                    failed.incrementAndGet();
                    rejectedDropped.incrementAndGet();
                }
            }
            if (!waitCommit) {
                continue;
            }
            // 等到有新的条目被应用
            if (!awaitApplied(cid, lastApplied, stats, stop)) {
                // leader 切换可能漏掉一条，重新对一次计数继续
                lastApplied = stats.committed[cid].get();
                continue;
            }
            lastApplied = stats.committed[cid].get();
        }
    }

    /**
     * 异步客户端：提案走 {@code proposeAsync}，不阻塞调用方。
     *
     * <p>模型照抄 dragonboat 的基准：滚动维持每客户端 window 个在途提案，
     * 提案被拒（队列满）就记一次失败并停止补窗口，然后等最老的那条完成。</p>
     */
    private static void runAsyncClient(int cid, int maxInflight, Map<Long, Member> members,
            AtomicLong leaderId, AtomicBoolean stop, AtomicLong published, AtomicLong failed,
            AtomicLong rejectedBusy, AtomicLong rejectedDropped, ApplyStats stats) {
        long seq = 0;
        ArrayDeque<Pending> inflight = new ArrayDeque<>();

        while (!stop.get()) {
            long lead = leaderId.get();
            Member leader = lead == Raft.NONE ? null : members.get(lead);
            if (leader == null) {
                sleep(10);
                continue;
            }
            // 补满窗口
            while (inflight.size() < maxInflight && !stop.get()) {
                seq++;
                long publishedAt = System.nanoTime();
                byte[] data = payload(cid, seq, publishedAt);
                CompletableFuture<Void> future;
                try {
                    future = leader.node.proposeAsync(data, ASYNC_TIMEOUT_MILLIS);
                } catch (RaftException e) {
                    failed.incrementAndGet();
                    rejectedDropped.incrementAndGet();
                    break;
                }
                if (future.isDone() && future.isCompletedExceptionally()) {
                    // 提案根本没进去（队列满 / 节点已停止）
                    failed.incrementAndGet();
                    if (causeOf(future) instanceof RaftBusyException) {
                        rejectedBusy.incrementAndGet();
                    } else {
                        rejectedDropped.incrementAndGet();
                    }
                    break;
                }
                inflight.addLast(new Pending(seq, future, publishedAt));
                published.incrementAndGet();
                stats.inFlight.incrementAndGet();
            }
            if (inflight.isEmpty()) {
                sleep(1);
                continue;
            }
            // 等最老的一条完成（提案被提交）
            Pending p = inflight.peekFirst();
            try {
                p.future.get(ASYNC_TIMEOUT_MILLIS + 5000, TimeUnit.MILLISECONDS);
                // 客户端真正等到的点：提案被提交
                long committedAt = System.nanoTime();
                stats.recordCommit(committedAt - p.publishedAt);
                // RTT 分解：① 发布 → MSG_APP 发出，② MSG_APP 发出 → 提交
                Long sentAt = SEND_MARKS.remove(markKey(cid, p.seq));
                if (sentAt != null) {
                    stats.recordSplit(sentAt - p.publishedAt, committedAt - sentAt);
                } else {
                    MISSING_MARKS.incrementAndGet();
                }
            } catch (ExecutionException e) {
                // 被丢弃或者超时
                failed.incrementAndGet();
                rejectedDropped.incrementAndGet();
            } catch (TimeoutException e) {
                failed.incrementAndGet();
                rejectedDropped.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            inflight.pollFirst();
            stats.inFlight.decrementAndGet();
        }
    }

    /** 取 future 的异常原因 */
    private static Throwable causeOf(CompletableFuture<Void> future) {
        try {
            future.join();
            return null;
        } catch (java.util.concurrent.CompletionException e) {
            return e.getCause() != null ? e.getCause() : e;
        }
    }

    private static void report(Map<Long, Member> members, int clients, int durationSec,
            String mode, long published, long failed,
            long rejectedBusy, long rejectedDropped, ApplyStats stats, Path root,
            boolean keepDir, String transportMode, int maxInflight, long leaderId)
            throws IOException {
        double sec = durationSec;
        long persistRounds = 0;
        long persistedEntries = 0;
        long sent = 0;
        long persistNanos = 0;
        for (Member m : members.values()) {
            // 落盘账单统一取存储自己的计数：框架落盘时应用层看不到条目，
            // 只有存储知道这一轮真正写了多少
            persistRounds += m.storage.getPersistCount();
            persistedEntries += m.storage.getPersistEntries();
            persistNanos += m.storage.getPersistNanos();
            sent += m.sentMessages.get();
        }
        // 客户端真正看到的提交数：leader 上应用的普通条目
        long leaderCommitted = stats.leaderCommitted.get();
        System.out.printf(
                "mode=%-13s 传输=%-6s 节点=%d 客户端=%-3d  发布 %9.0f/s  提交 %9.0f/s  落盘 %7.0f/s"
                        + "  %7.1f 条/次落盘  转发消息 %9.0f/s%n",
                mode, transportMode, members.size(), clients,
                published / sec,
                leaderCommitted / sec,
                persistRounds / sec,
                persistRounds == 0 ? 0 : (double) persistedEntries / persistRounds,
                sent / sec);
        System.out.printf("  落盘耗时 平均 %.2fms/次（共 %d 次）%n",
                persistRounds == 0 ? 0 : persistNanos / 1e6 / persistRounds, persistRounds);
        // 拒绝率：口径对齐 dragonboat 的 ErrSystemBusy
        // （失败数 / (发布数 + 失败数)）
        long totalAttempts = published + failed;
        System.out.printf("  失败 %d（队列满 %d / raft 丢弃 %d）拒绝率 %.4f%%%n",
                failed, rejectedBusy, rejectedDropped,
                totalAttempts == 0 ? 0 : 100.0 * failed / totalAttempts);
        long framesSent = 0;
        long bytesSent = 0;
        long framesRecv = 0;
        long framesDropped = 0;
        long criticalDropped = 0;
        for (Member m : members.values()) {
            TransportStats s = m.stats;
            if (s != null) {
                framesSent += s.getFramesSent();
                bytesSent += s.getBytesSent();
                framesRecv += s.getFramesReceived();
                framesDropped += s.getFramesDropped();
                criticalDropped += s.getCriticalFramesDropped();
            }
        }
        if (framesSent > 0 || framesRecv > 0) {
            System.out.printf("  传输 发出 %d 帧 / %.1f MB  收到 %d 帧  丢弃 %d 帧（复制类 %d）%n",
                    framesSent, bytesSent / 1024.0 / 1024.0, framesRecv, framesDropped, criticalDropped);
        }
        PROP_RT_NANOS.report("propose 往返");
        // 蓄水池样本：覆盖整轮（抽中数 = 容量，总数 = 整轮见到的样本数）
        long[] sorted = stats.latencies.snapshot();
        if (sorted.length > 0) {
            Arrays.sort(sorted);
            System.out.printf("  端到端延迟(发布→应用) p50=%.2fms p99=%.2fms 均值=%.2fms (样本 %d/%d)%n",
                    pct(sorted, 50) / 1e6, pct(sorted, 99) / 1e6, mean(sorted) / 1e6,
                    sorted.length, stats.latencies.total());
        }
        // 客户端可见的「发布 → 提交」：异步提案的 future 在提交时完成，
        // 这是跨系统可比的口径（应用点受架构影响，不能直接比）。
        long[] commitSorted = stats.commitLatencies.snapshot();
        if (commitSorted.length > 0) {
            Arrays.sort(commitSorted);
            System.out.printf("  端到端延迟(发布→提交) p50=%.2fms p99=%.2fms 均值=%.2fms (样本 %d/%d)%n",
                    pct(commitSorted, 50) / 1e6, pct(commitSorted, 99) / 1e6, mean(commitSorted) / 1e6,
                    commitSorted.length, stats.commitLatencies.total());
        }
        if (!stats.inFlightSamples.isEmpty()) {
            List<Long> s = new ArrayList<>(stats.inFlightSamples);
            long sum = 0;
            for (Long v : s) {
                sum += v;
            }
            System.out.printf("  在途深度 平均 %.1f 条（上限 %d）%n",
                    sum / (double) s.size(), clients * maxInflight);
        }
        // RTT 分解打到 stderr —— stdout 的格式要和 Go 侧完全一致，
        // 同一套解析器才能直接吃。
        long[] a = stats.propToSendLatencies.snapshot();
        if (a.length > 0) {
            Arrays.sort(a);
            long[] b = stats.sendToCommitLatencies.snapshot();
            Arrays.sort(b);
            System.err.printf("  RTT 分解① 发布→MSG_APP发出 p50=%.2fms p99=%.2fms 均值=%.2fms%n",
                    pct(a, 50) / 1e6, pct(a, 99) / 1e6, mean(a) / 1e6);
            System.err.printf("  RTT 分解② MSG_APP发出→提交 p50=%.2fms p99=%.2fms 均值=%.2fms%n",
                    pct(b, 50) / 1e6, pct(b, 99) / 1e6, mean(b) / 1e6);
        }
        System.err.printf("  leader MSG_APP 发出 %d 次（%.1f/s）携带条目 %d（%.1f 条/次）  未找到发送时刻 %d%n",
                LEADER_APP_SENDS.get(), LEADER_APP_SENDS.get() / (double) sec,
                LEADER_APP_ENTRIES.get(),
                LEADER_APP_ENTRIES.get() / (double) Math.max(1, LEADER_APP_SENDS.get()),
                MISSING_MARKS.get());
        if (RTT_PROBE) {
            // 探针：每个节点自己的落盘占用率（落盘耗时 / 运行时间）
            // —— 用来判断「Ready 组装被落盘闸门卡住」占了多少时间
            System.err.printf("  [探针] 每个节点落盘占用率：%n");
            for (Member m : members.values()) {
                long cnt = m.storage.getPersistCount();
                long nanos = m.storage.getPersistNanos();
                long ents = m.storage.getPersistEntries();
                System.err.printf(
                        "  [探针] 节点 %d: 落盘 %d 次（%.1f/s）平均 %.2fms/次 累计 %.2fms 占用率 %.1f%% 条/次 %.1f%n",
                        m.id, cnt, cnt / sec,
                        cnt == 0 ? 0 : nanos / 1e6 / cnt,
                        nanos / 1e6,
                        nanos / 1e9 / sec * 100,
                        cnt == 0 ? 0 : (double) ents / cnt);
            }
            PROBE_ENTRY_AGE.report("[探针] 复制消息里所有条目的年龄(发送-发布)");
            PROBE_SM_ACCEPT.report("[探针] 发布→状态机接收(sync propose 往返)");
            for (java.util.Map.Entry<Long, LatencyRecorder> e : PROBE_GAPS.entrySet()) {
                e.getValue().report("[探针] 到节点 " + e.getKey() + " 的复制消息发送间隔");
            }
        }
        if (!keepDir) {
            deleteTree(root);
        } else {
            System.out.println("  数据目录: " + root);
        }
        if (DIAG) {
            printDiag(members, leaderId, sec);
        }
        System.out.println();
    }

    /**
     * 诊断输出：持久化周期的时间去向 + 各线程 CPU 占用。
     *
     * <p>回答的问题：「条/次落盘翻倍、fsync 次数腰斩」到底是因为
     * ① 通知提前让提案赶上下一轮 Ready 组装（批更大），
     * 还是 ② 持久化周期本身变长了（周期更长 ⇒ 攒的条目更多）。</p>
     */
    private static void printDiag(Map<Long, Member> members, long lead, double sec) {
        System.err.printf("  [诊断] 持久化周期与通知开销（窗口 %.1fs）：%n", sec);
        for (Member m : members.values()) {
            Node.Diag d = m.node.diagSnapshot();
            String tag = m.id == lead ? " (leader)" : "";
            long tasks = d.persistTasks;
            if (tasks <= 0) {
                System.err.printf("  [诊断] 节点 %d%s: 没有采样到落盘批次%n", m.id, tag);
                continue;
            }
            double cycleMean = d.cycleNanosSum / (double) tasks / 1e6;
            double notifyPerCycle = d.notifyNanosSum / (double) tasks / 1e6;
            double notifyPerNotice = d.notifyCountSum == 0 ? 0
                    : d.notifyNanosSum / (double) d.notifyCountSum / 1e3;
            double batchMean = d.persistEntriesSum / (double) tasks;
            double expectMean = d.expectSum / (double) tasks;
            double deferWait = d.deferWaitNanosSum / (double) tasks / 1e6;
            double drainMean = d.drainCallsSum == 0 ? 0
                    : d.drainEntriesSum / (double) d.drainCallsSum;
            System.err.printf(
                    "  [诊断] 节点 %d%s: 落盘 %d 批 均值 %.0f 条（预期 %.0f）| 周期 均值 %.2fms"
                            + " 中位 %.2fms | 推迟 %d 次 累计等待 %.2fms/批 | 通知 %.2fms/批"
                            + "（占 %4.1f%%）每次 %.1fµs 共 %d 次 | 组装 %d 次 排空 %d 次每次 %.0f 条%n",
                    m.id, tag, tasks, batchMean, expectMean, cycleMean, medianCycle(d),
                    d.deferrals, deferWait, notifyPerCycle,
                    notifyPerCycle / cycleMean * 100, notifyPerNotice, d.notifyCountSum,
                    d.assemblies, d.drainCallsSum, drainMean);
            // 真落盘批（非空）的口径：区分「空 Ready 只带消息」和「真的写了盘」
            long real = d.realTasks;
            if (real > 0) {
                double realCycleMean = d.realCycleNanosSum / (double) real / 1e6;
                double drainsPerReal = d.drainsPerRealSum / (double) real;
                double drainGap = d.drainGapCount == 0 ? 0
                        : d.drainGapSum / (double) d.drainGapCount / 1e6;
                System.err.printf(
                        "  [诊断] 节点 %d%s: 真落盘 %d 批 均值 %.0f 条 | 真周期 均值 %.2fms"
                                + " 中位 %.2fms | 每真批攒 %.1f 次排空 | 提案到达→落盘 间隔 %.2fms"
                                + " | 空批 %d 次%n",
                        m.id, tag, real, d.persistEntriesSum / (double) real, realCycleMean,
                        medianRealCycle(d), drainsPerReal, drainGap, d.emptyTasks);
            }
            // 主循环节奏与「提案到达 vs 通知」的相位
            System.err.printf(
                    "  [诊断] 节点 %d%s: 主循环 %d 次（%.0f 次/s）| 网络消息 %d 条（%.0f 条/s）"
                            + " | commit 推进 %d 次 | 每轮消息 %.1f 条 | 通知事件 %d 次 每次 %.0f 个"
                            + " | 排空时队列已有 均值 %.0f 条 | 排空距上次通知 均值 %.2fms%n",
                    m.id, tag, d.loopIterations, d.loopIterations / sec,
                    d.netMessages, d.netMessages / sec, d.commitAdvances,
                    d.loopIterations == 0 ? 0 : d.netMessages / (double) d.loopIterations,
                    d.notifyEvents, d.notifyEvents == 0 ? 0
                            : d.notifyCountSum / (double) d.notifyEvents,
                    d.drainCallsSum == 0 ? 0 : d.queueDepthSum / (double) d.drainCallsSum,
                    d.drainCallsSum == 0 ? 0 : d.sinceNotifySum / (double) d.drainCallsSum / 1e6);
            // 「瓶颈在不在 fsync 上」：两个独立口径
            //   ① 窗口实测 = 落盘线程真的写+fsync 的时间 / 墙钟（不经过任何判据）
            //   ② 判据采样 = 碎片批次合并每次做判据时读到的忙时占比的均值
            long busyDelta = m.node.diagPersistBusyNanos() - d.persistBusyNanosAtStart;
            double busyWindow = busyDelta / (double) (long) (sec * 1e9);
            double busyAtDecision = d.busyRatioSamples == 0 ? -1
                    : d.busyRatioMilliSum / (double) d.busyRatioSamples / 1000.0;
            System.err.printf(
                    "  [诊断] 节点 %d%s: 落盘忙时占比 窗口实测 %.3f | 判据采样 %.3f"
                            + "（%d 次：≥阈值 %d / <阈值 %d / 未测出 %d）| 推迟 %d 次"
                            + "（判据未测出 %d / 已测出过阈值 %d）| 首次判据 @%.1fs"
                            + " 占比可测 @%.1fs 首次推迟 @%.1fs 末次推迟 @%.1fs%n",
                    m.id, tag, busyWindow, busyAtDecision, d.busyRatioSamples,
                    d.busyAboveThreshold, d.busyBelowThreshold, d.busyUnknown,
                    d.deferrals, d.deferralsUnknownBusy, d.deferralsMeasuredBusy,
                    d.firstBusyDecisionOffsetMillis / 1000.0,
                    d.firstValidBusyOffsetMillis / 1000.0,
                    d.firstDeferralOffsetMillis / 1000.0,
                    d.lastDeferralOffsetMillis / 1000.0);
            long[] before = CPU_BEFORE.get(m.id);
            long[] after = CPU_AFTER.get(m.id);
            if (before != null && after != null) {
                System.err.printf(
                        "  [诊断] 节点 %d%s: CPU 状态机 %.1f%% 落盘 %.1f%% apply %.1f%%%n",
                        m.id, tag, cpuPct(before[0], after[0], sec), cpuPct(before[1], after[1], sec),
                        cpuPct(before[2], after[2], sec));
            }
        }
        System.err.printf("  [诊断] 机器：%d 核  loadavg %.1f%n",
                Runtime.getRuntime().availableProcessors(),
                ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
    }

    /**
     * 整个进程的 CPU 占用率（百分比）。
     */
    private static double processCpuPct(double sec) {
        java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        try {
            java.lang.reflect.Method m = os.getClass().getMethod("getProcessCpuLoad");
            Object v = m.invoke(os);
            if (v instanceof Number) {
                return ((Number) v).doubleValue() * 100;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            // 取不到就算了
        }
        return -1;
    }

    /**
     * 周期长度的中位数（毫秒）。
     */
    private static double medianCycle(Node.Diag d) {
        int n = d.cycleSampleCount;
        if (n == 0) {
            return 0;
        }
        long[] copy = Arrays.copyOf(d.cycleSamples, n);
        Arrays.sort(copy);
        return copy[n / 2] / 1e6;
    }

    /**
     * 「真落盘批」周期长度的中位数（毫秒）。
     */
    private static double medianRealCycle(Node.Diag d) {
        int n = d.realCycleSampleCount;
        if (n == 0) {
            return 0;
        }
        long[] copy = Arrays.copyOf(d.realCycleSamples, n);
        Arrays.sort(copy);
        return copy[n / 2] / 1e6;
    }

    /**
     * 线程的 CPU 占用率（百分比）。
     */
    private static double cpuPct(long before, long after, double sec) {
        if (before < 0 || after < 0) {
            return -1;
        }
        return (after - before) / 1e9 / sec * 100;
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // 忽略
                }
            });
        }
    }

    /** 取分位数（输入已排序） */
    private static long pct(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }

    private static double mean(long[] values) {
        if (values.length == 0) {
            return 0;
        }
        long sum = 0;
        for (long v : values) {
            sum += v;
        }
        return (double) sum / values.length;
    }

    /**
     * 等到有新的条目被应用。
     *
     * <p>等的是「计数前进」而不是「计数到达某个序号」：提案被拒绝时
     * 序号会开洞，按序号等会永远等不到。</p>
     */
    private static boolean awaitApplied(int cid, long lastApplied, ApplyStats stats, AtomicBoolean stop) {
        synchronized (stats.commitLocks[cid]) {
            long deadline = System.currentTimeMillis() + 5000;
            while (!stop.get() && stats.committed[cid].get() <= lastApplied) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) {
                    // leader 切换可能让计数漏掉一条，超时后放弃这一条
                    return false;
                }
                try {
                    stats.commitLocks[cid].wait(Math.min(20, remain));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static byte[] payload(int clientId, long seq, long publishedAt) {
        ByteBuffer buf = ByteBuffer.allocate(PAYLOAD_LEN);
        buf.putInt(clientId);
        buf.putLong(seq);
        buf.putLong(publishedAt);
        return buf.array();
    }

    /**
     * 延迟采样器：<b>蓄水池抽样</b>（Algorithm R），抽的是整条流的等概率样本。
     *
     * <p>旧版是「固定容量的数组，满了就丢掉后面的采样」，
     * 于是采样只覆盖<b>运行开头</b>：容量 400,000、每应用一条条目记一个
     * ⇒ 覆盖时长 = 400,000 ÷ 提交/s，w=1024 时只有约 1.5s
     * —— 测到的是<b>启动爬坡期</b>，不是稳态，尾延迟因此完全失真。</p>
     *
     * <p>改成蓄水池之后：池子里的 capacity 个样本是整轮的等概率无偏抽样，
     * p50/p99 反映的是<b>整轮</b>分布（含尾延迟）。
     * 打印时会带上 {@code (样本 抽中数/总数)}，让覆盖口径可见。</p>
     */
    static final class LatencyRecorder {

        /** 采样策略 */
        enum Mode {
            /** 首 N 采样：容量满了就丢弃后面的（旧实现，只能覆盖运行开头） */
            FIRST_N,
            /** 蓄水池抽样（Algorithm R）：整轮的等概率无偏样本 */
            RESERVOIR
        }

        /** 全局采样策略，由系统属性 {@code edap.bench.latencyMode} 决定 */
        static final Mode MODE = parseMode(System.getProperty("edap.bench.latencyMode", "reservoir"));

        /** 解析采样策略属性名（容忍 {@code firstN} / {@code first_n} 这类写法） */
        private static Mode parseMode(String v) {
            String k = v.replace("_", "").toUpperCase(java.util.Locale.ROOT);
            for (Mode m : Mode.values()) {
                if (m.name().replace("_", "").equals(k)) {
                    return m;
                }
            }
            throw new IllegalArgumentException("未知的采样策略：" + v);
        }

        /**
         * 样本值里的「已写好」标记位。
         *
         * <p>用最高位当标记，而不是另开一个 {@code written[]} 数组：
         * 多线程下槽位的写入顺序和索引顺序不一致（线程 A 拿到索引 5、
         * 线程 B 拿到索引 6，B 可能先写完 6），按计数去读槽位会读到还没写的槽
         * （= 0 纳秒），0 纳秒的样本会把 p50/p99 直接拉低，看起来「延迟很好」—— 假数据。</p>
         *
         * <p>纳秒级延迟远小于 2^62（≈146 年），所以最高位可以安全地当标记用。
         * 标记<b>不额外占内存</b>，这点很重要 —— 见 {@link #LATENCY_CAPACITY}。</p>
         */
        private static final long MARK = 1L << 62;

        /** 蓄水池里的样本（值的最高位是「已写好」标记） */
        private final AtomicLongArray samples;

        /** 总共见过的样本数（可能远大于容量） */
        private final AtomicLong seen = new AtomicLong();

        /** 蓄水池容量 */
        private final int capacity;

        LatencyRecorder(int capacity) {
            this.capacity = capacity;
            this.samples = new AtomicLongArray(capacity);
        }

        /**
         * 记一个样本。
         *
         * <p>两种策略的取舍（见 {@link Mode}）：</p>
         * <ul>
         * <li>{@link Mode#FIRST_N}：容量满了之后<b>什么都不做</b> —— 最便宜，
         *     但只覆盖运行开头（w=1024 时只有 1.5s）。</li>
         * <li>{@link Mode#RESERVOIR}：整轮的等概率无偏样本，
         *     但每次采样都要<b>随机写</b>一次（3.2MB 的数组 ⇒ 缓存缺失）。</li>
         * </ul>
         *
         * <p>用 {@link AtomicLongArray} 而不是 {@code long[]}：
         * JLS 不保证非 volatile long 的读写是原子的（64 位写可能撕裂）。</p>
         */
        void add(long v) {
            long n = seen.getAndIncrement();
            if (MODE == Mode.FIRST_N) {
                if (n < capacity) {
                    samples.set((int) n, v | MARK);
                }
                return;
            }
            // 蓄水池抽样（Algorithm R）：前 capacity 个直接填满，
            // 之后每个样本以 capacity/(n+1) 的概率替换掉池子里随机一个槽位
            if (n < capacity) {
                samples.set((int) n, v | MARK);
                return;
            }
            long j = ThreadLocalRandom.current().nextLong(n + 1);
            if (j < capacity) {
                samples.set((int) j, v | MARK);
            }
        }

        /** 总共见过的样本数 */
        long total() {
            return seen.get();
        }

        /**
         * 抽中的样本（未排序）。
         *
         * <p>扫全池子、只收带标记的槽位 —— 不能按计数取前 N 个，
         * 因为多线程下槽位的写入顺序和索引顺序不一致（见 {@link #MARK}）。</p>
         */
        long[] snapshot() {
            long[] out = new long[capacity];
            int cnt = 0;
            for (int i = 0; i < capacity; i++) {
                long raw = samples.get(i);
                if ((raw & MARK) != 0) {
                    out[cnt++] = raw & ~MARK;
                }
            }
            return Arrays.copyOf(out, cnt);
        }

        void report(String name) {
            long[] copy = snapshot();
            if (copy.length == 0) {
                return;
            }
            Arrays.sort(copy);
            System.out.printf("  %s p50=%.3fms p99=%.3fms max=%.3fms (样本 %d/%d)%n",
                    name, pct(copy, 50) / 1e6, pct(copy, 99) / 1e6, copy[copy.length - 1] / 1e6,
                    copy.length, total());
        }
    }
}
