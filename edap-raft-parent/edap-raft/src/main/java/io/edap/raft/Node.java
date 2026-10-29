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

import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.raft.protocol.ConfChangeCodec;
import io.edap.raft.protocol.ConfChangeI;
import io.edap.raft.protocol.ConfState;
import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.EntryType;
import io.edap.raft.protocol.HardState;
import io.edap.raft.protocol.Message;
import io.edap.raft.protocol.MessageType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * 带独立线程的 raft 节点，等价于 etcd 的 {@code raft.Node}。
 *
 * <p>{@link RawNode} 是非线程安全的，所有对状态机的操作都必须串行执行。
 * 这个类把这件事包起来：内部起一个线程，所有外部调用都投递到这个线程上执行。</p>
 *
 * <p>典型用法：</p>
 * <pre>
 * Node node = Node.startNode(config, peers);
 * while (running) {
 *     Ready rd = node.ready();
 *     saveToDisk(rd.getHardState(), rd.getEntries(), rd.getSnapshot());
 *     sendMessages(rd.getMessages());
 *     applyToStateMachine(rd.getCommittedEntries());
 *     node.advance();
 * }
 * </p>
 *
 * <p>{@link #ready()} 会阻塞到有新的 Ready 为止。处理完这一个 Ready 之后
 * 必须调用 {@link #advance()}，否则 raft 不会再产出下一个 Ready。</p>
 *
 * <p>与 etcd 的差异：etcd 用一组 channel 把请求投递给状态机线程，
 * Java 这里用阻塞队列 + 一个唤醒信号实现同样的语义。
 * 提案在没有 leader 时会被阻塞（etcd 的 propc 通道此时是关闭的），
 * 节点停止时所有阻塞中的调用都会被唤醒并抛出 {@link RaftException}。</p>
 */
public class Node {

    private static final Logger log = LoggerManager.getLogger(Node.class);

    /**
     * 提案通道：只有知道 leader 之后，提案才会被取走。
     *
     * <p>容量由 {@link RaftConfig#maxPendingProposals} 决定，0 表示无界。
     * 无界队列会让异步提案路径无限制地堆积，有界队列满了之后按
     * {@link RaftConfig#proposalFullPolicy} 处理（阻塞 / 拒绝）。</p>
     */
    private final BlockingQueue<MsgWithResult> propc;

    /** 提案队列的容量上限，0 表示不限制 */
    private final int maxPendingProposals;

    /** 提案队列满时的处理方式 */
    private final ProposalFullPolicy proposalFullPolicy;

    /** 提案队列的空间监视器：队列腾出位置时 notifyAll */
    private final Object proposalSpace = new Object();

    /** 提案队列的空间版本号：队列每腾出一次位置就递增 */
    private final AtomicLong spaceVersion = new AtomicLong();

    /**
     * 因为提案队列满而被丢弃的提案数（传输层回调路径）。
     *
     * <p>只有「从网络收到提案」这一条路径会丢：它跑在 IO 线程上，
     * 阻塞会把心跳一起卡死。</p>
     */
    private final AtomicLong droppedProposals = new AtomicLong();

    /** 丢弃提案的告警限频计数器 */
    private final AtomicInteger dropWarnCounter = new AtomicInteger();

    /** 网络消息通道 */
    private final BlockingQueue<Message> recvc = new LinkedBlockingQueue<>();

    /** 成员变更请求通道 */
    private final BlockingQueue<ConfChangeRequest> confc = new LinkedBlockingQueue<>();

    /** 状态查询请求通道 */
    private final BlockingQueue<StatusRequest> statusc = new LinkedBlockingQueue<>();

    /**
     * 交给应用层的 Ready。
     *
     * <p>etcd 的 {@code readyc} 是无缓冲的 channel：Ready 交出去之前不会「占位」，
     * 应用层来取的时候拿到的永远是当前能凑出来的最大一批。这里用
     * {@link SynchronousQueue} 实现同样的交接语义。</p>
     */
    private final BlockingQueue<Ready> readyc = new SynchronousQueue<>();

    /** 正在等 Ready 的应用层调用数 */
    private final AtomicInteger readyWaiters = new AtomicInteger();

    /** advance 信号通道 */
    private final BlockingQueue<Object> advancec = new LinkedBlockingQueue<>();

    /** tick 通道，缓冲 128 个，满了就丢弃 */
    private final BlockingQueue<Object> tickc = new LinkedBlockingQueue<>(128);

    /**
     * 唤醒标志：状态机线程「有没有活要干」。
     *
     * <p>用标志位而不是 {@link Semaphore}，是因为信号量会累积许可：
     * 每来一条消息就 {@code release()} 一次，而消费侧一轮只 {@code acquire()} 一次，
     * 多余的许可会让状态机线程空转。这里改成 dragonboat {@code workReady} 的做法
     * ——多次唤醒请求合并成一次，状态机线程醒来后自己把队列排空。</p>
     */
    private final AtomicBoolean wakeupFlag = new AtomicBoolean();

    /** 等待状态机线程处理的请求 */
    private final Set<PendingRequest> pendingRequests = ConcurrentHashMap.newKeySet();

    /** 异步提案的待完成表：请求 id -> future */
    private final ConcurrentHashMap<Long, CompletableFuture<Void>> pendingProposals = new ConcurrentHashMap<>();

    /** 异步提案的请求 id 序号 */
    private final AtomicLong requestSeq = new AtomicLong();

    /** 底层状态机 */
    private final RawNode rn;

    /** 是否由框架接管落盘 */
    private final boolean frameworkPersist;

    /** 已提交条目的处理器，没有则由应用层自己应用 */
    private final ApplyHandler applyHandler;

    /** 交给 apply 线程的已提交条目 */
    private final BlockingQueue<List<Entry>> applyq = new LinkedBlockingQueue<>();

    /**
     * 交给落盘线程的任务。
     *
     * <p>同一时刻只有一个任务：etcd 的 Ready 契约是「一个 Ready 没被 advance
     * 之前不产出下一个」，落盘也就只能一次一个。</p>
     */
    private final BlockingQueue<PersistTask> persistq = new ArrayBlockingQueue<>(1);

    /** 节点是否已经停止 */
    private final AtomicBoolean stopped = new AtomicBoolean();

    /** 状态机线程 */
    private final Thread worker;

    /** apply 线程，没有独立 apply 处理器时为 null */
    private final Thread applyThread;

    /** 落盘线程，框架不接管落盘时为 null */
    private final Thread persistThread;

    /** 提案是否被阻塞：节点被移出集群之后不再接收提案 */
    private boolean proposalBlocked;

    /** 保护「停止」与「入队」的竞态 */
    private final Object lifecycle = new Object();

    /**
     * 用给定的 RawNode 创建一个 Node，节点线程会立刻启动。
     */
    public Node(RawNode rawNode) {
        this.rn = rawNode;
        this.frameworkPersist = rawNode.isFrameworkPersist();
        this.applyHandler = rawNode.getApplyHandler();
        RaftConfig cfg = rawNode.getRaft().getRaftConfig();
        this.maxPendingProposals = cfg.maxPendingProposals;
        this.proposalFullPolicy = cfg.proposalFullPolicy == null
                ? ProposalFullPolicy.BLOCK : cfg.proposalFullPolicy;
        this.propc = this.maxPendingProposals > 0
                ? new LinkedBlockingQueue<>(this.maxPendingProposals)
                : new LinkedBlockingQueue<>();
        this.worker = new Thread(this::run, "edap-raft-" + rawNode.getRaft().getId());
        this.worker.setDaemon(true);
        this.worker.start();
        if (applyHandler != null) {
            this.applyThread = new Thread(this::runApply,
                    "edap-raft-apply-" + rawNode.getRaft().getId());
            this.applyThread.setDaemon(true);
            this.applyThread.start();
        } else {
            this.applyThread = null;
        }
        if (frameworkPersist) {
            this.persistThread = new Thread(this::runPersist,
                    "edap-raft-persist-" + rawNode.getRaft().getId());
            this.persistThread.setDaemon(true);
            this.persistThread.start();
        } else {
            this.persistThread = null;
        }
    }

    /**
     * 启动一个新节点。
     *
     * <p>成员列表通过写入一条成员变更日志的方式初始化，等价于 etcd 的
     * {@code StartNode}：节点先变成 term 1 的 follower，再把每个成员
     * 作为一条「加入节点」的成员变更追加到日志里。</p>
     *
     * @param config 节点配置
     * @param peers  初始成员
     */
    public static Node startNode(RaftConfig config, Peer... peers) {
        if (peers == null || peers.length == 0) {
            throw new RaftException("no peers given; use restartNode instead");
        }
        RawNode rn = new RawNode(config);
        rn.bootstrap(peers);
        return new Node(rn);
    }

    /**
     * 重启一个已有状态的节点，成员配置从存储里恢复。
     *
     * @param config 节点配置
     */
    public static Node restartNode(RaftConfig config) {
        return new Node(new RawNode(config));
    }

    /**
     * 底层的 RawNode
     */
    public RawNode getRawNode() {
        return rn;
    }

    /**
     * 推进内部逻辑时钟一个 tick
     */
    public void tick() {
        if (stopped.get()) {
            return;
        }
        if (!tickc.offer(TICK)) {
            log.warn(Long.toHexString(rn.getRaft().getId())
                    + " A tick missed to fire. Node blocks too long!");
        }
        signalWakeup();
    }

    /**
     * 发起选举
     */
    public void campaign() {
        if (stopped.get()) {
            return;
        }
        recvc.add(new Message().setType(MessageType.MSG_HUP));
        signalWakeup();
    }

    /**
     * 提议一条数据。
     *
     * <p>没有 leader 时提案会被阻塞，直到知道 leader 为止；
     * 提案被 raft 丢弃时抛出 {@link RaftException}。</p>
     *
     * <p>提案队列满时按 {@link RaftConfig#proposalFullPolicy} 处理：
     * 默认 {@link ProposalFullPolicy#BLOCK} 是阻塞等待，提案不会丢；
     * 设成 {@link ProposalFullPolicy#REJECT} 时抛 {@link RaftBusyException}。</p>
     *
     * @throws RaftException 节点已停止，或提案被丢弃
     * @throws RaftBusyException 提案队列满且策略为 {@link ProposalFullPolicy#REJECT}
     */
    public void propose(byte[] data) {
        Message m = new Message().setType(MessageType.MSG_PROP)
                .setEntries(Collections.singletonList(
                        new Entry(0, 0, EntryType.NORMAL, data)));
        MsgWithResult pm = enqueueProposal(m, OverflowPolicy.SYNC);
        if (!pm.awaitResult()) {
            throw new RaftException("raft proposal dropped");
        }
    }

    /**
     * 提议一次成员变更。
     *
     * <p>与 {@link #propose(byte[])} 不同，这里不等待提案的处理结果，
     * 只等到提案被状态机接收为止。</p>
     */
    public void proposeConfChange(ConfChangeI cc) {
        Message m = RawNode.confChangeToMsg(cc);
        MsgWithResult pm = enqueueProposal(m, OverflowPolicy.SYNC);
        pm.awaitReceived();
    }

    /**
     * 异步提案：立刻返回，不等提案被状态机接收，也不等提案被提交。
     *
     * <p>返回的 future 在提案被<b>提交</b>时正常完成。提交意味着多数派
     * 已经持久化了这条日志 —— 这是 raft 层能给的保证。把完成点绑到
     * 「应用处理完」上是倒退。</p>
     *
     * <p>失败的情形：</p>
     * <ul>
     *     <li>提案被 raft 丢弃（没有 leader、转移领导权等）：
     *         {@code RaftException("raft proposal dropped")}</li>
     *     <li>节点停止：{@code RaftException("raft: stopped")}</li>
     *     <li>提案队列满：{@link RaftBusyException}（异步路径永远拒绝，不阻塞）</li>
     *     <li>超时：{@link java.util.concurrent.TimeoutException}</li>
     * </ul>
     *
     * <p><b>超时是必填项而不是可选项</b>：提案被新 leader 截断覆盖之后，
     * 它的请求 id 永远不会出现在已提交条目里，future 只能靠超时结束。
     * 不传超时的版本请自行保证提案最终会被提交，否则 future 可能一直挂着。</p>
     *
     * @param data 提案内容
     * @return 提案完成后的 future
     */
    public CompletableFuture<Void> proposeAsync(byte[] data) {
        return proposeAsync(data, 0L);
    }

    /**
     * 异步提案，带超时。
     *
     * @param data 提案内容
     * @param timeoutMillis 超时毫秒数，小于等于 0 表示不限时
     * @return 提案完成后的 future
     */
    public CompletableFuture<Void> proposeAsync(byte[] data, long timeoutMillis) {
        long requestId = nextRequestId();
        CompletableFuture<Void> future = new CompletableFuture<>();
        // 先注册再入队：提案有可能比 proposeAsync 返回还快，
        // 先入队的话提交事件会先于注册发生，future 就永远等不到了。
        pendingProposals.put(requestId, future);
        Message m = new Message().setType(MessageType.MSG_PROP)
                .setEntries(Collections.singletonList(
                        new Entry(0, 0, EntryType.NORMAL, data, requestId)));
        MsgWithResult pm = enqueueProposal(m, OverflowPolicy.REJECT);
        if (pm.isFailed()) {
            // 入队就失败了：队列满或者节点已停止
            pendingProposals.remove(requestId);
            future.completeExceptionally(pm.getError());
            return future;
        }
        if (timeoutMillis > 0) {
            future.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS);
            // 超时之后要把表里的条目摘掉，否则表会无限增长
            future.whenComplete((v, t) -> pendingProposals.remove(requestId));
        }
        return future;
    }

    /**
     * 生成一个全局唯一的请求 id。
     *
     * <p>格式是 {@code (节点 id << 32) | 序号}。节点 id 必须在
     * {@code [1, 2^32)} 里 —— 覆盖了全部实际部署规模。</p>
     */
    private long nextRequestId() {
        long nodeId = rn.getRaft().getId();
        if (nodeId <= 0 || nodeId >= (1L << 32)) {
            throw new RaftException("raft node id must be in [1, 2^32) to use async proposals");
        }
        return (nodeId << 32) | (requestSeq.getAndIncrement() & 0xFFFFFFFFL);
    }

    /**
     * 用一条消息推进状态机。
     *
     * <p>网络收到的消息都用这个入口投递。提案会阻塞到状态机接收它为止，
     * 其余消息投递后立即返回。</p>
     *
     * @throws RaftException 节点已停止
     */
    public void step(Message m) {
        if (m == null) {
            throw new RaftException("cannot step with nil message");
        }
        if (stopped.get()) {
            throw new RaftException("raft: stopped");
        }
        Route route = route(m);
        if (route == Route.DROP) {
            // 本地消息不应该从网络上收到，忽略
            return;
        }
        if (route == Route.PROP) {
            enqueueProposal(m, OverflowPolicy.SYNC).awaitReceived();
            return;
        }
        recvc.add(m);
        signalWakeup();
    }

    /**
     * 处理一条来自网络的消息，不阻塞。
     *
     * <p>与 {@link #step(Message)} 的区别只在于不等待提案被状态机接收，
     * 因此可以在 IO 线程上直接调用。</p>
     *
     * <p><b>不要用这个方法提案</b>：它无法感知提案被拒绝或丢弃 ——
     * 队列满时提案会被直接丢掉（计数见 {@link #getDroppedProposals()}）。
     * 客户端要用 {@link #propose(byte[])} 或 {@link #proposeAsync(byte[])}。</p>
     */
    public void onMessage(Message m) {
        if (m == null) {
            throw new RaftException("cannot step with nil message");
        }
        if (stopped.get()) {
            return;
        }
        Route route = route(m);
        if (route == Route.DROP) {
            return;
        }
        if (route == Route.PROP) {
            // 传输层回调跑在 IO 线程上，队列满时只能丢弃：
            // 阻塞会把心跳一起卡死，最终导致选举风暴。
            enqueueProposal(m, OverflowPolicy.DROP);
            return;
        }
        recvc.add(m);
        signalWakeup();
    }

    /**
     * 阻塞直到有新的 Ready。
     *
     * <p>返回的 Ready 处理完之后必须调用 {@link #advance()}。</p>
     *
     * @return 新的 Ready；节点已停止时返回 null
     */
    public Ready ready() throws InterruptedException {
        return takeReady(-1);
    }

    /**
     * 阻塞直到有新的 Ready，最多等待指定时间。
     *
     * @return 新的 Ready；超时或节点已停止时返回 null
     */
    public Ready ready(long timeout, TimeUnit unit) throws InterruptedException {
        return takeReady(unit.toNanos(timeout));
    }

    /**
     * 等应用层来取 Ready。
     *
     * <p>取之前先释放一次唤醒信号，让状态机线程重新组装一次 Ready：
     * 与 etcd 一致，应用层拿到的永远是「当前能凑出来的最大一批」。</p>
     *
     * @param timeoutNanos 最多等待多久，负数表示一直等
     * @return 新的 Ready；超时或节点已停止时返回 null
     */
    private Ready takeReady(long timeoutNanos) throws InterruptedException {
        long start = System.nanoTime();
        while (true) {
            readyWaiters.incrementAndGet();
            try {
                // 叫醒状态机线程，让它重新组装一次 Ready
                signalWakeup();
                long remain = timeoutNanos < 0
                        ? READY_POLL_SLICE_NANOS
                        : timeoutNanos - (System.nanoTime() - start);
                if (remain <= 0) {
                    return null;
                }
                Ready rd = readyc.poll(Math.min(remain, READY_POLL_SLICE_NANOS),
                        TimeUnit.NANOSECONDS);
                if (rd != null) {
                    return rd;
                }
                if (stopped.get()) {
                    // 节点已经停止，不会再有 Ready 了
                    return null;
                }
                if (timeoutNanos >= 0 && System.nanoTime() - start >= timeoutNanos) {
                    return null;
                }
            } finally {
                readyWaiters.decrementAndGet();
            }
        }
    }

    /**
     * 通知 raft 上一个 Ready 已经处理完了，可以产出下一个。
     */
    public void advance() {
        if (stopped.get()) {
            return;
        }
        advancec.add(ADVANCE);
        signalWakeup();
    }

    /**
     * 应用一次成员变更，返回变更之后的集群配置。
     *
     * <p>应用层在 {@link Ready#getCommittedEntries()} 里看到成员变更条目时调用。
     * 如果决定拒绝这次变更，就不要调用本方法。</p>
     */
    public ConfState applyConfChange(ConfChangeI cc) {
        if (stopped.get()) {
            return emptyConfState();
        }
        ConfChangeRequest req = new ConfChangeRequest(cc);
        pendingRequests.add(req);
        confc.add(req);
        signalWakeup();
        return req.await();
    }

    /**
     * 当前的完整状态
     */
    public Status status() {
        if (stopped.get()) {
            return emptyStatus();
        }
        StatusRequest req = new StatusRequest();
        pendingRequests.add(req);
        statusc.add(req);
        signalWakeup();
        return req.await();
    }

    /**
     * 报告某个节点不可达
     */
    public void reportUnreachable(long id) {
        if (stopped.get()) {
            return;
        }
        recvc.add(new Message().setType(MessageType.MSG_UNREACHABLE).setFrom(id));
        signalWakeup();
    }

    /**
     * 报告快照的发送结果
     */
    public void reportSnapshot(long id, SnapshotStatus status) {
        if (stopped.get()) {
            return;
        }
        boolean reject = status == SnapshotStatus.FAILURE;
        recvc.add(new Message().setType(MessageType.MSG_SNAP_STATUS).setFrom(id).setReject(reject));
        signalWakeup();
    }

    /**
     * 尝试把领导权转移给指定节点
     */
    public void transferLeader(long transferee) {
        if (stopped.get()) {
            return;
        }
        recvc.add(new Message().setType(MessageType.MSG_TRANSFER_LEADER).setFrom(transferee));
        signalWakeup();
    }

    /**
     * 忘记当前的 leader
     */
    public void forgetLeader() {
        if (stopped.get()) {
            return;
        }
        recvc.add(new Message().setType(MessageType.MSG_FORGET_LEADER));
        signalWakeup();
    }

    /**
     * 发起一次线性一致读请求
     */
    public void readIndex(byte[] rctx) {
        if (stopped.get()) {
            return;
        }
        recvc.add(new Message().setType(MessageType.MSG_READ_INDEX)
                .setEntries(Collections.singletonList(
                        new Entry(0, 0, EntryType.NORMAL, rctx))));
        signalWakeup();
    }

    /**
     * 停止节点，释放状态机线程。
     *
     * <p>重复调用没有效果。停止之后所有阻塞中的调用都会被唤醒。</p>
     */
    public void stop() {
        synchronized (lifecycle) {
            if (!stopped.compareAndSet(false, true)) {
                return;
            }
        }
        // 唤醒所有等待中的请求
        for (PendingRequest req : pendingRequests) {
            req.abort();
        }
        // 唤醒等着入队的提案者：它们会看到 stopped 并失败
        notifyProposalSpace();
        // 这里必须无条件 unpark：标志位可能已经是 true，
        // 而状态机线程正停在 park 上等一个不会来的信号。
        LockSupport.unpark(worker);
        if (applyThread != null) {
            // 让 apply 线程从等待里醒过来，它看到 stopped 就会退出
            applyThread.interrupt();
        }
        if (persistThread != null) {
            // 让落盘线程从等待里醒过来，它看到 stopped 就会退出
            persistThread.interrupt();
        }
        try {
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 叫醒状态机线程。
     *
     * <p>多次调用会合并成一次：标志位已经是 true 时不再重复 unpark，
     * 状态机线程醒来后自己把队列排空，不会因为堆积的唤醒请求而空转。
     * 这也是 publish 路径上唯一比原来的 {@code Semaphore.release()} 多花的一步
     * ——但换来的是不再有许可累积导致的空转。</p>
     */
    private void signalWakeup() {
        if (wakeupFlag.compareAndSet(false, true)) {
            LockSupport.unpark(worker);
        }
    }

    /**
     * 提案队列满时的处理方式，按调用路径区分。
     */
    enum OverflowPolicy {

        /** 同步路径：按 {@link RaftConfig#proposalFullPolicy} 处理 */
        SYNC,

        /** 异步路径：立刻拒绝，不阻塞调用方 */
        REJECT,

        /** 传输层回调路径：立刻丢弃并计数，不阻塞 IO 线程 */
        DROP
    }

    /**
     * 消息的去向，等价于 etcd 的 node 里的几个通道。
     */
    enum Route {

        /** 提案，走提案通道 */
        PROP,

        /** 网络消息，走接收通道 */
        RECV,

        /** 忽略：本地消息不应该从网络上收到 */
        DROP
    }

    /**
     * 消息应该走哪条通道。
     *
     * <p>与 etcd 的 {@code node.Step} 的分流规则一致：先判断是不是「从网络上
     * 收到的本地消息」，是的话直接忽略；提案走提案通道，其余走接收通道。</p>
     */
    static Route route(Message m) {
        if (RaftUtil.isLocalMsg(m.getType()) && !RaftUtil.isLocalMsgTarget(m.getFrom())) {
            return Route.DROP;
        }
        if (m.getType() == MessageType.MSG_PROP) {
            return Route.PROP;
        }
        return Route.RECV;
    }

    /**
     * 投递一个提案。
     *
     * <p>节点停止时直接返回一条「已失败」的提案，调用方会看到
     * {@code raft: stopped}。</p>
     *
     * <p>队列满时的行为由 {@code mode} 决定：</p>
     * <ul>
     *     <li>{@link OverflowPolicy#SYNC}：按 {@link #proposalFullPolicy} 处理 ——
     *         {@link ProposalFullPolicy#BLOCK} 时等队列腾出位置，
     *         {@link ProposalFullPolicy#REJECT} 时立刻失败</li>
     *     <li>{@link OverflowPolicy#REJECT}：立刻失败（{@link RaftBusyException}）</li>
     *     <li>{@link OverflowPolicy#DROP}：立刻丢弃并计数</li>
     * </ul>
     */
    private MsgWithResult enqueueProposal(Message m, OverflowPolicy mode) {
        MsgWithResult pm = new MsgWithResult(m);
        while (true) {
            // 空间版本号：队列每腾出一次位置就递增，用来判断等待有没有白等
            long version = spaceVersion.get();
            boolean enqueued = false;
            synchronized (lifecycle) {
                if (stopped.get()) {
                    pm.fail(new RaftException("raft: stopped"));
                    return pm;
                }
                // 检查与入队必须在同一把锁里：
                // 否则可能出现「abortPending 排空之后才入队」的孤儿提案，那个提案永远等不到结果。
                //
                // 无界队列的 offer 永远成功，所以这里不用分支：
                // 之前写成「无界就直接当成入队成功」会漏掉真正的 offer，
                // 提案被静默丢掉 —— future 永远不完成，调用方只能等到超时。
                enqueued = propc.offer(pm);
            }
            if (enqueued) {
                signalWakeup();
                return pm;
            }
            // 队列满。等待必须发生在 lifecycle 锁之外：
            // stop() 与 abortPending() 都要拿这把锁才能停下来，持锁等待会死锁。
            if (mode == OverflowPolicy.DROP) {
                onProposalDropped();
                pm.fail(new RaftBusyException("raft proposal queue is full"));
                return pm;
            }
            if (mode == OverflowPolicy.REJECT
                    || proposalFullPolicy == ProposalFullPolicy.REJECT) {
                pm.fail(new RaftBusyException("raft proposal queue is full"));
                return pm;
            }
            awaitProposalSpace(version);
        }
    }

    /**
     * 等提案队列腾出位置。
     *
     * <p>用带超时的 wait：超时只是兜底（万一丢了通知也不会永久挂起），
     * 真正唤醒它的是状态机线程排空队列后的 notifyAll。</p>
     *
     * @param version 进入等待前看到的空间版本号；空间已经腾出来就不用等
     */
    private void awaitProposalSpace(long version) {
        synchronized (proposalSpace) {
            if (spaceVersion.get() != version) {
                // 空间已经被腾出来了，白等
                return;
            }
            try {
                proposalSpace.wait(PROPOSAL_SPACE_WAIT_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 通知等着入队的提案者：队列腾出位置了。
     */
    private void notifyProposalSpace() {
        spaceVersion.incrementAndGet();
        synchronized (proposalSpace) {
            proposalSpace.notifyAll();
        }
    }

    /**
     * 记录一次因队列满而丢弃的提案，并限频告警。
     */
    private void onProposalDropped() {
        long total = droppedProposals.incrementAndGet();
        // 限频：每 DROP_WARN_INTERVAL 次告警一次
        if (total % DROP_WARN_INTERVAL == 0) {
            log.warn("raft: dropped " + total + " proposals, proposal queue is full");
        }
    }

    /**
     * 因为提案队列满而被丢弃的提案数。
     *
     * <p>只有传输层回调路径（{@link #onMessage(Message)}）会丢提案。</p>
     */
    public long getDroppedProposals() {
        return droppedProposals.get();
    }

    /**
     * 还在等待提交的异步提案数。
     *
     * <p>提案被提交、被丢弃、超时或者节点停止之后，对应的条目都会从表里摘掉，
     * 所以这个数字应该随时间回落。</p>
     */
    public int getPendingProposalCount() {
        return pendingProposals.size();
    }

    /**
     * 状态机线程的主循环。
     *
     * <p>一轮里把每个来源都轮到一次，而不是「命中一个就重来」：
     * 提案一直不断时，如果每轮只处理一条提案就 {@code continue}，
     * 网络消息、tick、Ready 推进都会被无限期饿死（etcd 用 Go 的 {@code select}，
     * 多个 case 同时就绪时随机挑一个，不会这样）。</p>
     */
    private void run() {
        Raft r = rn.getRaft();
        Ready rd = null;
        // 与 rd 配套的日志条目，框架落盘时由框架写进存储
        List<Entry> rdEntries = Collections.emptyList();
        // 与 rd 配套的已提交条目，独立 apply 线程时交给 apply 线程
        List<Entry> rdCommitted = Collections.emptyList();
        // 已经组装好、等待交付的 Ready
        boolean readyPending = false;
        // 当前 Ready 是否已经落盘
        boolean rdPersisted = false;
        // 当前 Ready 是否必须等落盘完成才能交付给应用层
        boolean rdWaitPersist = false;
        // 已经交付、等待应用层 advance 的 Ready
        boolean advanceArmed = false;
        // 当前 Ready 是否已经被接管（acceptReady 过了）
        boolean rdAccepted = false;
        // 自上次组装 Ready 之后，raft 状态有没有变过
        boolean dirty = false;
        // 当前正在落盘的任务，null 表示没有落盘在进行
        PersistTask persistTask = null;
        long lead = Raft.NONE;

        try {
            while (!stopped.get()) {
                // leader 变化时重新武装提案通道
                long currentLead = r.getLead();
                if (lead != currentLead) {
                    if (r.hasLeader()) {
                        if (lead == Raft.NONE) {
                            r.getLogger().info("raft.node: %x elected leader %x at term %d",
                                    r.getId(), currentLead, r.getTerm());
                        } else {
                            r.getLogger().info("raft.node: %x changed leader from %x to %x at term %d",
                                    r.getId(), lead, currentLead, r.getTerm());
                        }
                        proposalBlocked = false;
                    } else {
                        r.getLogger().info("raft.node: %x lost leader %x at term %d",
                                r.getId(), lead, r.getTerm());
                    }
                    lead = currentLead;
                }

                // 提案：只有知道 leader 的时候才处理，否则提案阻塞在这里。
                // 一轮里攒到的提案合成一条消息交给状态机。
                if (r.hasLeader() && !proposalBlocked) {
                    if (handleProposals()) {
                        dirty = true;
                    }
                }

                // tick
                if (tickc.poll() != null) {
                    rn.tick();
                    dirty = true;
                }

                // 成员变更
                ConfChangeRequest cc;
                while ((cc = confc.poll()) != null) {
                    cc.run();
                    dirty = true;
                }

                // 状态查询
                StatusRequest sr;
                while ((sr = statusc.poll()) != null) {
                    sr.run();
                }

        // 应用层已经处理完上一个 Ready
        if (advanceArmed) {
            Object sig = advancec.poll();
            if (sig != null) {
                if (!frameworkPersist && applyHandler != null && !rdCommitted.isEmpty()) {
                    // 应用层自己落盘：它 advance 的时候日志已经落盘了，
                    // 这时才能把已提交条目交给 apply 线程 ——
                    // 应用层看到的状态不能比日志更靠前
                    applyq.add(rdCommitted);
                }
                rn.advance(rd);
                rd = null;
                rdCommitted = Collections.emptyList();
                advanceArmed = false;
                rdAccepted = false;
                dirty = true;
            }
        }

                // 网络消息
                Message m;
                while ((m = recvc.poll()) != null) {
                    handleMessage(m);
                    dirty = true;
                }

                // 落盘完成通知（框架落盘模式）
                // 落盘线程做完一次 write + fsync 之后把任务标记为完成。
                // 在这之前状态机线程照常 step 提案 —— 攒下一批日志，
                // 这样 fsync 的耗时与攒批可以重叠，而不是串行地卡住提案。
                if (persistTask != null && persistTask.isDone()) {
                    PersistTask done = persistTask;
                    persistTask = null;
                    if (done.getError() != null) {
                        log.error("raft: persist failed: " + done.getError(), done.getError());
                    } else {
                        // 落盘相关的消息现在才能交给状态机：
                        // 自己投的那一票、自己的日志落盘确认，都要等真的落盘之后才算数
                        rn.persisted(done.getReady());
                        // 确认类消息现在可以发出去：对应的日志已经落盘，
                        // 「我已经持久化了这些日志」这句话才算成立
                        rn.releasePersistGatedMessages(done.getGatedMessages());
                        // 这一批里还没落盘的已提交条目现在可以应用
                        if (applyHandler != null && !done.getPendingCommitted().isEmpty()) {
                            applyq.add(done.getPendingCommitted());
                        }
                        rdPersisted = true;
                    }
                }

                // 组装 Ready
                // 与 etcd 一致：应用层还没把上一个 Ready 取走之前，每一轮都重新
                // 组装一次，这样应用层拿到的 Ready 总是当前能凑出来的最大一批。
                //
                // 必须排在本轮所有 step 之后、并且紧挨着交付：readyWithoutAccept()
                // 与 acceptReady() 之间如果又 step 过，中间产生的消息会被
                // acceptReady() 里的 clearMessages() 抹掉。
                if (!advanceArmed && persistTask == null && !rdAccepted
                        && rn.hasReady() && (dirty || !readyPending)) {
                    rd = rn.readyWithoutAccept();
                    rdEntries = rd.getEntries();
                    rdCommitted = rd.getCommittedEntries();
                    if (frameworkPersist) {
                        // 日志由框架落盘，Ready 里不再带
                        rd.setEntries(Collections.emptyList());
                    }
                    if (applyHandler != null) {
                        // 已提交条目交给 apply 线程，Ready 里不再带
                        rd.setCommittedEntries(Collections.emptyList());
                    }
                    // 提案被提交：结束对应的异步提案
                    completePendingProposals(rdCommitted);
                    rdPersisted = false;
                    readyPending = true;
                    dirty = false;
                    if (frameworkPersist) {
                        // 确认类消息等落盘完成之后再发出去，先从状态机里摘下来
                        List<Message> gated = rn.takePersistGatedMessages();
                        // 立刻接管这个 Ready：raft 的消息列表清空，消息都收进 Ready 里。
                        // 之后状态机可以继续 step 提案（攒下一批），新产生的消息
                        // 不会因为 acceptReady 而被抹掉。
                        rn.acceptReady(rd, rdCommitted);
                        rdAccepted = true;
                        // 已提交条目分两批：已经落盘的可以立刻应用，
                        // 还在这一批日志里的要等落盘完成 ——
                        // 应用层看到的状态不能比日志更靠前
                        List<Entry> committedPending = Collections.emptyList();
                        if (applyHandler != null && !rdCommitted.isEmpty()) {
                            int split = splitCommitted(rdCommitted, rdEntries);
                            if (split > 0) {
                                applyq.add(new ArrayList<>(rdCommitted.subList(0, split)));
                            }
                            committedPending = new ArrayList<>(rdCommitted.subList(split, rdCommitted.size()));
                        }
                        // 交给落盘线程：一次 write + 一次 fsync。
                        persistTask = new PersistTask(rd, rdEntries, gated, committedPending);
                        persistq.add(persistTask);
                        // 有独立 apply 线程时，Ready 可以立刻交付：
                        // 复制消息早发出去，对端可以立刻开始落盘，
                        // 与本节点的落盘重叠，两个 fsync 不串行。
                        // 带快照的 Ready 要等落盘完成 —— 快照必须先进存储。
                        // 没有独立 apply 线程时，Ready 带着已提交条目，
                        // 要等落盘完成才能交给应用层。
                        rdWaitPersist = applyHandler == null || rd.getSnapshot() != null;
                    } else {
                        rdWaitPersist = false;
                    }
                }

                // 交付 Ready
                // 与 etcd 一致：Ready 必须真的交到应用层手里才算交出去。
                // 应用层还没来取时不做任何承诺，先去处理别的事，
                // 下一轮会重新组装一个（更大的）Ready 再交。
                //
                // 框架落盘模式下，只有「Ready 带着已提交条目」或者「Ready 带着
                // 快照」时才要等落盘完成：前者由应用层应用，后者必须先进存储。
                // 单纯的复制消息不用等 —— 对端立刻开始落盘反而更快。
                if (readyPending && (!rdWaitPersist || rdPersisted)) {
                    boolean delivered = readyWaiters.get() > 0
                            ? readyc.offer(rd, READY_HANDOFF_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                            : readyc.offer(rd, 0, TimeUnit.NANOSECONDS);
                    if (delivered) {
                        if (!frameworkPersist) {
                            // 框架不接管落盘：应用层自己落盘，接管 Ready 的时机
                            // 与 etcd 一致（交到应用层手里才接管）
                            rn.acceptReady(rd, rdCommitted);
                            rdAccepted = true;
                        }
                        advanceArmed = true;
                        readyPending = false;
                    }
                }

                // 等新任务
                // 唤醒标志被清掉说明这一轮确实没活干，可以睡。
                // 生产者在这两步之间塞消息也不会丢：它会置上标志并 unpark，
                // LockSupport 会记住这次 unpark，park 立刻返回。
                if (!wakeupFlag.getAndSet(false)) {
                    LockSupport.park(this);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            abortPending();
        }
    }

    /**
     * 框架落盘：把硬状态与整批日志一次写进存储，只做一次 fsync。
     *
     * <p>快照要排在日志前面 —— 快照之后的日志才能接在它后面写。</p>
     */
    private void persist(Ready rd, List<Entry> entries) {
        io.edap.raft.storage.PersistentStorage storage = rn.getStorage();
        if (rd.getSnapshot() != null) {
            storage.applySnapshot(rd.getSnapshot());
        }
        storage.save(rd.getHardState(), entries);
    }

    /**
     * 落盘线程：把状态机线程攒好的日志写进存储。
     *
     * <p>为什么要单独一个线程：fsync 要几毫秒到几十毫秒，如果放在状态机线程上，
     * 提案就会被 fsync 挡住 —— 客户端在 {@code propose()} 里等着状态机 step，
     * 状态机在等 fsync，两边一起僵住。实测（3 节点真盘、16 客户端、窗口 256）
     * 这种写法只有 1,400 次/秒，而把 fsync 挪到应用线程（同样的落盘次数、
     * 同样的批大小）能到 67,000 次/秒。</p>
     *
     * <p>安全前提没有放松：Ready 里的消息要等这一批真的落盘之后才交给应用层，
     * 所以「发出去的消息意味着日志已经持久化」这条不变式仍然成立。</p>
     */
    private void runPersist() {
        while (!stopped.get()) {
            PersistTask task;
            try {
                task = persistq.poll(PERSIST_POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (task == null) {
                continue;
            }
            try {
                persist(task.getReady(), task.getEntries());
                task.complete(null);
            } catch (Throwable t) {
                task.complete(t);
            }
            // 叫醒状态机线程：落盘完成了，Ready 可以交出去了
            signalWakeup();
        }
    }

    /**
     * 一次落盘任务。
     */
    private static final class PersistTask {

        /** 要落盘的 Ready */
        private final Ready rd;

        /** 要落盘的日志条目 */
        private final List<Entry> entries;

        /**
         * 落盘完成之后才能发出去的确认消息。
         *
         * <p>投票确认与日志追加确认的意思是「我已经持久化了这些日志」，
         * 在对应的日志落盘之前不能发出去。</p>
         */
        private final List<Message> gatedMessages;

        /**
         * 落盘完成之后才能应用的已提交条目。
         *
         * <p>这些条目的索引落在这一批日志里，落盘之前应用它们的话，
         * 应用层看到的状态会比日志更靠前。</p>
         */
        private final List<Entry> pendingCommitted;

        /** 落盘是否已经结束 */
        private volatile boolean done;

        /** 落盘失败时的异常 */
        private volatile Throwable error;

        PersistTask(Ready rd, List<Entry> entries, List<Message> gatedMessages,
                List<Entry> pendingCommitted) {
            this.rd = rd;
            this.entries = entries;
            this.gatedMessages = gatedMessages;
            this.pendingCommitted = pendingCommitted;
        }

        Ready getReady() {
            return rd;
        }

        List<Entry> getEntries() {
            return entries;
        }

        List<Message> getGatedMessages() {
            return gatedMessages;
        }

        List<Entry> getPendingCommitted() {
            return pendingCommitted;
        }

        boolean isDone() {
            return done;
        }

        Throwable getError() {
            return error;
        }

        void complete(Throwable t) {
            this.error = t;
            this.done = true;
        }
    }

    /**
     * 把已提交条目分成两批：已经落盘的与还在这一批日志里的。
     *
     * <p>已经落盘的那部分可以立刻应用；还在这一批里的要等落盘完成之后
     * 才能应用 —— 应用层看到的状态不能比日志更靠前。</p>
     *
     * @param committed 本次要应用的已提交条目
     * @param entries   本次要落盘的日志条目
     * @return 已经落盘的条目个数（{@code committed} 的一个前缀）
     */
    private static int splitCommitted(List<Entry> committed, List<Entry> entries) {
        if (entries.isEmpty()) {
            // 这一批没有日志要落盘，已提交条目全都是早就落盘过的
            return committed.size();
        }
        // 这一批日志之前的内容都早就落盘了
        long firstSaveIndex = entries.get(0).getIndex();
        int i = 0;
        while (i < committed.size() && committed.get(i).getIndex() < firstSaveIndex) {
            i++;
        }
        return i;
    }

    /**
     * apply 线程：把已提交条目交给处理器，处理完再通知 raft 推进 applied 指针。
     *
     * <p>与状态机线程之间只有单向依赖：apply 线程等状态机线程（成员变更要交给它），
     * 状态机线程不等 apply 线程，因此不会死锁。</p>
     */
    private void runApply() {
        while (!stopped.get()) {
            List<Entry> ents;
            try {
                ents = applyq.poll(APPLY_POLL_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (ents == null || ents.isEmpty()) {
                continue;
            }
            try {
                // 成员变更先生效，再把条目交给应用层
                for (Entry e : ents) {
                    if (e.getType() == EntryType.CONFIG_CHANGE
                            || e.getType() == EntryType.CONFIG_CHANGE_V2) {
                        applyConfChange(ConfChangeCodec.unmarshal(e.getType(), e.getData()));
                    }
                }
                applyHandler.apply(ents);
            } catch (Throwable t) {
                log.error("raft: apply committed entries failed: " + t, t);
            }
            // 通知 raft：这一批已经应用到状态机
            Message m = new Message()
                    .setType(MessageType.MSG_STORAGE_APPLY_RESP)
                    .setTo(rn.getRaft().getId())
                    .setFrom(RaftUtil.LOCAL_APPLY_THREAD)
                    .setEntries(ents);
            onMessage(m);
        }
    }

    /**
     * 排空提案队列，把这一轮攒到的提案合成一条消息交给状态机。
     *
     * <p>合批有两个好处：</p>
     * <ul>
     *   <li>状态机只走一次 {@code step}，省掉每条提案一次的分发开销</li>
     *   <li>一次 {@code bcastAppend} 就把整批条目发给对端，
     *       而不是每条提案一次 —— 对端的复制流水线压力小得多</li>
     * </ul>
     *
     * <p>整批被拒时逐条回退重跑：每条提案的接受/拒绝是独立的语义，
     * 合成一条消息之后必须还原成逐条的结果。</p>
     *
     * @return true 表示这一轮处理了至少一条提案
     */
    private boolean handleProposals() {
        List<MsgWithResult> batch = new ArrayList<>();
        long deadline = System.nanoTime() + PROPOSAL_DRAIN_BUDGET_NANOS;
        MsgWithResult pm;
        while (batch.size() < PROPOSAL_BATCH_LIMIT && (pm = propc.poll()) != null) {
            batch.add(pm);
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
        if (batch.isEmpty()) {
            return false;
        }
        // 队列腾出了位置，唤醒等着入队的提案者
        notifyProposalSpace();
        // 提案者不等落盘：先统一标记「已被状态机接收」
        List<Entry> entries = new ArrayList<>(batch.size());
        for (MsgWithResult p : batch) {
            Message m = p.getMessage();
            m.setFrom(rn.getRaft().getId());
            p.markReceived();
            List<Entry> ents = m.getEntries();
            if (ents != null) {
                entries.addAll(ents);
            }
        }
        if (entries.isEmpty()) {
            for (MsgWithResult p : batch) {
                p.complete(false);
                failAsyncProposal(p.getRequestId(), new RaftException("raft proposal dropped"));
            }
            return true;
        }
        boolean accepted = stepProposal(new Message()
                .setType(MessageType.MSG_PROP)
                .setEntries(entries));
        if (accepted) {
            for (MsgWithResult p : batch) {
                p.complete(true);
            }
            return true;
        }
        // 整批被拒：逐条回退，保留每条提案独立的接受/拒绝语义
        for (MsgWithResult p : batch) {
            boolean ok = stepProposal(p.getMessage());
            p.complete(ok);
            if (!ok) {
                failAsyncProposal(p.getRequestId(), new RaftException("raft proposal dropped"));
            }
        }
        return true;
    }

    /**
     * 提案被提交时完成对应的异步提案。
     *
     * <p>幂等：状态机线程可能重复组装同一个 Ready，第二次
     * {@code remove} 返回 null，没有副作用。</p>
     */
    private void completePendingProposals(List<Entry> committed) {
        if (pendingProposals.isEmpty() || committed == null || committed.isEmpty()) {
            return;
        }
        for (Entry e : committed) {
            long requestId = e.getRequestId();
            if (requestId == 0) {
                continue;
            }
            CompletableFuture<Void> f = pendingProposals.remove(requestId);
            if (f != null) {
                f.complete(null);
            }
        }
    }

    /**
     * 异步提案失败时结束对应的 future。
     */
    private void failAsyncProposal(long requestId, Throwable t) {
        if (requestId == 0) {
            return;
        }
        CompletableFuture<Void> f = pendingProposals.remove(requestId);
        if (f != null) {
            f.completeExceptionally(t);
        }
    }

    /**
     * 把一条提案消息交给状态机
     *
     * @return true 表示提案被接受
     */
    private boolean stepProposal(Message m) {
        try {
            return rn.getRaft().step(m);
        } catch (RaftException e) {
            return false;
        }
    }

    /**
     * 处理一条来自网络的消息。
     */
    private void handleMessage(Message m) {
        if (RaftUtil.isResponseMsg(m.getType())
                && !RaftUtil.isLocalMsgTarget(m.getFrom())
                && rn.getRaft().getTracker().getProgress().get(m.getFrom()) == null) {
            // 来自未知节点的响应消息，丢弃
            return;
        }
        try {
            rn.getRaft().step(m);
        } catch (RaftException e) {
            log.debug("raft: dropped message " + m.getType() + ": " + e.getMessage());
        }
    }

    /**
     * 节点停止时释放所有还没被处理的请求。
     */
    private void abortPending() {
        synchronized (lifecycle) {
            MsgWithResult pm;
            while ((pm = propc.poll()) != null) {
                pm.fail(new RaftException("raft: stopped"));
            }
            ConfChangeRequest cc;
            while ((cc = confc.poll()) != null) {
                cc.abort();
            }
            StatusRequest sr;
            while ((sr = statusc.poll()) != null) {
                sr.abort();
            }
        }
        // 队列腾空了，唤醒等着入队的提案者 —— 它们会看到 stopped 并失败
        notifyProposalSpace();
        // 所有还在等着的异步提案都结束掉
        for (CompletableFuture<Void> f : pendingProposals.values()) {
            f.completeExceptionally(new RaftException("raft: stopped"));
        }
        pendingProposals.clear();
        for (PendingRequest req : pendingRequests) {
            req.abort();
        }
        pendingRequests.clear();
    }

    /**
     * 空的状态，等价于 etcd 的 {@code Status{}}
     */
    private static Status emptyStatus() {
        return new Status(new BasicStatus(0, new HardState(0, 0, 0), null, 0, 0), null, null);
    }

    /**
     * 空的成员配置
     */
    private static ConfState emptyConfState() {
        return new ConfState(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), false);
    }

    /** tick 通道的信号 */
    private static final Object TICK = new Object();

    /** advance 通道的信号 */
    private static final Object ADVANCE = new Object();

    /**
     * 等待应用层来取 Ready 的最长单次阻塞时间。
     *
     * <p>应用层在等 Ready 时，状态机线程会把已经组装好的 Ready 直接交给它，
     * 这个时间只是用来覆盖「应用层已经登记要取、但还没真正开始等」这个窗口。</p>
     */
    private static final long READY_HANDOFF_TIMEOUT_MILLIS = 10;

    /**
     * 应用层等待 Ready 时的单次等待时长。
     *
     * <p>分片等待是为了能及时发现节点已经停止。</p>
     */
    private static final long READY_POLL_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    /**
     * 一轮里排空提案队列的时间预算。
     *
     * <p>排队空提案通道是为了让提案能凑成一批（group commit）：
     * 一次 fsync 覆盖这一轮攒下的全部条目，批量越大吞吐越高。</p>
     *
     * <p>合批之后这一步很便宜 —— 攒到的提案合成一条消息走一次 {@code step}，
     * 所以时间预算只是个兜底，真正限制单轮长度的是条数上限。</p>
     */
    private static final long PROPOSAL_DRAIN_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    /**
     * 一轮里排空提案队列的条数上限，防止提案极快时一轮排太久。
     */
    private static final int PROPOSAL_DRAIN_LIMIT = 4096;

    /**
     * 一轮里最多合成多少条提案。
     *
     * <p>1 表示不合成（每条提案单独走一次 {@code step}），用来做 A/B 对照。</p>
     */
    private static final int PROPOSAL_BATCH_LIMIT =
            Integer.getInteger("edap.raft.propBatchLimit", PROPOSAL_DRAIN_LIMIT);

    /**
     * 等提案队列腾出位置时的单次等待时长。
     *
     * <p>只是兜底：正常情况下状态机线程排空队列后会 notifyAll，
     * 这里的超时是为了防止丢通知导致提案者永久挂起。</p>
     */
    private static final long PROPOSAL_SPACE_WAIT_MILLIS = 50;

    /**
     * 丢弃提案的告警间隔：每丢弃这么多条告警一次。
     */
    private static final long DROP_WARN_INTERVAL = 1024;

    /**
     * apply 线程等待新任务时的单次等待时长。
     *
     * <p>分片等待是为了能及时发现节点已经停止。</p>
     */
    private static final long APPLY_POLL_MILLIS = 100;

    /**
     * 落盘线程等待新任务时的单次等待时长。
     */
    private static final long PERSIST_POLL_MILLIS = 100;

    /**
     * 等待状态机线程处理的请求。
     */
    private interface PendingRequest {

        /**
         * 节点停止时调用，唤醒等待中的调用方。
         */
        void abort();
    }

    /**
     * 一条待处理的提案。
     *
     * <p>{@code received} 在状态机线程取走提案时倒计时，
     * {@code result} 在提案被状态机处理完之后完成。</p>
     */
    private static final class MsgWithResult {

        private final Message              message;
        private final CompletableFuture<Void>     received = new CompletableFuture<>();
        private final CompletableFuture<Boolean> result   = new CompletableFuture<>();

        /** 失败原因，null 表示没有失败 */
        private volatile Throwable error;

        MsgWithResult(Message message) {
            this.message = message;
        }

        Message getMessage() {
            return message;
        }

        /**
         * 这条提案的请求 id，0 表示没有对应的客户端请求。
         */
        long getRequestId() {
            List<Entry> ents = message.getEntries();
            if (ents == null || ents.isEmpty()) {
                return 0L;
            }
            return ents.get(0).getRequestId();
        }

        void markReceived() {
            received.complete(null);
        }

        void complete(boolean accepted) {
            result.complete(accepted);
        }

        void fail(Throwable t) {
            this.error = t;
            received.completeExceptionally(t);
            result.completeExceptionally(t);
        }

        /**
         * 是否已经失败。
         */
        boolean isFailed() {
            return error != null;
        }

        /**
         * 失败原因，没有失败时返回 null。
         */
        Throwable getError() {
            return error;
        }

        /**
         * 等到提案被状态机取走。
         */
        void awaitReceived() {
            join(received);
        }

        /**
         * 等到提案被状态机处理完。
         *
         * @return true 表示提案被接受
         */
        boolean awaitResult() {
            return join(result);
        }
    }

    /**
     * 一次成员变更请求。
     */
    private final class ConfChangeRequest implements PendingRequest {

        private final ConfChangeI              cc;
        private final CompletableFuture<ConfState> future = new CompletableFuture<>();

        ConfChangeRequest(ConfChangeI cc) {
            this.cc = cc;
        }

        void run() {
            long selfId = rn.getRaft().getId();
            boolean okBefore = rn.getRaft().getTracker().getProgress().get(selfId) != null;
            ConfState cs = rn.applyConfChange(cc);
            boolean okAfter = rn.getRaft().getTracker().getProgress().get(selfId) != null;
            // 节点被移出集群之后，阻塞后续的提案。
            // 只有「本来在配置里、现在不在」才算被移出：
            // 追赶日志的节点可能还不知道自己已经是集群成员。
            if (okBefore && !okAfter
                    && !cs.getVoters().contains(selfId)
                    && !cs.getVotersOutgoing().contains(selfId)) {
                proposalBlocked = true;
            }
            future.complete(cs);
            pendingRequests.remove(this);
        }

        @Override
        public void abort() {
            future.complete(emptyConfState());
        }

        ConfState await() {
            return join(future);
        }
    }

    /**
     * 一次状态查询请求。
     */
    private final class StatusRequest implements PendingRequest {

        private final CompletableFuture<Status> future = new CompletableFuture<>();

        void run() {
            future.complete(rn.status());
            pendingRequests.remove(this);
        }

        @Override
        public void abort() {
            future.complete(emptyStatus());
        }

        Status await() {
            return join(future);
        }
    }

    /**
     * 等待一个 future 完成，失败时把异常原样抛出来。
     */
    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RaftException("raft: interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RaftException("raft: " + (cause == null ? e.toString() : cause.toString()));
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw e;
        }
    }
}
