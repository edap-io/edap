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
import io.edap.raft.protocol.ConfChangeI;
import io.edap.raft.protocol.ConfChangeType;
import io.edap.raft.protocol.ConfState;
import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.EntryType;
import io.edap.raft.protocol.HardState;
import io.edap.raft.protocol.Message;
import io.edap.raft.protocol.MessageType;
import io.edap.raft.protocol.RaftCodec;
import io.edap.raft.protocol.Snapshot;
import io.edap.raft.protocol.SnapshotMetadata;
import io.edap.raft.storage.IgnoreSizeHintMemoryStorage;
import io.edap.raft.storage.MemoryStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * etcd {@code node_test.go} 的 Java 实现。
 *
 * <p>etcd 里这些用例直接操作 {@code node} 的内部通道，Java 侧的 {@link Node}
 * 用阻塞队列 + 唤醒信号实现了同样的语义，因此大部分用例可以直接照搬。</p>
 *
 * <p>两处没有照搬：</p>
 * <ul>
 *     <li>{@code TestNodeStepUnblock} 的「取消 context」分支：Java 没有
 *     context 取消机制，只保留「停止节点解除阻塞」这一支。</li>
 *     <li>{@code TestCommitPaginationWithAsyncStorageWrites}：Java 侧的
 *     {@link RaftConfig} 没有异步落盘开关，只支持同步落盘。</li>
 * </ul>
 */
@Timeout(60)
public class RaftNodeTest {

    /**
     * Go: {@code node_test.go} 里的 {@code readyWithTimeout}。
     *
     * <p>带 1 秒超时地取一个 Ready，超时直接失败（Go 里是 panic）。</p>
     */
    private static Ready readyWithTimeout(Node n) throws InterruptedException {
        Ready rd = n.ready(1, TimeUnit.SECONDS);
        assertNotNull(rd, "timed out waiting for ready");
        return rd;
    }

    /**
     * Go: {@code newNodeTestHarness}（不带 peers 的分支）。
     *
     * <p>只创建 RawNode，不做 bootstrap。</p>
     */
    private static Node newNode(RaftConfig cfg) {
        return new Node(new RawNode(cfg));
    }

    /**
     * Go: {@code newNodeTestHarness}（带 peers 的分支，即 {@code setupNode}）。
     */
    private static Node newNodeWithPeers(RaftConfig cfg, Peer... peers) {
        RawNode rn = new RawNode(cfg);
        rn.bootstrap(peers);
        return new Node(rn);
    }

    /**
     * Go: TestNodeStep
     *
     * <p>提案走提案通道，其余消息走接收通道；从网络上收到的本地消息直接忽略。</p>
     */
    @Test
    void nodeStep() {
        for (Map.Entry<String, MessageType> entry : MessageType.names().entrySet()) {
            MessageType msgt = entry.getValue();
            Message m = new Message().setType(msgt);
            Node.Route route = Node.route(m);
            if (msgt == MessageType.MSG_PROP) {
                assertEquals(Node.Route.PROP, route, entry.getKey());
            } else if (RaftUtil.isLocalMsg(msgt)) {
                assertEquals(Node.Route.DROP, route, entry.getKey());
            } else {
                assertEquals(Node.Route.RECV, route, entry.getKey());
            }
        }
    }

    /**
     * Go: TestNodeStepUnblock
     *
     * <p>节点停止时阻塞中的提案要被唤醒并报错。</p>
     */
    @Test
    void nodeStepUnblock() throws Exception {
        Node n = newNode(RaftTestSupport.newTestConfig(1, 10, 1,
                RaftTestSupport.newTestMemoryStorage(1)));
        ExecutorService es = Executors.newSingleThreadExecutor();
        try {
            Future<?> f = es.submit(() -> {
                RaftException e = assertThrows(RaftException.class,
                        () -> n.propose("somedata".getBytes()));
                assertEquals("raft: stopped", e.getMessage());
            });
            // 等提案真正阻塞在提案通道上
            Thread.sleep(50);
            n.stop();
            f.get(5, TimeUnit.SECONDS);
        } finally {
            es.shutdownNow();
        }
    }

