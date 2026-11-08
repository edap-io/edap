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
import io.edap.util.timing.TimerHandle;
import io.edap.util.timing.TimerService;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
import java.util.concurrent.TimeoutException;
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

    /** 正在等提案队列腾位置的提案者数 */
    private final AtomicInteger proposalSpaceWaiters = new AtomicInteger();

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

    /** 异步提案的待完成表：请求 id -> 待完成记录 */
    private final ConcurrentHashMap<Long, PendingProposal> pendingProposals = new ConcurrentHashMap<>();

    /**
     * 已经分配了日志索引、等着被提交通知的提案，按追加顺序排队。
     *
     * <p>只有「提交即通知」打开时才有内容。提案在 raft 日志里的索引按追加顺序
     * 单调递增，而 raft 的提交也是按序发生的，所以「已提交到 C」就是
     * 「队头那些索引 ≤ C 的提案」。用 FIFO 队列而不是按索引排序的 map：
     * 每条提案只做一次入队一次出队，摊销 O(1)，不给状态机线程添开销
     * —— 状态机线程是提案消费速率的瓶颈，每条提案多花几十纳秒都是全局代价。</p>
     *
     * <p>只由状态机线程读写，不需要并发容器。</p>
     */
    private final ArrayDeque<PendingProposal> pendingByOrder = new ArrayDeque<>();

    /**
     * 已经通知过客户端的最大提交索引。
     *
     * <p>只由状态机线程读写。碎片批次合并期间同一个 Ready 会被反复
     * 重新组装，用它来跳过已经通知过的那一段提交。</p>
     */
    private long lastNotifiedIndex;

    /**
     * 「提交即通知」模式下，提前通知了多少个客户端。
     *
     * <p>这些通知发生在两次 Ready 组装之间。碎片批次合并要用「刚通知了多少个
     * 客户端」来估算下一批的预期条数，所以这里记一份，等 Ready 组装时用掉。</p>
     */
    private int earlyNotified;

    /** 异步提案的请求 id 序号 */
    private final AtomicLong requestSeq = new AtomicLong();

    /**
     * 诊断开关：{@code -Dedap.raft.diag=true} 时才记录分段计时。
     *
     * <p>默认关闭，关掉时除了几个布尔判断没有任何额外开销。</p>
     */
    private static final boolean DIAG = Boolean.getBoolean("edap.raft.diag");

    /** 诊断数据：状态机线程的分段计时 */
    private final Diag diag = new Diag();

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

    /**
     * tick 驱动句柄（{@link #startTicker} 注册的）。
     *
     * <p>{@link #stop()} 会把它撤掉 —— tick 是"节点还活着"的信号，
     * 节点停了还继续 tick 只是白烧 CPU。</p>
     */
    private volatile TimerHandle ticker;

    /** 状态机线程 */
    private final Thread worker;

    /** apply 线程，没有独立 apply 处理器时为 null */
    private final Thread applyThread;

    /** 落盘线程，框架不接管落盘时为 null */
    private final Thread persistThread;

    /** 超时清扫线程：把到期的异步提案结束掉 */
    private final Thread timeoutThread;

    /**
     * 落盘线程累计的忙时（纳秒）：只统计真正在写 + fsync 的时间。
     *
     * <p>落盘线程自己写，落盘线程自己读；读到的永远是已经发生过的值，
     * 用来算「忙时占比」不需要精确到纳秒。</p>
     */
    private final AtomicLong persistBusyNanos = new AtomicLong();

    /**
     * 忙时占比的统计窗口起点（纳秒）。只有状态机线程读写。
     */
    private long busyWindowStartNanos;

    /**
     * 统计窗口起点时的累计忙时（纳秒）。只有状态机线程读写。
     */
    private long busyWindowStartBusyNanos;

    /**
     * 最近的落盘忙时占比：落盘线程有多少比例的时间在真的写 + fsync。
     *
     * <p>负数表示还没测出来（第一个统计窗口没走完）。
     * 只有状态机线程读写。</p>
     */
    private double persistBusyRatio = -1.0;

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
        this.notifyOnCommit = Boolean.parseBoolean(
                System.getProperty("edap.raft.notifyOnCommit", "false"));
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
        this.timeoutThread = new Thread(this::runTimeoutSweeper,
                "edap-raft-timeout-" + rawNode.getRaft().getId());
        this.timeoutThread.setDaemon(true);
        this.timeoutThread.start();
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
     * 启动 tick 驱动 —— 按固定节奏调 {@link #tick()}，驱动选举超时与心跳。
     *
     * <p><b>raft 模块不自带定时器</b>：时间源由调用方给（通常是平台共享的
     * {@code io.edap.util.timing.Timers#get()}），这样整个平台只有一套时间口径
     * 和一套线程模型。</p>
     *
     * <p><b>tick 周期怎么定</b>：raft 的超时用「tick 数」表达
     * （{@link RaftConfig#electionTick} / {@link RaftConfig#heartbeatTick}），
     * 这里给的是<b>一个 tick 折合多少毫秒</b>。比如 electionTick=10、
     * heartbeatTick=1、tickMillis=100 → 心跳 100ms、选举超时 1s。</p>
     *
     * <p><b>生命周期</b>：句柄挂在节点上，{@link #stop()} 会自动撤掉 tick ——
     * 节点停了还继续 tick 只是白烧 CPU。重复调用会先撤掉上一个。</p>
     *
     * @param timerService 时间源（平台共享定时服务）
     * @param tickMillis   一个 tick 折合的毫秒数，必须 &gt; 0
     * @return tick 任务的句柄（可提前取消）
     */
    public synchronized TimerHandle startTicker(TimerService timerService, long tickMillis) {
        if (timerService == null) {
            throw new NullPointerException("timerService");
        }
        if (tickMillis <= 0) {
            throw new IllegalArgumentException("tickMillis must be positive");
        }
        TimerHandle old = this.ticker;
        if (old != null) {
            old.cancel();
        }
        TimerHandle h = timerService.scheduleAtFixedRate(this::tick, tickMillis, tickMillis, TimeUnit.MILLISECONDS);
        this.ticker = h;
        return h;
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
        long deadlineNanos = timeoutMillis > 0
                ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
                : 0L;
        pendingProposals.put(requestId, new PendingProposal(future, deadlineNanos, requestId));
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
        // 撤掉 tick 驱动：节点停了还继续 tick 只是白烧 CPU
        TimerHandle t = this.ticker;
        if (t != null) {
            t.cancel();
            this.ticker = null;
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
        // 让超时清扫线程从等待里醒过来，它看到 stopped 就会退出
        LockSupport.unpark(timeoutThread);
        // 先等落盘线程：它可能正写盘。等它退出，调用方才能安全地关存储 ——
        // 否则 close() 会和一次还在进行的 fsync 撞在一起。
        joinBounded(persistThread, PERSIST_JOIN_TIMEOUT_MILLIS, "persist");
        joinBounded(applyThread, APPLY_JOIN_TIMEOUT_MILLIS, "apply");
        joinBounded(timeoutThread, TIMEOUT_JOIN_TIMEOUT_MILLIS, "timeout");
        joinBounded(worker, WORKER_JOIN_TIMEOUT_MILLIS, "worker");
    }

    /**
     * 等一个线程退出，最多等 {@code timeoutMillis}。
     *
     * <p>等到就返回；等不到就打印告警（带线程栈）、打断它，
     * 再给一小段时间，然后放弃 —— 停机不能因为一个线程卡住而把调用方也拖死。
     * 线程都是守护线程，放弃之后它们也不会挡住 JVM 退出。</p>
     */
    private void joinBounded(Thread t, long timeoutMillis, String name) {
        if (t == null) {
            return;
        }
        try {
            t.join(timeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!t.isAlive()) {
            return;
        }
        // 没退出：把它的栈打出来，下一次再遇到就能直接定位
        StringBuilder sb = new StringBuilder("raft: thread ").append(name)
                .append(" did not exit within ").append(timeoutMillis)
                .append("ms, interrupting it; stack:");
        for (StackTraceElement ste : t.getStackTrace()) {
            sb.append("\n\tat ").append(ste);
        }
        log.warn(sb.toString());
        t.interrupt();
        try {
            t.join(STOP_JOIN_RETRY_MILLIS);
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
        // 先登记自己是等待者，再进监视器：
        // 状态机线程看到「有人在等」才会 notifyAll
        proposalSpaceWaiters.incrementAndGet();
        try {
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
        } finally {
            proposalSpaceWaiters.decrementAndGet();
        }
    }

    /**
     * 通知等着入队的提案者：队列腾出位置了。
     *
     * <p>没有等待者时直接跳过：{@code notifyAll} 是 native 调用，
     * 每次调用都是一次 JVM safepoint —— 全线程都要停一下。
     * 队列每腾空一次就通知一次，在提案速率高的时候会白白拖慢整个进程。</p>
     */
    private void notifyProposalSpace() {
        spaceVersion.incrementAndGet();
        if (proposalSpaceWaiters.get() == 0) {
            return;
        }
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
        // 碎片批次合并：等到这个时刻为止还在等新提案；0 表示不在等
        long deferUntilNanos = 0;
        // 碎片批次合并：这一轮推迟从什么时候开始（总时长上限）
        long deferStartNanos = 0;
        // 碎片批次合并：预计会到来的新提案条数（上一批通知了多少个客户端）
        int expectBatchSize = 0;
        // 碎片批次合并：上一次判断时本批的条目数（用来发现「批还在长」）
        int deferSeenEntries = -1;
        long lead = Raft.NONE;
        // 诊断：commit 指针推进的次数（两种模式用同一个口径）
        long lastCommittedSeen = 0;

        try {
            while (!stopped.get()) {
                if (DIAG) {
                    diag.onLoop();
                    long committedNow = r.getRaftLog().committed();
                    if (committedNow > lastCommittedSeen) {
                        lastCommittedSeen = committedNow;
                        diag.commitAdvances++;
                    }
                }
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
                    // 换了 leader：上一任的通知跟这一任对不上，预期作废
                    expectBatchSize = 0;
                    deferUntilNanos = 0;
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
                    if (DIAG) {
                        diag.netMessages++;
                    }
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

                // 提交即通知
                // 上面两段（网络消息、落盘完成）都可能让 raft 的 commit 指针前进。
                // 正常路径下提交通知要等下一次 Ready 组装顺带完成，而 Ready 的组装
                // 被「上一个 Ready 还在落盘 / 应用层还没 advance」卡住 ——
                // 实测这一段要占「MSG_APP 发出 → 提交」的 34%（p50 15.5ms）。
                // 这里在 commit 指针推进之后立刻通知，不等 Ready。
                if (notifyOnCommit) {
                    long committed = r.getRaftLog().committed();
                    if (committed > lastNotifiedIndex) {
                        long tn = DIAG ? System.nanoTime() : 0L;
                        int n = notifyCommitted(committed);
                        if (DIAG) {
                            diag.onNotify(System.nanoTime() - tn, n);
                        }
                        earlyNotified += n;
                        lastNotifiedIndex = committed;
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
                    if (DIAG) {
                        diag.onAssembly();
                    }
                    if (frameworkPersist) {
                        // 日志由框架落盘，Ready 里不再带
                        rd.setEntries(Collections.emptyList());
                    }
                    if (applyHandler != null) {
                        // 已提交条目交给 apply 线程，Ready 里不再带
                        rd.setCommittedEntries(Collections.emptyList());
                    }
                    // 提案被提交：结束对应的异步提案
                    long tc = DIAG ? System.nanoTime() : 0L;
                    int notified = completePendingProposals(rdCommitted);
                    if (DIAG) {
                        diag.onNotify(System.nanoTime() - tc, notified);
                    }
                    if (notified > 0) {
                        // 通知了多少个客户端，就预计会回来同样数量的新提案。
                        // 下一批攒到这个量再落盘，一次 fsync 就能覆盖整批。
                        expectBatchSize = notified;
                    } else if (earlyNotified > 0) {
                        // 提交即通知：这些客户端在 Ready 组装之前就被通知过了，
                        // 用同一份计数维持碎片批次合并的预期批大小。
                        expectBatchSize = earlyNotified;
                    }
                    earlyNotified = 0;
                    rdPersisted = false;
                    dirty = false;
                    // 碎片批次合并：这一批只有零星几条，而刚刚提交了更多条目 ——
                    // 拿到通知的客户端马上会发新提案过来，等它们到齐再一起落盘，
                    // 比「为一条条目付一次 fsync」划算得多。
                    boolean deferred = false;
                    if (frameworkPersist && shouldDeferPersist(rd, rdEntries, expectBatchSize)) {
                        long now = System.nanoTime();
                        if (deferUntilNanos == 0) {
                            // 开始这一轮推迟
                            deferUntilNanos = now + TimeUnit.MILLISECONDS.toNanos(DEFER_WAIT_MILLIS);
                            deferStartNanos = now;
                        } else if (rdEntries.size() != deferSeenEntries) {
                            // 批还在长：把等待窗口往后延，才能等到整批
                            deferUntilNanos = now + TimeUnit.MILLISECONDS.toNanos(DEFER_WAIT_MILLIS);
                        }
                        deferSeenEntries = rdEntries.size();
                        long waited = now - deferStartNanos;
                        if (now < deferUntilNanos
                                && waited < TimeUnit.MILLISECONDS.toNanos(DEFER_MAX_WAIT_MILLIS)) {
                            deferred = true;
                            // 丢弃这个 Ready：readyWithoutAccept() 是只读的，
                            // 下一轮重新组装，条目会更多。
                            // 消息也还在状态机里（acceptReady 才会清空它们），
                            // 下一轮的 Ready 会带上。
                            if (DIAG) {
                                diag.onDeferral(System.nanoTime());
                            }
                            rd = null;
                            rdEntries = Collections.emptyList();
                            rdCommitted = Collections.emptyList();
                            readyPending = false;
                            dirty = true;
                        }
                    }
                    if (!deferred) {
                        deferUntilNanos = 0;
                        deferSeenEntries = -1;
                        readyPending = true;
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
                            if (DIAG) {
                                diag.onPersist(System.nanoTime(), rdEntries.size(),
                                        expectBatchSize, diag.deferralStartNanos);
                            }
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
                //
                // 这里用带超时的 park 而不是无限期 park：万一有一次 unpark
                // 没能送到（实测出现过一次，1/约 100 次运行），无限期 park
                // 的线程就再也醒不过来了 —— 停机时 stop() 会一直等它退出。
                // 带超时的 park 最多晚 WORKER_PARK_MILLIS 就能醒来重新看一眼
                // stopped，代价是每个节点每秒多醒一次（空转一轮循环，可忽略）。
                if (!wakeupFlag.getAndSet(false)) {
                    if (deferUntilNanos > 0) {
                        // 碎片批次合并：等新提案到齐。
                        // 有新提案到达时会被 unpark 提前唤醒；
                        // 没人来就等到窗口结束为止。
                        long remain = deferUntilNanos - System.nanoTime();
                        if (remain > 0) {
                            LockSupport.parkNanos(this, remain);
                        }
                    } else {
                        LockSupport.parkNanos(this, WORKER_PARK_NANOS);
                    }
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
        long t0 = System.nanoTime();
        try {
            io.edap.raft.storage.PersistentStorage storage = rn.getStorage();
            if (rd.getSnapshot() != null) {
                storage.applySnapshot(rd.getSnapshot());
            }
            storage.save(rd.getHardState(), entries);
        } finally {
            // 落盘线程的忙时：只统计真正在写 + fsync 的时间。
            // 碎片批次合并用它判断「瓶颈在不在 fsync 上」。
            persistBusyNanos.addAndGet(System.nanoTime() - t0);
        }
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
     * 这一批日志要不要推迟落盘（碎片批次合并）。
     *
     * <p>leader 刚提交了一批条目，拿到通知的客户端会马上发同样数量的新提案
     * 过来。如果这时候只攒到零星几条就落盘，一次 fsync（十几毫秒）只覆盖了
     * 几条条目 —— 吞吐被 fsync 次数卡住，而不是被条目数卡住。
     * 等新提案到齐再一起落盘，一次 fsync 就能覆盖整批。</p>
     *
     * <p>只有 leader 这么做：跟随者的批次由 leader 的复制消息决定，
     * 推迟落盘只会推迟自己的确认消息，反过来拖慢 leader 的提交。</p>
     *
     * <p>还要满足「瓶颈在 fsync 上」这个前提：落盘线程的忙时占比
     * （见 {@link #currentPersistBusyRatio()}）太低时，合并 fsync 省不出吞吐，
     * 只会把这一轮的复制消息一起拖住。</p>
     *
     * @param rd      这一轮组装出来的 Ready
     * @param entries 这一轮要落盘的日志条目
     * @param expect  预计会到来的新提案条数（上一批通知了多少个客户端）
     * @return true 表示这一轮先不落盘，等更多条目并进来
     */
    private boolean shouldDeferPersist(Ready rd, List<Entry> entries, int expect) {
        if (entries.isEmpty()) {
            // 空批次的落盘不要钱（没有日志要写），不用等
            return false;
        }
        if (rd.getSnapshot() != null || !rd.getReadStates().isEmpty()) {
            // 快照必须先进存储；线性一致读的确认也不能拖
            return false;
        }
        Raft r = rn.getRaft();
        if (r.getLead() != r.getId()) {
            return false;
        }
        if (expect < DEFER_MIN_COMMITTED) {
            // 刚提交的量太小，等来的新提案也少，不值得
            return false;
        }
        double busy = currentPersistBusyRatio();
        diag.onBusyDecision(busy);
        if (busy >= 0 && busy < DEFER_MIN_BUSY_RATIO) {
            // 落盘线程有大半时间在闲着 ⇒ 瓶颈不在 fsync 上 ⇒
            // 少一次 fsync 省下来的时间没有产出，推迟落盘换来的只是延迟。
            //
            // 深窗口（提案堆到几千条）就是这种状态：批本来就能攒得很大，
            // 推迟落盘会把复制消息一起拖住（Ready 里的消息跟条目一起被推迟），
            // 提交变慢 → 客户端补位不上 → 批更小 → 继续推迟，
            // 变成反向的自维持正反馈。
            return false;
        }
        // 本批还没攒到预期量：这是一个碎片
        return entries.size() < expect;
    }

    /**
     * 落盘线程最近一个统计窗口内的忙时占比。
     *
     * <p>「忙时占比」= 落盘线程花在写 + fsync 上的时间 / 墙钟时间。
     * 这个比例回答的是：<b>瓶颈在不在 fsync 上</b>。</p>
     *
     * <ul>
     * <li>接近 1：落盘线程一直在 fsync ⇒ 系统被 fsync 卡住 ⇒
     *     把碎片批次合并掉能直接换来吞吐。</li>
     * <li>明显小于 1：落盘线程在等网络、等对端确认 ⇒ 合并 fsync 省不出东西，
     *     只会把这一轮的复制消息一起拖住。</li>
     * </ul>
     *
     * <p>只由状态机线程调用。</p>
     *
     * @return 占比（0~1）；负数表示第一个统计窗口还没走完，测不出来
     */
    private double currentPersistBusyRatio() {
        long now = System.nanoTime();
        if (busyWindowStartNanos == 0) {
            busyWindowStartNanos = now;
            busyWindowStartBusyNanos = persistBusyNanos.get();
            return persistBusyRatio;
        }
        long elapsed = now - busyWindowStartNanos;
        if (elapsed >= TimeUnit.MILLISECONDS.toNanos(DEFER_BUSY_WINDOW_MILLIS)) {
            long busy = persistBusyNanos.get();
            persistBusyRatio = (double) (busy - busyWindowStartBusyNanos) / (double) elapsed;
            busyWindowStartNanos = now;
            busyWindowStartBusyNanos = busy;
        }
        return persistBusyRatio;
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
        int queueDepth = DIAG ? propc.size() : 0;
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
        if (DIAG) {
            diag.onDrain(batch.size(), queueDepth, System.nanoTime());
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
        long before = rn.getRaft().getRaftLog().lastIndex();
        boolean accepted = stepProposal(new Message()
                .setType(MessageType.MSG_PROP)
                .setEntries(entries));
        if (accepted) {
            // 提案进了本节点的日志：记下每条提案的日志索引，
            // 「提交即通知」要靠它把「已提交到 C」翻译成「通知哪些客户端」。
            // follower 只是把提案转发给 leader，本节点的日志没变，lastIndex 不动，
            // 这里自然就跳过了。
            if (notifyOnCommit) {
                recordProposalIndexes(entries, before);
            }
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
     * {@code remove} 返回 null，没有副作用。已经通知过的那一段提交
     * 直接跳过 —— 碎片批次合并期间同一个 Ready 会被反复重新组装，
     * 每次都重扫一遍已提交的条目是白白的开销。</p>
     *
     * @return 本次实际通知到的客户端数。碎片批次合并用它估算
     *         「接下来会来多少条新提案」。
     */
    private int completePendingProposals(List<Entry> committed) {
        if (pendingProposals.isEmpty() || committed == null || committed.isEmpty()) {
            return 0;
        }
        long lastIndex = committed.get(committed.size() - 1).getIndex();
        if (lastIndex <= lastNotifiedIndex) {
            // 这一段已经通知过了
            return 0;
        }
        int notified = 0;
        for (Entry e : committed) {
            long requestId = e.getRequestId();
            if (requestId == 0) {
                continue;
            }
            PendingProposal p = pendingProposals.remove(requestId);
            if (p != null) {
                p.future.complete(null);
                notified++;
            }
        }
        if (lastIndex > lastNotifiedIndex) {
            // 「提交即通知」可能已经把指针推得更远了，不能往回退
            lastNotifiedIndex = lastIndex;
        }
        return notified;
    }

    /**
     * 记下刚被追加的提案在 raft 日志里的索引。
     *
     * <p>raft 追加条目时给每条条目分配的索引是连续的：
     * {@code before + 1 ... before + entries.size()}。
     * 「提交即通知」要靠这个索引把「已提交到 C」翻译成「该通知哪些客户端」，
     * 免得为了拿请求 id 再把日志读一遍。</p>
     *
     * <p>只由状态机线程调用。</p>
     *
     * @param entries 刚被追加的条目（顺序与 raft 里的顺序一致）
     * @param before  追加之前的日志最后一条索引
     */
    private void recordProposalIndexes(List<Entry> entries, long before) {
        long after = rn.getRaft().getRaftLog().lastIndex();
        if (after <= before) {
            // 没有追加到本节点（日志没变）：follower 转发提案时就是这种情况
            return;
        }
        int n = (int) Math.min(after - before, entries.size());
        // 追加时的任期：只有「同一个任期的同一个 leader」才能安全地
        // 靠索引来通知（见 {@link #notifyCommitted} 的说明）
        long term = rn.getRaft().getTerm();
        for (int i = 0; i < n; i++) {
            long requestId = entries.get(i).getRequestId();
            if (requestId == 0) {
                continue;
            }
            PendingProposal p = pendingProposals.get(requestId);
            if (p == null) {
                // 不是本节点发起的提案（或者是别的节点转过来的）
                continue;
            }
            long index = before + 1 + i;
            p.index = index;
            p.term = term;
            // 入队顺序就是索引顺序：FIFO 队列的队头永远是索引最小的那条
            pendingByOrder.addLast(p);
        }
    }

    /**
     * 通知所有「日志索引不超过 {@code committedIndex}」的提案。
     *
     * <p>「提交即通知」用：commit 指针一推进就通知客户端，
     * 不等下一次 Ready 组装（那要等一个落盘周期）。</p>
     *
     * <p>索引按提案顺序单调递增，所以「已提交到 C」就是队列里队首那些
     * 索引 ≤ C 的提案。已经超时或已被别的路径摘掉的提案会被跳过
     * （{@code pendingProposals} 里已经没有它了）。</p>
     *
     * <p>只由状态机线程调用。</p>
     *
     * @return 本次实际通知到的客户端数
     */
    private int notifyCommitted(long committedIndex) {
        if (pendingByOrder.isEmpty()) {
            return 0;
        }
        // 通知的前提：本节点还是「当初追加这条提案时」的那个 leader。
        //
        // 索引会被复用：节点 A 在任期 T 当 leader 时把提案 P 追加到索引 100，
        // 随后 A 失去领导权，新 leader 的日志在索引 100 上是另一条内容。
        // 这时 commit 指针推过 100，如果只看索引就通知 P，
        // 客户端会拿到一个「提交成功」，而 P 其实已经被覆盖丢掉了 ——
        // 这是丢数据。
        //
        // leader 不会截断自己的日志（leader 收到 MSG_APP 直接忽略），
        // 而失去领导权必然伴随任期变化，所以「还是同一个任期的 leader」
        // 就足以保证「索引 100 上仍然是当初那条提案」。
        // 不满足时就不通知，让它走超时 —— 提案确实丢了，超时才是对的答案。
        long nowTerm = rn.getRaft().getTerm();
        boolean stillLeader = rn.getRaft().getLead() == rn.getRaft().getId();
        int notified = 0;
        // 队头的索引 > commit 就说明后面的都还没提交，停。
        // 队列按索引递增排列，所以只需要看队头。
        while (!pendingByOrder.isEmpty()) {
            PendingProposal p = pendingByOrder.peekFirst();
            if (p.index > committedIndex) {
                break;
            }
            pendingByOrder.pollFirst();
            if (!stillLeader || nowTerm != p.term) {
                // 慢路径：任期对不上不代表提案丢了，先读一格日志确认
                if (!isProposalCommittedAnyway(p)) {
                    // 这条提案确实不可信了：不通知，超时清扫会把它结束掉
                    continue;
                }
            }
            PendingProposal cur = pendingProposals.remove(p.requestId);
            if (cur != null) {
                cur.future.complete(null);
                notified++;
            }
        }
        return notified;
    }

    /**
     * 慢路径：任期对不上时，确认这条提案是不是其实提交了。
     *
     * <p>场景：节点 A 在任期 T 当 leader 时把提案 P 追加到索引 i 并复制给了多数派，
     * 随后 A 失去领导权。新 leader B 的日志里索引 i 上还是 P，B 提交了 P ——
     * 提案其实成功了，只是 A 不再用得上「同一个任期的同一个 leader」这条捷径。
     * 这时如果一律当成「丢了」，客户端会拿到一个假失败。</p>
     *
     * <p>反过来，B 的日志在索引 i 上是别的内容（任期 T' &gt; T），那 P 就是真的
     * 被覆盖丢了，不该通知。</p>
     *
     * <p>判据是「索引 i 处的任期还是不是 T」：一个任期里同一个索引只可能有一条条目
     * —— 每个任期只有一个 leader，它在索引 i 上只写一条 —— 所以 (i, T) 唯一确定
     * 那条条目，它只可能是 P。而已提交的条目永不被覆盖，commit ≥ i ⇒ P 确实提交了。</p>
     *
     * <p>只有任期变化时才会走到这条路径，正常路径零开销。</p>
     */
    private boolean isProposalCommittedAnyway(PendingProposal p) {
        try {
            return rn.getRaft().getRaftLog().term(p.index) == p.term;
        } catch (RuntimeException e) {
            // 日志被压缩掉了或者读不到：查不到就不通知，走超时
            return false;
        }
    }

    /**
     * 异步提案失败时结束对应的 future。
     */
    private void failAsyncProposal(long requestId, Throwable t) {
        if (requestId == 0) {
            return;
        }
        PendingProposal p = pendingProposals.remove(requestId);
        if (p != null) {
            p.future.completeExceptionally(t);
        }
    }

    /**
     * 超时清扫线程：把到期的异步提案结束掉。
     *
     * <p>这条线程替代 {@link CompletableFuture#orTimeout}：后者在 JVM 全局的
     * 延迟队列上给每条提案挂一个定时任务，提案正常完成时还要把定时任务取回来，
     * 取的动作是线性扫描 —— 在途提案越多，扫得越久。</p>
     *
     * <p>这里改成定时扫一遍待完成表：扫的代价是 O(在途提案数)，
     * 但只在这条线程上发生，而且表里只有「还没被提交」的提案 ——
     * 提案一提交就被摘掉了，比全局延迟队列里堆积的定时任务少得多。</p>
     */
    private void runTimeoutSweeper() {
        while (!stopped.get()) {
            sweepTimeouts();
            LockSupport.parkNanos(this, PROPOSAL_TIMEOUT_SWEEP_NANOS);
        }
    }

    /**
     * 扫一遍待完成表，把到期的提案超时结束掉。
     */
    private void sweepTimeouts() {
        if (pendingProposals.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        for (Map.Entry<Long, PendingProposal> e : pendingProposals.entrySet()) {
            PendingProposal p = e.getValue();
            if (p.deadlineNanos == 0 || p.deadlineNanos > now) {
                continue;
            }
            // 只有还是这一条记录时才超时结束它：
            // 提案可能刚好在这期间被提交掉了
            if (pendingProposals.remove(e.getKey(), p)) {
                // 「提交即通知」的排队表里不用摘它：那条队列只由状态机线程碰。
                // 它会被弹出（弹出时发现 pendingProposals 里已经没有它，跳过），
                // 或者留到 leader 换人之后由任期校验丢掉。
                p.future.completeExceptionally(new TimeoutException("raft proposal timed out"));
            }
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
        for (PendingProposal p : pendingProposals.values()) {
            p.future.completeExceptionally(new RaftException("raft: stopped"));
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
     * 碎片批次合并：等待新提案到齐的时间上限（毫秒）。
     *
     * <p>leader 攒到一批零星条目就落盘时，一次 fsync（十几毫秒）只覆盖了
     * 几条条目 —— 吞吐被 fsync 次数而不是条目数卡住。等刚被通知的客户端
     * 把新提案发过来再一起落盘，一次 fsync 就能覆盖整批。</p>
     *
     * <p>这个值是兜底：新提案迟迟不来时不能一直等下去。</p>
     */
    private static final long DEFER_WAIT_MILLIS =
            Long.getLong("edap.raft.deferWaitMillis", 10L);

    /**
     * 碎片批次合并：刚提交（并已通知客户端）的条目数达到这个值才值得等。
     *
     * <p>提交量太小的时候等来的新提案也少，不值得为它多花一次等待。</p>
     */
    private static final int DEFER_MIN_COMMITTED =
            Integer.getInteger("edap.raft.deferMinCommitted", 2);

    /**
     * 碎片批次合并：一轮推迟最多等这么久（毫秒）。
     *
     * <p>等待窗口在批次还在增长时会不断往后延（这样才能等到整批），
     * 这个上限是防止「新提案一直来一点点」时无限期地等下去。</p>
     */
    private static final long DEFER_MAX_WAIT_MILLIS =
            Long.getLong("edap.raft.deferMaxWaitMillis", 50L);

    /**
     * 碎片批次合并：落盘线程的忙时占比低于这个值就不推迟。
     *
     * <p>判据是「瓶颈在不在 fsync 上」：落盘线程有大半时间闲着，
     * 说明系统在等网络而不是等磁盘，这时候合并 fsync 省不出吞吐，
     * 只会把这一轮一起推迟。</p>
     *
     * <p>取值依据（本机 3 节点真盘实测，16 客户端，窗口 1/16/256/1024）：
     * 合并有用的三个窗口忙时占比是 0.88 / 0.84 / 0.71，
     * 合并有害的深窗口是 0.36 —— 0.5 落在中间，两侧都留了余量。</p>
     */
    private static final double DEFER_MIN_BUSY_RATIO =
            Double.parseDouble(System.getProperty("edap.raft.deferMinBusyRatio", "0.5"));

    /**
     * 忙时占比的统计窗口（毫秒）。
     *
     * <p>窗口要够长才能把 fsync 的抖动平均掉：一次 fsync 十几毫秒，
     * 窗口太短的话测出来的比例会在阈值附近来回跳，
     * 深窗口里就会有一小部分轮次被误判成「fsync 是瓶颈」而白白推迟。</p>
     *
     * <p>实测（本机 3 节点真盘、16 客户端）：500ms 的窗口在窗口 1024 上
     * 还会残留 6.6% 的推迟，吞吐比完全不推迟低 7%；改成 1000ms 之后
     * 残留的推迟可以忽略，吞吐与不推迟持平，而窗口 256 的收益（+39%）不变。</p>
     */
    private static final long DEFER_BUSY_WINDOW_MILLIS =
            Long.getLong("edap.raft.deferBusyWindowMillis", 1000L);

    /**
     * 提交即通知：commit 指针一推进就通知客户端，不等下一次 Ready 组装。
     *
     * <p>默认关闭。开着的时候，异步提案的完成点从「下一次 Ready 组装」
     * 提前到「commit 指针推进」那一刻。</p>
     *
     * <p>为什么值得单独做：提交通知原本搭在 Ready 组装的顺风车上，
     * 而 Ready 的组装被「上一个 Ready 还在落盘 / 应用层还没 advance」卡住。
     * 实测（本机 3 节点真盘、16 客户端、窗口 1024）这一步要占
     * 「MSG_APP 发出 → 提交」的 34%（p50 15.5ms）——
     * 而协议路径本身（收到 ACK → 推进 commit）只要 0.03ms。</p>
     *
     * <p>安全性：commit 指针只会在「leader 自己的日志落盘完成」之后才推进
     * （leader 确认自己的日志要等落盘），所以提前通知不会让客户端
     * 看到比日志更靠前的状态。</p>
     *
     * <p>按实例读，不是按类读：这样单元测试可以在建节点之前打开开关，
     * 不受「Node 类什么时候被加载」的影响。</p>
     */
    private final boolean notifyOnCommit;

    /**
     * 等提案队列腾出位置时的单次等待时长。
     *
     * <p>只是兜底：正常情况下状态机线程排空队列后会 notifyAll，
     * 这里的超时是为了防止丢通知导致提案者永久挂起。</p>
     */
    private static final long PROPOSAL_SPACE_WAIT_MILLIS = 50;

    /**
     * 异步提案超时的清扫周期。
     *
     * <p>超时的精度就是这一个周期：提案到期后最多晚这么久才结束。</p>
     */
    private static final long PROPOSAL_TIMEOUT_SWEEP_NANOS =
            TimeUnit.MILLISECONDS.toNanos(100);

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
     * 状态机线程空闲时的单次等待时长。
     *
     * <p>带超时而不是无限期等待：万一有一次 unpark 没能送到，
     * 线程最迟在这个时长之后也会醒来重新看一眼 {@code stopped}。
     * 无限期 park 的线程一旦丢掉唤醒就再也醒不过来，
     * 停机时 {@code stop()} 会一直等它退出。</p>
     */
    private static final long WORKER_PARK_NANOS = TimeUnit.SECONDS.toNanos(1);

    /**
     * 停机时等待落盘线程退出的时长。落盘线程可能正卡在一次 fsync 上，
     * 所以给得比别个线程长。
     */
    private static final long PERSIST_JOIN_TIMEOUT_MILLIS = 5000;

    /**
     * 停机时等待 apply 线程退出的时长。
     */
    private static final long APPLY_JOIN_TIMEOUT_MILLIS = 2000;

    /**
     * 停机时等待超时清扫线程退出的时长。
     */
    private static final long TIMEOUT_JOIN_TIMEOUT_MILLIS = 1000;

    /**
     * 停机时等待状态机线程退出的时长。
     */
    private static final long WORKER_JOIN_TIMEOUT_MILLIS = 5000;

    /**
     * 打断线程之后再给它的收尾时间。
     */
    private static final long STOP_JOIN_RETRY_MILLIS = 1000;

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
     * 一条还在等提交的异步提案。
     *
     * <p>超时时刻放在这里，而不是用 {@link CompletableFuture#orTimeout}：
     * {@code orTimeout} 会往 JVM 全局共享的延迟队列里塞一个定时任务，
     * 提案正常完成时又要把那个任务取回来 —— 取的动作是<b>线性扫描</b>，
     * 队列里堆积的定时任务越多扫得越久。提案速率一高，
     * 每条提案都要付一次 O(在途提案数) 的扫描，整体退化成 O(n²)。</p>
     */
    private static final class PendingProposal {

        /** 提案完成后的 future */
        private final CompletableFuture<Void> future;

        /** 到期时刻（{@link System#nanoTime()} 基准），0 表示不限时 */
        private final long deadlineNanos;

        /** 提案的请求 id */
        private final long requestId;

        /**
         * 提案在 raft 日志里的索引，0 表示还没分配。
         *
         * <p>由状态机线程在提案被追加之后写入；超时清扫线程会读它，
         * 所以要可见。</p>
         */
        private volatile long index;

        /**
         * 提案被追加时的 raft 任期，0 表示还没分配。
         *
         * <p>「提交即通知」靠它确认「当初追加这条提案的 leader 还在」，
         * 否则日志索引可能被复用，通知到一条已经被覆盖掉的提案。</p>
         */
        private volatile long term;

        PendingProposal(CompletableFuture<Void> future, long deadlineNanos, long requestId) {
            this.future = future;
            this.deadlineNanos = deadlineNanos;
            this.requestId = requestId;
        }
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
     * 诊断数据：状态机线程的分段计时。
     *
     * <p>用来回答「一个持久化周期（Ready 组装 → 下一次 Ready 组装）里，
     * 时间分别花在哪」：是花在 fsync 上，还是花在「把 future 完成掉、
     * 把客户端唤醒」上。</p>
     *
     * <p>只有状态机线程写（{@link #onAssembly}、{@link #onNotify}、
     * {@link #onDrain}），读取走 {@link #diagSnapshot()} 的快照。</p>
     */
    static final class Diag {

        /** Ready 组装次数（含被推迟后重新组装的） */
        volatile long assemblies;

        /** 其中「碎片批次合并」推迟掉的次数 */
        volatile long deferrals;

        /** 真正交给落盘线程的批次数 */
        volatile long persistTasks;

        /** 落盘批的条目数之和 */
        volatile long persistEntriesSum;

        /** 「预期批大小」之和（推迟落盘的判据） */
        volatile long expectSum;

        /** 相邻两次落盘批之间的时间之和 */
        volatile long cycleNanosSum;

        /** 推迟落盘累计等待的时间 */
        volatile long deferWaitNanosSum;

        /** 提交通知（完成 future）花掉的时间之和 */
        volatile long notifyNanosSum;

        /** 通知出去的客户端数 */
        volatile long notifyCountSum;

        /** 真正通知到客户端的次数（每一轮算一次） */
        volatile long notifyEvents;

        /** 提案排空的次数 */
        volatile long drainCallsSum;

        /** 排空掉的提案条数 */
        volatile long drainEntriesSum;

        /** 真正写了盘（非空）的批次数 */
        volatile long realTasks;

        /** 空批（有任务但没条目可写）次数 */
        volatile long emptyTasks;

        /** 相邻两次「真落盘批」之间的时间之和 */
        volatile long realCycleNanosSum;

        /** 「这一批之前攒了多少次排空」之和 */
        volatile long drainsPerRealSum;

        /** 从最后一条提案到达、到这一批真落盘，中间隔的时间之和 */
        volatile long drainGapSum;

        /** 上面那个间隔的样本数 */
        volatile long drainGapCount;

        /** 主循环迭代次数 */
        volatile long loopIterations;

        /** 主循环处理的网络消息数 */
        volatile long netMessages;

        /** 状态机推进 commit 的次数 */
        volatile long commitAdvances;

        /** 排空提案时，队列里已有的条目数之和（排空前采样） */
        volatile long queueDepthSum;

        /** 排空提案时，距上一次客户端通知的时间之和 */
        volatile long sinceNotifySum;

        /**
         * 「碎片批次合并」每次做忙时占比判据时采到的占比之和（放大 1000 倍存整数）。
         *
         * <p>这个量回答的是「瓶颈在不在 fsync 上」——判据只写在
         * {@link #shouldDeferPersist} 里，而那个判据自己从没被量过。</p>
         */
        volatile long busyRatioMilliSum;

        /** 忙时占比的采样次数（占比测得出来的那几次） */
        volatile long busyRatioSamples;

        /** 判据时忙时占比 ≥ 阈值的次数（⇒ 允许推迟落盘） */
        volatile long busyAboveThreshold;

        /** 判据时忙时占比 < 阈值的次数（⇒ 不许推迟落盘） */
        volatile long busyBelowThreshold;

        /** 判据时忙时占比还测不出来的次数（第一个统计窗口没走完） */
        volatile long busyUnknown;

        /** 测量窗口起点时落盘线程的累计忙时（纳秒） */
        volatile long persistBusyNanosAtStart;

        /**
         * 测量窗口起点（纳秒）。所有「相对时刻」都以它为基准。
         */
        long windowStartNanos;

        /**
         * 最近一次判据读到的忙时占比（负数 = 还测不出来）。
         *
         * <p>用来给「推迟」分类：判据读不出占比时（第一个统计窗口没走完）
         * 那个判据是不拦人的，推迟照样会发生 —— 这两种推迟必须分开数。</p>
         */
        double lastBusyAtDecision = -1.0;

        /** 推迟时「忙时占比还测不出来」的次数 */
        volatile long deferralsUnknownBusy;

        /** 推迟时「忙时占比已测出且过阈值」的次数 */
        volatile long deferralsMeasuredBusy;

        /** 第一次做忙时占比判据的时刻（相对测量窗口起点，毫秒；0 = 没有） */
        volatile long firstBusyDecisionOffsetMillis;

        /** 忙时占比第一次测得出来的时刻（相对测量窗口起点，毫秒；0 = 没有） */
        volatile long firstValidBusyOffsetMillis;

        /** 第一次推迟落盘的时刻（相对测量窗口起点，毫秒；0 = 没有） */
        volatile long firstDeferralOffsetMillis;

        /** 最后一次推迟落盘的时刻（相对测量窗口起点，毫秒；0 = 没有） */
        volatile long lastDeferralOffsetMillis;

        /** 上一次客户端通知的时刻 */
        long lastNotifyNanos;

        /** 周期长度的样本（取中位数用） */
        long[] cycleSamples = new long[4096];

        /** 周期长度的样本个数（先写样本再写这个，读的人先读这个才安全） */
        volatile int cycleSampleCount;

        /** 真落盘周期的样本 */
        long[] realCycleSamples = new long[4096];

        /** 真落盘周期的样本个数 */
        volatile int realCycleSampleCount;

        /** 上一次落盘批的时刻 */
        long lastPersistNanos;

        /** 上一次「真落盘批」的时刻 */
        long lastRealPersistNanos;

        /** 上一次排空提案的时刻 */
        long lastDrainNanos;

        /** 这一轮推迟落盘开始的时刻，0 表示没推迟过 */
        long deferralStartNanos;

        /** 距离上一次真落盘，期间攒下的排空次数 */
        long drainsSinceReal;

        /**
         * 组装了一次 Ready。
         */
        void onAssembly() {
            assemblies++;
        }

        /**
         * 这一轮被「碎片批次合并」推迟了。
         */
        void onDeferral(long now) {
            deferrals++;
            if (deferralStartNanos == 0) {
                deferralStartNanos = now;
            }
            if (lastBusyAtDecision < 0) {
                deferralsUnknownBusy++;
            } else {
                deferralsMeasuredBusy++;
            }
            long off = now - windowStartNanos;
            if (firstDeferralOffsetMillis == 0) {
                firstDeferralOffsetMillis = TimeUnit.NANOSECONDS.toMillis(off);
            }
            lastDeferralOffsetMillis = TimeUnit.NANOSECONDS.toMillis(off);
        }

        /**
         * 真正落盘了一批。
         *
         * @param now             这一刻
         * @param entries        这一批的条目数
         * @param expect         预期批大小（判据）
         * @param deferStarted   这一轮推迟开始的时刻，0 表示没推迟过
         */
        void onPersist(long now, int entries, int expect, long deferStarted) {
            persistTasks++;
            persistEntriesSum += entries;
            expectSum += expect;
            if (deferStarted != 0) {
                deferWaitNanosSum += now - deferStarted;
            }
            deferralStartNanos = 0;
            if (entries > 0) {
                // 真正写了盘的一批
                realTasks++;
                if (lastRealPersistNanos != 0) {
                    long cycle = now - lastRealPersistNanos;
                    realCycleNanosSum += cycle;
                    if (realCycleSampleCount < realCycleSamples.length) {
                        realCycleSamples[realCycleSampleCount] = cycle;
                        realCycleSampleCount = realCycleSampleCount + 1;
                    }
                }
                lastRealPersistNanos = now;
                // 这一批之前攒了多少次排空
                drainsPerRealSum += drainsSinceReal;
                drainsSinceReal = 0;
                // 从最后一条提案到达，到这一批真的落盘，中间隔了多久
                if (lastDrainNanos != 0) {
                    drainGapSum += now - lastDrainNanos;
                    drainGapCount++;
                }
            } else {
                emptyTasks++;
            }
            if (lastPersistNanos != 0) {
                long cycle = now - lastPersistNanos;
                cycleNanosSum += cycle;
                if (cycleSampleCount < cycleSamples.length) {
                    cycleSamples[cycleSampleCount] = cycle;
                    cycleSampleCount = cycleSampleCount + 1;
                }
            }
            lastPersistNanos = now;
        }

        /**
         * 完成了一批提交通知。
         */
        void onNotify(long nanos, int count) {
            notifyNanosSum += nanos;
            notifyCountSum += count;
            if (count > 0) {
                // 只有真的通知到了客户端才算一次「通知时刻」：
                // count = 0 的调用（这一轮没有新提交）不能刷新这个时刻，
                // 否则「排空距上次通知」会量到「距上次 Ready 组装」。
                lastNotifyNanos = System.nanoTime();
                notifyEvents++;
            }
        }

        /**
         * 排空了一批提案。
         *
         * @param entries      这一批取出来的条数
         * @param queueDepth   排空前队列里已有的条数
         * @param now          这一刻
         */
        void onDrain(int entries, int queueDepth, long now) {
            drainCallsSum++;
            drainEntriesSum += entries;
            queueDepthSum += queueDepth;
            if (lastNotifyNanos != 0) {
                sinceNotifySum += now - lastNotifyNanos;
            }
            lastDrainNanos = now;
            drainsSinceReal++;
        }

        /**
         * 主循环转了一圈。
         */
        void onLoop() {
            loopIterations++;
        }

        /**
         * 记录一次「碎片批次合并」的忙时占比判据。
         *
         * @param busy 落盘线程的忙时占比；负数表示第一个统计窗口没走完、还测不出来
         */
        void onBusyDecision(double busy) {
            long now = System.nanoTime();
            lastBusyAtDecision = busy;
            if (firstBusyDecisionOffsetMillis == 0) {
                firstBusyDecisionOffsetMillis = TimeUnit.NANOSECONDS.toMillis(now - windowStartNanos);
            }
            if (busy < 0) {
                busyUnknown++;
                return;
            }
            if (firstValidBusyOffsetMillis == 0) {
                firstValidBusyOffsetMillis = TimeUnit.NANOSECONDS.toMillis(now - windowStartNanos);
            }
            busyRatioSamples++;
            busyRatioMilliSum += Math.round(busy * 1000.0);
            if (busy >= DEFER_MIN_BUSY_RATIO) {
                busyAboveThreshold++;
            } else {
                busyBelowThreshold++;
            }
        }

        /**
         * 清零（测量窗口开始时调用）。
         */
        void reset() {
            windowStartNanos = System.nanoTime();
            assemblies = 0;
            deferrals = 0;
            persistTasks = 0;
            persistEntriesSum = 0;
            expectSum = 0;
            cycleNanosSum = 0;
            deferWaitNanosSum = 0;
            notifyNanosSum = 0;
            notifyCountSum = 0;
            notifyEvents = 0;
            drainCallsSum = 0;
            drainEntriesSum = 0;
            loopIterations = 0;
            netMessages = 0;
            commitAdvances = 0;
            queueDepthSum = 0;
            sinceNotifySum = 0;
            busyRatioMilliSum = 0;
            busyRatioSamples = 0;
            busyAboveThreshold = 0;
            busyBelowThreshold = 0;
            busyUnknown = 0;
            deferralsUnknownBusy = 0;
            deferralsMeasuredBusy = 0;
            lastBusyAtDecision = -1.0;
            firstBusyDecisionOffsetMillis = 0;
            firstValidBusyOffsetMillis = 0;
            firstDeferralOffsetMillis = 0;
            lastDeferralOffsetMillis = 0;
            lastNotifyNanos = 0;
            realTasks = 0;
            emptyTasks = 0;
            realCycleNanosSum = 0;
            drainsPerRealSum = 0;
            drainGapSum = 0;
            drainGapCount = 0;
            cycleSampleCount = 0;
            realCycleSampleCount = 0;
            lastPersistNanos = 0;
            lastRealPersistNanos = 0;
            lastDrainNanos = 0;
            deferralStartNanos = 0;
            drainsSinceReal = 0;
        }
    }

    /**
     * 诊断：清零分段计时（测量窗口开始时调用）。
     */
    void diagReset() {
        diag.reset();
        diag.persistBusyNanosAtStart = persistBusyNanos.get();
    }

    /**
     * 诊断：落盘线程累计的忙时（纳秒）。
     *
     * <p>用来算测量窗口内「落盘线程有多少比例的时间在真的写 + fsync」——
     * 这是「瓶颈在不在 fsync 上」最直接的答案（不经过任何判据）。</p>
     */
    long diagPersistBusyNanos() {
        return persistBusyNanos.get();
    }

    /**
     * 诊断数据的快照。
     *
     * <p>只在 {@code edap.raft.diag=true} 时有意义。</p>
     */
    synchronized Diag diagSnapshot() {
        return diag;
    }

    /**
     * 状态机线程的 id：诊断用（量这个线程的 CPU 占用率）。
     */
    long workerThreadId() {
        return worker.getId();
    }

    /**
     * 落盘线程的 id：诊断用。没有落盘线程时返回 -1。
     */
    long persistThreadId() {
        return persistThread == null ? -1L : persistThread.getId();
    }

    /**
     * apply 线程的 id：诊断用。没有 apply 线程时返回 -1。
     */
    long applyThreadId() {
        return applyThread == null ? -1L : applyThread.getId();
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
