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
import io.edap.raft.protocol.EntryType;
import io.edap.raft.protocol.Message;
import io.edap.raft.protocol.MessageType;
import io.edap.raft.storage.MemoryStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 提案队列的背压行为。
 *
 * <p>队列有界之后，队列满时的语义分三种：</p>
 * <ul>
 *     <li>同步提案（{@link Node#propose(byte[])}）：按
 *         {@link RaftConfig#proposalFullPolicy} 阻塞等待或者拒绝</li>
 *     <li>异步提案（{@code proposeAsync}）：永远拒绝</li>
 *     <li>传输层回调（{@link Node#onMessage(Message)}）：永远丢弃并计数</li>
 * </ul>
 *
 * <p>最容易写错的地方是「等队列腾位置」时持着 {@code lifecycle} 锁：
 * {@link Node#stop()} 要拿同一把锁才能停下来，持锁等待就是死锁。
 * {@link #stopDoesNotDeadlockWhenProposerWaitsForQueueSpace()} 是这里的守门人。</p>
 */
@Timeout(120)
public class NodeBackpressureTest {

    /** 提案队列容量：故意取得很小，方便把队列灌满 */
    private static final int CAP = 4;

    /**
     * 在提案上装一道闸门：用来把提案队列稳定地灌满。
     *
     * <p>闸门关着的时候状态机线程卡在 {@code step} 里，
     * 提案队列就只会进不会出。</p>
     */
    static class GatedRaft extends Raft {

        /** 状态机线程进入提案处理时减到 0 */
        final CountDownLatch entered = new CountDownLatch(1);

        /** 闸门 */
        final AtomicBoolean gateOpen = new AtomicBoolean();

        GatedRaft(RaftConfig cfg) {
            super(cfg);
        }

        @Override
        public boolean step(Message m) {
            if (!gateOpen.get() && m.getType() == MessageType.MSG_PROP) {
                entered.countDown();
                while (!gateOpen.get()) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            return super.step(m);
        }

        void openGate() {
            gateOpen.set(true);
        }
    }

    /**
     * 一个跑起来的单节点 leader，以及驱动它的应用层。
     */
    private static final class Fixture {

        final Node node;
        final GatedRaft raft;
        final MemoryStorage storage;
        final AtomicBoolean appStop = new AtomicBoolean();

        /** 应用层看到的已提交条目数 */
        final AtomicLong committed = new AtomicLong();

        Fixture(ProposalFullPolicy policy, int cap) throws Exception {
            MemoryStorage storage = RaftTestSupport.newTestMemoryStorage(1L);
            RaftConfig cfg = RaftTestSupport.newTestConfig(1, 10, 1, storage);
            cfg.maxPendingProposals = cap;
            cfg.proposalFullPolicy = policy;
            this.storage = storage;
            this.raft = new GatedRaft(cfg);
            this.node = new Node(new RawNode(raft));
            startAppLoop();
            // 单节点集群，自己给自己投票就能当选
            node.campaign();
            long deadline = System.currentTimeMillis() + 5000;
            while (raft.getLead() == Raft.NONE && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertNotEquals(Raft.NONE, raft.getLead(), "node should have become leader");
        }

        /**
         * 应用层：取 Ready → 落盘 → 应用已提交条目 → advance。
         *
         * <p>没有这一层，候选者给自己的投票永远不会被释放，选不出主。</p>
         */
        private void startAppLoop() {
            Thread app = new Thread(() -> {
                while (!appStop.get()) {
                    Ready rd;
                    try {
                        rd = node.ready(20, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (rd == null) {
                        continue;
                    }
                    if (!rd.getEntries().isEmpty()) {
                        storage.append(rd.getEntries());
                    }
                    committed.addAndGet(rd.getCommittedEntries().size());
                    node.advance();
                }
            }, "raft-app");
            app.setDaemon(true);
            app.start();
        }

        /**
         * 把提案队列灌满。
         *
         * <p>先塞一条让状态机线程取走并卡在闸门上（队列因此腾空），
         * 再塞满整个队列容量。</p>
         */
        void fillQueue() throws Exception {
            node.onMessage(propMsg());
            assertTrue(raft.entered.await(5, TimeUnit.SECONDS),
                    "state machine thread should have picked up the first proposal");
            for (int i = 0; i < CAP; i++) {
                node.onMessage(propMsg());
            }
        }

        void close() {
            appStop.set(true);
            raft.openGate();
            node.stop();
        }
    }

    private static Message propMsg() {
        return new Message().setType(MessageType.MSG_PROP)
                .setFrom(1L)
                .setEntries(Collections.singletonList(
                        new Entry(0, 0, EntryType.NORMAL, new byte[]{1})));
    }

    /**
     * 同步提案 + BLOCK 策略：队列满时阻塞等待，提案不丢。
     */
    @Test
    public void blockingPolicyWaitsForQueueSpaceInsteadOfFailing() throws Exception {
        Fixture f = new Fixture(ProposalFullPolicy.BLOCK, CAP);
        try {
            f.fillQueue();

            AtomicReference<Throwable> err = new AtomicReference<>();
            AtomicBoolean returned = new AtomicBoolean();
            Thread t = new Thread(() -> {
                try {
                    f.node.propose(new byte[]{1});
                    returned.set(true);
                } catch (Throwable e) {
                    err.set(e);
                }
            }, "blocked-proposer");
            t.start();

            // 等一会儿：它应该还卡着，而且没有异常
            Thread.sleep(300);
            assertNull(err.get(), "BLOCK policy must not fail the proposal, got " + err.get());
            assertFalse(returned.get(), "proposal should still be waiting for queue space");

            // 放闸门：队列腾出来，提案被状态机接收
            f.raft.openGate();
            t.join(10000);
            assertNull(err.get(), "proposal should succeed once queue space is available");
            assertTrue(returned.get(), "proposal should return normally");
        } finally {
            f.close();
        }
    }

    /**
     * 同步提案 + REJECT 策略：队列满时立刻拒绝，抛 {@link RaftBusyException}。
     */
    @Test
    public void rejectPolicyFailsFastWhenQueueIsFull() throws Exception {
        Fixture f = new Fixture(ProposalFullPolicy.REJECT, CAP);
        try {
            f.fillQueue();
            RaftBusyException e = assertThrows(RaftBusyException.class,
                    () -> f.node.propose(new byte[]{1}));
            assertEquals("raft proposal queue is full", e.getMessage());
        } finally {
            f.close();
        }
    }

    /**
     * 传输层回调路径：队列满时直接丢弃，不阻塞 IO 线程。
     */
    @Test
    public void onMessageDropsInsteadOfBlockingWhenQueueIsFull() throws Exception {
        Fixture f = new Fixture(ProposalFullPolicy.BLOCK, CAP);
        try {
            f.fillQueue();
            long droppedBefore = f.node.getDroppedProposals();

            long t0 = System.nanoTime();
            f.node.onMessage(propMsg());
            long elapsedMillis = (System.nanoTime() - t0) / 1_000_000L;

            assertTrue(elapsedMillis < 1000,
                    "onMessage must not block, took " + elapsedMillis + "ms");
            assertEquals(droppedBefore + 1, f.node.getDroppedProposals(),
                    "the proposal should have been dropped and counted");
        } finally {
            f.close();
        }
    }

    /**
     * 节点停止时，阻塞在满队列上的提案者必须被唤醒，不能死锁。
     */
    @Test
    public void stopDoesNotDeadlockWhenProposerWaitsForQueueSpace() throws Exception {
        Fixture f = new Fixture(ProposalFullPolicy.BLOCK, CAP);
        try {
            f.fillQueue();

            AtomicReference<Throwable> err = new AtomicReference<>();
            Thread t = new Thread(() -> {
                try {
                    f.node.propose(new byte[]{1});
                } catch (Throwable e) {
                    err.set(e);
                }
            }, "blocked-proposer");
            t.start();
            Thread.sleep(300);

            AtomicBoolean stopped = new AtomicBoolean();
            Thread stopper = new Thread(() -> {
                f.node.stop();
                stopped.set(true);
            }, "stopper");
            stopper.start();

            // 闸门放行，让状态机线程能退出
            Thread.sleep(200);
            f.raft.openGate();

            stopper.join(10000);
            assertTrue(stopped.get(), "node.stop() must not deadlock when a proposer is blocked");

            t.join(10000);
            assertNotNull(err.get(), "the blocked proposer should have failed");
            assertTrue(err.get() instanceof RaftException,
                    "unexpected failure: " + err.get());
            assertEquals("raft: stopped", err.get().getMessage());
        } finally {
            f.close();
        }
    }

    /**
     * 无界队列（{@code maxPendingProposals = 0}）也必须真的把提案送进队列。
     *
     * <p>这里防的是一个曾经的 bug：无界分支被写成「直接当成入队成功」，
     * 跳过了真正的 {@code offer}，提案被静默丢掉 —— future 永远不完成，
     * 调用方只能等到超时。症状是「发布了几千条，提交 0 条」。</p>
     */
    @Test
    public void unboundedQueueStillEnqueuesProposals() throws Exception {
        Fixture f = new Fixture(ProposalFullPolicy.BLOCK, 0);
        try {
            f.raft.openGate();
            CompletableFuture<Void> future = f.node.proposeAsync(new byte[]{1}, 10000);
            future.get(20, TimeUnit.SECONDS);

            long deadline = System.currentTimeMillis() + 5000;
            while (f.committed.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(f.committed.get() > 0,
                    "the proposal should reach the state machine and be committed");
        } finally {
            f.close();
        }
    }

    /**
     * 有界队列下持续提案，一条都不能丢。
     */
    @Test
    public void boundedQueueDoesNotDropProposals() throws Exception {
        Fixture f = new Fixture(ProposalFullPolicy.BLOCK, CAP);
        try {
            // 闸门打开，状态机正常处理提案 —— 这里要的是「队列有界也不丢提案」
            f.raft.openGate();
            AtomicBoolean stop = new AtomicBoolean();
            AtomicLong ok = new AtomicLong();
            AtomicLong failed = new AtomicLong();
            Thread[] producers = new Thread[8];
            for (int i = 0; i < producers.length; i++) {
                producers[i] = new Thread(() -> {
                    while (!stop.get()) {
                        try {
                            f.node.propose(new byte[]{1});
                            ok.incrementAndGet();
                        } catch (RaftException e) {
                            failed.incrementAndGet();
                            return;
                        }
                    }
                }, "producer-" + i);
                producers[i].setDaemon(true);
                producers[i].start();
            }

            Thread.sleep(1000);
            stop.set(true);
            for (Thread t : producers) {
                t.join(10000);
            }

            assertTrue(ok.get() > 0, "proposals should keep flowing");
            assertEquals(0, failed.get(), "no proposal should be dropped by the bounded queue");
            assertEquals(0, f.node.getDroppedProposals(),
                    "the transport path should not drop anything here");
            assertTrue(f.committed.get() > 0, "proposals should still be committed");
        } finally {
            f.close();
        }
    }
}
