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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
 *      [节点数] [客户端数] [时长秒] [sync|sync-commit|async|async-commit] [每客户端在途上限] [tick毫秒] \
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

    /** propose() 的往返耗时采样（发布到状态机 step 完） */
    private static final LatencyRecorder PROP_RT_NANOS = new LatencyRecorder(200_000);

    /** 异步提案的等待超时，与 dragonboat 基准的 30 秒同量级 */
    private static final long ASYNC_TIMEOUT_MILLIS = 30_000;

    /** nio 传输模式的起始端口 */
    private static final int NIO_BASE_PORT = 9300;

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

        /** 延迟样本 */
        final long[] latencies;

        /** 延迟样本数 */
        final AtomicInteger latN = new AtomicInteger();

        /** leader 上应用的条目总数 */
        final AtomicLong leaderCommitted = new AtomicLong();

        ApplyStats(int clients, int latencyCapacity) {
            committed = new AtomicLong[clients];
            commitLocks = new Object[clients];
            for (int i = 0; i < clients; i++) {
                committed[i] = new AtomicLong();
                commitLocks[i] = new Object();
            }
            latencies = new long[latencyCapacity];
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
            int i = latN.getAndIncrement();
            if (i < latencies.length) {
                latencies[i] = latency;
            }
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

        Pending(long seq, CompletableFuture<Void> future) {
            this.seq = seq;
            this.future = future;
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
        boolean fireAndForget = mode.startsWith("async");
        boolean waitCommit = mode.endsWith("-commit");
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
        ApplyStats stats = new ApplyStats(clients, 400_000);
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

            // 等选主完成
            long deadline = System.currentTimeMillis() + 15000;
            while (leaderId.get() == Raft.NONE && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            if (leaderId.get() == Raft.NONE) {
                throw new IllegalStateException("cluster has no leader");
            }

            CountDownLatch done = new CountDownLatch(clients);
            for (int i = 0; i < clients; i++) {
                final int cid = i;
                Thread t = new Thread(() -> {
                    try {
                        if (fireAndForget) {
                            runAsyncClient(cid, maxInflight, members, leaderId, stop,
                                    published, failed, rejectedBusy, rejectedDropped);
                        } else {
                            runSyncClient(cid, waitCommit, maxInflight, members, leaderId, stats,
                                    stop, published, failed, rejectedBusy, rejectedDropped);
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

            Thread.sleep(durationSec * 1000L);
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

            report(members, clients, durationSec, mode, stats.latencies, stats.latN.get(),
                    published.get(), failed.get(), rejectedBusy.get(), rejectedDropped.get(),
                    stats, root, baseDir != null, transportMode);
            System.out.flush();
            if (sharedEdap != null) {
                // edap-nio 依赖的 EdapTime 单例有非 daemon 线程，JVM 自己不会退出。
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
                    sendMessages(m, rd, members, false);
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
                    sendMessages(m, rd, members, true);
                } else {
                    // 框架接管落盘：框架保证确认类消息在落盘完成之后才放进
                    // Ready，所以这里一次性全发出去
                    sendAllMessages(m, rd, members);
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
    private static void sendMessages(Member m, Ready rd, Map<Long, Member> members, boolean gated) {
        for (Message msg : rd.getMessages()) {
            if (Raft.isPersistGated(msg.getType()) != gated) {
                continue;
            }
            deliver(m, msg, members);
        }
    }

    /**
     * 把 Ready 里的所有消息发给对应的节点。
     *
     * <p>框架落盘模式下用这个：框架已经保证确认类消息是在落盘完成之后
     * 才被放进 Ready 的，应用层不用再区分。</p>
     */
    private static void sendAllMessages(Member m, Ready rd, Map<Long, Member> members) {
        for (Message msg : rd.getMessages()) {
            deliver(m, msg, members);
        }
    }

    /**
     * 把一条消息投递给目标节点。
     *
     * <p>direct 模式进程内直投；tcp / nio 模式走各自的传输层。</p>
     */
    private static void deliver(Member m, Message msg, Map<Long, Member> members) {
        Member target = members.get(msg.getTo());
        if (target == null) {
            return;
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
            AtomicLong rejectedDropped) {
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
                    leader.node.propose(data);
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
            AtomicLong rejectedBusy, AtomicLong rejectedDropped) {
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
                byte[] data = payload(cid, seq, System.nanoTime());
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
                inflight.addLast(new Pending(seq, future));
                published.incrementAndGet();
            }
            if (inflight.isEmpty()) {
                sleep(1);
                continue;
            }
            // 等最老的一条完成（提案被提交）
            Pending p = inflight.peekFirst();
            try {
                p.future.get(ASYNC_TIMEOUT_MILLIS + 5000, TimeUnit.MILLISECONDS);
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
            String mode, long[] latencies, int latN, long published, long failed,
            long rejectedBusy, long rejectedDropped, ApplyStats stats, Path root,
            boolean keepDir, String transportMode) throws IOException {
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
        if (latN > 0) {
            long[] sorted = Arrays.copyOf(latencies, Math.min(latN, latencies.length));
            Arrays.sort(sorted);
            System.out.printf("  端到端延迟(发布→应用) p50=%.2fms p99=%.2fms 均值=%.2fms%n",
                    pct(sorted, 50) / 1e6, pct(sorted, 99) / 1e6, mean(sorted) / 1e6);
        }
        if (!keepDir) {
            deleteTree(root);
        } else {
            System.out.println("  数据目录: " + root);
        }
        System.out.println();
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
     * 延迟采样器：固定容量的数组，满了就丢掉后面的采样。
     */
    static final class LatencyRecorder {

        private final long[] samples;

        private final AtomicInteger n = new AtomicInteger();

        LatencyRecorder(int capacity) {
            this.samples = new long[capacity];
        }

        void add(long v) {
            int i = n.getAndIncrement();
            if (i < samples.length) {
                samples[i] = v;
            }
        }

        void report(String name) {
            int cnt = Math.min(n.get(), samples.length);
            if (cnt == 0) {
                return;
            }
            long[] copy = Arrays.copyOf(samples, cnt);
            Arrays.sort(copy);
            System.out.printf("  %s p50=%.3fms p99=%.3fms max=%.3fms (采样 %d)%n",
                    name, pct(copy, 50) / 1e6, pct(copy, 99) / 1e6, copy[copy.length - 1] / 1e6, cnt);
        }
    }
}
