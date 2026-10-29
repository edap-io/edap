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

import io.edap.raft.protocol.ConfChange;
import io.edap.raft.protocol.ConfChangeCodec;
import io.edap.raft.protocol.ConfChangeType;
import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.EntryType;
import io.edap.raft.storage.MemoryStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 独立 apply 线程（{@link RaftConfig#applyHandler}）的行为。
 *
 * <p>已提交条目的应用从 Ready 周期里挪出来，交给单独的线程做：</p>
 * <ul>
 *   <li>Ready 里不再带已提交条目，应用层不用处理它们</li>
 *   <li>条目按序应用，applied 指针跟着推进</li>
 *   <li>成员变更在 apply 线程里生效</li>
 * </ul>
 */
@Timeout(120)
public class NodeApplyHandlerTest {

    /** 载荷长度：4 字节客户端 id + 8 字节序号 */
    private static final int PAYLOAD_LEN = 12;

    /**
     * 把应用过的条目记下来的处理器。
     */
    static final class RecordingApplyHandler implements ApplyHandler {

        /** 应用过的条目，按应用顺序 */
        final List<Entry> applied = Collections.synchronizedList(new ArrayList<>());

        /** 调用次数 */
        final AtomicLong batches = new AtomicLong();

        /** 应用过的条目数 */
        final AtomicLong count = new AtomicLong();

        /** 处理器所在线程的名字，用来确认不是状态机线程在应用 */
        volatile String threadName;

        /** 每应用一条就倒计时一次 */
        final CountDownLatch latch;

        /**
         * 落盘存储，用来检查「应用的时候日志是不是已经落盘了」。
         *
         * <p>没设的话不做这项检查。</p>
         */
        volatile MemoryStorage storage;

        /** 应用时日志还没落盘的条目 index */
        final List<Long> appliedBeforePersist = Collections.synchronizedList(new ArrayList<>());

        RecordingApplyHandler(int expected) {
            this.latch = new CountDownLatch(expected);
        }

        @Override
        public void apply(List<Entry> entries) {
            threadName = Thread.currentThread().getName();
            batches.incrementAndGet();
            for (Entry e : entries) {
                applied.add(e);
                count.incrementAndGet();
                MemoryStorage s = storage;
                if (s != null && s.lastIndex() < e.getIndex()) {
                    // 应用层看到的状态不能比日志更靠前
                    appliedBeforePersist.add(e.getIndex());
                }
                latch.countDown();
            }
        }
    }

    /**
     * 一个跑起来的单节点 leader，以及驱动它的应用层。
     */
    private static final class Cluster {

        /** raft 节点 */
        final Node node;

        /** 存储 */
        final MemoryStorage storage;

        /** 底层状态机 */
        final Raft raft;

        /** apply 处理器 */
        final RecordingApplyHandler handler;

        /** 应用层是否停止 */
        final AtomicBoolean appStop = new AtomicBoolean();

        /** 应用层收到的、还带着已提交条目的 Ready 数 */
        final AtomicLong readysWithCommitted = new AtomicLong();

        /** 是否由框架接管落盘 */
        final boolean frameworkPersist;

        Cluster(RecordingApplyHandler handler, boolean frameworkPersist) {
            MemoryStorage storage = RaftTestSupport.newTestMemoryStorage(1L);
            RaftConfig cfg = RaftTestSupport.newTestConfig(1, 10, 1, storage);
            cfg.applyHandler = handler;
            cfg.frameworkPersist = frameworkPersist;
            this.storage = storage;
            this.handler = handler;
            this.frameworkPersist = frameworkPersist;
            // 让处理器能检查「应用的时候日志是不是已经落盘了」
            handler.storage = storage;
            this.raft = new Raft(cfg);
            this.node = new Node(new RawNode(raft));
        }

        /**
         * 应用层：取 Ready → 发消息 → advance。
         *
         * <p>已提交条目由 apply 线程处理，应用层只管发消息；
         * 框架不接管落盘时，应用层自己把日志写进存储。</p>
         */
        void startAppLoop() {
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
                    if (!rd.getCommittedEntries().isEmpty()) {
                        readysWithCommitted.incrementAndGet();
                    }
                    if (!frameworkPersist) {
                        // 应用层自己落盘：Ready 交付之前必须把日志写进存储，
                        // 否则未落盘区会被清空，日志的 lastIndex 会退回去
                        if (rd.getHardState() != null || !rd.getEntries().isEmpty()) {
                            storage.save(rd.getHardState(), rd.getEntries());
                        }
                    }
                    node.advance();
                }
            }, "raft-app");
            app.setDaemon(true);
            app.start();
        }
    }

    /**
     * 造一个已经成为 leader 的单节点集群。
     */
    private static Cluster startLeader(RecordingApplyHandler handler) throws Exception {
        return startLeader(handler, true);
    }

    /**
     * 造一个已经成为 leader 的单节点集群。
     *
     * @param frameworkPersist 是否由框架接管落盘
     */
    private static Cluster startLeader(RecordingApplyHandler handler, boolean frameworkPersist)
            throws Exception {
        Cluster c = new Cluster(handler, frameworkPersist);
        c.startAppLoop();
        c.node.campaign();
        long deadline = System.currentTimeMillis() + 5000;
        while (c.raft.getLead() == Raft.NONE && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertNotEquals(Raft.NONE, c.raft.getLead(), "node should have become leader");
        return c;
    }

    /**
     * 已提交条目交给 apply 线程，Ready 里不再带它们。
     */
    @Test
    public void committedEntriesAreHandedToTheApplyHandler() throws Exception {
        RecordingApplyHandler handler = new RecordingApplyHandler(1);
        Cluster c = startLeader(handler);
        try {
            c.node.propose(payload(3, 1));

            assertTrue(handler.latch.await(10, TimeUnit.SECONDS), "entry should be applied");
            assertEquals(1, handler.count.get(), "one entry should have been applied");
            assertTrue(c.readysWithCommitted.get() == 0,
                    "Ready should not carry committed entries when an apply handler is set");
        } finally {
            c.appStop.set(true);
            c.node.stop();
        }
    }

    /**
     * apply 跑在独立的线程上，不是状态机线程。
     */
    @Test
    public void applyRunsOnItsOwnThread() throws Exception {
        RecordingApplyHandler handler = new RecordingApplyHandler(1);
        Cluster c = startLeader(handler);
        try {
            c.node.propose(payload(3, 1));
            assertTrue(handler.latch.await(10, TimeUnit.SECONDS), "entry should be applied");
            assertNotEquals("edap-raft-" + c.raft.getId(), handler.threadName,
                    "apply should not run on the state machine thread");
            assertTrue(handler.threadName.contains("apply"),
                    "apply should run on the apply thread, was " + handler.threadName);
        } finally {
            c.appStop.set(true);
            c.node.stop();
        }
    }

    /**
     * 条目按序应用，applied 指针跟着推进。
     */
    @Test
    public void entriesAreAppliedInOrderAndAppliedIndexAdvances() throws Exception {
        int total = 20;
        RecordingApplyHandler handler = new RecordingApplyHandler(total);
        Cluster c = startLeader(handler);
        try {
            for (int i = 1; i <= total; i++) {
                c.node.propose(payload(3, i));
            }
            assertTrue(handler.latch.await(20, TimeUnit.SECONDS), "all entries should be applied");

            // 应用顺序与 index 顺序一致
            long prev = 0;
            synchronized (handler.applied) {
                for (Entry e : handler.applied) {
                    assertTrue(e.getIndex() > prev,
                            "entries must be applied in index order: " + handler.applied);
                    prev = e.getIndex();
                }
            }
            // applied 指针推进到最后一个应用过的条目
            long deadline = System.currentTimeMillis() + 5000;
            while (c.raft.getRaftLog().applied() < prev && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(prev, c.raft.getRaftLog().applied(),
                    "applied index should follow the applied entries");
        } finally {
            c.appStop.set(true);
            c.node.stop();
        }
    }

    /**
     * 成员变更在 apply 线程里生效。
     *
     * <p>成员变更条目是已提交条目的一种，apply 线程处理它时要调用
     * {@code applyConfChange}，否则集群成员不会更新。</p>
     */
    @Test
    public void confChangeIsAppliedOnTheApplyThread() throws Exception {
        RecordingApplyHandler handler = new RecordingApplyHandler(1);
        Cluster c = startLeader(handler);
        try {
            ConfChange cc = new ConfChange(ConfChangeType.ADD_NODE, 2L, new byte[0]);
            c.node.proposeConfChange(cc);

            assertTrue(handler.latch.await(10, TimeUnit.SECONDS), "conf change should be applied");
            // 新成员在集群配置里
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                if (c.node.status().getConfig().getVoters().contains(2L)) {
                    break;
                }
                Thread.sleep(10);
            }
            assertTrue(c.node.status().getConfig().getVoters().contains(2L),
                    "new member should be in the cluster config");
            // 成员变更条目本身也要被 apply 线程看到
            boolean sawConfChange = false;
            synchronized (handler.applied) {
                for (Entry e : handler.applied) {
                    if (e.getType() == EntryType.CONFIG_CHANGE
                            || e.getType() == EntryType.CONFIG_CHANGE_V2) {
                        sawConfChange = true;
                        // 条目里的变更与提案一致
                        assertNotEquals(null, ConfChangeCodec.unmarshal(e.getType(), e.getData()));
                    }
                }
            }
            assertTrue(sawConfChange, "the conf change entry should reach the apply handler");
        } finally {
            c.appStop.set(true);
            c.node.stop();
        }
    }

    /**
     * 落盘由应用层负责时，apply 线程照旧工作。
     *
     * <p>独立 apply 线程与框架接管落盘是两个独立的开关：应用层自己落盘，
     * 已提交条目照样交给 apply 线程。</p>
     */
    @Test
    public void applyHandlerWorksWhenTheApplicationPersists() throws Exception {
        RecordingApplyHandler handler = new RecordingApplyHandler(1);
        Cluster c = startLeader(handler, false);
        try {
            c.node.propose(payload(3, 1));

            assertTrue(handler.latch.await(10, TimeUnit.SECONDS), "entry should be applied");
            assertEquals(1, handler.count.get(), "one entry should have been applied");
            assertTrue(c.readysWithCommitted.get() == 0,
                    "Ready should not carry committed entries when an apply handler is set");
        } finally {
            c.appStop.set(true);
            c.node.stop();
        }
    }

    /**
     * 应用的时候，对应的日志必须已经落盘。
     *
     * <p>应用层看到的状态不能比日志更靠前：日志丢了还能重放，
     * 应用过的状态丢了就没了。</p>
     */
    @Test
    public void entriesAreAppliedOnlyAfterTheyArePersisted() throws Exception {
        RecordingApplyHandler handler = new RecordingApplyHandler(1);
        Cluster c = startLeader(handler);
        try {
            c.node.propose(payload(3, 1));

            assertTrue(handler.latch.await(10, TimeUnit.SECONDS), "entry should be applied");
            assertTrue(handler.appliedBeforePersist.isEmpty(),
                    "entries must be applied only after they are persisted, applied before persist: "
                            + handler.appliedBeforePersist);
        } finally {
            c.appStop.set(true);
            c.node.stop();
        }
    }

    private static byte[] payload(int clientId, long seq) {
        ByteBuffer buf = ByteBuffer.allocate(PAYLOAD_LEN);
        buf.putInt(clientId);
        buf.putLong(seq);
        return buf.array();
    }
}