    /**
     * Go: TestNodePropose
     *
     * <p>提案要送到状态机里。</p>
     */
    @Test
    void nodePropose() throws Exception {
        CapturingRaft r = new CapturingRaft(RaftTestSupport.newTestConfig(1, 10, 1,
                RaftTestSupport.newTestMemoryStorage(1)));
        MemoryStorage s = (MemoryStorage) r.getRaftLog().getStorage();
        RawNode rn = new RawNode(r);
        Node n = new Node(rn);
        n.campaign();
        // 一直消费 Ready，直到本节点成为 leader
        while (true) {
            Ready rd = readyWithTimeout(n);
            s.append(rd.getEntries());
            if (rd.getSoftState() != null && rd.getSoftState().getLead() == r.getId()) {
                // 换成捕获消息的 step，直到本节点成为 leader
                r.capture = true;
                n.advance();
                break;
            }
            n.advance();
        }
        n.propose("somedata".getBytes());
        n.stop();

        assertEquals(1, r.msgs.size(), "captured messages");
        assertEquals(MessageType.MSG_PROP, r.msgs.get(0).getType());
        assertArrayEquals("somedata".getBytes(), r.msgs.get(0).getEntries().get(0).getData());
    }

    /**
     * Go: TestNodeProposeConfig
     *
     * <p>成员变更提案要送到状态机里。</p>
     */
    @Test
    void nodeProposeConfig() throws Exception {
        CapturingRaft r = new CapturingRaft(RaftTestSupport.newTestConfig(1, 10, 1,
                RaftTestSupport.newTestMemoryStorage(1)));
        MemoryStorage s = (MemoryStorage) r.getRaftLog().getStorage();
        RawNode rn = new RawNode(r);
        Node n = new Node(rn);
        n.campaign();
        while (true) {
            Ready rd = readyWithTimeout(n);
            s.append(rd.getEntries());
            if (rd.getSoftState() != null && rd.getSoftState().getLead() == r.getId()) {
                r.capture = true;
                n.advance();
                break;
            }
            n.advance();
        }
        ConfChange cc = new ConfChange(ConfChangeType.ADD_NODE, 1, new byte[0]);
        byte[] ccdata = ConfChangeCodec.marshal(cc).getData();
        n.proposeConfChange(cc);
        n.stop();

        assertEquals(1, r.msgs.size(), "captured messages");
        assertEquals(MessageType.MSG_PROP, r.msgs.get(0).getType());
        assertArrayEquals(ccdata, r.msgs.get(0).getEntries().get(0).getData());
    }

    /**
     * Go: TestNodeProposeWaitDropped
     *
     * <p>提案被状态机丢弃时，提案方要立刻收到失败。</p>
     */
    @Test
    void nodeProposeWaitDropped() throws Exception {
        CapturingRaft r = new CapturingRaft(RaftTestSupport.newTestConfig(1, 10, 1,
                RaftTestSupport.newTestMemoryStorage(1)));
        r.dropMarker = "test_dropping";
        MemoryStorage s = (MemoryStorage) r.getRaftLog().getStorage();
        RawNode rn = new RawNode(r);
        Node n = new Node(rn);
        n.campaign();
        while (true) {
            Ready rd = readyWithTimeout(n);
            s.append(rd.getEntries());
            if (rd.getSoftState() != null && rd.getSoftState().getLead() == r.getId()) {
                r.capture = true;
                n.advance();
                break;
            }
            n.advance();
        }
        RaftException e = assertThrows(RaftException.class,
                () -> n.propose("test_dropping".getBytes()));
        assertEquals("raft proposal dropped", e.getMessage());
        n.stop();

        assertTrue(r.msgs.isEmpty(), "captured messages");
    }

    /**
     * Go: TestBlockProposal
     *
     * <p>不知道 leader 时提案阻塞，知道 leader 之后提案放行。</p>
     */
    @Test
    void blockProposal() throws Exception {
        MemoryStorage s = RaftTestSupport.newTestMemoryStorage(1);
        RawNode rn = new RawNode(RaftTestSupport.newTestConfig(1, 10, 1, s));
        Node n = new Node(rn);
        ExecutorService es = Executors.newSingleThreadExecutor();
        try {
            Future<?> f = es.submit(() -> n.propose("somedata".getBytes()));
            Thread.sleep(100);
            assertFalse(f.isDone(), "proposal should be blocked without a leader");

            n.campaign();
            Ready rd = readyWithTimeout(n);
            s.append(rd.getEntries());
            n.advance();
            f.get(5, TimeUnit.SECONDS);
        } finally {
            n.stop();
            es.shutdownNow();
        }
    }

