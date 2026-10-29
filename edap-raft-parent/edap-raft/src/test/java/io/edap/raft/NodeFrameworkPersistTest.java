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

import io.edap.raft.protocol.ConfState;
import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.HardState;
import io.edap.raft.protocol.Message;
import io.edap.raft.protocol.MessageType;
import io.edap.raft.storage.MemoryStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 框架接管落盘（{@link RaftConfig#frameworkPersist}）的行为。
 *
 * <p>框架落盘要守住两条不变式：</p>
 * <ol>
 *   <li><b>Ready 里不再带日志</b>：日志由框架写进存储，应用层拿到的
 *       Ready 只剩消息与已提交条目。</li>
 *   <li><b>确认类消息晚于落盘</b>：投票确认（MSG_VOTE_RESP）与日志追加确认
 *       （MSG_APP_RESP）的意思是「我已经持久化了这些日志」，必须等对应的
 *       日志真的落盘之后才发出去。复制类消息（MSG_APP）与心跳不带任何
 *       持久化承诺，可以先发 —— 对端立刻开始落盘，与本节点的落盘重叠，
 *       两个 fsync 不串行。</li>
 * </ol>
 */
@Timeout(120)
public class NodeFrameworkPersistTest {

    /** 载荷长度：4 字节客户端 id + 8 字节序号 */
    private static final int PAYLOAD_LEN = 12;

    /** 落盘时的模拟耗时，要够大才能把「先发」与「后发」区分开 */
    private static final long SAVE_DELAY_MILLIS = 100;

    /**
     * 记录「落盘」与「发消息」先后顺序的存储。
     *
     * <p>{@link #save} 可以模拟一段 fsync 耗时，用来把「落盘在前、
     * 「消息投递在后」这件事放大到肉眼可见。</p>
     */
    static final class RecordingStorage extends MemoryStorage {

        /** 事件序列：{@code save} / {@code send:消息类型} */
        final List<String> events;

        /** 模拟一次落盘的耗时 */
        volatile long saveDelayMillis;

        /** 落盘次数 */
        final AtomicLong saves = new AtomicLong();

        /** 落盘的条目总数 */
        final AtomicLong savedEntries = new AtomicLong();

        RecordingStorage(List<String> events, ConfState cs) {
            super(cs);
            this.events = events;
        }

        @Override
        public void save(HardState st, List<Entry> entries) {
            saves.incrementAndGet();
            savedEntries.addAndGet(entries.size());
            long delay = saveDelayMillis;
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            super.save(st, entries);
            // 事件记在落盘完成之后：「落盘完成」才是可以发确认消息的时刻
            events.add("save:" + entries.size());
        }

        /** 事件序列的快照 */
        List<String> eventsSnapshot() {
            synchronized (events) {
                return new ArrayList<>(events);
            }
        }
    }

    /**
     * 记录状态机处理消息的顺序，用来确认「日志落盘确认」在落盘之后才被 step。
     */
    static final class RecordingRaft extends Raft {

        /** 事件序列，与存储共用一个 */
        final List<String> events;

        /** 收到「日志落盘确认」的次数 */
        final AtomicLong appendResps = new AtomicLong();

        RecordingRaft(RaftConfig cfg, List<String> events) {
            super(cfg);
            this.events = events;
        }

        @Override
        public boolean step(Message m) {
            if (m.getType() == MessageType.MSG_STORAGE_APPEND_RESP) {
                appendResps.incrementAndGet();
                events.add("append-resp");
            }
            return super.step(m);
        }
    }

    /**
     * 一个跑起来的单节点 leader，以及驱动它的应用层。
     */
    private static final class Cluster {

        /** raft 节点 */
        final Node node;

        /** 记录落盘顺序的存储 */
        final RecordingStorage storage;

        /** 底层状态机 */
        final RecordingRaft raft;

        /** 应用层是否停止 */
        final AtomicBoolean appStop = new AtomicBoolean();

        /** 应用层收到的 Ready 数 */
        final AtomicLong readys = new AtomicLong();

        Cluster(long saveDelayMillis) {
            List<String> events = Collections.synchronizedList(new ArrayList<>());
            RecordingStorage storage = new RecordingStorage(events, singleNodeConfState());
            RaftConfig cfg = RaftTestSupport.newTestConfig(1, 10, 1, storage);
            cfg.frameworkPersist = true;
            this.storage = storage;
            this.storage.saveDelayMillis = saveDelayMillis;
            this.raft = new RecordingRaft(cfg, events);
            this.node = new Node(new RawNode(raft));
        }

        /**
         * 应用层：取 Ready → 发消息 → advance。
         *
         * <p>框架接管落盘，应用层不再碰存储。</p>
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
                    readys.incrementAndGet();
                    for (Message m : rd.getMessages()) {
                        storage.events.add("send:" + m.getType().name());
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
    private static Cluster startLeader(long saveDelayMillis) throws Exception {
        Cluster c = new Cluster(saveDelayMillis);
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
     * 框架落盘时，Ready 里不再带日志，日志由框架写进存储。
     */
    @Test
    public void frameworkPersistKeepsEntriesOutOfTheReady() throws Exception {
        Cluster c = startLeader(0);
        try {
            c.node.propose(payload(1, 1));

            // 等提案被应用层看到
            long deadline = System.currentTimeMillis() + 5000;
            while (c.readys.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }

            assertTrue(c.storage.saves.get() > 0, "framework should have persisted the log");
            assertTrue(c.storage.savedEntries.get() > 0, "framework should have persisted entries");

            // 落盘是异步的：存储追平日志之后，两者必须完全一致
            deadline = System.currentTimeMillis() + 5000;
            while (c.storage.lastIndex() != c.raft.getRaftLog().lastIndex()
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertEquals(c.raft.getRaftLog().lastIndex(), c.storage.lastIndex(),
                    "storage should hold the same log as the raft state machine");
            assertTrue(c.storage.lastIndex() > 0, "storage should hold the proposal");
        } finally {
            c.appStop.set(true);
            c.node.stop();
        }
    }

    /**
     * 没有独立 apply 线程时，Ready 要等落盘完成才交出去。
     *
     * <p>这种模式下 Ready 带着已提交条目，由应用层应用 —— 应用层看到的状态
     * 不能比日志更靠前，所以 Ready 必须等落盘完成。</p>
     */
    @Test
    public void readyIsDeliveredAfterThePersistWhenTheApplicationApplies() throws Exception {
        TwoNodeCluster c = new TwoNodeCluster(false);
        try {
            c.n1.campaign();

            // 等到投票请求与投票确认都走过一遍，两个节点都拿到过带消息的 Ready
            long deadline = System.currentTimeMillis() + 10000;
            while (System.currentTimeMillis() < deadline
                    && !(c.hasSent(c.events1) && c.hasSent(c.events2))) {
                Thread.sleep(10);
            }
            assertTrue(c.hasSent(c.events1),
                    "the candidate should have delivered a Ready with messages");
            assertTrue(c.hasSent(c.events2),
                    "the voter should have delivered a Ready with messages");

            // 每交付一个带消息的 Ready，之前至少已经落盘过一次
            for (List<String> events : Arrays.asList(c.events1, c.events2)) {
                int saves = 0;
                int sends = 0;
                for (String e : c.snapshot(events)) {
                    if (e.startsWith("save")) {
                        saves++;
                    } else if (e.startsWith("send:")) {
                        sends++;
                        assertTrue(saves >= sends,
                                "a Ready must be delivered only after a persist, events=" + events);
                    }
                }
                assertTrue(sends > 0, "should have delivered Ready with messages");
            }
        } finally {
            c.stop();
        }
    }

    /**
     * 「日志落盘确认」要等落盘真的完成之后才交给状态机。
     *
     * <p>这条消息的意思是「我承认自己日志里的这些条目已经持久化了」。
     * 提前交给状态机，leader 就会把自己的票算进多数派里 ——
     * 而它的日志其实还没落盘。</p>
     */
    @Test
    public void ownAppendConfirmationIsSteppedOnlyAfterThePersist() throws Exception {
        Cluster c = startLeader(20);
        try {
            c.node.propose(payload(1, 1));

            long deadline = System.currentTimeMillis() + 10000;
            while (c.raft.appendResps.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(c.raft.appendResps.get() > 0, "should have received an append confirmation");

            List<String> events = c.storage.eventsSnapshot();
            int firstSave = -1;
            int firstResp = -1;
            for (int i = 0; i < events.size(); i++) {
                if (events.get(i).startsWith("save") && firstSave < 0) {
                    firstSave = i;
                }
                if (events.get(i).equals("append-resp") && firstResp < 0) {
                    firstResp = i;
                }
            }
            assertTrue(firstSave >= 0, "expected a persist, events=" + events);
            assertTrue(firstResp > firstSave,
                    "the append confirmation must come after the persist, events=" + events);
        } finally {
            c.appStop.set(true);
            c.node.stop();
        }
    }

    /**
     * 框架落盘时，硬状态（term/vote）也要落进存储。
     */
    @Test
    public void hardStateIsPersistedByTheFramework() throws Exception {
        Cluster c = startLeader(0);
        try {
            long deadline = System.currentTimeMillis() + 5000;
            while (c.storage.initialState() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            HardState st = c.storage.initialState();
            assertNotEquals(null, st, "hard state should be persisted");
            assertEquals(c.raft.getTerm(), st.getTerm(),
                    "persisted term should match the state machine");
            assertTrue(st.getTerm() > 0, "term should be set after the election");
        } finally {
            c.appStop.set(true);
            c.node.stop();
        }
    }

    /**
     * 提案能正常提交：框架落盘不影响功能正确性。
     */
    @Test
    public void proposalsStillGetCommitted() throws Exception {
        Cluster c = startLeader(0);
        try {
            c.node.propose(payload(7, 1));

            long deadline = System.currentTimeMillis() + 5000;
            while (c.raft.getRaftLog().applied() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(c.raft.getRaftLog().applied() > 0, "proposal should be committed and applied");
            assertFalse(c.storage.eventsSnapshot().isEmpty(), "there should be persist events");
        } finally {
            c.appStop.set(true);
            c.node.stop();
        }
    }

    /**
     * 确认类消息必须等落盘完成之后才发出去。
     *
     * <p>投票确认（MSG_VOTE_RESP）与日志追加确认（MSG_APP_RESP）的意思是
     * 「我已经持久化了这些日志」。提前发出去，对端会以为这些日志已经安全 ——
     * 机器掉电之后对端可能拿不回这些日志，而 leader 已经把它算进多数派了。</p>
     */
    @Test
    public void confirmationsAreOnlySentAfterThePersist() throws Exception {
        TwoNodeCluster c = new TwoNodeCluster(true);
        try {
            c.n1.campaign();

            long deadline = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < deadline
                    && !(c.sent(c.events2, MessageType.MSG_VOTE_RESP)
                            && c.sent(c.events2, MessageType.MSG_APP_RESP))) {
                Thread.sleep(10);
            }

            for (List<String> events : Arrays.asList(c.events1, c.events2)) {
                int saves = 0;
                for (String e : c.snapshot(events)) {
                    if (e.startsWith("save")) {
                        saves++;
                    } else if (e.startsWith("send:")) {
                        MessageType type = MessageType.valueOf(e.substring("send:".length()));
                        if (type == MessageType.MSG_APP_RESP
                                || type == MessageType.MSG_VOTE_RESP
                                || type == MessageType.MSG_PRE_VOTE_RESP) {
                            assertTrue(saves > 0,
                                    "a confirmation must be sent only after a persist, events=" + events);
                        }
                    }
                }
            }
            assertTrue(c.sent(c.events2, MessageType.MSG_VOTE_RESP),
                    "the voter should have confirmed the vote");
        } finally {
            c.stop();
        }
    }

    /**
     * 复制类消息可以先发出去：让对端立刻开始落盘。
     *
     * <p>复制消息不带任何持久化承诺。先发出去，对端的落盘与本节点的落盘
     * 就重叠了，两个 fsync 不串行，端到端延迟少一个落盘周期。</p>
     */
    @Test
    public void replicationMessagesAreSentBeforeThePersist() throws Exception {
        TwoNodeCluster c = new TwoNodeCluster(true);
        try {
            c.n1.campaign();

            // 等到竞选方把投票请求发出去
            long deadline = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < deadline
                    && !c.sent(c.events1, MessageType.MSG_VOTE)) {
                Thread.sleep(10);
            }
            assertTrue(c.sent(c.events1, MessageType.MSG_VOTE),
                    "the candidate should have sent the vote request");

            // 竞选方的投票请求是在自己的落盘完成之前发出去的
            List<String> events = c.snapshot(c.events1);
            int voteAt = indexOf(events, "send:" + MessageType.MSG_VOTE.name());
            int firstSave = indexOfPrefix(events, "save");
            assertTrue(voteAt >= 0, "expected a vote request, events=" + events);
            assertTrue(firstSave < 0 || voteAt < firstSave,
                    "the vote request should be sent before the persist, events=" + events);
        } finally {
            c.stop();
        }
    }

    /**
     * 单节点集群的成员配置。
     */
    private static ConfState singleNodeConfState() {
        return new ConfState(Collections.singletonList(1L), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), false);
    }

    /**
     * 两个节点的集群：节点之间直接传消息。
     *
     * <p>单节点集群里没有任何对外消息，「消息投递顺序」类断言
     * 在单节点上根本无从验证，所以这里要起两个节点：节点 1 竞选，
     * 节点 2 投票，两边都会产生带消息的 Ready。</p>
     */
    private static final class TwoNodeCluster {

        /** 竞选方 */
        final Node n1;

        /** 投票方 */
        final Node n2;

        /** 竞选方的状态机 */
        final Raft r1;

        /** 投票方的状态机 */
        final Raft r2;

        /** 竞选方的事件序列 */
        final List<String> events1 = Collections.synchronizedList(new ArrayList<>());

        /** 投票方的事件序列 */
        final List<String> events2 = Collections.synchronizedList(new ArrayList<>());

        /** 应用层是否停止 */
        final AtomicBoolean appStop = new AtomicBoolean();

        /**
         * @param separateApply 是否给两个节点都挂上独立的 apply 处理器
         */
        TwoNodeCluster(boolean separateApply) {
            ConfState cs = new ConfState(Arrays.asList(1L, 2L), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), false);
            RecordingStorage s1 = new RecordingStorage(events1, cs);
            RecordingStorage s2 = new RecordingStorage(events2, cs);
            s1.saveDelayMillis = SAVE_DELAY_MILLIS;
            s2.saveDelayMillis = SAVE_DELAY_MILLIS;
            RaftConfig c1 = RaftTestSupport.newTestConfig(1, 10, 1, s1);
            c1.frameworkPersist = true;
            RaftConfig c2 = RaftTestSupport.newTestConfig(2, 10, 1, s2);
            c2.frameworkPersist = true;
            if (separateApply) {
                c1.applyHandler = new NoopApplyHandler();
                c2.applyHandler = new NoopApplyHandler();
            }
            r1 = new Raft(c1);
            r2 = new Raft(c2);
            n1 = new Node(new RawNode(r1));
            n2 = new Node(new RawNode(r2));
            startAppLoop(n1, n2, events1);
            startAppLoop(n2, n1, events2);
        }

        /**
         * 应用层：取 Ready → 把消息转给对端 → advance。
         */
        private void startAppLoop(Node self, Node peer, List<String> events) {
            Thread app = new Thread(() -> {
                while (!appStop.get()) {
                    Ready rd;
                    try {
                        rd = self.ready(20, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (rd == null) {
                        continue;
                    }
                    for (Message m : rd.getMessages()) {
                        events.add("send:" + m.getType().name());
                        peer.onMessage(m);
                    }
                    self.advance();
                }
            }, "raft-app-" + self);
            app.setDaemon(true);
            app.start();
        }

        /** 事件序列里有没有发过某种类型的消息 */
        boolean sent(List<String> events, MessageType type) {
            for (String e : snapshot(events)) {
                if (e.equals("send:" + type.name())) {
                    return true;
                }
            }
            return false;
        }

        /** 事件序列里有没有发过消息 */
        boolean hasSent(List<String> events) {
            for (String e : snapshot(events)) {
                if (e.startsWith("send:")) {
                    return true;
                }
            }
            return false;
        }

        /** 事件序列的快照 */
        List<String> snapshot(List<String> events) {
            synchronized (events) {
                return new ArrayList<>(events);
            }
        }

        void stop() {
            appStop.set(true);
            n1.stop();
            n2.stop();
        }
    }

    /**
     * 什么都不做的 apply 处理器：只是为了打开独立 apply 线程这个开关。
     */
    static final class NoopApplyHandler implements ApplyHandler {

        /** 应用过的条目数 */
        final AtomicLong count = new AtomicLong();

        @Override
        public void apply(List<Entry> entries) {
            count.addAndGet(entries.size());
        }
    }

    /** 事件序列里某个事件的位置，找不到返回 -1 */
    private static int indexOf(List<String> events, String event) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).equals(event)) {
                return i;
            }
        }
        return -1;
    }

    /** 事件序列里第一个带前缀的事件的位置，找不到返回 -1 */
    private static int indexOfPrefix(List<String> events, String prefix) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).startsWith(prefix)) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] payload(int clientId, long seq) {
        ByteBuffer buf = ByteBuffer.allocate(PAYLOAD_LEN);
        buf.putInt(clientId);
        buf.putLong(seq);
        return buf.array();
    }
}
