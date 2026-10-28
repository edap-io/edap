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

import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.Message;
import io.edap.raft.protocol.MessageType;
import io.edap.raft.storage.MemoryStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多个客户端并发提案时的行为。
 *
 * <p>etcd 的 {@code node.run()} 用 Go 的 {@code select} 收消息，
 * Go 的 select 在多个 case 同时就绪时是<b>随机</b>挑一个的，所以
 * 提案、tick、网络消息之间是公平的。</p>
 *
 * <p>Java 侧的主循环把它翻译成了「固定优先级轮询」：
 * 每轮依次 poll {@code propc → tickc → confc → statusc → advancec → recvc}，
 * 命中一条就 {@code continue} 重来。优先级是固定的，
 * 只要 {@code propc} 一直非空，{@code recvc} 就永远轮不到。</p>
 */
@Timeout(180)
public class NodeProposalFloodTest {

    /** 客户端提案的负载长度：4 字节客户端 id + 8 字节序号 */
    private static final int PAYLOAD_LEN = 12;

    /**
     * 在提案上故意停顿的 raft 状态机，用来把 {@code propc} 稳定地灌满。
     */
    static class SlowRaft extends Raft {

        /** 处理过的提案数 */
        final AtomicInteger propSteps = new AtomicInteger();

        /** 处理过的、来自其它节点的网络消息数 */
        final AtomicInteger netSteps = new AtomicInteger();

        /** 每条提案在状态机里停留多久 */
        volatile long propDelayMillis;

        SlowRaft(RaftConfig cfg) {
            super(cfg);
        }

        @Override
        public boolean step(Message m) {
            if (m.getType() == MessageType.MSG_PROP) {
                propSteps.incrementAndGet();
                sleep(propDelayMillis);
            } else {
                long from = m.getFrom();
                // 只有「来自其它节点」的消息才算网络消息。
                // 落盘确认、投票确认这些内部消息的 from 是本节点或负数，
                // MSG_HUP 的 from 是 0，都不算。
                if (from > 0 && from != getId()) {
                    netSteps.incrementAndGet();
                }
            }
            return super.step(m);
        }