    /**
     * Go: TestNodeTick
     *
     * <p>tick 要推进状态机的逻辑时钟。</p>
     */
    @Test
    void nodeTick() {
        Raft r = RaftTestSupport.newTestRaft(1, 10, 1, RaftTestSupport.newTestMemoryStorage(1));
        RawNode rn = new RawNode(r);
        Node n = new Node(rn);
        int elapsed = r.electionElapsed;
        n.tick();
        // 状态机线程先处理 tick 再处理状态查询，因此查询返回时 tick 一定已经生效
        n.status();
        n.stop();
        assertEquals(elapsed + 1, r.electionElapsed);
    }

    /**
     * Go: TestNodeStop
     *
     * <p>停止是幂等的，停止之后状态查询返回空状态。</p>
     */
    @Test
    void nodeStop() {
        RawNode rn = new RawNode(RaftTestSupport.newTestConfig(1, 10, 1,
                RaftTestSupport.newTestMemoryStorage(1)));
        Node n = new Node(rn);

        Status status = n.status();
        n.stop();

        Status emptyStatus = new Status(new BasicStatus(0, new HardState(0, 0, 0), null, 0, 0), null, null);
        assertNotEquals(emptyStatus, status);

        // 停止之后状态查询返回空状态
        assertEquals(emptyStatus, n.status());

        // 重复停止没有影响
        n.stop();
    }

    /**
     * Go: TestNodeStart
     *
     * <p>节点启动时要带上正确的配置变更条目，并且能接受并提交提案。</p>
     */
    @Test
    void nodeStart() throws Exception {
        ConfChange cc = new ConfChange(ConfChangeType.ADD_NODE, 1, new byte[0]);
        byte[] ccdata = ConfChangeCodec.marshal(cc).getData();

        MemoryStorage storage = new MemoryStorage();
        RaftConfig c = RaftTestSupport.newTestConfig(1, 10, 1, storage);

        // Go: StartNode(c, []Peer{{ID: 1}}) —— bootstrap 的条目只留在未落盘区，
        // 存储里仍然是空的，所以对后面的用例没有影响
        Node.startNode(c, new Peer(1, "localhost", 8080)).stop();

        Node n = newNodeWithPeers(c, new Peer(1, "localhost", 8080));
        try {
            // 第一个 Ready：引导用的成员变更条目
            Ready rd = readyWithTimeout(n);
            assertReadyEquals(new HardState(1, 0, 1),
                    Collections.singletonList(new Entry(1, 1, EntryType.CONFIG_CHANGE, ccdata)),
                    Collections.singletonList(new Entry(1, 1, EntryType.CONFIG_CHANGE, ccdata)),
                    true, rd);
            storage.append(rd.getEntries());
            n.advance();

            n.campaign();

            // 持久化投票
            rd = readyWithTimeout(n);
            storage.append(rd.getEntries());
            n.advance();
            // 追加空条目
            rd = readyWithTimeout(n);
            storage.append(rd.getEntries());
            n.advance();

            n.propose("foo".getBytes());
            {
                rd = readyWithTimeout(n);
                assertReadyEquals(new HardState(2, 1, 2),
                        Collections.singletonList(new Entry(3, 2, EntryType.NORMAL, "foo".getBytes())),
                        Collections.singletonList(new Entry(2, 2, EntryType.NORMAL, null)),
                        true, rd);
                storage.append(rd.getEntries());
                n.advance();
            }

            {
                rd = readyWithTimeout(n);
                assertReadyEquals(new HardState(2, 1, 3),
                        Collections.emptyList(),
                        Collections.singletonList(new Entry(3, 2, EntryType.NORMAL, "foo".getBytes())),
                        false, rd);
                storage.append(rd.getEntries());
                n.advance();
            }

            assertNull(n.ready(50, TimeUnit.MILLISECONDS), "unexpected Ready");
        } finally {
            n.stop();
        }
    }

