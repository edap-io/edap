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
import io.edap.raft.protocol.ConfChangeI;
import io.edap.raft.protocol.ConfState;
import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.EntryType;
import io.edap.raft.protocol.HardState;
import io.edap.raft.protocol.Message;
import io.edap.raft.protocol.MessageType;

import java.util.Collections;
import java.util.Set;
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

    /** 提案通道：只有知道 leader 之后，提案才会被取走 */
    private final BlockingQueue<MsgWithResult> propc = new LinkedBlockingQueue<>();

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

    /** 底层状态机 */
    private final RawNode rn;

    /** 节点是否已经停止 */
    private final AtomicBoolean stopped = new AtomicBoolean();

    /** 状态机线程 */
    private final Thread worker;

    /** 提案是否被阻塞：节点被移出集群之后不再接收提案 */
    private boolean proposalBlocked;

    /** 保护「停止」与「入队」的竞态 */
    private final Object lifecycle = new Object();

    /**
     * 用给定的 RawNode 创建一个 Node，节点线程会立刻启动。
     */
    public Node(RawNode rawNode) {
        this.rn = rawNode;
        this.worker = new Thread(this::run, "edap-raft-" + rawNode.getRaft().getId());
        this.worker.setDaemon(true);
        this.worker.start();
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
     * @throws RaftException 节点已停止，或提案被丢弃
     */
    public void propose(byte[] data) {
        Message m = new Message().setType(MessageType.MSG_PROP)
                .setEntries(Collections.singletonList(
                        new Entry(0, 0, EntryType.NORMAL, data)));
        MsgWithResult pm = enqueueProposal(m);
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
        MsgWithResult pm = enqueueProposal(m);
        pm.awaitReceived();
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
            enqueueProposal(m).awaitReceived();
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
            enqueueProposal(m);
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
        // 这里必须无条件 unpark：标志位可能已经是 true，
        // 而状态机线程正停在 park 上等一个不会来的信号。
        LockSupport.unpark(worker);
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
     */
    private MsgWithResult enqueueProposal(Message m) {
        MsgWithResult pm = new MsgWithResult(m);
        synchronized (lifecycle) {
            if (stopped.get()) {
                pm.fail(new RaftException("raft: stopped"));
                return pm;
            }
            propc.add(pm);
        }
        signalWakeup();
        return pm;
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
        // 已经组装好、等待交付的 Ready
        boolean readyPending = false;
        // 已经交付、等待应用层 advance 的 Ready
        boolean advanceArmed = false;
        // 自上次组装 Ready 之后，raft 状态有没有变过
        boolean dirty = false;
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
                // 一次排空，避免每条提案都把整个循环重来一遍；
                // 排空受时间预算约束，网络消息和 tick 才不会被拖太久。
                if (r.hasLeader() && !proposalBlocked) {
                    MsgWithResult pm;
                    int drained = 0;
                    long deadline = System.nanoTime() + PROPOSAL_DRAIN_BUDGET_NANOS;
                    while (drained++ < PROPOSAL_DRAIN_LIMIT && (pm = propc.poll()) != null) {
                        handleProposal(pm);
                        dirty = true;
                        if (System.nanoTime() >= deadline) {
                            break;
                        }
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
                        rn.advance(rd);
                        rd = null;
                        advanceArmed = false;
                        dirty = true;
                    }
                }

                // 网络消息
                Message m;
                while ((m = recvc.poll()) != null) {
                    handleMessage(m);
                    dirty = true;
                }

                // 组装 Ready
                // 与 etcd 一致：应用层还没把上一个 Ready 取走之前，每一轮都重新
                // 组装一次，这样应用层拿到的 Ready 总是当前能凑出来的最大一批。
                //
                // 必须排在本轮所有 step 之后、并且紧挨着交付：readyWithoutAccept()
                // 与 acceptReady() 之间如果又 step 过，中间产生的消息会被
                // acceptReady() 里的 clearMessages() 抹掉。
                if (!advanceArmed && rn.hasReady() && (dirty || !readyPending)) {
                    rd = rn.readyWithoutAccept();
                    readyPending = true;
                    dirty = false;
                }

                // 交付 Ready
                // 与 etcd 一致：Ready 必须真的交到应用层手里才算交出去。
                // 应用层还没来取时不做任何承诺，先去处理别的事，
                // 下一轮会重新组装一个（更大的）Ready 再交。
                if (readyPending) {
                    boolean delivered = readyWaiters.get() > 0
                            ? readyc.offer(rd, READY_HANDOFF_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                            : readyc.offer(rd, 0, TimeUnit.NANOSECONDS);
                    if (delivered) {
                        rn.acceptReady(rd);
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
     * 处理一条提案。
     */
    private void handleProposal(MsgWithResult pm) {
        Message m = pm.getMessage();
        m.setFrom(rn.getRaft().getId());
        pm.markReceived();
        boolean accepted;
        try {
            accepted = rn.getRaft().step(m);
        } catch (RaftException e) {
            accepted = false;
        }
        pm.complete(accepted);
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
     * <p>排队空提案通道是为了让提案能凑成一批（group commit），
     * 但一轮不能无限排：网络消息和 tick 还要插进来。
     * 用时间而不是条数做上限，是因为单条提案的耗时不可控
     * （状态机里可能做重活），按条数排会让网络消息等上好几百毫秒。</p>
     */
    private static final long PROPOSAL_DRAIN_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(1);

    /**
     * 一轮里排空提案队列的条数上限，防止提案极快时一轮排太久。
     */
    private static final int PROPOSAL_DRAIN_LIMIT = 4096;

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

        MsgWithResult(Message message) {
            this.message = message;
        }

        Message getMessage() {
            return message;
        }

        void markReceived() {
            received.complete(null);
        }

        void complete(boolean accepted) {
            result.complete(accepted);
        }

        void fail(Throwable t) {
            received.completeExceptionally(t);
            result.completeExceptionally(t);
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