        private static void sleep(long millis) {
            if (millis <= 0) {
                return;
            }
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 一个跑起来的单节点 leader，以及驱动它的应用层。
     */
    private static final class Leader {

        /** raft 节点 */
        final Node node;

        /** 已落盘的日志存储 */
        final MemoryStorage storage;

        /** 底层状态机 */
        final SlowRaft raft;

        /** 应用层是否停止 */
        final AtomicBoolean appStop = new AtomicBoolean();

        /** 落盘次数（每次 Ready 里带日志就算一次） */
        final AtomicInteger fsyncs = new AtomicInteger();

        /** 落盘的日志条数 */
        final AtomicLong entries = new AtomicLong();

        /** 每个客户端已提交的提案数 */
        final AtomicLong[] committedByClient;

        /**
         * 每个客户端一把锁：应用层提交后 notify，客户端立刻醒来看结果。
         *
         * <p>不用轮询是因为轮询的粒度会直接变成测量出来的吞吐上限。</p>
         */
        final Object[] commitLocks;

        Leader(int clients) {
            this.committedByClient = new AtomicLong[clients];
            this.commitLocks = new Object[clients];
            for (int i = 0; i < clients; i++) {
                this.committedByClient[i] = new AtomicLong();
                this.commitLocks[i] = new Object();
            }
            MemoryStorage storage = RaftTestSupport.newTestMemoryStorage(1L);
            RaftConfig cfg = RaftTestSupport.newTestConfig(1, 10, 1, storage);
            this.storage = storage;
            this.raft = new SlowRaft(cfg);
            this.node = new Node(new RawNode(raft));
        }

        /**
         * 等待自己的下一条提案被提交。返回 false 表示节点已停止。
         */
        boolean awaitCommit(int cid, long seq, AtomicBoolean stop) {
            synchronized (commitLocks[cid]) {
                while (!stop.get() && committedByClient[cid].get() < seq) {
                    try {
                        commitLocks[cid].wait(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
            return true;
        }
    }

    /**
     * 应用层：取 Ready → 落盘 → 模拟 fsync 耗时 → 应用已提交条目 → advance。
     *
     * <p>没有这一层，候选者给自己的投票（MSG_VOTE_RESP，走 msgsAfterAppend）
     * 永远不会被释放，节点也就永远选不出主。</p>
     *
     * @param fsyncMillis 模拟一次落盘的耗时
     */
    private static void startAppLoop(Leader h, long fsyncMillis) {
        Thread app = new Thread(() -> {
            while (!h.appStop.get()) {
                Ready rd;
                try {
                    rd = h.node.ready(20, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (rd == null) {
                    continue;
                }
                if (!rd.getEntries().isEmpty()) {
                    h.fsyncs.incrementAndGet();
                    h.entries.addAndGet(rd.getEntries().size());
                    // 落盘：日志必须真的写进存储，否则 raft 会认为日志不存在
                    h.storage.append(rd.getEntries());
                    if (fsyncMillis > 0) {
                        try {
                            Thread.sleep(fsyncMillis);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
                // 应用已提交的条目：客户端就是在这里看到自己的提案生效了
                boolean[] touched = null;
                for (Entry e : rd.getCommittedEntries()) {
                    byte[] data = e.getData();
                    if (data != null && data.length == PAYLOAD_LEN) {
                        int cid = ByteBuffer.wrap(data).getInt();
                        if (cid >= 0 && cid < h.committedByClient.length) {
                            if (touched == null) {
                                touched = new boolean[h.committedByClient.length];
                            }
                            touched[cid] = true;
                            h.committedByClient[cid].incrementAndGet();
                        }
                    }
                }
                // 通知等待中的客户端：一批只 notify 一次
                if (touched != null) {
                    for (int cid = 0; cid < touched.length; cid++) {
                        if (touched[cid]) {
                            synchronized (h.commitLocks[cid]) {
                                h.commitLocks[cid].notifyAll();
                            }
                        }
                    }
                }
                h.node.advance();
            }
        }, "raft-app");
        app.setDaemon(true);
        app.start();
    }

    /**
     * 造一个已经成为 leader 的单节点 {@link Node}。
     */
    private static Leader startLeader(int clients, long fsyncMillis) throws Exception {
        Leader h = new Leader(clients);
        startAppLoop(h, fsyncMillis);
        // 单节点集群，自己给自己投票就能当选
        h.node.campaign();
        long deadline = System.currentTimeMillis() + 5000;
        while (h.raft.getLead() == Raft.NONE && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertNotEquals(Raft.NONE, h.raft.getLead(), "node should have become leader");
        return h;
    }

    /**
     * 提案源源不断时，入站的网络消息不再被饿死。
     *
     * <p>etcd 用 Go 的 {@code select}（随机选就绪的 case）本来就不会这样；
     * Java 侧原来的固定优先级轮询会这样。现在主循环一轮里把每个来源
     * 都轮到一次，提案排空受时间预算约束，网络消息最多等一个预算。</p>
     */
    @Test
    public void proposalFloodDoesNotStarveInboundNetworkMessages() throws Exception {
        Leader h = startLeader(1, 0);
        // 每条提案在状态机里停 10ms，保证 propc 一直非空
        h.raft.propDelayMillis = 10;

        try {
            // 6 个客户端线程持续提案
            AtomicBoolean stop = new AtomicBoolean();
            CountDownLatch producers = startFlood(h, stop, 6);

            // 等提案流起来
            long deadline = System.currentTimeMillis() + 5000;
            while (h.raft.propSteps.get() < 3 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(h.raft.propSteps.get() >= 3, "proposals should be flowing");

            // 注入一条入站网络消息
            int before = h.raft.netSteps.get();
            h.node.onMessage(new Message().setType(MessageType.MSG_HEARTBEAT)
                    .setFrom(2L).setTo(1L).setTerm(h.raft.getTerm()));

            // 提案还在源源不断，网络消息也应该被处理（最多等一个排空预算）
            deadline = System.currentTimeMillis() + 2000;
            while (h.raft.netSteps.get() == before && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
            assertTrue(h.raft.netSteps.get() > before,
                    "inbound network message must not be starved while proposals keep flowing");

            stop.set(true);
            producers.await(10, TimeUnit.SECONDS);
        } finally {
            h.appStop.set(true);
            h.node.stop();
        }
    }

    /**
     * 起 6 个客户端线程持续提案。
     *
     * @return 计数器，客户端线程退出时减到 0
     */
    private static CountDownLatch startFlood(Leader h, AtomicBoolean stop, int clients) {
        CountDownLatch producers = new CountDownLatch(clients);
        for (int i = 0; i < clients; i++) {
            final int cid = i;
            Thread t = new Thread(() -> {
                try {
                    long seq = 0;
                    while (!stop.get()) {
                        seq++;
                        try {
                            h.node.propose(payload(cid, seq));
                        } catch (RaftException e) {
                            return;
                        }
                    }
                } finally {
                    producers.countDown();
                }
            }, "flood-producer-" + i);
            t.setDaemon(true);
            t.start();
        }
        return producers;
    }

    /**
     * 提案持续涌入时，Ready 推进不再被饿死，批量照样能落盘、能提交。
     *
     * <p>修复前：{@code advancec} 排在 {@code propc} 后面，只要提案队列一直非空，
     * 状态机线程就永远轮不到 {@code advancec}，应用层的 {@code advance()}
     * 信号堆在那里不起作用。Ready 合不拢 → 一次落盘都不会发生 →
     * raft 什么也不提交。</p>
     *
     * <p>修复后：主循环一轮里把每个来源都轮到一次，{@code advancec}
     * 和 Ready 的交付不再排在提案后面。</p>
     */
    @Test
    public void sustainedProposalLoadDoesNotStarveAdvance() throws Exception {
        Leader h = startLeader(1, 0);
        h.raft.propDelayMillis = 10;

        try {
            AtomicBoolean stop = new AtomicBoolean();
            CountDownLatch producers = startFlood(h, stop, 6);

            // 等提案流起来
            long deadline = System.currentTimeMillis() + 5000;
            while (h.raft.propSteps.get() < 3 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(h.raft.propSteps.get() >= 3, "proposals should be flowing");

            int fsyncsBefore = h.fsyncs.get();
            long committedBefore = h.committedByClient[0].get();

            Thread.sleep(1000);

            System.out.printf("flood: propSteps=%d  fsyncs=%d  committed=%d%n",
                    h.raft.propSteps.get(), h.fsyncs.get(), h.committedByClient[0].get());

            assertTrue(h.raft.propSteps.get() > 10, "proposals should keep flowing");
            assertTrue(h.fsyncs.get() > fsyncsBefore,
                    "batches should still reach the application while proposals keep flowing");
            assertTrue(h.committedByClient[0].get() > committedBefore,
                    "proposals should still be committed while proposals keep flowing");

            stop.set(true);
            producers.await(10, TimeUnit.SECONDS);
        } finally {
            h.appStop.set(true);
            h.node.stop();
        }
    }

    /**
     * 客户端在 {@code propose()} 里被卡住的时间分布，以及等提交的时间分布。
     */
    private static final class BlockStats {

        long samples;
        long p50;
        long p99;
        long max;
        long commitP50;
        long commitP99;

        @Override
        public String toString() {
            return String.format(
                    "propose()阻塞 p50=%.1fus p99=%.1fus max=%.1fus | 等提交 p50=%.1fus p99=%.1fus | 样本=%d",
                    p50 / 1000.0, p99 / 1000.0, max / 1000.0,
                    commitP50 / 1000.0, commitP99 / 1000.0, samples);
        }
    }

    /**
     * 客户端在 {@code propose()} 里被卡多久。
     *
     * <p>{@code propose()} 阻塞到状态机 step 完这条提案（不等提交）。
     * 这段时间由两部分组成：</p>
     * <ol>
     *   <li><b>publish</b>：{@code enqueueProposal} 里的
     *       {@code synchronized(lifecycle) + propc.add() + wakeup.release()}；</li>
     *   <li><b>等状态机</b>：主循环轮到 propc 并把提案 step 掉。</li>
     * </ol>
     * <p>如果第 1 部分占比很小，那么「换一个更快的队列」对客户端体验没有任何意义。</p>
     */
    @Test
    public void proposeBlockTimeIsDominatedByWaitingForTheStateMachine() throws Exception {
        BlockStats one = measureProposeBlock(1, 0, 2000);
        BlockStats sixteen = measureProposeBlock(16, 0, 2000);
        BlockStats sixteenFsync = measureProposeBlock(16, 2, 2000);

        System.out.println("1 client,  no fsync : " + one);
        System.out.println("16 clients,no fsync : " + sixteen);
        System.out.println("16 clients,fsync 2ms: " + sixteenFsync);

        // publish 本身（Node.enqueueProposal 的三步）在 MPSC 下实测 p50 约 2.3us
        // （见 benchmark/PublishPathCostHarness）。
        //
        // 单客户端 + 纯内存时，客户端的端到端延迟只有几微秒，publish 占到三成 ——
        // 这是唯一 publish 占比显著的场景。但只要有 fsync、或者客户端一多，
        // 客户端等的就是「提案被提交」，publish 立刻变成噪声。
        assertTrue(sixteenFsync.commitP50 > 1_000_000L,
                "with fsync the commit wait is dominated by the disk: " + sixteenFsync);
        assertTrue(sixteenFsync.commitP50 > sixteenFsync.p50 * 50,
                "publish should be noise compared to the commit wait: " + sixteenFsync);
    }

    /**
     * 测客户端在 {@code propose()} 里被卡的时长。
     */
    private static BlockStats measureProposeBlock(int clients, long fsyncMillis, int millis)
            throws Exception {
        Leader h = startLeader(clients, fsyncMillis);

        final long[] propose = new long[200_000];
        final long[] commit = new long[200_000];
        final int[] n = {0};
        final int[] nc = {0};
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(clients);

        for (int i = 0; i < clients; i++) {
            final int cid = i;
            Thread t = new Thread(() -> {
                try {
                    long seq = 0;
                    while (!stop.get()) {
                        seq++;
                        long t0 = System.nanoTime();
                        h.node.propose(payload(cid, seq));
                        long t1 = System.nanoTime();
                        int k = n[0];
                        if (k < propose.length) {
                            propose[k] = t1 - t0;
                            n[0] = k + 1;
                        }
                        // 等自己的提案被提交
                        long t2 = System.nanoTime();
                        if (!h.awaitCommit(cid, seq, stop)) {
                            return;
                        }
                        long t3 = System.nanoTime();
                        int kc = nc[0];
                        if (kc < commit.length) {
                            commit[kc] = t3 - t2;
                            nc[0] = kc + 1;
                        }
                    }
                } catch (RaftException e) {
                    return;
                } finally {
                    done.countDown();
                }
            }, "prop-block-" + i);
            t.setDaemon(true);
            t.start();
        }

        try {
            Thread.sleep(millis);
            stop.set(true);
            done.await(10, TimeUnit.SECONDS);
        } finally {
            h.appStop.set(true);
            h.node.stop();
        }

        BlockStats s = new BlockStats();
        s.samples = n[0];
        long[] p = Arrays.copyOf(propose, n[0]);
        Arrays.sort(p);
        s.p50 = pct(p, 50);
        s.p99 = pct(p, 99);
        s.max = p.length == 0 ? 0 : p[p.length - 1];
        long[] c = Arrays.copyOf(commit, nc[0]);
        Arrays.sort(c);
        s.commitP50 = pct(c, 50);
        s.commitP99 = pct(c, 99);
        return s;
    }

    /** 取分位数（输入已排序） */
    private static long pct(long[] sorted, double p) {
        if (sorted.length == 0) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= sorted.length) {
            idx = sorted.length - 1;
        }
        return sorted[idx];
    }

    /** 一次压测的统计 */
    private static final class Stats {

        /** 已提交的客户端提案数 */
        long proposals;

        /** 落盘次数 */
        long fsyncs;

        /** 落盘的日志条数 */
        long entries;

        @Override
        public String toString() {
            return String.format("proposals=%d fsyncs=%d entries=%d  %.2f fsync/提案  %.1f 条/次落盘",
                    proposals, fsyncs, entries,
                    proposals == 0 ? 0 : (double) fsyncs / proposals,
                    fsyncs == 0 ? 0 : (double) entries / fsyncs);
        }
    }

    /**
     * 模拟一次落盘耗时，测不同并发度下的落盘次数。
     *
     * <p>用来展示并发写提案的真正收益：多个提案会被合并成一个 Ready，
     * 一次落盘 —— 也就是 group commit。</p>
     *
     * <p>客户端是「提完等提交」的模型：提案被状态机接收后，要等它出现在
     * 应用层的已提交条目里，才提下一条。这跟真实的 raft 客户端一致。</p>
     */
    private static Stats measure(int clients, long fsyncMillis, int millis) throws Exception {
        Leader h = startLeader(clients, fsyncMillis);

        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch done = new CountDownLatch(clients);
        for (int i = 0; i < clients; i++) {
            final int cid = i;
            Thread t = new Thread(() -> {
                try {
                    long seq = 0;
                    while (!stop.get()) {
                        seq++;
                        try {
                            h.node.propose(payload(cid, seq));
                        } catch (RaftException e) {
                            return;
                        }
                        // 等自己的提案被提交
                        if (!h.awaitCommit(cid, seq, stop)) {
                            return;
                        }
                    }
                } finally {
                    done.countDown();
                }
            }, "prop-client-" + i);
            t.setDaemon(true);
            t.start();
        }

        try {
            Thread.sleep(millis);
            stop.set(true);
            done.await(10, TimeUnit.SECONDS);

            Stats s = new Stats();
            for (AtomicLong c : h.committedByClient) {
                s.proposals += c.get();
            }
            s.fsyncs = h.fsyncs.get();
            s.entries = h.entries.get();
            return s;
        } finally {
            h.appStop.set(true);
            h.node.stop();
        }
    }

    private static byte[] payload(int clientId, long seq) {
        ByteBuffer buf = ByteBuffer.allocate(PAYLOAD_LEN);
        buf.putInt(clientId);
        buf.putLong(seq);
        return buf.array();
    }

    @Test
    public void concurrentProposalsAreGroupCommitted() throws Exception {
        long fsync = 2;
        Stats one = measure(1, fsync, 2000);
        Stats sixteen = measure(16, fsync, 2000);
        // 不落盘（fsync=0）时的天花板：这时瓶颈完全在 raft 状态机与驱动链路上
        Stats noFsync = measure(16, 0, 2000);

        System.out.println("fsync=" + fsync + "ms");
        System.out.println("  1 client  : " + one);
        System.out.println("  16 clients: " + sixteen);
        System.out.println("  16 clients, no fsync: " + noFsync
                + "  (" + (noFsync.proposals / 2) + " proposals/s)");

        // 客户端数扫描：吞吐不随客户端数增长，说明瓶颈是「每提案两次线程交接」，
        // 而不是队列争用。
        for (int clients : new int[]{1, 2, 4, 8, 32, 64}) {
            Stats s = measure(clients, 0, 2000);
            System.out.printf("  no fsync, %2d clients: %8.0f proposals/s  (%.1f 条/次落盘)%n",
                    clients, s.proposals / 2.0, s.fsyncs == 0 ? 0 : (double) s.entries / s.fsyncs);
        }

        // 单客户端：一次落盘一个提案，落盘次数约等于提案数
        assertTrue(one.fsyncs >= one.proposals * 0.8,
                "single client should need one fsync per proposal, got " + one);
        // 多客户端：提案被合并成批，一次落盘覆盖多个提案。
        // 比较的是「每个提案摊到几次落盘」，而不是落盘总次数：
        // 并发度高时提案总数本身也会涨，比绝对值没有意义。
        double oneRatio = (double) one.fsyncs / one.proposals;
        double sixteenRatio = (double) sixteen.fsyncs / sixteen.proposals;
        assertTrue(sixteenRatio * 3 < oneRatio,
                "concurrent clients should amortize fsync via batching, got "
                        + sixteen + " vs " + one);
        // 一次落盘平均覆盖多条日志
        assertTrue((double) sixteen.entries / sixteen.fsyncs > 3,
                "a single fsync should cover several proposals, got " + sixteen);
    }
}