    /**
     * Go: TestNodeRestart
     *
     * <p>带已有状态重启时，只提交到已持久化的 commit 位置，不发硬状态。</p>
     */
    @Test
    void nodeRestart() throws Exception {
        List<Entry> entries = Arrays.asList(
                new Entry(1, 1, EntryType.NORMAL, null),
                new Entry(2, 1, EntryType.NORMAL, "foo".getBytes()));
        HardState st = new HardState(1, 0, 1);

        MemoryStorage storage = new MemoryStorage();
        storage.setHardState(st);
        storage.append(entries);

        Node n = newNode(RaftTestSupport.newTestConfig(1, 10, 1, storage));
        try {
            Ready rd = readyWithTimeout(n);
            // 硬状态没有变化，不发
            assertTrue(RaftUtil.isEmptyHardState(rd.getHardState()), "hard state");
            // 提交到已持久化的 commit 位置
            assertEqualEntries(entries.subList(0, (int) st.getCommit()), rd.getCommittedEntries());
            // 没有新的硬状态也没有新日志，不需要同步落盘
            assertFalse(rd.isMustSync(), "must sync");
            n.advance();

            assertNull(n.ready(50, TimeUnit.MILLISECONDS), "unexpected Ready");
        } finally {
            n.stop();
        }
    }

    /**
     * Go: TestNodeRestartFromSnapshot
     *
     * <p>带快照重启时同理。</p>
     */
    @Test
    void nodeRestartFromSnapshot() throws Exception {
        Snapshot snap = new Snapshot(new byte[0], new SnapshotMetadata(
                new ConfState(Arrays.asList(1L, 2L), Collections.emptyList(),
                        Collections.emptyList(), Collections.emptyList(), false), 2, 1));
        List<Entry> entries = Collections.singletonList(
                new Entry(3, 1, EntryType.NORMAL, "foo".getBytes()));
        HardState st = new HardState(1, 0, 3);

        MemoryStorage s = new MemoryStorage();
        s.setHardState(st);
        s.applySnapshot(snap);
        s.append(entries);

        Node n = newNode(RaftTestSupport.newTestConfig(1, 10, 1, s));
        try {
            Ready rd = readyWithTimeout(n);
            assertTrue(RaftUtil.isEmptyHardState(rd.getHardState()), "hard state");
            assertEqualEntries(entries, rd.getCommittedEntries());
            assertFalse(rd.isMustSync(), "must sync");
            n.advance();

            assertNull(n.ready(50, TimeUnit.MILLISECONDS), "unexpected Ready");
        } finally {
            n.stop();
        }
    }

    /**
     * Go: TestNodeAdvance
     *
     * <p>调用 Advance 之后要能拿到下一个 Ready。</p>
     */
    @Test
    void nodeAdvance() throws Exception {
        MemoryStorage storage = RaftTestSupport.newTestMemoryStorage(1);
        Node n = newNode(RaftTestSupport.newTestConfig(1, 10, 1, storage));
        try {
            n.campaign();
            // 持久化投票
            Ready rd = readyWithTimeout(n);
            storage.append(rd.getEntries());
            n.advance();
            // 追加空条目
            rd = readyWithTimeout(n);
            storage.append(rd.getEntries());
            n.advance();

            n.propose("foo".getBytes());
            rd = readyWithTimeout(n);
            storage.append(rd.getEntries());
            n.advance();
            assertNotNull(n.ready(1, TimeUnit.SECONDS), "expect Ready after Advance");
        } finally {
            n.stop();
        }
    }

    /**
     * Go: TestNodeProposeAddDuplicateNode
     *
     * <p>两次加入同一个节点不影响之后加入新节点。</p>
     */
    @Test
    void nodeProposeAddDuplicateNode() throws Exception {
        MemoryStorage s = RaftTestSupport.newTestMemoryStorage(1);
        RaftConfig cfg = RaftTestSupport.newTestConfig(1, 10, 1, s);
        Node n = newNode(cfg);
        try {
            n.campaign();

            List<Entry> allCommittedEntries = new CopyOnWriteArrayList<>();
            BlockingQueue<Boolean> applyConfChan = new LinkedBlockingQueue<>();
            AtomicBoolean stopped = new AtomicBoolean();

            Thread worker = new Thread(() -> {
                while (!stopped.get()) {
                    try {
                        Ready rd = n.ready(50, TimeUnit.MILLISECONDS);
                        if (rd == null) {
                            continue;
                        }
                        s.append(rd.getEntries());
                        boolean applied = false;
                        for (Entry e : rd.getCommittedEntries()) {
                            allCommittedEntries.add(e);
                            if (e.getType() == EntryType.CONFIG_CHANGE) {
                                ConfChangeI cc = ConfChangeCodec.unmarshal(e.getType(), e.getData());
                                n.applyConfChange(cc);
                                applied = true;
                            }
                        }
                        n.advance();
                        if (applied) {
                            applyConfChan.offer(Boolean.TRUE);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "raft-node-test-consumer");
            worker.setDaemon(true);
            worker.start();

            ConfChange cc1 = new ConfChange(ConfChangeType.ADD_NODE, 1, new byte[0]);
            byte[] ccdata1 = ConfChangeCodec.marshal(cc1).getData();
            n.proposeConfChange(cc1);
            assertNotNull(applyConfChan.poll(5, TimeUnit.SECONDS), "first conf change not applied");

            // 再加入一次同一个节点
            n.proposeConfChange(cc1);
            assertNotNull(applyConfChan.poll(5, TimeUnit.SECONDS), "second conf change not applied");

            // 加入新节点应该没问题
            ConfChange cc2 = new ConfChange(ConfChangeType.ADD_NODE, 2, new byte[0]);
            byte[] ccdata2 = ConfChangeCodec.marshal(cc2).getData();
            n.proposeConfChange(cc2);
            assertNotNull(applyConfChan.poll(5, TimeUnit.SECONDS), "third conf change not applied");

            stopped.set(true);
            worker.join(2000);

            assertEquals(4, allCommittedEntries.size(), "committed entries");
            assertArrayEquals(ccdata1, allCommittedEntries.get(1).getData());
            assertArrayEquals(ccdata2, allCommittedEntries.get(3).getData());
        } finally {
            n.stop();
        }
    }

    /**
     * Go: TestNodeProposeAddLearnerNode
     *
     * <p>加入 learner 不会改变投票成员。</p>
     */
    @Test
    void nodeProposeAddLearnerNode() throws Exception {
        MemoryStorage s = RaftTestSupport.newTestMemoryStorage(1);
        RawNode rn = new RawNode(RaftTestSupport.newTestConfig(1, 10, 1, s));
        Node n = new Node(rn);
        try {
            n.campaign();
            CountDownLatch applied = new CountDownLatch(1);
            AtomicBoolean stopped = new AtomicBoolean();
            Thread worker = new Thread(() -> {
                while (!stopped.get()) {
                    try {
                        Ready rd = n.ready(50, TimeUnit.MILLISECONDS);
                        if (rd == null) {
                            continue;
                        }
                        s.append(rd.getEntries());
                        for (Entry ent : rd.getEntries()) {
                            if (ent.getType() != EntryType.CONFIG_CHANGE) {
                                continue;
                            }
                            ConfChangeI cc = ConfChangeCodec.unmarshal(ent.getType(), ent.getData());
                            ConfState state = n.applyConfChange(cc);
                            long nodeId = cc.asV1().getNodeId();
                            assertFalse(state.getLearners().isEmpty(), "learners");
                            assertEquals(nodeId, (long) state.getLearners().get(0));
                            assertEquals(2, nodeId);
                            assertEquals(1, state.getVoters().size(), "voters");
                            applied.countDown();
                        }
                        n.advance();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "raft-node-test-consumer");
            worker.setDaemon(true);
            worker.start();

            ConfChange cc = new ConfChange(ConfChangeType.ADD_LEARNER_NODE, 2, new byte[0]);
            n.proposeConfChange(cc);
            assertTrue(applied.await(5, TimeUnit.SECONDS), "learner conf change not applied");
            stopped.set(true);
            worker.join(2000);
        } finally {
            n.stop();
        }
    }

    /**
     * Go: TestDisableProposalForwarding
     *
     * <p>打开 {@code disableProposalForwarding} 之后 follower 不再转发提案。</p>
     */
    @Test
    void disableProposalForwarding() {
        Raft r1 = RaftTestSupport.newTestRaft(1, 10, 1, RaftTestSupport.newTestMemoryStorage(1, 2, 3));
        Raft r2 = RaftTestSupport.newTestRaft(2, 10, 1, RaftTestSupport.newTestMemoryStorage(1, 2, 3));
        RaftConfig cfg3 = RaftTestSupport.newTestConfig(3, 10, 1,
                RaftTestSupport.newTestMemoryStorage(1, 2, 3));
        cfg3.disableProposalForwarding = true;
        Raft r3 = new Raft(cfg3);
        RaftNetwork nt = RaftNetwork.newNetwork(r1, r2, r3);

        // 选 r1 当 leader
        nt.send(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_HUP));

        List<Entry> testEntries = Collections.singletonList(
                new Entry(0, 0, EntryType.NORMAL, "testdata".getBytes()));

        // r2 没有关闭转发，提案要转发给 leader
        r2.step(new Message().setFrom(2).setTo(2).setType(MessageType.MSG_PROP)
                .setEntries(testEntries));
        assertEquals(1, r2.getMessages().size(), "r2 should forward the proposal");

        // r3 关闭了转发，提案不转发
        r3.step(new Message().setFrom(3).setTo(3).setType(MessageType.MSG_PROP)
                .setEntries(testEntries));
        assertTrue(r3.getMessages().isEmpty(), "r3 should not forward the proposal");
    }

    /**
     * Go: TestNodeReadIndexToOldLeader
     *
     * <p>发给旧 leader 的读请求要转发给新 leader，并且转发时不带任期。</p>
     */
    @Test
    void nodeReadIndexToOldLeader() {
        Raft r1 = RaftTestSupport.newTestRaft(1, 10, 1, RaftTestSupport.newTestMemoryStorage(1, 2, 3));
        Raft r2 = RaftTestSupport.newTestRaft(2, 10, 1, RaftTestSupport.newTestMemoryStorage(1, 2, 3));
        Raft r3 = RaftTestSupport.newTestRaft(3, 10, 1, RaftTestSupport.newTestMemoryStorage(1, 2, 3));

        RaftNetwork nt = RaftNetwork.newNetwork(r1, r2, r3);

        // 选 r1 当 leader
        nt.send(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_HUP));

        List<Entry> testEntries = Collections.singletonList(
                new Entry(0, 0, EntryType.NORMAL, "testdata".getBytes()));

        // 给 follower r2 发读请求
        r2.step(new Message().setFrom(2).setTo(2).setType(MessageType.MSG_READ_INDEX)
                .setEntries(testEntries));
        assertEquals(1, r2.getMessages().size(), "r2 should forward the read index request");
        Message readIndxMsg1 = new Message().setFrom(2).setTo(1).setType(MessageType.MSG_READ_INDEX)
                .setEntries(testEntries);
        assertEquals(readIndxMsg1, r2.getMessages().get(0));

        // 给 follower r3 发读请求
        r3.step(new Message().setFrom(3).setTo(3).setType(MessageType.MSG_READ_INDEX)
                .setEntries(testEntries));
        assertEquals(1, r3.getMessages().size(), "r3 should forward the read index request");
        Message readIndxMsg2 = new Message().setFrom(3).setTo(1).setType(MessageType.MSG_READ_INDEX)
                .setEntries(testEntries);
        assertEquals(readIndxMsg2, r3.getMessages().get(0));

        // 现在选 r3 当 leader
        nt.send(new Message().setFrom(3).setTo(3).setType(MessageType.MSG_HUP));

        // 让 r1 处理刚才收到的两条消息
        r1.step(readIndxMsg1);
        r1.step(readIndxMsg2);

        // r1 现在是 follower，要再转发给新 leader
        assertEquals(2, r1.getMessages().size(), "r1 should forward the read index requests");
        Message readIndxMsg3 = new Message().setFrom(2).setTo(3).setType(MessageType.MSG_READ_INDEX)
                .setEntries(testEntries);
        assertEquals(readIndxMsg3, r1.getMessages().get(0));
        readIndxMsg3 = new Message().setFrom(3).setTo(3).setType(MessageType.MSG_READ_INDEX)
                .setEntries(testEntries);
        assertEquals(readIndxMsg3, r1.getMessages().get(1));
    }

    /**
     * Go: TestSoftStateEqual
     */
    @Test
    void softStateEqual() {
        assertEquals(new SoftState(0, null), new SoftState(0, null));
        assertNotEquals(new SoftState(1, null), new SoftState(0, null));
        assertNotEquals(new SoftState(0, RaftRole.LEADER), new SoftState(0, null));
    }

    /**
     * Go: TestIsHardStateEqual
     */
    @Test
    void isHardStateEqual() {
        HardState empty = new HardState(0, 0, 0);
        assertTrue(RaftUtil.isHardStateEqual(empty, new HardState(0, 0, 0)));
        assertFalse(RaftUtil.isHardStateEqual(new HardState(0, 1, 0), empty));
        assertFalse(RaftUtil.isHardStateEqual(new HardState(0, 0, 1), empty));
        assertFalse(RaftUtil.isHardStateEqual(new HardState(1, 0, 0), empty));
    }

    /**
     * Go: TestAppendPagination
     *
     * <p>追加消息不能超过单条消息的字节上限。</p>
     */
    @Test
    void appendPagination() {
        long maxSizePerMsg = 2048;
        RaftNetwork n = RaftNetwork.newNetworkWithConfig(cfg -> cfg.maxSizePerMsg = maxSizePerMsg,
                null, null, null);

        boolean[] seenFullMessage = {false};
        n.msgHook = m -> {
            if (m.getType() == MessageType.MSG_APP) {
                long size = 0;
                for (Entry e : m.getEntries()) {
                    size += e.getData().length;
                }
                assertTrue(size <= maxSizePerMsg, "sent MsgApp that is too large");
                if (size > maxSizePerMsg / 2) {
                    seenFullMessage[0] = true;
                }
            }
            return true;
        };

        n.send(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_HUP));

        // 网络分区期间提案，强制把日志攒成更大的消息
        n.isolate(1);
        byte[] blob = new byte[1000];
        Arrays.fill(blob, (byte) 'a');
        for (int i = 0; i < 5; i++) {
            n.send(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_PROP)
                    .setEntries(Collections.singletonList(new Entry(0, 0, EntryType.NORMAL, blob))));
        }
        n.recover();

        // 分区恢复之后把消息发出去
        n.send(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_BEAT));
        assertTrue(seenFullMessage[0],
                "didn't see any messages more than half the max size; something is wrong with this test");
    }

    /**
     * Go: TestCommitPagination
     *
     * <p>一次 Ready 里的已提交条目不能超过字节上限。</p>
     */
    @Test
    void commitPagination() throws Exception {
        MemoryStorage s = RaftTestSupport.newTestMemoryStorage(1);
        RaftConfig cfg = RaftTestSupport.newTestConfig(1, 10, 1, s);
        cfg.maxCommittedSizePerReady = 2048;
        Node n = newNode(cfg);
        try {
            n.campaign();

            // 持久化投票
            Ready rd = readyWithTimeout(n);
            s.append(rd.getEntries());
            n.advance();
            // 追加空条目
            rd = readyWithTimeout(n);
            s.append(rd.getEntries());
            n.advance();
            // 应用空条目
            rd = readyWithTimeout(n);
            assertEquals(1, rd.getCommittedEntries().size(), "committed entries");

            s.append(rd.getEntries());
            n.advance();

            byte[] blob = new byte[1000];
            Arrays.fill(blob, (byte) 'a');
            for (int i = 0; i < 3; i++) {
                n.propose(blob);
            }

            // 三个提案先要被追加
            rd = readyWithTimeout(n);
            assertEquals(3, rd.getEntries().size(), "entries");

            s.append(rd.getEntries());
            n.advance();

            // 三个提案分两批提交
            rd = readyWithTimeout(n);
            assertEquals(2, rd.getCommittedEntries().size(), "committed entries");

            s.append(rd.getEntries());
            n.advance();
            rd = readyWithTimeout(n);
            assertEquals(1, rd.getCommittedEntries().size(), "committed entries");

            s.append(rd.getEntries());
            n.advance();
        } finally {
            n.stop();
        }
    }

    /**
     * Go: TestNodeCommitPaginationAfterRestart
     *
     * <p>回归测试：存储实现对大小的限制比 raft 内部宽松时，重启之后不能出现
     * commit index 倒退的硬状态。</p>
     */
    @Test
    void nodeCommitPaginationAfterRestart() throws Exception {
        List<Entry> ents = new ArrayList<>();
        long size = 0;
        for (int i = 0; i < 10; i++) {
            Entry ent = new Entry(i + 1, 1, EntryType.NORMAL, new byte[] {'a'});
            ents.add(ent);
            size += RaftCodec.entrySize(ent);
        }
        IgnoreSizeHintMemoryStorage s = new IgnoreSizeHintMemoryStorage(ents);
        HardState persistedHardState = new HardState(1, 1, 10);
        s.setHardState(persistedHardState);

        RaftConfig cfg = RaftTestSupport.newTestConfig(1, 10, 1, s);
        // 设一个比最后一条已提交日志还小的上限，让 raft 以为最后一条不该出现在
        // 第一个 Ready 里；但存储会忽略这个上限，还是把最后一条返回出来
        cfg.maxSizePerMsg = size - RaftCodec.entrySize(ents.get(ents.size() - 1)) - 1;

        Node n = newNode(cfg);
        try {
            Ready rd = readyWithTimeout(n);
            // 只有在硬状态发生变化时才会带上它，出现变化就意味着 commit index 可能倒退
            HardState hs = rd.getHardState();
            assertFalse(hs != null && !RaftUtil.isEmptyHardState(hs)
                            && hs.getCommit() < persistedHardState.getCommit(),
                    "HardState regressed: Commit " + persistedHardState.getCommit()
                            + " -> " + (hs == null ? "none" : hs.getCommit()));
        } finally {
            n.stop();
        }
    }

    /**
     * 比较一个 Ready 的关键字段。
     */
    private static void assertReadyEquals(HardState hardState, List<Entry> entries,
            List<Entry> committedEntries, boolean mustSync, Ready rd) {
        assertEquals(hardState, rd.getHardState(), "hard state");
        RaftTestSupport.assertEqualEntries(entries, rd.getEntries());
        RaftTestSupport.assertEqualEntries(committedEntries, rd.getCommittedEntries());
        assertEquals(mustSync, rd.isMustSync(), "must sync");
    }

    /**
     * 比较两批条目。
     */
    private static void assertEqualEntries(List<Entry> expected, List<Entry> actual) {
        RaftTestSupport.assertEqualEntries(expected, actual);
    }

    /**
     * Go: 把 {@code r.step} 换成捕获消息的函数。
     *
     * <p>etcd 里 {@code raft.step} 是一个可替换的函数字段，
     * {@code TestNodePropose} 靠替换它来观察状态机实际处理了哪些消息。
     * Java 侧对应的是 {@link Raft#stepStateMachine(Message)}。</p>
     */
    private static final class CapturingRaft extends Raft {

        /** 捕获到的消息 */
        private final List<Message> msgs = new CopyOnWriteArrayList<>();

        /** 是否开始捕获 */
        private volatile boolean capture;

        /** 含有这个标记的提案要被丢弃 */
        private volatile String dropMarker;

        CapturingRaft(RaftConfig cfg) {
            super(cfg);
        }

        @Override
        boolean stepStateMachine(Message m) {
            if (!capture) {
                return super.stepStateMachine(m);
            }
            if (m.getType() == MessageType.MSG_PROP && dropMarker != null
                    && m.getEntries() != null && !m.getEntries().isEmpty()
                    && contains(m.getEntries().get(0).getData(), dropMarker)) {
                // 模拟 raft 丢弃提案
                return false;
            }
            if (m.getType() == MessageType.MSG_APP_RESP) {
                // 这个消息是 raft 内部产生的，见 (*raft).advance
                return true;
            }
            msgs.add(m);
            return true;
        }

        private static boolean contains(byte[] data, String marker) {
            if (data == null) {
                return false;
            }
            String s = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            return s.contains(marker);
        }
    }
}
