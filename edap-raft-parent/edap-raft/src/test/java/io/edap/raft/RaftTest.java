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

import io.edap.raft.log.RaftLog;
import io.edap.raft.protocol.ConfChange;
import io.edap.raft.protocol.ConfChangeCodec;
import io.edap.raft.protocol.ConfChangeSingle;
import io.edap.raft.protocol.ConfChangeTransition;
import io.edap.raft.protocol.ConfChangeType;
import io.edap.raft.protocol.ConfChangeV2;
import io.edap.raft.protocol.ConfState;
import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.EntryType;
import io.edap.raft.protocol.HardState;
import io.edap.raft.protocol.Message;
import io.edap.raft.protocol.MessageType;
import io.edap.raft.protocol.Snapshot;
import io.edap.raft.protocol.SnapshotMetadata;
import io.edap.raft.storage.MemoryStorage;
import io.edap.raft.tracker.Progress;
import io.edap.raft.tracker.State;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static io.edap.raft.RaftTestSupport.advanceMessagesAfterAppend;
import static io.edap.raft.RaftTestSupport.diffLogs;
import static io.edap.raft.RaftTestSupport.entsWithConfig;
import static io.edap.raft.RaftTestSupport.expectOneMessage;
import static io.edap.raft.RaftTestSupport.newLog;
import static io.edap.raft.RaftTestSupport.newTestConfig;
import static io.edap.raft.RaftTestSupport.newTestLearnerRaft;
import static io.edap.raft.RaftTestSupport.newTestMemoryStorage;
import static io.edap.raft.RaftTestSupport.newTestMemoryStorageWithLearners;
import static io.edap.raft.RaftTestSupport.newTestRaft;
import static io.edap.raft.RaftTestSupport.mustAppendEntry;
import static io.edap.raft.RaftTestSupport.nextEnts;
import static io.edap.raft.RaftTestSupport.readMessages;
import static io.edap.raft.RaftTestSupport.stepOrSend;
import static io.edap.raft.RaftTestSupport.takeMessagesAfterAppend;
import static io.edap.raft.RaftTestSupport.votedWithConfig;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * etcd {@code raft_test.go} 的 Java 实现。
 *
 * <p>Go 里的 {@code newNetwork}、{@code nopStepper}、{@code entsWithConfig}
 * 这些脚手架分别对应 {@link RaftNetwork} 与 {@link RaftTestSupport}，
 * 方法名尽量与 Go 保持一致，便于逐条对照。</p>
 */
public class RaftTest {

    // ------------------------------------------------------------------
    // 复制进度
    // ------------------------------------------------------------------

    /**
     * Go: TestProgressLeader
     */
    @Test
    // Go: TestProgressLeader
    void testProgressLeader() {
        MemoryStorage s = newTestMemoryStorage(1, 2);
        Raft r = newTestRaft(1, 5, 1, s);
        r.becomeCandidate();
        r.becomeLeader();
        r.getTracker().getProgress().get(2).becomeReplicate();

        // 给 r1 提 5 个提案，这 5 条日志都排在未落盘队列里
        Message propMsg = prop(1, 1, "foo");
        for (int i = 0; i < 5; i++) {
            assertTrue(r.step(propMsg), "#" + i);
        }

        assertEquals(0, r.getTracker().getProgress().get(1).getMatch());

        List<Entry> ents = r.getRaftLog().nextUnstableEnts();
        assertEquals(6, ents.size());
        assertEquals(0, dataLength(ents.get(0)));
        assertEquals("foo", new String(ents.get(5).getData()));

        advanceMessagesAfterAppend(r);

        assertEquals(6, r.getTracker().getProgress().get(1).getMatch());
        assertEquals(7, r.getTracker().getProgress().get(1).getNext());
    }

    /**
     * Go: TestProgressResumeByHeartbeatResp
     *
     * <p>心跳的回复会重置 {@code progress.paused}。</p>
     */
    @Test
    // Go: TestProgressResumeByHeartbeatResp
    void testProgressResumeByHeartbeatResp() {
        Raft r = newTestRaft(1, 5, 1, newTestMemoryStorage(1, 2));
        r.becomeCandidate();
        r.becomeLeader();

        r.getTracker().getProgress().get(2).setMsgAppFlowPaused(true);

        r.step(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_BEAT));
        assertTrue(r.getTracker().getProgress().get(2).isMsgAppFlowPaused());

        r.getTracker().getProgress().get(2).becomeReplicate();
        assertFalse(r.getTracker().getProgress().get(2).isMsgAppFlowPaused());
        r.getTracker().getProgress().get(2).setMsgAppFlowPaused(true);
        r.step(new Message().setFrom(2).setTo(1).setType(MessageType.MSG_HEARTBEAT_RESP));
        assertFalse(r.getTracker().getProgress().get(2).isMsgAppFlowPaused());
    }

    /**
     * Go: TestProgressPaused
     */
    @Test
    // Go: TestProgressPaused
    void testProgressPaused() {
        Raft r = newTestRaft(1, 5, 1, newTestMemoryStorage(1, 2));
        r.becomeCandidate();
        r.becomeLeader();
        r.step(prop(1, 1, "somedata"));
        r.step(prop(1, 1, "somedata"));
        r.step(prop(1, 1, "somedata"));

        List<Message> ms = readMessages(r);
        assertEquals(1, ms.size());
    }

    /**
     * Go: TestProgressFlowControl
     */
    @Test
    // Go: TestProgressFlowControl
    void testProgressFlowControl() {
        RaftConfig cfg = newTestConfig(1, 5, 1, newTestMemoryStorage(1, 2));
        cfg.maxInflightMsgs = 3;
        cfg.maxSizePerMsg = 2048;
        // 比 MaxInflightMsgs * MaxSizePerMsg 稍微大一点
        cfg.maxInflightBytes = 9000;
        Raft r = new Raft(cfg);
        r.becomeCandidate();
        r.becomeLeader();

        // 把选举期间的消息全部丢掉
        readMessages(r);

        // 节点 2 处于探测状态，这时提一堆日志
        r.getTracker().getProgress().get(2).becomeProbe();
        byte[] blob = filled(1000, (byte) 'a');
        byte[] large = filled(5000, (byte) 'b');
        for (int i = 0; i < 22; i++) {
            // 中间几条临时用大消息
            byte[] data = (i >= 10 && i < 16) ? large : blob;
            r.step(prop(1, 1, data));
        }

        List<Message> ms = readMessages(r);
        // 第一条追加消息里有两条日志：确认选举的空日志，以及第一个提案
        // （探测状态下一次只发一个提案）
        assertEquals(1, ms.size());
        assertEquals(MessageType.MSG_APP, ms.get(0).getType());

        assertEquals(2, ms.get(0).getEntries().size());

        assertEquals(0, dataLength(ms.get(0).getEntries().get(0)));
        assertEquals(1000, ms.get(0).getEntries().get(1).getData().length);

        // 这次追加被确认后就切到复制状态，可以一次发多条消息了
        long index = ackAndVerify(r, ms.get(0).getEntries().get(1).getIndex(), 2, 2, 2);
        // 三条一起被确认，换来另外三条消息。第三条里只有一条大日志，
        // 跟之前的每条两条不一样
        index = ackAndVerify(r, index, 2, 1, 1);
        // 后面的消息都只有一条大日志，而且因为超出了 MaxInflightBytes
        // 所以每次只发两条
        index = ackAndVerify(r, index, 1, 1);
        index = ackAndVerify(r, index, 1, 1);
        // 又开始发小消息
        index = ackAndVerify(r, index, 1, 2, 2);
        ackAndVerify(r, index, 2);
    }

    /**
     * Go: TestUncommittedEntryLimit
     */
    @Test
    // Go: TestUncommittedEntryLimit
    void testUncommittedEntryLimit() {
        // 这里用相对多的条目，防止「大小在修复前计算」的旧 bug 复现
        int maxEntries = 1024;
        Entry testEntry = entry(0, 0, "testdata");
        long maxEntrySize = maxEntries * Raft.payloadsSize(Collections.singletonList(testEntry));

        assertEquals(0, Raft.payloadsSize(Collections.singletonList(emptyEntry())));

        RaftConfig cfg = newTestConfig(1, 5, 1, newTestMemoryStorage(1, 2, 3));
        cfg.maxUncommittedEntriesSize = maxEntrySize;
        // 避免在途窗口的影响
        cfg.maxInflightMsgs = 2 * 1024;
        Raft r = new Raft(cfg);
        r.becomeCandidate();
        r.becomeLeader();
        assertEquals(0, r.getUncommittedSize());

        // 把两个 follower 都切到复制状态，日志尾部就是已提交的位置
        r.getTracker().getProgress().get(2).becomeReplicate();
        r.getTracker().getProgress().get(3).becomeReplicate();

        // 给 r1 提 maxEntries 个提案
        Message propMsg = prop(1, 1, testEntry);
        List<Entry> propEnts = new ArrayList<>();
        for (int i = 0; i < maxEntries; i++) {
            assertTrue(r.step(propMsg), "#" + i);
            propEnts.add(testEntry);
        }

        // 再提一个应该被拒绝
        assertFalse(r.step(propMsg));

        // 读出消息，并把这些日志当作已提交，抵消掉未提交大小的记账
        List<Message> ms = readMessages(r);
        assertEquals(maxEntries * 2, ms.size());
        r.reduceUncommittedSize(Raft.payloadsSize(propEnts));
        assertEquals(0, r.getUncommittedSize());

        // 一次提一大批：虽然在上限之上，但之前没超过上限，所以允许
        propEnts = new ArrayList<>();
        for (int i = 0; i < 2 * maxEntries; i++) {
            propEnts.add(testEntry);
        }
        Message propMsgLarge = prop(1, 1, propEnts);
        assertTrue(r.step(propMsgLarge));

        // 再提一个应该又被拒绝
        assertFalse(r.step(propMsg));

        // 但空日志总是可以追加：leader 的第一条空日志、以及自动退出联合配置时
        // 追加的空日志都用它
        assertTrue(r.step(prop(1, 1, emptyEntry())));

        ms = readMessages(r);
        assertEquals(2 * 2, ms.size());
        r.reduceUncommittedSize(Raft.payloadsSize(propEnts));
        assertEquals(0, r.getUncommittedSize());
    }

    // ------------------------------------------------------------------
    // 选举
    // ------------------------------------------------------------------

    /**
     * Go: TestLeaderElection
     */
    @Test
    // Go: TestLeaderElection
    void testLeaderElection() {
        testLeaderElection(false);
    }

    /**
     * Go: TestLeaderElectionPreVote
     */
    @Test
    // Go: TestLeaderElectionPreVote
    void testLeaderElectionPreVote() {
        testLeaderElection(true);
    }

    /**
     * Go: testLeaderElection
     */
    private static void testLeaderElection(boolean preVote) {
        RaftNetwork.ConfigFunc cfg = preVote ? RaftNetwork::preVoteConfig : null;
        // 预投票模式下，选��没成功时任期不会往前推，节点停在预候选状态
        RaftRole candState = preVote ? RaftRole.PRE_CANDIDATE : RaftRole.CANDIDATE;
        long candTerm = preVote ? 0 : 1;

        RaftNetwork[] networks = new RaftNetwork[]{
                networkWithConfig(cfg, null, null, null),
                networkWithConfig(cfg, null, null, new RaftNetwork.BlackHole()),
                networkWithConfig(cfg, null, new RaftNetwork.BlackHole(), new RaftNetwork.BlackHole()),
                networkWithConfig(cfg, null, new RaftNetwork.BlackHole(), new RaftNetwork.BlackHole(), null),
                networkWithConfig(cfg, null, new RaftNetwork.BlackHole(), new RaftNetwork.BlackHole(), null, null),
                // 三个节点的日志都比 0 新，但任期相同，所以会返回拒绝而不是忽略投票
                networkWithConfig(cfg, null, entsWithConfig(cfg, 1), entsWithConfig(cfg, 1),
                        entsWithConfig(cfg, 1, 1), null),
        };
        RaftRole[] states = new RaftRole[]{
                RaftRole.LEADER, RaftRole.LEADER, candState, candState, RaftRole.LEADER, RaftRole.FOLLOWER,
        };
        long[] terms = new long[]{1, 1, candTerm, candTerm, 1, 1};

        for (int i = 0; i < networks.length; i++) {
            networks[i].send(hup(1, 1));
            Raft sm = networks[i].getRaft(1);
            assertEquals(states[i], sm.getState(), "#" + i);
            assertEquals(terms[i], sm.getTerm(), "#" + i);
        }
    }

    /**
     * Go: TestLearnerElectionTimeout
     *
     * <p>learner 即使超时也不应该发起选举。</p>
     */
    @Test
    // Go: TestLearnerElectionTimeout
    void testLearnerElectionTimeout() {
        Raft n1 = newTestLearnerRaft(1, 10, 1, newTestMemoryStorageWithLearners(new long[]{1}, new long[]{2}));
        Raft n2 = newTestLearnerRaft(2, 10, 1, newTestMemoryStorageWithLearners(new long[]{1}, new long[]{2}));

        n1.becomeFollower(1, Raft.NONE);
        n2.becomeFollower(1, Raft.NONE);

        // n2 是 learner，超时了也不应该发起选举
        n2.setRandomizedElectionTimeout(n2.getElectionTimeout());
        for (int i = 0; i < n2.getElectionTimeout(); i++) {
            n2.tick();
        }

        assertEquals(RaftRole.FOLLOWER, n2.getState());
    }

    /**
     * Go: TestLearnerPromotion
     *
     * <p>learner 在被提升为正式成员之前不参与选举。</p>
     */
    @Test
    // Go: TestLearnerPromotion
    void testLearnerPromotion() {
        Raft n1 = newTestLearnerRaft(1, 10, 1, newTestMemoryStorageWithLearners(new long[]{1}, new long[]{2}));
        Raft n2 = newTestLearnerRaft(2, 10, 1, newTestMemoryStorageWithLearners(new long[]{1}, new long[]{2}));

        n1.becomeFollower(1, Raft.NONE);
        n2.becomeFollower(1, Raft.NONE);

        RaftNetwork nt = network(n1, n2);

        assertNotEquals(RaftRole.LEADER, n1.getState());

        // n1 应该成为 leader
        n1.setRandomizedElectionTimeout(n1.getElectionTimeout());
        for (int i = 0; i < n1.getElectionTimeout(); i++) {
            n1.tick();
        }
        advanceMessagesAfterAppend(n1);

        assertEquals(RaftRole.LEADER, n1.getState());
        assertEquals(RaftRole.FOLLOWER, n2.getState());

        nt.send(beat(1, 1));

        n1.applyConfChange(new ConfChange(ConfChangeType.ADD_NODE, 2, null).asV2());
        n2.applyConfChange(new ConfChange(ConfChangeType.ADD_NODE, 2, null).asV2());
        assertFalse(n2.isLearner());

        // n2 发起选举，应该成为 leader
        n2.setRandomizedElectionTimeout(n2.getElectionTimeout());
        for (int i = 0; i < n2.getElectionTimeout(); i++) {
            n2.tick();
        }
        advanceMessagesAfterAppend(n2);

        nt.send(beat(2, 2));

        assertEquals(RaftRole.FOLLOWER, n1.getState());
        assertEquals(RaftRole.LEADER, n2.getState());
    }

    /**
     * Go: TestLearnerCanVote
     *
     * <p>learner 收到合法的投票请求时必须投票。</p>
     */
    @Test
    // Go: TestLearnerCanVote
    void testLearnerCanVote() {
        Raft n2 = newTestLearnerRaft(2, 10, 1, newTestMemoryStorageWithLearners(new long[]{1}, new long[]{2}));

        n2.becomeFollower(1, Raft.NONE);

        n2.step(new Message().setFrom(1).setTo(2).setTerm(2)
                .setType(MessageType.MSG_VOTE).setLogTerm(11).setIndex(11));

        List<Message> msgs = readMessages(n2);
        assertEquals(1, msgs.size());
        assertEquals(MessageType.MSG_VOTE_RESP, msgs.get(0).getType());
        assertFalse(msgs.get(0).getReject(), "expected learner to not reject vote");
    }

    /**
     * Go: TestLeaderCycle
     */
    @Test
    // Go: TestLeaderCycle
    void testLeaderCycle() {
        testLeaderCycle(false);
    }

    /**
     * Go: TestLeaderCyclePreVote
     */
    @Test
    // Go: TestLeaderCyclePreVote
    void testLeaderCyclePreVote() {
        testLeaderCycle(true);
    }

    /**
     * Go: testLeaderCycle
     *
     * <p>集群里的每个节点都能依次发起竞选并当选。</p>
     */
    private static void testLeaderCycle(boolean preVote) {
        RaftNetwork.ConfigFunc cfg = preVote ? RaftNetwork::preVoteConfig : null;
        RaftNetwork n = networkWithConfig(cfg, null, null, null);
        for (long campaignerID = 1; campaignerID <= 3; campaignerID++) {
            n.send(hup(campaignerID, campaignerID));

            for (Long pid : n.peerIds()) {
                Raft sm = n.getRaft(pid);
                if (pid == campaignerID) {
                    assertEquals(RaftRole.LEADER, sm.getState(),
                            "preVote=" + preVote + ": campaigning node " + pid);
                } else {
                    assertEquals(RaftRole.FOLLOWER, sm.getState(),
                            "preVote=" + preVote + ": campaigning node " + campaignerID
                                    + ", current node " + pid);
                }
            }
        }
    }

    /**
     * Go: TestLeaderElectionOverwriteNewerLogs
     */
    @Test
    // Go: TestLeaderElectionOverwriteNewerLogs
    void testLeaderElectionOverwriteNewerLogs() {
        testLeaderElectionOverwriteNewerLogs(false);
    }

    /**
     * Go: TestLeaderElectionOverwriteNewerLogsPreVote
     */
    @Test
    // Go: TestLeaderElectionOverwriteNewerLogsPreVote
    void testLeaderElectionOverwriteNewerLogsPreVote() {
        testLeaderElectionOverwriteNewerLogs(true);
    }

    /**
     * Go: testLeaderElectionOverwriteNewerLogs
     *
     * <p>新当选的 leader 不一定拥有最新的日志，必须用低任期的日志覆盖高任期的。</p>
     */
    private static void testLeaderElectionOverwriteNewerLogs(boolean preVote) {
        RaftNetwork.ConfigFunc cfg = preVote ? RaftNetwork::preVoteConfig : null;
        // 这个网络表示下面这串事件的结果：
        // - 节点 1 在任期 1 当选；
        // - 节点 1 把一条日志复制给了节点 2，但在发给其它节点之前就挂了；
        // - 节点 3 在任期 2 当选；
        // - 节点 3 写了一条日志，但在发给任何其它节点之前就挂了。
        //
        // 这时节点 1、2、3 都有未提交的日志，都可能在任期 3 当选。
        // 当选者的日志会覆盖落选者的。
        RaftNetwork n = networkWithConfig(cfg,
                entsWithConfig(cfg, 1),      // 节点 1：第一次选举当选
                entsWithConfig(cfg, 1),      // 节点 2：从节点 1 收到了日志
                entsWithConfig(cfg, 2),      // 节点 3：第二次选举当选
                votedWithConfig(cfg, 3, 2),  // 节点 4：投过票但没收到日志
                votedWithConfig(cfg, 3, 2)); // 节点 5：投过票但没收到日志

        // 节点 1 发起竞选。选举失败，因为多数节点知道任期 2 已经发生过选举。
        // 节点 1 的任期被推到 2。
        n.send(hup(1, 1));
        Raft sm1 = n.getRaft(1);
        assertEquals(RaftRole.FOLLOWER, sm1.getState());
        assertEquals(2, sm1.getTerm());

        // 节点 1 用更高的任期再竞选一次，这次成功了
        n.send(hup(1, 1));
        assertEquals(RaftRole.LEADER, sm1.getState());
        assertEquals(3, sm1.getTerm());

        // 现在所有节点都同意 index 1 是任期 1，index 2 是任期 3
        for (Long pid : n.peerIds()) {
            Raft sm = n.getRaft(pid);
            List<Entry> entries = sm.getRaftLog().allEntries();
            assertEquals(2, entries.size());
            assertEquals(1, entries.get(0).getTerm());
            assertEquals(3, entries.get(1).getTerm());
        }
    }

    /**
     * Go: TestVoteFromAnyState
     */
    @Test
    // Go: TestVoteFromAnyState
    void testVoteFromAnyState() {
        testVoteFromAnyState(MessageType.MSG_VOTE);
    }

    /**
     * Go: TestPreVoteFromAnyState
     */
    @Test
    // Go: TestPreVoteFromAnyState
    void testPreVoteFromAnyState() {
        testVoteFromAnyState(MessageType.MSG_PRE_VOTE);
    }

    /**
     * Go: testVoteFromAnyState
     */
    private static void testVoteFromAnyState(MessageType vt) {
        for (RaftRole st : RaftRole.values()) {
            Raft r = newTestRaft(1, 10, 1, newTestMemoryStorage(1, 2, 3));
            r.setTermForTest(1);

            switch (st) {
                case FOLLOWER:
                    r.becomeFollower(r.getTerm(), 3);
                    break;
                case PRE_CANDIDATE:
                    r.becomePreCandidate();
                    break;
                case CANDIDATE:
                    r.becomeCandidate();
                    break;
                case LEADER:
                    r.becomeCandidate();
                    r.becomeLeader();
                    break;
                default:
                    break;
            }

            // 上面的状态切换可能已经把任期往前推了
            long origTerm = r.getTerm();
            long newTerm = r.getTerm() + 1;

            Message msg = new Message().setFrom(2).setTo(1).setType(vt)
                    .setTerm(newTerm).setLogTerm(newTerm).setIndex(42);
            assertTrue(r.step(msg), vt + "," + st);
            List<Message> msgs = readMessages(r);
            assertEquals(1, msgs.size(), vt + "," + st);
            Message resp = msgs.get(0);
            assertEquals(voteRespMsgType(vt), resp.getType(), vt + "," + st);
            assertFalse(resp.getReject(), vt + "," + st);

            // 真正的投票会重置自己的状态与任期
            if (vt == MessageType.MSG_VOTE) {
                assertEquals(RaftRole.FOLLOWER, r.getState(), vt + "," + st);
                assertEquals(newTerm, r.getTerm(), vt + "," + st);
                assertEquals(2, r.getVote(), vt + "," + st);
            } else {
                // 预投票什么都不变
                assertEquals(st, r.getState(), vt + "," + st);
                assertEquals(origTerm, r.getTerm(), vt + "," + st);
                // follower / 预候选者还没投过票，candidate / leader 投给了自己
                assertTrue(r.getVote() == Raft.NONE || r.getVote() == 1,
                        vt + "," + st + ": vote " + r.getVote() + ", want " + Raft.NONE + " or 1");
            }
        }
    }

    // ------------------------------------------------------------------
    // 日志复制
    // ------------------------------------------------------------------

    /**
     * Go: TestLogReplication
     */
    @Test
    // Go: TestLogReplication
    void testLogReplication() {
        RaftNetwork[] networks = new RaftNetwork[]{
                network(null, null, null),
                network(null, null, null),
        };
        Message[][] msgs = new Message[][]{
                {prop(1, 1, "somedata")},
                {prop(1, 1, "somedata"), hup(1, 2), prop(1, 2, "somedata")},
        };
        long[] wcommitted = new long[]{2, 4};

        for (int i = 0; i < networks.length; i++) {
            RaftNetwork tt = networks[i];
            tt.send(hup(1, 1));

            for (Message m : msgs[i]) {
                tt.send(m);
            }

            for (Long j : tt.peerIds()) {
                Raft sm = tt.getRaft(j);

                assertEquals(wcommitted[i], sm.getRaftLog().committed(), "#" + i + "." + j);

                List<Entry> ents = new ArrayList<>();
                for (Entry e : nextEnts(sm, tt.getStorage(j))) {
                    // Go 里判断的是 e.GetData() != nil，用来跳过确认选举的那条空日志；
                    // Java 的 Entry 把 null 负载统一成空数组，所以这里按长度判断
                    if (dataLength(e) > 0) {
                        ents.add(e);
                    }
                }
                List<Message> props = new ArrayList<>();
                for (Message m : msgs[i]) {
                    if (m.getType() == MessageType.MSG_PROP) {
                        props.add(m);
                    }
                }
                for (int k = 0; k < props.size(); k++) {
                    assertArrayEquals(props.get(k).getEntries().get(0).getData(), ents.get(k).getData(),
                            "#" + i + "." + j);
                }
            }
        }
    }

    /**
     * Go: TestLearnerLogReplication
     */
    @Test
    // Go: TestLearnerLogReplication
    void testLearnerLogReplication() {
        MemoryStorage s1 = newTestMemoryStorageWithLearners(new long[]{1}, new long[]{2});
        Raft n1 = newTestLearnerRaft(1, 10, 1, s1);
        Raft n2 = newTestLearnerRaft(2, 10, 1, newTestMemoryStorageWithLearners(new long[]{1}, new long[]{2}));

        RaftNetwork nt = network(n1, n2);

        n1.becomeFollower(1, Raft.NONE);
        n2.becomeFollower(1, Raft.NONE);

        n1.setRandomizedElectionTimeout(n1.getElectionTimeout());
        for (int i = 0; i < n1.getElectionTimeout(); i++) {
            n1.tick();
        }
        advanceMessagesAfterAppend(n1);

        nt.send(beat(1, 1));

        // n1 是 leader，n2 是 learner
        assertEquals(RaftRole.LEADER, n1.getState());
        assertTrue(n2.isLearner());

        long nextCommitted = 2;
        nt.send(prop(1, 1, "somedata"));

        assertEquals(nextCommitted, n1.getRaftLog().committed());
        assertEquals(n1.getRaftLog().committed(), n2.getRaftLog().committed());

        long match = n1.getTracker().getProgress().get(2).getMatch();
        assertEquals(n2.getRaftLog().committed(), match);
    }

    /**
     * Go: TestSingleNodeCommit
     */
    @Test
    // Go: TestSingleNodeCommit
    void testSingleNodeCommit() {
        MemoryStorage s = newTestMemoryStorage(1);
        RaftConfig cfg = newTestConfig(1, 10, 1, s);
        Raft r = new Raft(cfg);
        RaftNetwork tt = network(r);
        tt.send(hup(1, 1));
        tt.send(prop(1, 1, "some data"));
        tt.send(prop(1, 1, "some data"));

        Raft sm = tt.getRaft(1);
        assertEquals(3, sm.getRaftLog().committed());
    }

    /**
     * Go: TestCannotCommitWithoutNewTermEntry
     */
    @Test
    // Go: TestCannotCommitWithoutNewTermEntry
    void testCannotCommitWithoutNewTermEntry() {
        RaftNetwork tt = network(null, null, null, null, null);
        tt.send(hup(1, 1));

        // 0 连不上 2、3、4
        tt.cut(1, 3);
        tt.cut(1, 4);
        tt.cut(1, 5);

        tt.send(prop(1, 1, "some data"));
        tt.send(prop(1, 1, "some data"));

        Raft sm = tt.getRaft(1);
        assertEquals(1, sm.getRaftLog().committed());

        // 网络恢复
        tt.recover();
        // 避免提交掉换任期的提案
        tt.ignore(MessageType.MSG_APP);

        // 选 2 当新 leader，任期是 2
        tt.send(hup(2, 2));

        // 上一任期的日志不应该被提交
        sm = tt.getRaft(2);
        assertEquals(1, sm.getRaftLog().committed());

        tt.recover();
        // 发心跳，重置等待
        tt.send(beat(2, 2));
        // 在当前任期追加一条日志
        tt.send(prop(2, 2, "some data"));
        // 这时提交位置应该往前推
        assertEquals(5, sm.getRaftLog().committed());
    }

    /**
     * Go: TestCommitWithoutNewTermEntry
     */
    @Test
    // Go: TestCommitWithoutNewTermEntry
    void testCommitWithoutNewTermEntry() {
        RaftNetwork tt = network(null, null, null, null, null);
        tt.send(hup(1, 1));

        // 0 连不上 3、4、5
        tt.cut(1, 3);
        tt.cut(1, 4);
        tt.cut(1, 5);

        tt.send(prop(1, 1, "some data"));
        tt.send(prop(1, 1, "some data"));

        Raft sm = tt.getRaft(1);
        assertEquals(1, sm.getRaftLog().committed());

        // 网络恢复
        tt.recover();

        // 选 2 当新 leader，任期是 2
        // 在当前任期追加一条日志后，所有日志都应该被提交
        tt.send(hup(2, 2));

        assertEquals(4, sm.getRaftLog().committed());
    }

    /**
     * Go: TestDuelingCandidates
     */
    @Test
    // Go: TestDuelingCandidates
    void testDuelingCandidates() {
        MemoryStorage s1 = newTestMemoryStorage(1, 2, 3);
        MemoryStorage s2 = newTestMemoryStorage(1, 2, 3);
        MemoryStorage s3 = newTestMemoryStorage(1, 2, 3);
        Raft a = newTestRaft(1, 10, 1, s1);
        Raft b = newTestRaft(2, 10, 1, s2);
        Raft c = newTestRaft(3, 10, 1, s3);

        RaftNetwork nt = network(a, b, c);
        nt.cut(1, 3);

        nt.send(hup(1, 1));
        nt.send(hup(3, 3));

        // 1 收到 1 和 2 的票，成为 leader
        Raft sm = nt.getRaft(1);
        assertEquals(RaftRole.LEADER, sm.getState());

        // 3 只收到自己的票和 2 的拒绝票，仍然是候选者
        sm = nt.getRaft(3);
        assertEquals(RaftRole.CANDIDATE, sm.getState());

        nt.recover();

        // 候选者 3 提高任期再竞选一次。
        // 由于 3 的日志不够长，1 和 2 都会拒绝它，所以 3 会退回 follower
        nt.send(hup(3, 3));
        assertEquals(RaftRole.FOLLOWER, sm.getState());

        Raft[] sms = new Raft[]{a, b, c};
        RaftRole[] states = new RaftRole[]{RaftRole.FOLLOWER, RaftRole.FOLLOWER, RaftRole.FOLLOWER};
        long[] terms = new long[]{2, 2, 2};
        long[] lastIndexes = new long[]{1, 1, 0};

        for (int i = 0; i < sms.length; i++) {
            assertEquals(states[i], sms[i].getState(), "#" + i);
            assertEquals(terms[i], sms[i].getTerm(), "#" + i);
            assertEquals(lastIndexes[i], sms[i].getRaftLog().lastIndex(), "#" + i);
        }
    }

    /**
     * Go: TestDuelingPreCandidates
     */
    @Test
    // Go: TestDuelingPreCandidates
    void testDuelingPreCandidates() {
        RaftConfig cfgA = newTestConfig(1, 10, 1, newTestMemoryStorage(1, 2, 3));
        RaftConfig cfgB = newTestConfig(2, 10, 1, newTestMemoryStorage(1, 2, 3));
        RaftConfig cfgC = newTestConfig(3, 10, 1, newTestMemoryStorage(1, 2, 3));
        cfgA.preVote = true;
        cfgB.preVote = true;
        cfgC.preVote = true;
        Raft a = new Raft(cfgA);
        Raft b = new Raft(cfgB);
        Raft c = new Raft(cfgC);

        RaftNetwork nt = network(a, b, c);
        nt.cut(1, 3);

        nt.send(hup(1, 1));
        nt.send(hup(3, 3));

        // 1 收到 1 和 2 的票，成为 leader
        Raft sm = nt.getRaft(1);
        assertEquals(RaftRole.LEADER, sm.getState());

        // 3 发起预投票但被拒绝，退回 follower
        sm = nt.getRaft(3);
        assertEquals(RaftRole.FOLLOWER, sm.getState());

        nt.recover();

        // 候选者 3 提高任期再竞选一次。
        // 有了预投票，它不会打断 leader
        nt.send(hup(3, 3));

        Raft[] sms = new Raft[]{a, b, c};
        RaftRole[] states = new RaftRole[]{RaftRole.LEADER, RaftRole.FOLLOWER, RaftRole.FOLLOWER};
        long[] terms = new long[]{1, 1, 1};
        long[] lastIndexes = new long[]{1, 1, 0};

        for (int i = 0; i < sms.length; i++) {
            assertEquals(states[i], sms[i].getState(), "#" + i);
            assertEquals(terms[i], sms[i].getTerm(), "#" + i);
            assertEquals(lastIndexes[i], sms[i].getRaftLog().lastIndex(), "#" + i);
        }
    }

    // ------------------------------------------------------------------
    // 竞选与提案
    // ------------------------------------------------------------------

    /**
     * Go: TestCandidateConcede
     */
    @Test
    // Go: TestCandidateConcede
    void testCandidateConcede() {
        RaftNetwork tt = network(null, null, null);
        tt.isolate(1);

        tt.send(hup(1, 1));
        tt.send(hup(3, 3));

        // heal the partition
        tt.recover();
        // send heartbeat; reset wait
        tt.send(beat(3, 3));

        byte[] data = "force follower".getBytes();
        // send a proposal to 3 to flush out a MsgApp to 1
        tt.send(prop(3, 3, data));
        // send heartbeat; flush out commit
        tt.send(beat(3, 3));

        Raft a = tt.getRaft(1);
        assertEquals(RaftRole.FOLLOWER, a.getState());
        assertEquals(1, a.getTerm());

        RaftLog wantLog = newLog(entry(1, 1, null), entryData(2, 1, data));
        for (Long id : tt.peerIds()) {
            Raft sm = tt.getRaft(id);
            if (sm != null) {
                assertEquals("", diffLogs(wantLog, sm.getRaftLog()), "#" + id);
            }
        }
    }

    /**
     * Go: TestSingleNodeCandidate
     */
    @Test
    // Go: TestSingleNodeCandidate
    void testSingleNodeCandidate() {
        RaftNetwork tt = network(new Object[]{null});
        tt.send(hup(1, 1));

        Raft sm = tt.getRaft(1);
        assertEquals(RaftRole.LEADER, sm.getState());
    }

    /**
     * Go: TestSingleNodePreCandidate
     */
    @Test
    // Go: TestSingleNodePreCandidate
    void testSingleNodePreCandidate() {
        RaftNetwork tt = networkWithConfig(RaftNetwork::preVoteConfig, new Object[]{null});
        tt.send(hup(1, 1));

        Raft sm = tt.getRaft(1);
        assertEquals(RaftRole.LEADER, sm.getState());
    }

    /**
     * Go: TestOldMessages
     */
    @Test
    // Go: TestOldMessages
    void testOldMessages() {
        RaftNetwork tt = network(null, null, null);
        // make 0 leader @ term 3
        tt.send(hup(1, 1));
        tt.send(hup(2, 2));
        tt.send(hup(1, 1));
        // pretend we're an old leader trying to make progress; this entry is expected to be ignored.
        tt.send(new Message().setFrom(2).setTo(1).setType(MessageType.MSG_APP).setTerm(2)
                .setEntries(Collections.singletonList(entry(3, 2, null))));
        // commit a new entry
        tt.send(prop(1, 1, "somedata"));

        RaftLog ilog = newLog(entry(0, 0, null), entry(1, 1, null), entry(2, 2, null),
                entry(3, 3, null), entry(4, 3, "somedata"));
        for (Long id : tt.peerIds()) {
            Raft sm = tt.getRaft(id);
            if (sm != null) {
                assertEquals("", diffLogs(ilog, sm.getRaftLog()), "#" + id);
            }
        }
    }

    /**
     * Go: TestProposal
     */
    @Test
    // Go: TestProposal
    void testProposal() {
        // 第二个元素是「这次提案能不能成功」，
        // Go 里靠 recover 掉提案被丢弃时抛出的 panic，Java 侧靠返回值判断
        boolean[] success = new boolean[]{true, true, false, false, true};
        Object[][] peers = new Object[][]{
                {null, null, null},
                {null, null, new RaftNetwork.BlackHole()},
                {null, new RaftNetwork.BlackHole(), new RaftNetwork.BlackHole()},
                {null, new RaftNetwork.BlackHole(), new RaftNetwork.BlackHole(), null},
                {null, new RaftNetwork.BlackHole(), new RaftNetwork.BlackHole(), null, null},
        };

        for (int j = 0; j < peers.length; j++) {
            RaftNetwork tt = network(peers[j]);
            byte[] data = "somedata".getBytes();

            // promote 1 to become leader
            tt.send(hup(1, 1));
            tt.send(prop(1, 1, data));
            Raft r = tt.getRaft(1);

            RaftLog wantLog;
            if (success[j]) {
                wantLog = newLog(entry(1, 1, null), entryData(2, 1, data));
            } else {
                wantLog = newLog();
            }
            for (Long id : tt.peerIds()) {
                Raft sm = tt.getRaft(id);
                if (sm != null) {
                    assertEquals("", diffLogs(wantLog, sm.getRaftLog()), "#" + j + ", peer " + id);
                }
            }
            assertEquals(1, r.getTerm(), "#" + j);
        }
    }

    /**
     * Go: TestProposalByProxy
     */
    @Test
    // Go: TestProposalByProxy
    void testProposalByProxy() {
        byte[] data = "somedata".getBytes();
        Object[][] peers = new Object[][]{
                {null, null, null},
                {null, null, new RaftNetwork.BlackHole()},
        };

        for (int j = 0; j < peers.length; j++) {
            RaftNetwork tt = network(peers[j]);

            // promote 0 the leader
            tt.send(hup(1, 1));

            // propose via follower
            tt.send(prop(2, 2, data));

            RaftLog wantLog = newLog(entry(1, 1, null), entryData(2, 1, data));
            for (Long id : tt.peerIds()) {
                Raft sm = tt.getRaft(id);
                if (sm != null) {
                    assertEquals("", diffLogs(wantLog, sm.getRaftLog()), "#" + j + "." + id);
                }
            }
            Raft sm = tt.getRaft(1);
            assertEquals(1, sm.getTerm(), "#" + j);
        }
    }

    /**
     * Go: TestCommit
     */
    @Test
    // Go: TestCommit
    void testCommit() {
        long[][] matches = new long[][]{
                // single
                {1}, {1}, {2}, {1},
                // odd
                {2, 1, 1}, {2, 1, 1}, {2, 1, 2}, {2, 1, 2},
                // even
                {2, 1, 1, 1}, {2, 1, 1, 1}, {2, 1, 1, 2}, {2, 1, 1, 2}, {2, 1, 2, 2}, {2, 1, 2, 2},
        };
        long[][] terms = new long[][]{
                // single
                {1}, {1}, {1, 2}, {2},
                // odd
                {1, 2}, {1, 1}, {1, 2}, {1, 1},
                // even
                {1, 2}, {1, 1}, {1, 2}, {1, 1}, {1, 2}, {1, 1},
        };
        long[] smTerms = new long[]{1, 2, 2, 2, 1, 2, 2, 2, 1, 2, 1, 2, 2, 2};
        long[] want = new long[]{1, 0, 2, 1, 1, 0, 2, 0, 1, 0, 1, 0, 2, 0};

        for (int i = 0; i < matches.length; i++) {
            MemoryStorage storage = newTestMemoryStorage(1);
            storage.append(entries(terms[i]));
            storage.setHardState(new HardState(smTerms[i], 0, 0));

            Raft sm = newTestRaft(1, 10, 2, storage);
            for (int j = 0; j < matches[i].length; j++) {
                long id = j + 1;
                if (id > 1) {
                    sm.applyConfChange(new ConfChangeV2(ConfChangeTransition.AUTO,
                            Collections.singletonList(
                                    new ConfChangeSingle(ConfChangeType.ADD_NODE, id)), null));
                }
                Progress pr = sm.getTracker().getProgress().get(id);
                pr.setMatch(matches[i][j]);
                pr.setNext(matches[i][j] + 1);
            }
            sm.maybeCommit();
            assertEquals(want[i], sm.getRaftLog().committed(), "#" + i);
        }
    }

    /**
     * Go: TestPastElectionTimeout
     */
    @Test
    // Go: TestPastElectionTimeout
    void testPastElectionTimeout() {
        int[] elapsed = new int[]{5, 10, 13, 15, 18, 20};
        double[] wantProbability = new double[]{0, 0.1, 0.4, 0.6, 0.9, 1};
        boolean[] round = new boolean[]{false, true, true, true, true, false};

        for (int i = 0; i < elapsed.length; i++) {
            Raft sm = newTestRaft(1, 10, 1, newTestMemoryStorage(1));
            sm.electionElapsed = elapsed[i];
            int c = 0;
            for (int j = 0; j < 10000; j++) {
                sm.resetRandomizedElectionTimeout();
                if (sm.pastElectionTimeout()) {
                    c++;
                }
            }
            double got = c / 10000.0;
            if (round[i]) {
                got = Math.floor(got * 10 + 0.5) / 10.0;
            }
            assertEquals(wantProbability[i], got, "#" + i);
        }
    }

    /**
     * Go: TestStepIgnoreOldTermMsg
     */
    @Test
    // Go: TestStepIgnoreOldTermMsg
    void testStepIgnoreOldTermMsg() {
        StepSpy sm = new StepSpy(newTestConfig(1, 10, 1, newTestMemoryStorage(1)));
        sm.setTermForTest(2);
        sm.step(new Message().setType(MessageType.MSG_APP).setTerm(sm.getTerm() - 1));
        assertFalse(sm.called);
    }

    /**
     * Go: TestHandleMsgApp
     */
    @Test
    // Go: TestHandleMsgApp
    void testHandleMsgApp() {
        Message[] msgs = new Message[]{
                // Ensure 1
                msgApp(2, 3, 2, 3, null), // previous log mismatch
                msgApp(2, 3, 3, 3, null), // previous log non-exist

                // Ensure 2
                msgApp(2, 1, 1, 1, null),
                msgApp(2, 0, 0, 1, entry(1, 2, null)),
                msgApp(2, 2, 2, 3, entry(3, 2, null), entry(4, 2, null)),
                msgApp(2, 2, 2, 4, entry(3, 2, null)),
                msgApp(2, 1, 1, 4, entry(2, 2, null)),

                // Ensure 3
                // match entry 1, commit up to last new entry 1
                msgApp(1, 1, 1, 3, null),
                // match entry 1, commit up to last new entry 2
                msgApp(1, 1, 1, 3, entry(2, 2, null)),
                // match entry 2, commit up to last new entry 2
                msgApp(2, 2, 2, 3, null),
                // commit up to log.last()
                msgApp(2, 2, 2, 4, null),
        };
        long[] wantIndex = new long[]{2, 2, 2, 1, 4, 3, 2, 2, 2, 2, 2};
        long[] wantCommit = new long[]{0, 0, 1, 1, 3, 3, 2, 1, 2, 2, 2};
        boolean[] wantReject = new boolean[]{true, true, false, false, false, false, false, false, false, false, false};

        for (int i = 0; i < msgs.length; i++) {
            MemoryStorage storage = newTestMemoryStorage(1);
            storage.append(entries(new long[]{1, 2}));
            Raft sm = newTestRaft(1, 10, 1, storage);
            sm.becomeFollower(2, Raft.NONE);

            sm.handleAppendEntries(msgs[i]);
            assertEquals(wantIndex[i], sm.getRaftLog().lastIndex(), "#" + i);
            assertEquals(wantCommit[i], sm.getRaftLog().committed(), "#" + i);
            List<Message> m = readMessages(sm);
            assertEquals(1, m.size(), "#" + i);
            assertEquals(wantReject[i], m.get(0).getReject(), "#" + i);
        }
    }

    /**
     * Go: TestHandleHeartbeat
     */
    @Test
    // Go: TestHandleHeartbeat
    void testHandleHeartbeat() {
        long commit = 2;
        Message[] msgs = new Message[]{
                new Message().setFrom(2).setTo(1).setType(MessageType.MSG_HEARTBEAT).setTerm(2).setCommit(commit + 1),
                // do not decrease commit
                new Message().setFrom(2).setTo(1).setType(MessageType.MSG_HEARTBEAT).setTerm(2).setCommit(commit - 1),
        };
        long[] wantCommit = new long[]{commit + 1, commit};

        for (int i = 0; i < msgs.length; i++) {
            MemoryStorage storage = newTestMemoryStorage(1, 2);
            storage.append(entries(new long[]{1, 2, 3}));
            Raft sm = newTestRaft(1, 5, 1, storage);
            sm.becomeFollower(2, 2);
            sm.getRaftLog().commitTo(commit);
            sm.handleHeartbeat(msgs[i]);
            assertEquals(wantCommit[i], sm.getRaftLog().committed(), "#" + i);
            List<Message> m = readMessages(sm);
            assertEquals(1, m.size(), "#" + i);
            assertEquals(MessageType.MSG_HEARTBEAT_RESP, m.get(0).getType(), "#" + i);
        }
    }

    /**
     * Go: TestHandleHeartbeatResp
     */
    @Test
    // Go: TestHandleHeartbeatResp
    void testHandleHeartbeatResp() {
        MemoryStorage storage = newTestMemoryStorage(1, 2);
        storage.append(entries(new long[]{1, 2, 3}));
        Raft sm = newTestRaft(1, 5, 1, storage);
        sm.becomeCandidate();
        sm.becomeLeader();
        sm.getRaftLog().commitTo(sm.getRaftLog().lastIndex());

        // A heartbeat response from a node that is behind; re-send MsgApp
        sm.step(new Message().setFrom(2).setType(MessageType.MSG_HEARTBEAT_RESP));
        List<Message> msgs = readMessages(sm);
        assertEquals(1, msgs.size());
        assertEquals(MessageType.MSG_APP, msgs.get(0).getType());

        // A second heartbeat response generates another MsgApp re-send
        sm.step(new Message().setFrom(2).setType(MessageType.MSG_HEARTBEAT_RESP));
        msgs = readMessages(sm);
        assertEquals(1, msgs.size());
        assertEquals(MessageType.MSG_APP, msgs.get(0).getType());

        // Once we have an MsgAppResp, heartbeats no longer send MsgApp.
        sm.step(new Message().setFrom(2).setType(MessageType.MSG_APP_RESP)
                .setIndex(msgs.get(0).getIndex() + msgs.get(0).getEntries().size()));
        // Consume the message sent in response to MsgAppResp
        readMessages(sm);

        sm.step(new Message().setFrom(2).setType(MessageType.MSG_HEARTBEAT_RESP));
        msgs = readMessages(sm);
        assertTrue(msgs.isEmpty());
    }

    /**
     * Go: TestRaftFreesReadOnlyMem
     */
    @Test
    // Go: TestRaftFreesReadOnlyMem
    void testRaftFreesReadOnlyMem() {
        Raft sm = newTestRaft(1, 5, 1, newTestMemoryStorage(1, 2));
        sm.becomeCandidate();
        sm.becomeLeader();
        sm.getRaftLog().commitTo(sm.getRaftLog().lastIndex());

        byte[] reqCtx = "ctx".getBytes();

        // leader starts linearizable read request.
        // more info: raft dissertation 6.4, step 2.
        sm.step(new Message().setFrom(2).setType(MessageType.MSG_READ_INDEX)
                .setEntries(Collections.singletonList(entryData(0, 0, reqCtx))));
        List<Message> msgs = readMessages(sm);
        assertEquals(1, msgs.size());
        assertEquals(MessageType.MSG_HEARTBEAT, msgs.get(0).getType());
        assertEquals(1, sm.getReadOnly().unconfirmedCount());

        // heartbeat responses from majority of followers (1 in this case)
        // acknowledge the authority of the leader.
        // more info: raft dissertation 6.4, step 3.
        sm.step(new Message().setFrom(2).setType(MessageType.MSG_HEARTBEAT_RESP)
                .setContext(msgs.get(0).getContext()));
        assertEquals(0, sm.getReadOnly().unconfirmedCount());
    }

    /**
     * Go: TestMsgAppRespWaitReset
     */
    @Test
    // Go: TestMsgAppRespWaitReset
    void testMsgAppRespWaitReset() {
        MemoryStorage s = newTestMemoryStorage(1, 2, 3);
        Raft sm = newTestRaft(1, 5, 1, s);
        sm.becomeCandidate();
        sm.becomeLeader();

        // Run n1 which includes sending a message like the below
        // one to n2, but also appending to its own log.
        nextEnts(sm, s);

        // Node 2 acks the first entry, making it committed.
        sm.step(new Message().setFrom(2).setType(MessageType.MSG_APP_RESP).setIndex(1));
        assertEquals(1, sm.getRaftLog().committed());

        // Also consume the MsgApp messages that update Commit on the followers.
        readMessages(sm);

        // A new command is now proposed on node 1.
        sm.step(new Message().setFrom(1).setType(MessageType.MSG_PROP)
                .setEntries(Collections.singletonList(emptyEntry())));

        // The command is broadcast to all nodes not in the wait state.
        // Node 2 left the wait state due to its MsgAppResp, but node 3 is still waiting.
        List<Message> msgs = readMessages(sm);
        assertEquals(1, msgs.size());
        assertEquals(MessageType.MSG_APP, msgs.get(0).getType());
        assertEquals(2, msgs.get(0).getTo());
        assertEquals(1, msgs.get(0).getEntries().size());
        assertEquals(2, msgs.get(0).getEntries().get(0).getIndex());

        // Now Node 3 acks the first entry. This releases the wait and entry 2 is sent.
        sm.step(new Message().setFrom(3).setType(MessageType.MSG_APP_RESP).setIndex(1));
        msgs = readMessages(sm);
        assertEquals(1, msgs.size());
        assertEquals(MessageType.MSG_APP, msgs.get(0).getType());
        assertEquals(3, msgs.get(0).getTo());
        assertEquals(1, msgs.get(0).getEntries().size());
        assertEquals(2, msgs.get(0).getEntries().get(0).getIndex());
    }

    // ------------------------------------------------------------------
    // 状态转换
    // ------------------------------------------------------------------

    /**
     * Go: TestRecvMsgVote
     */
    @Test
    // Go: TestRecvMsgVote
    void testRecvMsgVote() {
        testRecvMsgVote(MessageType.MSG_VOTE);
    }

    /**
     * Go: TestRecvMsgPreVote
     */
    @Test
    // Go: TestRecvMsgPreVote
    void testRecvMsgPreVote() {
        testRecvMsgVote(MessageType.MSG_PRE_VOTE);
    }

    /**
     * Go: testRecvMsgVote
     */
    private static void testRecvMsgVote(MessageType msgType) {
        RaftRole[] states = new RaftRole[]{
                RaftRole.FOLLOWER, RaftRole.FOLLOWER, RaftRole.FOLLOWER, RaftRole.FOLLOWER,
                RaftRole.FOLLOWER, RaftRole.FOLLOWER, RaftRole.FOLLOWER, RaftRole.FOLLOWER,
                RaftRole.FOLLOWER, RaftRole.FOLLOWER, RaftRole.FOLLOWER, RaftRole.FOLLOWER,
                RaftRole.FOLLOWER, RaftRole.FOLLOWER, RaftRole.FOLLOWER, RaftRole.FOLLOWER,
                RaftRole.FOLLOWER, RaftRole.FOLLOWER,
                RaftRole.LEADER, RaftRole.PRE_CANDIDATE, RaftRole.CANDIDATE,
        };
        long[] indexes = new long[]{0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3};
        long[] logTerms = new long[]{0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 2, 2, 3, 3, 3};
        long[] voteFor = new long[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 1, 1, 1, 1};
        boolean[] wantReject = new boolean[]{
                true, true, true, false,
                true, true, true, false,
                true, true, false, false,
                true, true, false, false,
                false, true,
                true, true, true,
        };

        for (int i = 0; i < states.length; i++) {
            MemoryStorage storage = newTestMemoryStorage(1);
            // Go 里直接把 raftLog 换成一份 index(0).terms(0, 2, 2) 的日志，
            // 这里改成往存储里追加同样的两条日志，效果一致
            storage.append(entries(2, 2));
            Raft sm = newTestRaft(1, 10, 1, storage);
            sm.setStateForTest(states[i]);
            sm.setVoteForTest(voteFor[i]);

            // raft.Term is greater than or equal to raft.raftLog.lastTerm. In this
            // test we're only testing MsgVote responses when the campaigning node
            // has a different raft log compared to the recipient node.
            // Additionally we're verifying behaviour when the recipient node has
            // already given out its vote for its current term. We're not testing
            // what the recipient node does when receiving a message with a
            // different term number, so we simply initialize both term numbers to
            // be the same.
            long term = Math.max(sm.getRaftLog().lastTerm(), logTerms[i]);
            sm.setTermForTest(term);
            sm.step(new Message().setType(msgType).setTerm(term).setFrom(2)
                    .setIndex(indexes[i]).setLogTerm(logTerms[i]));

            List<Message> msgs = readMessages(sm);
            assertEquals(1, msgs.size(), "#" + i);
            assertEquals(voteRespMsgType(msgType), msgs.get(0).getType(), "#" + i);
            assertEquals(wantReject[i], msgs.get(0).getReject(), "#" + i);
        }
    }

    /**
     * Go: TestStateTransition
     */
    @Test
    // Go: TestStateTransition
    void testStateTransition() {
        RaftRole[] from = new RaftRole[]{
                RaftRole.FOLLOWER, RaftRole.FOLLOWER, RaftRole.FOLLOWER, RaftRole.FOLLOWER,
                RaftRole.PRE_CANDIDATE, RaftRole.PRE_CANDIDATE, RaftRole.PRE_CANDIDATE, RaftRole.PRE_CANDIDATE,
                RaftRole.CANDIDATE, RaftRole.CANDIDATE, RaftRole.CANDIDATE, RaftRole.CANDIDATE,
                RaftRole.LEADER, RaftRole.LEADER, RaftRole.LEADER, RaftRole.LEADER,
        };
        RaftRole[] to = new RaftRole[]{
                RaftRole.FOLLOWER, RaftRole.PRE_CANDIDATE, RaftRole.CANDIDATE, RaftRole.LEADER,
                RaftRole.FOLLOWER, RaftRole.PRE_CANDIDATE, RaftRole.CANDIDATE, RaftRole.LEADER,
                RaftRole.FOLLOWER, RaftRole.PRE_CANDIDATE, RaftRole.CANDIDATE, RaftRole.LEADER,
                RaftRole.FOLLOWER, RaftRole.PRE_CANDIDATE, RaftRole.CANDIDATE, RaftRole.LEADER,
        };
        boolean[] wantAllow = new boolean[]{
                true, true, true, false,
                true, true, true, true,
                true, true, true, true,
                true, false, false, true,
        };
        long[] wantTerm = new long[]{
                1, 0, 1, 0,
                0, 0, 1, 0,
                0, 0, 1, 0,
                1, 0, 1, 0,
        };
        long[] wantLead = new long[]{
                0, 0, 0, 0,
                0, 0, 0, 1,
                0, 0, 0, 1,
                0, 0, 0, 1,
        };

        for (int i = 0; i < from.length; i++) {
            Raft sm = newTestRaft(1, 10, 1, newTestMemoryStorage(1));
            sm.setStateForTest(from[i]);

            boolean thrown = false;
            try {
                switch (to[i]) {
                    case FOLLOWER:
                        sm.becomeFollower(wantTerm[i], wantLead[i]);
                        break;
                    case PRE_CANDIDATE:
                        sm.becomePreCandidate();
                        break;
                    case CANDIDATE:
                        sm.becomeCandidate();
                        break;
                    case LEADER:
                        sm.becomeLeader();
                        break;
                    default:
                        break;
                }
            } catch (RaftException e) {
                thrown = true;
            }

            if (thrown) {
                assertFalse(wantAllow[i], "#" + i);
            } else {
                assertEquals(wantTerm[i], sm.getTerm(), "#" + i);
                assertEquals(wantLead[i], sm.getLead(), "#" + i);
            }
        }
    }

    /**
     * Go: TestAllServerStepdown
     */
    @Test
    // Go: TestAllServerStepdown
    void testAllServerStepdown() {
        RaftRole[] states = new RaftRole[]{
                RaftRole.FOLLOWER, RaftRole.PRE_CANDIDATE, RaftRole.CANDIDATE, RaftRole.LEADER,
        };
        long[] wantTerm = new long[]{3, 3, 3, 3};
        long[] wantIndex = new long[]{0, 0, 0, 1};
        MessageType[] msgTypes = new MessageType[]{MessageType.MSG_VOTE, MessageType.MSG_APP};

        for (int i = 0; i < states.length; i++) {
            Raft sm = newTestRaft(1, 10, 1, newTestMemoryStorage(1, 2, 3));
            switch (states[i]) {
                case FOLLOWER:
                    sm.becomeFollower(1, Raft.NONE);
                    break;
                case PRE_CANDIDATE:
                    sm.becomePreCandidate();
                    break;
                case CANDIDATE:
                    sm.becomeCandidate();
                    break;
                case LEADER:
                    sm.becomeCandidate();
                    sm.becomeLeader();
                    break;
                default:
                    break;
            }

            for (int j = 0; j < msgTypes.length; j++) {
                sm.step(new Message().setFrom(2).setType(msgTypes[j]).setTerm(3).setLogTerm(3));

                assertEquals(RaftRole.FOLLOWER, sm.getState(), "#" + i + "." + j);
                assertEquals(wantTerm[i], sm.getTerm(), "#" + i + "." + j);
                assertEquals(wantIndex[i], sm.getRaftLog().lastIndex(), "#" + i + "." + j);
                assertEquals(wantIndex[i], sm.getRaftLog().allEntries().size(), "#" + i + "." + j);

                long wantLead = msgTypes[j] == MessageType.MSG_VOTE ? Raft.NONE : 2;
                assertEquals(wantLead, sm.getLead(), "#" + i + "." + j);
            }
        }
    }

    /**
     * Go: TestCandidateResetTermMsgHeartbeat
     */
    @Test
    // Go: TestCandidateResetTermMsgHeartbeat
    void testCandidateResetTermMsgHeartbeat() {
        testCandidateResetTerm(MessageType.MSG_HEARTBEAT);
    }

    /**
     * Go: TestCandidateResetTermMsgApp
     */
    @Test
    // Go: TestCandidateResetTermMsgApp
    void testCandidateResetTermMsgApp() {
        testCandidateResetTerm(MessageType.MSG_APP);
    }

    /**
     * Go: testCandidateResetTerm
     *
     * <p>candidate 收到 leader 的 MsgHeartbeat / MsgApp 之后，
     * 任期要跟着 leader 重置，并退回 follower。</p>
     */
    private static void testCandidateResetTerm(MessageType mt) {
        Raft a = newTestRaft(1, 10, 1, newTestMemoryStorage(1, 2, 3));
        Raft b = newTestRaft(2, 10, 1, newTestMemoryStorage(1, 2, 3));
        Raft c = newTestRaft(3, 10, 1, newTestMemoryStorage(1, 2, 3));

        RaftNetwork nt = network(a, b, c);

        nt.send(hup(1, 1));

        assertEquals(RaftRole.LEADER, a.getState());
        assertEquals(RaftRole.FOLLOWER, b.getState());
        assertEquals(RaftRole.FOLLOWER, c.getState());

        // isolate 3 and increase term in rest
        nt.isolate(3);

        nt.send(hup(2, 2));
        nt.send(hup(1, 1));

        assertEquals(RaftRole.LEADER, a.getState());
        assertEquals(RaftRole.FOLLOWER, b.getState());

        // trigger campaign in isolated c
        c.resetRandomizedElectionTimeout();
        for (int i = 0; i < c.randomizedElectionTimeout(); i++) {
            c.tick();
        }
        advanceMessagesAfterAppend(c);

        assertEquals(RaftRole.CANDIDATE, c.getState());

        nt.recover();

        // leader sends to isolated candidate
        // and expects candidate to revert to follower
        nt.send(new Message().setFrom(1).setTo(3).setTerm(a.getTerm()).setType(mt));

        assertEquals(RaftRole.FOLLOWER, c.getState());
        // follower c term is reset with leader's
        assertEquals(a.getTerm(), c.getTerm());
    }

    /**
     * Go: TestCandidateSelfVoteAfterLostElection
     */
    @Test
    // Go: TestCandidateSelfVoteAfterLostElection
    void testCandidateSelfVoteAfterLostElection() {
        testCandidateSelfVoteAfterLostElection(false);
    }

    /**
     * Go: TestCandidateSelfVoteAfterLostElectionPreVote
     */
    @Test
    // Go: TestCandidateSelfVoteAfterLostElectionPreVote
    void testCandidateSelfVoteAfterLostElectionPreVote() {
        testCandidateSelfVoteAfterLostElection(true);
    }

    /**
     * Go: testCandidateSelfVoteAfterLostElection
     */
    private static void testCandidateSelfVoteAfterLostElection(boolean preVote) {
        RaftConfig cfg = newTestConfig(1, 5, 1, newTestMemoryStorage(1, 2, 3));
        cfg.preVote = preVote;
        Raft sm = new Raft(cfg);

        // n1 calls an election.
        sm.step(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_HUP));
        List<Message> steps = takeMessagesAfterAppend(sm);

        // n1 hears that n2 already won the election before it has had a
        // change to sync its vote to disk and account for its self-vote.
        // Becomes a follower.
        sm.step(new Message().setFrom(2).setTo(1).setTerm(sm.getTerm())
                .setType(MessageType.MSG_HEARTBEAT));
        assertEquals(RaftRole.FOLLOWER, sm.getState());

        // n1 remains a follower even after its self-vote is delivered.
        stepOrSend(sm, steps);
        assertEquals(RaftRole.FOLLOWER, sm.getState());

        // Its self-vote does not make its way to its ProgressTracker.
        assertEquals(0, sm.getTracker().tallyVotes().getGranted());
    }

    /**
     * Go: TestCandidateDeliversPreCandidateSelfVoteAfterBecomingCandidate
     */
    @Test
    // Go: TestCandidateDeliversPreCandidateSelfVoteAfterBecomingCandidate
    void testCandidateDeliversPreCandidateSelfVoteAfterBecomingCandidate() {
        RaftConfig cfg = newTestConfig(1, 5, 1, newTestMemoryStorage(1, 2, 3));
        cfg.preVote = true;
        Raft sm = new Raft(cfg);

        // n1 calls an election.
        sm.step(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_HUP));
        assertEquals(RaftRole.PRE_CANDIDATE, sm.getState());
        List<Message> steps = takeMessagesAfterAppend(sm);

        // n1 receives pre-candidate votes from both other peers before
        // voting for itself. n1 becomes a candidate.
        // NB: pre-vote messages carry the local term + 1.
        sm.step(new Message().setFrom(2).setTo(1).setTerm(sm.getTerm() + 1)
                .setType(MessageType.MSG_PRE_VOTE_RESP));
        sm.step(new Message().setFrom(3).setTo(1).setTerm(sm.getTerm() + 1)
                .setType(MessageType.MSG_PRE_VOTE_RESP));
        assertEquals(RaftRole.CANDIDATE, sm.getState());

        // n1 remains a candidate even after its delayed pre-vote self-vote is
        // delivered.
        stepOrSend(sm, steps);
        assertEquals(RaftRole.CANDIDATE, sm.getState());

        steps = takeMessagesAfterAppend(sm);

        // Its pre-vote self-vote does not make its way to its ProgressTracker.
        assertEquals(0, sm.getTracker().tallyVotes().getGranted());

        // A single vote from n2 does not move n1 to the leader.
        sm.step(new Message().setFrom(2).setTo(1).setTerm(sm.getTerm())
                .setType(MessageType.MSG_VOTE_RESP));
        assertEquals(RaftRole.CANDIDATE, sm.getState());

        // n1 becomes the leader once its self-vote is received because now
        // quorum is reached.
        stepOrSend(sm, steps);
        assertEquals(RaftRole.LEADER, sm.getState());
    }

    /**
     * Go: TestLeaderMsgAppSelfAckAfterTermChange
     */
    @Test
    // Go: TestLeaderMsgAppSelfAckAfterTermChange
    void testLeaderMsgAppSelfAckAfterTermChange() {
        Raft sm = newTestRaft(1, 5, 1, newTestMemoryStorage(1, 2, 3));
        sm.becomeCandidate();
        sm.becomeLeader();

        // n1 proposes a write.
        sm.step(prop(1, 1, "somedata"));
        List<Message> steps = takeMessagesAfterAppend(sm);

        // n1 hears that n2 is the new leader.
        sm.step(new Message().setFrom(2).setTo(1).setTerm(sm.getTerm() + 1)
                .setType(MessageType.MSG_HEARTBEAT));
        assertEquals(RaftRole.FOLLOWER, sm.getState());

        // n1 advances, ignoring its earlier self-ack of its MsgApp. The
        // corresponding MsgAppResp is ignored because it carries an earlier term.
        stepOrSend(sm, steps);
        assertEquals(RaftRole.FOLLOWER, sm.getState());
    }

    /**
     * Go: TestLeaderStepdownWhenQuorumActive
     */
    @Test
    // Go: TestLeaderStepdownWhenQuorumActive
    void testLeaderStepdownWhenQuorumActive() {
        RaftConfig cfg = newTestConfig(1, 5, 1, newTestMemoryStorage(1, 2, 3));
        cfg.checkQuorum = true;
        Raft sm = new Raft(cfg);

        sm.becomeCandidate();
        sm.becomeLeader();

        for (int i = 0; i < sm.getElectionTimeout() + 1; i++) {
            sm.step(new Message().setFrom(2).setType(MessageType.MSG_HEARTBEAT_RESP)
                    .setTerm(sm.getTerm()));
            sm.tick();
        }

        assertEquals(RaftRole.LEADER, sm.getState());
    }

    /**
     * Go: TestLeaderStepdownWhenQuorumLost
     */
    @Test
    // Go: TestLeaderStepdownWhenQuorumLost
    void testLeaderStepdownWhenQuorumLost() {
        RaftConfig cfg = newTestConfig(1, 5, 1, newTestMemoryStorage(1, 2, 3));
        cfg.checkQuorum = true;
        Raft sm = new Raft(cfg);

        sm.becomeCandidate();
        sm.becomeLeader();

        for (int i = 0; i < sm.getElectionTimeout() + 1; i++) {
            sm.tick();
        }

        assertEquals(RaftRole.FOLLOWER, sm.getState());
    }

    // ------------------------------------------------------------------
    // checkQuorum 与只读请求
    // ------------------------------------------------------------------

    /**
     * Go: TestLeaderSupersedingWithCheckQuorum
     */
    @Test
    // Go: TestLeaderSupersedingWithCheckQuorum
    void testLeaderSupersedingWithCheckQuorum() {
        Raft a = newTestRaftWithCheckQuorum(1);
        Raft b = newTestRaftWithCheckQuorum(2);
        Raft c = newTestRaftWithCheckQuorum(3);

        RaftNetwork nt = network(a, b, c);
        b.setRandomizedElectionTimeout(b.getElectionTimeout() + 1);

        for (int i = 0; i < b.getElectionTimeout(); i++) {
            b.tick();
        }
        nt.send(hup(1, 1));

        assertEquals(RaftRole.LEADER, a.getState());
        assertEquals(RaftRole.FOLLOWER, c.getState());

        nt.send(hup(3, 3));

        // Peer b rejected c's vote since its electionElapsed had not reached to electionTimeout
        assertEquals(RaftRole.CANDIDATE, c.getState());

        // Letting b's electionElapsed reach to electionTimeout
        for (int i = 0; i < b.getElectionTimeout(); i++) {
            b.tick();
        }
        nt.send(hup(3, 3));

        assertEquals(RaftRole.LEADER, c.getState());
    }

    /**
     * Go: TestLeaderElectionWithCheckQuorum
     */
    @Test
    // Go: TestLeaderElectionWithCheckQuorum
    void testLeaderElectionWithCheckQuorum() {
        Raft a = newTestRaftWithCheckQuorum(1);
        Raft b = newTestRaftWithCheckQuorum(2);
        Raft c = newTestRaftWithCheckQuorum(3);

        RaftNetwork nt = network(a, b, c);
        a.setRandomizedElectionTimeout(a.getElectionTimeout() + 1);
        b.setRandomizedElectionTimeout(b.getElectionTimeout() + 2);

        // Immediately after creation, votes are cast regardless of the
        // election timeout.
        nt.send(hup(1, 1));

        assertEquals(RaftRole.LEADER, a.getState());
        assertEquals(RaftRole.FOLLOWER, c.getState());

        // need to reset randomizedElectionTimeout larger than electionTimeout again,
        // because the value might be reset to electionTimeout since the last state changes
        a.setRandomizedElectionTimeout(a.getElectionTimeout() + 1);
        b.setRandomizedElectionTimeout(b.getElectionTimeout() + 2);
        for (int i = 0; i < a.getElectionTimeout(); i++) {
            a.tick();
        }
        for (int i = 0; i < b.getElectionTimeout(); i++) {
            b.tick();
        }
        nt.send(hup(3, 3));

        assertEquals(RaftRole.FOLLOWER, a.getState());
        assertEquals(RaftRole.LEADER, c.getState());
    }

    /**
     * Go: TestFreeStuckCandidateWithCheckQuorum
     */
    @Test
    // Go: TestFreeStuckCandidateWithCheckQuorum
    void testFreeStuckCandidateWithCheckQuorum() {
        Raft a = newTestRaftWithCheckQuorum(1);
        Raft b = newTestRaftWithCheckQuorum(2);
        Raft c = newTestRaftWithCheckQuorum(3);

        RaftNetwork nt = network(a, b, c);
        b.setRandomizedElectionTimeout(b.getElectionTimeout() + 1);

        for (int i = 0; i < b.getElectionTimeout(); i++) {
            b.tick();
        }
        nt.send(hup(1, 1));

        nt.isolate(1);
        nt.send(hup(3, 3));

        assertEquals(RaftRole.FOLLOWER, b.getState());
        assertEquals(RaftRole.CANDIDATE, c.getState());
        assertEquals(b.getTerm() + 1, c.getTerm());

        // Vote again for safety
        nt.send(hup(3, 3));

        assertEquals(RaftRole.FOLLOWER, b.getState());
        assertEquals(RaftRole.CANDIDATE, c.getState());
        assertEquals(b.getTerm() + 2, c.getTerm());

        nt.recover();
        nt.send(new Message().setFrom(1).setTo(3).setType(MessageType.MSG_HEARTBEAT)
                .setTerm(a.getTerm()));

        // Disrupt the leader so that the stuck peer is freed
        assertEquals(RaftRole.FOLLOWER, a.getState());
        assertEquals(a.getTerm(), c.getTerm());

        // Vote again, should become leader this time
        nt.send(hup(3, 3));

        assertEquals(RaftRole.LEADER, c.getState());
    }

    /**
     * Go: TestNonPromotableVoterWithCheckQuorum
     */
    @Test
    // Go: TestNonPromotableVoterWithCheckQuorum
    void testNonPromotableVoterWithCheckQuorum() {
        Raft a = newTestRaftWithCheckQuorum(1, 1, 2);
        Raft b = newTestRaftWithCheckQuorum(2, 1);

        RaftNetwork nt = network(a, b);
        b.setRandomizedElectionTimeout(b.getElectionTimeout() + 1);
        // Need to remove 2 again to make it a non-promotable node since newNetwork overwritten some internal states
        b.applyConfChange(new ConfChangeV2(ConfChangeTransition.AUTO,
                Collections.singletonList(new ConfChangeSingle(ConfChangeType.REMOVE_NODE, 2)), null));

        assertFalse(b.promotable());

        for (int i = 0; i < b.getElectionTimeout(); i++) {
            b.tick();
        }
        nt.send(hup(1, 1));

        assertEquals(RaftRole.LEADER, a.getState());
        assertEquals(RaftRole.FOLLOWER, b.getState());
        assertEquals(1, b.getLead());
    }

    /**
     * Go: TestDisruptiveFollower
     */
    @Test
    // Go: TestDisruptiveFollower
    void testDisruptiveFollower() {
        Raft n1 = newTestRaftWithCheckQuorum(1);
        Raft n2 = newTestRaftWithCheckQuorum(2);
        Raft n3 = newTestRaftWithCheckQuorum(3);

        n1.becomeFollower(1, Raft.NONE);
        n2.becomeFollower(1, Raft.NONE);
        n3.becomeFollower(1, Raft.NONE);

        RaftNetwork nt = network(n1, n2, n3);

        nt.send(hup(1, 1));

        // check state
        assertEquals(RaftRole.LEADER, n1.getState());
        assertEquals(RaftRole.FOLLOWER, n2.getState());
        assertEquals(RaftRole.FOLLOWER, n3.getState());

        // etcd server "advanceTicksForElection" on restart;
        // this is to expedite campaign trigger when given larger
        // election timeouts (e.g. multi-datacenter deploy)
        // Or leader messages are being delayed while ticks elapse
        n3.setRandomizedElectionTimeout(n3.getElectionTimeout() + 2);
        for (int i = 0; i < n3.randomizedElectionTimeout() - 1; i++) {
            n3.tick();
        }

        // ideally, before last election tick elapses,
        // the follower n3 receives "pb.MsgApp" or "pb.MsgHeartbeat"
        // from leader n1, and then resets its "electionElapsed"
        // however, last tick may elapse before receiving any
        // messages from leader, thus triggering campaign
        n3.tick();

        // n1 is still leader yet
        // while its heartbeat to candidate n3 is being delayed

        // check state
        assertEquals(RaftRole.LEADER, n1.getState());
        assertEquals(RaftRole.FOLLOWER, n2.getState());
        assertEquals(RaftRole.CANDIDATE, n3.getState());

        // check term
        assertEquals(2, n1.getTerm());
        assertEquals(2, n2.getTerm());
        assertEquals(3, n3.getTerm());

        // while outgoing vote requests are still queued in n3,
        // leader heartbeat finally arrives at candidate n3
        // however, due to delayed network from leader, leader
        // heartbeat was sent with lower term than candidate's
        nt.send(new Message().setFrom(1).setTo(3).setTerm(n1.getTerm())
                .setType(MessageType.MSG_HEARTBEAT));

        // then candidate n3 responds with "pb.MsgAppResp" of higher term
        // and leader steps down from a message with higher term
        // this is to disrupt the current leader, so that candidate
        // with higher term can be freed with following election

        // check state
        assertEquals(RaftRole.FOLLOWER, n1.getState());
        assertEquals(RaftRole.FOLLOWER, n2.getState());
        assertEquals(RaftRole.CANDIDATE, n3.getState());

        // check term
        assertEquals(3, n1.getTerm());
        assertEquals(2, n2.getTerm());
        assertEquals(3, n3.getTerm());
    }

    /**
     * Go: TestDisruptiveFollowerPreVote
     */
    @Test
    // Go: TestDisruptiveFollowerPreVote
    void testDisruptiveFollowerPreVote() {
        Raft n1 = newTestRaftWithCheckQuorum(1);
        Raft n2 = newTestRaftWithCheckQuorum(2);
        Raft n3 = newTestRaftWithCheckQuorum(3);

        n1.becomeFollower(1, Raft.NONE);
        n2.becomeFollower(1, Raft.NONE);
        n3.becomeFollower(1, Raft.NONE);

        RaftNetwork nt = network(n1, n2, n3);

        nt.send(hup(1, 1));

        // check state
        assertEquals(RaftRole.LEADER, n1.getState());
        assertEquals(RaftRole.FOLLOWER, n2.getState());
        assertEquals(RaftRole.FOLLOWER, n3.getState());

        nt.isolate(3);
        nt.send(prop(1, 1, "somedata"));
        nt.send(prop(1, 1, "somedata"));
        nt.send(prop(1, 1, "somedata"));
        n1.setPreVoteForTest(true);
        n2.setPreVoteForTest(true);
        n3.setPreVoteForTest(true);
        nt.recover();
        nt.send(hup(3, 3));

        // check state
        assertEquals(RaftRole.LEADER, n1.getState());
        assertEquals(RaftRole.FOLLOWER, n2.getState());
        assertEquals(RaftRole.PRE_CANDIDATE, n3.getState());

        // check term
        assertEquals(2, n1.getTerm());
        assertEquals(2, n2.getTerm());
        assertEquals(2, n3.getTerm());

        // delayed leader heartbeat does not force current leader to step down
        nt.send(new Message().setFrom(1).setTo(3).setTerm(n1.getTerm())
                .setType(MessageType.MSG_HEARTBEAT));
        assertEquals(RaftRole.LEADER, n1.getState());
    }

    /**
     * Go: TestReadOnlyOptionSafe
     */
    @Test
    // Go: TestReadOnlyOptionSafe
    void testReadOnlyOptionSafe() {
        Raft a = newTestRaft(1, 10, 1, newTestMemoryStorage(1, 2, 3));
        Raft b = newTestRaft(2, 10, 1, newTestMemoryStorage(1, 2, 3));
        Raft c = newTestRaft(3, 10, 1, newTestMemoryStorage(1, 2, 3));

        RaftNetwork nt = network(a, b, c);
        b.setRandomizedElectionTimeout(b.getElectionTimeout() + 1);

        for (int i = 0; i < b.getElectionTimeout(); i++) {
            b.tick();
        }
        nt.send(hup(1, 1));

        assertEquals(RaftRole.LEADER, a.getState());

        Raft[] sms = new Raft[]{a, b, c, a, b, c};
        int[] proposals = new int[]{10, 10, 10, 10, 10, 10};
        long[] wantIndex = new long[]{11, 21, 31, 41, 51, 61};
        byte[][] wantCtx = new byte[][]{
                "ctx1".getBytes(), "ctx2".getBytes(), "ctx3".getBytes(),
                "ctx4".getBytes(), "ctx5".getBytes(), "ctx6".getBytes(),
        };

        for (int i = 0; i < sms.length; i++) {
            for (int j = 0; j < proposals[i]; j++) {
                nt.send(prop(1, 1, emptyEntry()));
            }

            nt.send(readIndex(sms[i].getId(), wantCtx[i]));

            Raft r = sms[i];
            assertFalse(r.getReadStates().isEmpty(), "#" + i);
            ReadState rs = r.getReadStates().get(0);
            assertEquals(wantIndex[i], rs.getIndex(), "#" + i);
            assertArrayEquals(wantCtx[i], rs.getRequestCtx(), "#" + i);
            r.clearReadStates();
        }
    }

    /**
     * Go: TestReadOnlyWithLearner
     */
    @Test
    // Go: TestReadOnlyWithLearner
    void testReadOnlyWithLearner() {
        MemoryStorage s = newTestMemoryStorageWithLearners(new long[]{1}, new long[]{2});
        Raft a = newTestLearnerRaft(1, 10, 1, s);
        Raft b = newTestLearnerRaft(2, 10, 1,
                newTestMemoryStorageWithLearners(new long[]{1}, new long[]{2}));

        RaftNetwork nt = network(a, b);
        b.setRandomizedElectionTimeout(b.getElectionTimeout() + 1);

        for (int i = 0; i < b.getElectionTimeout(); i++) {
            b.tick();
        }
        nt.send(hup(1, 1));

        assertEquals(RaftRole.LEADER, a.getState());

        Raft[] sms = new Raft[]{a, b, a, b};
        int[] proposals = new int[]{10, 10, 10, 10};
        long[] wantIndex = new long[]{11, 21, 31, 41};
        byte[][] wantCtx = new byte[][]{
                "ctx1".getBytes(), "ctx2".getBytes(), "ctx3".getBytes(), "ctx4".getBytes(),
        };

        for (int i = 0; i < sms.length; i++) {
            for (int j = 0; j < proposals[i]; j++) {
                nt.send(prop(1, 1, emptyEntry()));
                nextEnts(a, s); // append the entries on the leader
            }

            nt.send(readIndex(sms[i].getId(), wantCtx[i]));

            Raft r = sms[i];
            assertFalse(r.getReadStates().isEmpty(), "#" + i);
            ReadState rs = r.getReadStates().get(0);
            assertEquals(wantIndex[i], rs.getIndex(), "#" + i);
            assertArrayEquals(wantCtx[i], rs.getRequestCtx(), "#" + i);
            r.clearReadStates();
        }
    }

    /**
     * Go: TestReadOnlyOptionLease
     */
    @Test
    // Go: TestReadOnlyOptionLease
    void testReadOnlyOptionLease() {
        Raft a = newTestRaftWithLeaseBasedReadOnly(1);
        Raft b = newTestRaftWithLeaseBasedReadOnly(2);
        Raft c = newTestRaftWithLeaseBasedReadOnly(3);

        RaftNetwork nt = network(a, b, c);
        b.setRandomizedElectionTimeout(b.getElectionTimeout() + 1);

        for (int i = 0; i < b.getElectionTimeout(); i++) {
            b.tick();
        }
        nt.send(hup(1, 1));

        assertEquals(RaftRole.LEADER, a.getState());

        Raft[] sms = new Raft[]{a, b, c, a, b, c};
        int[] proposals = new int[]{10, 10, 10, 10, 10, 10};
        long[] wantIndex = new long[]{11, 21, 31, 41, 51, 61};
        byte[][] wantCtx = new byte[][]{
                "ctx1".getBytes(), "ctx2".getBytes(), "ctx3".getBytes(),
                "ctx4".getBytes(), "ctx5".getBytes(), "ctx6".getBytes(),
        };

        for (int i = 0; i < sms.length; i++) {
            for (int j = 0; j < proposals[i]; j++) {
                nt.send(prop(1, 1, emptyEntry()));
            }

            nt.send(readIndex(sms[i].getId(), wantCtx[i]));

            Raft r = sms[i];
            ReadState rs = r.getReadStates().get(0);
            assertEquals(wantIndex[i], rs.getIndex(), "#" + i);
            assertArrayEquals(wantCtx[i], rs.getRequestCtx(), "#" + i);
            r.clearReadStates();
        }
    }

    // ------------------------------------------------------------------
    // 只读请求（续）与 leader 的复制进度
    // ------------------------------------------------------------------

    /**
     * Go: TestReadOnlyForNewLeader
     */
    @Test
    // Go: TestReadOnlyForNewLeader
    void testReadOnlyForNewLeader() {
        long[] ids = new long[]{1, 2, 3};
        long[] committed = new long[]{1, 2, 2};
        long[] applied = new long[]{1, 2, 2};
        long[] compactIndex = new long[]{0, 2, 2};

        List<Object> peers = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            MemoryStorage storage = newTestMemoryStorage(1, 2, 3);
            storage.append(entries(1, 1));
            storage.setHardState(new HardState(1, 0, committed[i]));
            if (compactIndex[i] != 0) {
                storage.compact(compactIndex[i]);
            }
            RaftConfig cfg = newTestConfig(ids[i], 10, 1, storage);
            cfg.applied = applied[i];
            peers.add(new Raft(cfg));
        }
        RaftNetwork nt = network(peers.toArray());

        // Drop MsgApp to forbid peer a to commit any log entry at its term after it becomes leader.
        nt.ignore(MessageType.MSG_APP);
        // Force peer a to become leader.
        nt.send(hup(1, 1));

        Raft sm = nt.getRaft(1);
        assertEquals(RaftRole.LEADER, sm.getState());

        // Ensure peer a drops read only request.
        long windex = 4;
        byte[] wctx = "ctx".getBytes();
        nt.send(readIndex(1, wctx));
        assertTrue(sm.getReadStates().isEmpty());

        nt.recover();

        // Force peer a to commit a log entry at its term
        for (int i = 0; i < sm.getHeartbeatTimeout(); i++) {
            sm.tick();
        }
        nt.send(prop(1, 1, emptyEntry()));
        assertEquals(4, sm.getRaftLog().committed());
        long lastLogTerm = sm.getRaftLog().zeroTermOnOutOfBounds(sm.getRaftLog().committed());
        assertEquals(sm.getTerm(), lastLogTerm);

        // Ensure peer a processed postponed read only request after it committed an entry at its term.
        assertEquals(1, sm.getReadStates().size());
        ReadState rs = sm.getReadStates().get(0);
        assertEquals(windex, rs.getIndex());
        assertArrayEquals(wctx, rs.getRequestCtx());

        // Ensure peer a accepts read only request after it committed an entry at its term.
        nt.send(readIndex(1, wctx));
        assertEquals(2, sm.getReadStates().size());
        rs = sm.getReadStates().get(1);
        assertEquals(windex, rs.getIndex());
        assertArrayEquals(wctx, rs.getRequestCtx());
    }

    /**
     * Go: TestReadOnlyForNewSingleVoterLeader
     */
    @Test
    // Go: TestReadOnlyForNewSingleVoterLeader
    void testReadOnlyForNewSingleVoterLeader() {
        RaftNetwork nt = network(null, null);
        nt.send(hup(2, 2));
        Raft a = nt.getRaft(1);
        Raft b = nt.getRaft(2);

        // Peer 2 proposes its removal (index 2) and x (index 3), and commits both;
        // peer 1 learns that the removal is committed, but not x. Both peers then
        // apply the removal.
        ConfChange cc = new ConfChange(ConfChangeType.REMOVE_NODE, 2, null);
        ConfChangeCodec.EntryTypeAndData ccData = ConfChangeCodec.marshal(cc);
        nt.msgHook = m -> m.getTo() != 1 || m.getCommit() < 3;
        nt.send(
                prop(2, 2, new Entry(0, 0, ccData.getType(), ccData.getData())),
                prop(2, 2, entry(0, 0, "x")));
        assertEquals(2, a.getRaftLog().committed());
        assertEquals(3, b.getRaftLog().committed());
        a.applyConfChange(cc.asV2());
        b.applyConfChange(cc.asV2());

        // Peer 1, now the only voter, becomes leader.
        a.becomeCandidate();
        a.becomeLeader();

        // Ensure the read index of a read issued now includes x.
        byte[] wctx = "ctx".getBytes();
        nt.send(readIndex(1, wctx));
        int i = -1;
        List<ReadState> states = a.getReadStates();
        for (int j = 0; j < states.size(); j++) {
            if (Arrays.equals(states.get(j).getRequestCtx(), wctx)) {
                i = j;
                break;
            }
        }
        assertNotEquals(-1, i, "no read state for the read");
        assertTrue(states.get(i).getIndex() >= 3, "read index");
    }

    /**
     * Go: TestReadOnlyDuplicateRequest
     */
    @Test
    // Go: TestReadOnlyDuplicateRequest
    void testReadOnlyDuplicateRequest() {
        Raft r1 = newTestRaft(1, 10, 1, newTestMemoryStorage(1, 2, 3));
        Raft r2 = newTestRaft(2, 10, 1, newTestMemoryStorage(1, 2, 3));
        Raft r3 = newTestRaft(3, 10, 1, newTestMemoryStorage(1, 2, 3));
        RaftNetwork net = network(r1, r2, r3);

        // These will be delayed until a new leader gets elected and commits a new entry
        List<Message> delayedMsgs = new ArrayList<>();

        // net hook that delays (but doesn't duplicate) only heartbeat responses
        net.msgHook = m -> {
            if (m.getType() == MessageType.MSG_HEARTBEAT_RESP) {
                delayedMsgs.add(m);
                return false;
            }
            return true;
        };

        // elect r1 as leader
        net.send(hup(1, 1));

        // Create readIndexMsgA; its eventual readIndex must be least readIndexMinimumA
        Message readIndexMsgA = new Message().setFrom(2).setTo(1).setType(MessageType.MSG_READ_INDEX)
                .setEntries(Collections.singletonList(entryData(0, 0, "A".getBytes())));
        long readIndexMinimumA = r1.getRaftLog().committed();

        // Explicitly duplicate readIndexMsgA for later; this would be done either by
        // the network itself, or by users of the raft library retrying `ReadIndex()`.
        delayedMsgs.add(readIndexMsgA);

        // Process readIndex request, but delay heartbeats until the end of this test
        net.send(readIndexMsgA);
        net.msgHook = null; // stop delaying

        // tick and send whatever raft wants to send; this will finish processing readIndexA
        r1.tick();
        advanceMessagesAfterAppend(r1);
        List<Message> msgs = new ArrayList<>(r1.getMessages());
        r1.clearMessages();
        net.send(msgs.toArray(new Message[0]));

        net.isolate(r1.getId());

        // elect r2 as leader, and commit a new entry
        net.send(hup(2, 2));
        net.send(prop(2, 2, "someOp"));

        // Try reading from the disconnected and stale leader r1
        // Create readIndexMsgB; its eventual readIndex must be least readIndexMinimumB
        Message readIndexMsgB = new Message().setFrom(1).setTo(1).setType(MessageType.MSG_READ_INDEX)
                .setEntries(Collections.singletonList(entryData(0, 0, "B".getBytes())));
        long readIndexMinimumB = r2.getRaftLog().committed();

        net.send(readIndexMsgB);
        net.recover();
        // First send the delayed readIndexMsgA, then the delayed heartbeats. This
        // should not cause B to be confirmed.
        net.send(delayedMsgs.toArray(new Message[0]));

        // make sure the readIndexes aren't too small (which would violate linearizability)
        List<ReadState> all = new ArrayList<>();
        all.addAll(r1.getReadStates());
        all.addAll(r2.getReadStates());
        all.addAll(r3.getReadStates());
        for (ReadState rd : all) {
            if ("A".equals(new String(rd.getRequestCtx()))) {
                assertTrue(rd.getIndex() >= readIndexMinimumA,
                        "readIndex for A is " + rd.getIndex() + " (should be >= " + readIndexMinimumA + ")");
            } else if ("B".equals(new String(rd.getRequestCtx()))) {
                assertTrue(rd.getIndex() >= readIndexMinimumB,
                        "readIndex for B is " + rd.getIndex() + " (should be >= " + readIndexMinimumB + ")");
            }
        }
    }

    /**
     * Go: TestReadOnlyForRemovedLeader
     */
    @Test
    // Go: TestReadOnlyForRemovedLeader
    void testReadOnlyForRemovedLeader() {
        RaftNetwork nt = network(null, null);
        nt.send(hup(2, 2));
        Raft a = nt.getRaft(1);
        Raft b = nt.getRaft(2);

        // Peer 2 removes itself (index 2), and both peers apply the removal once it
        // commits. Peer 2 stays leader.
        ConfChange cc = new ConfChange(ConfChangeType.REMOVE_NODE, 2, null);
        ConfChangeCodec.EntryTypeAndData ccData = ConfChangeCodec.marshal(cc);
        nt.send(prop(2, 2, new Entry(0, 0, ccData.getType(), ccData.getData())));
        assertEquals(2, a.getRaftLog().committed());
        assertEquals(2, b.getRaftLog().committed());
        a.applyConfChange(cc.asV2());
        b.applyConfChange(cc.asV2());

        // Peer 1, now the only voter, becomes leader and commits y.
        a.becomeCandidate();
        a.becomeLeader();
        nt.send(prop(1, 1, "y"));
        long y = a.getRaftLog().lastIndex();
        assertEquals(y, a.getRaftLog().committed());

        // Ensure the read index of a read issued now at peer 2 includes y.
        byte[] wctx = "ctx".getBytes();
        nt.send(readIndex(2, wctx));
        for (ReadState rs : b.getReadStates()) {
            if (Arrays.equals(rs.getRequestCtx(), wctx)) {
                assertTrue(rs.getIndex() >= y, "read index");
            }
        }
    }

    /**
     * Go: TestLeaderAppResp
     */
    @Test
    // Go: TestLeaderAppResp
    void testLeaderAppResp() {
        // initial progress: match = 0; next = 3
        long[] indexes = new long[]{3, 2, 2, 0};
        boolean[] rejects = new boolean[]{true, true, false, false};
        long[] wantMatch = new long[]{0, 0, 2, 0};
        long[] wantNext = new long[]{3, 2, 4, 4};
        int[] wantMsgNum = new int[]{0, 1, 2, 1};
        long[] wantIndex = new long[]{0, 1, 2, 0};
        long[] wantCommitted = new long[]{0, 0, 2, 0};

        for (int i = 0; i < indexes.length; i++) {
            MemoryStorage storage = newTestMemoryStorage(1, 2, 3);
            // Go 里直接把 raftLog 换成一份 index(0).terms(0, 1, 1) 的日志
            storage.append(entries(1, 1));
            Raft sm = newTestRaft(1, 10, 1, storage);
            sm.becomeCandidate();
            sm.becomeLeader();
            readMessages(sm);
            sm.step(new Message().setFrom(2).setType(MessageType.MSG_APP_RESP)
                    .setIndex(indexes[i]).setTerm(sm.getTerm()).setReject(rejects[i])
                    .setRejectHint(indexes[i]));

            Progress p = sm.getTracker().getProgress().get(2);
            assertEquals(wantMatch[i], p.getMatch());
            assertEquals(wantNext[i], p.getNext());

            List<Message> out = readMessages(sm);

            assertEquals(wantMsgNum[i], out.size());
            for (Message msg : out) {
                assertEquals(wantIndex[i], msg.getIndex());
                assertEquals(wantCommitted[i], msg.getCommit());
            }
        }
    }

    /**
     * Go: TestBcastBeat
     */
    @Test
    // Go: TestBcastBeat
    void testBcastBeat() {
        long offset = 1000;
        // make a state machine with log.offset = 1000
        ConfState confState = new ConfState(Arrays.asList(1L, 2L, 3L),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), false);
        Snapshot snap = new Snapshot(new byte[0],
                new SnapshotMetadata(confState, offset, 1));
        MemoryStorage storage = new MemoryStorage();
        storage.applySnapshot(snap);
        Raft sm = newTestRaft(1, 10, 1, storage);
        sm.setTermForTest(1);

        sm.becomeCandidate();
        sm.becomeLeader();
        for (int i = 0; i < 10; i++) {
            mustAppendEntry(sm, new Entry(i + 1, 0, EntryType.NORMAL, null));
        }
        advanceMessagesAfterAppend(sm);

        // slow follower
        sm.getTracker().getProgress().get(2).setMatch(5);
        sm.getTracker().getProgress().get(2).setNext(6);
        // normal follower
        long lastIndex = sm.getRaftLog().lastIndex();
        sm.getTracker().getProgress().get(3).setMatch(lastIndex);
        sm.getTracker().getProgress().get(3).setNext(lastIndex + 1);

        sm.step(new Message().setType(MessageType.MSG_BEAT));
        List<Message> msgs = readMessages(sm);
        assertEquals(2, msgs.size());

        java.util.Map<Long, Long> wantCommitMap = new java.util.HashMap<>();
        wantCommitMap.put(2L, Math.min(sm.getRaftLog().committed(),
                sm.getTracker().getProgress().get(2).getMatch()));
        wantCommitMap.put(3L, Math.min(sm.getRaftLog().committed(),
                sm.getTracker().getProgress().get(3).getMatch()));
        for (int i = 0; i < msgs.size(); i++) {
            Message m = msgs.get(i);
            assertEquals(MessageType.MSG_HEARTBEAT, m.getType(), "#" + i);
            assertEquals(0, m.getIndex(), "#" + i);
            assertEquals(0, m.getLogTerm(), "#" + i);

            Long commit = wantCommitMap.remove(m.getTo());
            assertTrue(commit != null, "#" + i);
            assertEquals(commit.longValue(), m.getCommit(), "#" + i);

            assertTrue(m.getEntries() == null || m.getEntries().isEmpty(), "#" + i);
        }
        assertTrue(wantCommitMap.isEmpty());
    }

    /**
     * Go: TestRecvMsgBeat
     */
    @Test
    // Go: TestRecvMsgBeat
    void testRecvMsgBeat() {
        RaftRole[] states = new RaftRole[]{RaftRole.LEADER, RaftRole.CANDIDATE, RaftRole.FOLLOWER};
        int[] wantMsg = new int[]{2, 0, 0};

        for (int i = 0; i < states.length; i++) {
            MemoryStorage storage = newTestMemoryStorage(1, 2, 3);
            // Go 里直接把 raftLog 换成一份 index(0).terms(0, 1, 1) 的日志
            storage.append(entries(1, 1));
            Raft sm = newTestRaft(1, 10, 1, storage);
            sm.setTermForTest(1);
            sm.setStateForTest(states[i]);
            sm.step(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_BEAT));

            List<Message> msgs = readMessages(sm);
            assertEquals(wantMsg[i], msgs.size(), "#" + i);
            for (Message m : msgs) {
                assertEquals(MessageType.MSG_HEARTBEAT, m.getType(), "#" + i);
            }
        }
    }

    /**
     * Go: TestLeaderIncreaseNext
     */
    @Test
    // Go: TestLeaderIncreaseNext
    void testLeaderIncreaseNext() {
        MemoryStorage storage = newTestMemoryStorage(1, 2);
        storage.append(entries(1, 2, 3));
        State[] states = new State[]{State.REPLICATE, State.PROBE};
        long[] nexts = new long[]{2, 2};
        long[] wantNext = new long[]{6, 2};

        for (int i = 0; i < states.length; i++) {
            Raft sm = newTestRaft(1, 10, 1, storage);
            sm.becomeCandidate();
            sm.becomeLeader();
            sm.getTracker().getProgress().get(2).setState(states[i]);
            sm.getTracker().getProgress().get(2).setNext(nexts[i]);
            sm.step(prop(1, 1, "somedata"));

            Progress p = sm.getTracker().getProgress().get(2);
            assertEquals(wantNext[i], p.getNext(), "#" + i);
        }
    }

    // ------------------------------------------------------------------
    // 复制进度与快照恢复
    // ------------------------------------------------------------------

    /**
     * Go: TestSendAppendForProgressProbe
     */
    @Test
    // Go: TestSendAppendForProgressProbe
    void testSendAppendForProgressProbe() {
        Raft r = newTestRaft(1, 10, 1, newTestMemoryStorage(1, 2));
        r.becomeCandidate();
        r.becomeLeader();
        readMessages(r);
        r.getTracker().getProgress().get(2).becomeProbe();

        // each round is a heartbeat
        for (int i = 0; i < 3; i++) {
            if (i == 0) {
                // we expect that raft will only send out one msgAPP on the first
                // loop. After that, the follower is paused until a heartbeat response is
                // received.
                mustAppendEntry(r, entry(0, 0, "somedata"));
                r.sendAppend(2);
                List<Message> msg = readMessages(r);
                assertEquals(1, msg.size());
                assertEquals(0, msg.get(0).getIndex());
            }

            assertTrue(r.getTracker().getProgress().get(2).isMsgAppFlowPaused());
            for (int j = 0; j < 10; j++) {
                mustAppendEntry(r, entry(0, 0, "somedata"));
                r.sendAppend(2);
                assertTrue(readMessages(r).isEmpty());
            }

            // do a heartbeat
            for (int j = 0; j < r.getHeartbeatTimeout(); j++) {
                r.step(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_BEAT));
            }
            assertTrue(r.getTracker().getProgress().get(2).isMsgAppFlowPaused());

            // consume the heartbeat
            List<Message> msg = readMessages(r);
            assertEquals(1, msg.size());
            assertEquals(MessageType.MSG_HEARTBEAT, msg.get(0).getType());
        }

        // a heartbeat response will allow another message to be sent
        r.step(new Message().setFrom(2).setTo(1).setType(MessageType.MSG_HEARTBEAT_RESP));
        List<Message> msg = readMessages(r);
        assertEquals(1, msg.size());
        assertEquals(0, msg.get(0).getIndex());
        assertTrue(r.getTracker().getProgress().get(2).isMsgAppFlowPaused());
    }

    /**
     * Go: TestSendAppendForProgressReplicate
     */
    @Test
    // Go: TestSendAppendForProgressReplicate
    void testSendAppendForProgressReplicate() {
        Raft r = newTestRaft(1, 10, 1, newTestMemoryStorage(1, 2));
        r.becomeCandidate();
        r.becomeLeader();
        readMessages(r);
        r.getTracker().getProgress().get(2).becomeReplicate();

        for (int i = 0; i < 10; i++) {
            mustAppendEntry(r, entry(0, 0, "somedata"));
            r.sendAppend(2);
            List<Message> msgs = readMessages(r);
            assertEquals(1, msgs.size(), "#" + i);
        }
    }

    /**
     * Go: TestSendAppendForProgressSnapshot
     */
    @Test
    // Go: TestSendAppendForProgressSnapshot
    void testSendAppendForProgressSnapshot() {
        Raft r = newTestRaft(1, 10, 1, newTestMemoryStorage(1, 2));
        r.becomeCandidate();
        r.becomeLeader();
        readMessages(r);
        r.getTracker().getProgress().get(2).becomeSnapshot(10);

        for (int i = 0; i < 10; i++) {
            mustAppendEntry(r, entry(0, 0, "somedata"));
            r.sendAppend(2);
            List<Message> msgs = readMessages(r);
            assertTrue(msgs.isEmpty(), "#" + i);
        }
    }

    /**
     * Go: TestRecvMsgUnreachable
     */
    @Test
    // Go: TestRecvMsgUnreachable
    void testRecvMsgUnreachable() {
        List<Entry> previousEnts = entries(1, 2, 3);
        MemoryStorage s = newTestMemoryStorage(1, 2);
        s.append(previousEnts);
        Raft r = newTestRaft(1, 10, 1, s);
        r.becomeCandidate();
        r.becomeLeader();
        readMessages(r);
        // set node 2 to state replicate
        Progress pr = r.getTracker().getProgress().get(2);
        pr.setMatch(3);
        pr.becomeReplicate();
        pr.setNext(6);

        r.step(new Message().setFrom(2).setTo(1).setType(MessageType.MSG_UNREACHABLE));

        assertEquals(State.PROBE, r.getTracker().getProgress().get(2).getState());
        long wnext = r.getTracker().getProgress().get(2).getMatch() + 1;
        assertEquals(wnext, r.getTracker().getProgress().get(2).getNext());
    }

    /**
     * Go: TestRestore
     */
    @Test
    // Go: TestRestore
    void testRestore() {
        Snapshot s = snapshot(11, 11, new ConfState(
                Arrays.asList(1L, 2L, 3L), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), false));

        MemoryStorage storage = newTestMemoryStorage(1, 2);
        Raft sm = newTestRaft(1, 10, 1, storage);
        assertTrue(sm.restore(s));

        assertEquals(s.getLastIncludedIndex(), sm.getRaftLog().lastIndex());
        assertEquals(s.getLastIncludedTerm(), sm.getRaftLog().term(s.getLastIncludedIndex()));
        assertEquals(Arrays.asList(1L, 2L, 3L), sm.getTracker().voterNodes());

        assertFalse(sm.restore(s));
        for (int i = 0; i < sm.randomizedElectionTimeout(); i++) {
            sm.tick();
        }
        assertEquals(RaftRole.FOLLOWER, sm.getState());
    }

    /**
     * Go: TestRestoreWithLearner
     */
    @Test
    // Go: TestRestoreWithLearner
    void testRestoreWithLearner() {
        Snapshot s = snapshot(11, 11, new ConfState(
                Arrays.asList(1L, 2L), Collections.singletonList(3L),
                Collections.emptyList(), Collections.emptyList(), false));

        MemoryStorage storage = newTestMemoryStorageWithLearners(new long[]{1, 2}, new long[]{3});
        Raft sm = newTestLearnerRaft(3, 8, 2, storage);
        assertTrue(sm.restore(s));

        assertEquals(s.getLastIncludedIndex(), sm.getRaftLog().lastIndex());
        assertEquals(s.getLastIncludedTerm(), sm.getRaftLog().term(s.getLastIncludedIndex()));

        List<Long> sg = sm.getTracker().voterNodes();
        assertEquals(s.getConfState().getVoters().size(), sg.size());

        List<Long> lns = sm.getTracker().learnerNodes();
        assertEquals(s.getConfState().getLearners().size(), lns.size());

        for (Long n : s.getConfState().getVoters()) {
            assertFalse(sm.getTracker().getProgress().get(n).isLearner());
        }
        for (Long n : s.getConfState().getLearners()) {
            assertTrue(sm.getTracker().getProgress().get(n).isLearner());
        }

        assertFalse(sm.restore(s));
    }

    /**
     * Go: TestRestoreWithVotersOutgoing
     */
    @Test
    // Go: TestRestoreWithVotersOutgoing
    void testRestoreWithVotersOutgoing() {
        Snapshot s = snapshot(11, 11, new ConfState(
                Arrays.asList(2L, 3L, 4L), Collections.emptyList(),
                Arrays.asList(1L, 2L, 3L), Collections.emptyList(), false));

        MemoryStorage storage = newTestMemoryStorage(1, 2);
        Raft sm = newTestRaft(1, 10, 1, storage);
        assertTrue(sm.restore(s));

        assertEquals(s.getLastIncludedIndex(), sm.getRaftLog().lastIndex());
        assertEquals(sm.getRaftLog().term(s.getLastIncludedIndex()), s.getLastIncludedTerm());

        List<Long> sg = sm.getTracker().voterNodes();
        assertEquals(Arrays.asList(1L, 2L, 3L, 4L), sg);

        assertFalse(sm.restore(s));

        // It should not campaign before actually applying data.
        for (int i = 0; i < sm.randomizedElectionTimeout(); i++) {
            sm.tick();
        }
        assertEquals(RaftRole.FOLLOWER, sm.getState());
    }

    /**
     * Go: TestRestoreVoterToLearner
     */
    @Test
    // Go: TestRestoreVoterToLearner
    void testRestoreVoterToLearner() {
        Snapshot s = snapshot(11, 11, new ConfState(
                Arrays.asList(1L, 2L), Collections.singletonList(3L),
                Collections.emptyList(), Collections.emptyList(), false));

        MemoryStorage storage = newTestMemoryStorage(1, 2, 3);
        Raft sm = newTestRaft(3, 10, 1, storage);

        assertFalse(sm.isLearner());
        assertTrue(sm.restore(s));
    }

    /**
     * Go: TestRestoreLearnerPromotion
     */
    @Test
    // Go: TestRestoreLearnerPromotion
    void testRestoreLearnerPromotion() {
        Snapshot s = snapshot(11, 11, new ConfState(
                Arrays.asList(1L, 2L, 3L), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), false));

        MemoryStorage storage = newTestMemoryStorageWithLearners(new long[]{1, 2}, new long[]{3});
        Raft sm = newTestLearnerRaft(3, 10, 1, storage);

        assertTrue(sm.isLearner());
        assertTrue(sm.restore(s));
        assertFalse(sm.isLearner());
    }

    /**
     * Go: TestLearnerReceiveSnapshot
     */
    @Test
    // Go: TestLearnerReceiveSnapshot
    void testLearnerReceiveSnapshot() {
        // restore the state machine from a snapshot so it has a compacted log and a snapshot
        Snapshot s = snapshot(11, 11, new ConfState(
                Collections.singletonList(1L), Collections.singletonList(2L),
                Collections.emptyList(), Collections.emptyList(), false));

        MemoryStorage store = newTestMemoryStorageWithLearners(new long[]{1}, new long[]{2});
        Raft n1 = newTestLearnerRaft(1, 10, 1, store);
        Raft n2 = newTestLearnerRaft(2, 10, 1,
                newTestMemoryStorageWithLearners(new long[]{1}, new long[]{2}));

        n1.restore(s);
        Snapshot snap = n1.getRaftLog().nextUnstableSnapshot();
        store.applySnapshot(snap);
        n1.appliedSnap(snap);

        RaftNetwork nt = network(n1, n2);

        n1.setRandomizedElectionTimeout(n1.getElectionTimeout());
        for (int i = 0; i < n1.getElectionTimeout(); i++) {
            n1.tick();
        }

        nt.send(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_BEAT));

        assertEquals(n1.getRaftLog().committed(), n2.getRaftLog().committed());
    }

    /**
     * Go: TestRestoreIgnoreSnapshot
     */
    @Test
    // Go: TestRestoreIgnoreSnapshot
    void testRestoreIgnoreSnapshot() {
        List<Entry> previousEnts = entries(1, 1, 1);
        long commit = 1;
        MemoryStorage storage = newTestMemoryStorage(1, 2);
        Raft sm = newTestRaft(1, 10, 1, storage);
        sm.getRaftLog().append(previousEnts);
        sm.getRaftLog().commitTo(commit);

        Snapshot s = snapshot(commit, 1, new ConfState(
                Arrays.asList(1L, 2L), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), false));

        // ignore snapshot
        assertFalse(sm.restore(s));
        assertEquals(commit, sm.getRaftLog().committed());

        // ignore snapshot and fast forward commit
        Snapshot s2 = snapshot(commit + 1, 1, new ConfState(
                Arrays.asList(1L, 2L), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), false));
        assertFalse(sm.restore(s2));
        assertEquals(commit + 1, sm.getRaftLog().committed());
    }

    /**
     * Go: TestProvideSnap
     */
    @Test
    // Go: TestProvideSnap
    void testProvideSnap() {
        // restore the state machine from a snapshot so it has a compacted log and a snapshot
        Snapshot s = snapshot(11, 11, new ConfState(
                Arrays.asList(1L, 2L), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), false));
        MemoryStorage storage = newTestMemoryStorage(1);
        Raft sm = newTestRaft(1, 10, 1, storage);
        sm.restore(s);

        sm.becomeCandidate();
        sm.becomeLeader();

        // force set the next of node 2, so that node 2 needs a snapshot
        sm.getTracker().getProgress().get(2).setNext(sm.getRaftLog().firstIndex());
        sm.step(new Message().setFrom(2).setTo(1).setType(MessageType.MSG_APP_RESP)
                .setIndex(sm.getTracker().getProgress().get(2).getNext() - 1).setReject(true));

        List<Message> msgs = readMessages(sm);
        assertEquals(1, msgs.size());
        Message m = msgs.get(0);
        assertEquals(MessageType.MSG_SNAP, m.getType());
    }

    /**
     * Go: TestIgnoreProvidingSnap
     */
    @Test
    // Go: TestIgnoreProvidingSnap
    void testIgnoreProvidingSnap() {
        // restore the state machine from a snapshot so it has a compacted log and a snapshot
        Snapshot s = snapshot(11, 11, new ConfState(
                Arrays.asList(1L, 2L), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), false));
        MemoryStorage storage = newTestMemoryStorage(1);
        Raft sm = newTestRaft(1, 10, 1, storage);
        sm.restore(s);

        sm.becomeCandidate();
        sm.becomeLeader();

        // force set the next of node 2, so that node 2 needs a snapshot
        // change node 2 to be inactive, expect node 1 ignore sending snapshot to 2
        sm.getTracker().getProgress().get(2).setNext(sm.getRaftLog().firstIndex() - 1);
        sm.getTracker().getProgress().get(2).setRecentActive(false);

        sm.step(prop(1, 1, "somedata"));

        List<Message> msgs = readMessages(sm);
        assertTrue(msgs.isEmpty());
    }

    /**
     * Go: TestRestoreFromSnapMsg
     */
    @Test
    // Go: TestRestoreFromSnapMsg
    void testRestoreFromSnapMsg() {
        Snapshot s = snapshot(11, 11, new ConfState(
                Arrays.asList(1L, 2L), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), false));
        Message m = new Message().setType(MessageType.MSG_SNAP).setFrom(1).setTerm(2).setSnapshot(s);

        Raft sm = newTestRaft(2, 10, 1, newTestMemoryStorage(1, 2));
        sm.step(m);

        assertEquals(1, sm.getLead());
    }

    /**
     * Go: TestSlowNodeRestore
     */
    @Test
    // Go: TestSlowNodeRestore
    void testSlowNodeRestore() {
        RaftNetwork nt = network(null, null, null);
        nt.send(hup(1, 1));

        nt.isolate(3);
        for (int j = 0; j <= 100; j++) {
            nt.send(prop(1, 1, emptyEntry()));
        }
        Raft lead = nt.getRaft(1);
        nextEnts(lead, nt.getStorage(1));
        nt.getStorage(1).createSnapshot(lead.getRaftLog().applied(),
                new ConfState(lead.getTracker().voterNodes(), Collections.emptyList(),
                        Collections.emptyList(), Collections.emptyList(), false), null);
        nt.getStorage(1).compact(lead.getRaftLog().applied());

        nt.recover();
        // send heartbeats so that the leader can learn everyone is active.
        // node 3 will only be considered as active when node 1 receives a reply from it.
        while (true) {
            nt.send(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_BEAT));
            if (lead.getTracker().getProgress().get(3).isRecentActive()) {
                break;
            }
        }

        // trigger a snapshot
        nt.send(prop(1, 1, emptyEntry()));

        Raft follower = nt.getRaft(3);

        // trigger a commit
        nt.send(prop(1, 1, emptyEntry()));
        assertEquals(lead.getRaftLog().committed(), follower.getRaftLog().committed());
    }

    // ------------------------------------------------------------------
    // 领导权转移
    // ------------------------------------------------------------------

    /**
     * Go: TestLeaderTransferToUpToDateNode
     *
     * <p>转移目标的日志是最新的时，转移立刻完成。</p>
     */
    @Test
    void testLeaderTransferToUpToDateNode() {
        RaftNetwork nt = network(null, null, null);
        nt.send(hup(1, 1));

        Raft lead = nt.getRaft(1);
        assertEquals(1, lead.getLead());

        // 把领导权转移给 2
        nt.send(transferLeader(2, 1));

        checkLeaderTransferState(lead, RaftRole.FOLLOWER, 2);

        // 复制了一些日志之后，再把领导权转回 1
        nt.send(prop(1, 1, emptyEntry()));

        nt.send(transferLeader(1, 2));

        checkLeaderTransferState(lead, RaftRole.LEADER, 1);
    }

    /**
     * Go: TestLeaderTransferToUpToDateNodeFromFollower
     *
     * <p>与 {@link #testLeaderTransferToUpToDateNode()} 的区别是：转移消息
     * 都发给 follower，由 follower 转发给 leader。</p>
     */
    @Test
    void testLeaderTransferToUpToDateNodeFromFollower() {
        RaftNetwork nt = network(null, null, null);
        nt.send(hup(1, 1));

        Raft lead = nt.getRaft(1);
        assertEquals(1, lead.getLead());

        // 把领导权转移给 2，消息发给 2 自己
        nt.send(transferLeader(2, 2));

        checkLeaderTransferState(lead, RaftRole.FOLLOWER, 2);

        // 复制了一些日志之后，再把领导权转回 1
        nt.send(prop(1, 1, emptyEntry()));

        nt.send(transferLeader(1, 1));

        checkLeaderTransferState(lead, RaftRole.LEADER, 1);
    }

    /**
     * Go: TestLeaderTransferWithCheckQuorum
     *
     * <p>开启 checkQuorum 后，leader 即使还在 leader lease 里，转移也能完成。</p>
     */
    @Test
    void testLeaderTransferWithCheckQuorum() {
        RaftNetwork nt = networkWithConfig(cfg -> cfg.checkQuorum = true, null, null, null);
        for (long i = 1; i < 4; i++) {
            Raft r = nt.getRaft(i);
            r.setRandomizedElectionTimeout(r.getElectionTimeout() + (int) i);
        }

        // 让 2 号节点的 electionElapsed 走到超时，这样它才能给 1 号节点投票
        Raft f = nt.getRaft(2);
        for (int i = 0; i < f.getElectionTimeout(); i++) {
            f.tick();
        }

        nt.send(hup(1, 1));

        Raft lead = nt.getRaft(1);
        assertEquals(RaftRole.LEADER, lead.getState());

        // 把领导权转移给 2
        nt.send(transferLeader(2, 1));

        checkLeaderTransferState(lead, RaftRole.FOLLOWER, 2);

        // 复制了一些日志之后，再把领导权转回 1
        nt.send(prop(1, 1, emptyEntry()));

        nt.send(transferLeader(1, 2));

        checkLeaderTransferState(lead, RaftRole.LEADER, 1);
    }

    /**
     * Go: TestLeaderTransferToSlowFollower
     *
     * <p>转移目标缺日志时，leader 先把日志补齐，等对方追上再转移。</p>
     */
    @Test
    void testLeaderTransferToSlowFollower() {
        RaftNetwork nt = network(null, null, null);
        nt.send(hup(1, 1));

        nt.isolate(3);
        nt.send(prop(1, 1, emptyEntry()));

        nt.recover();
        Raft lead = nt.getRaft(1);
        assertEquals(1, lead.getTracker().getProgress().get(3).getMatch());

        // 3 号节点缺日志时转移领导权
        nt.send(transferLeader(3, 1));

        checkLeaderTransferState(lead, RaftRole.FOLLOWER, 3);
    }

    /**
     * Go: TestLeaderTransferAfterSnapshot
     *
     * <p>转移目标落后太多、只能靠快照追上时，leader 要等快照被应用后才转移。</p>
     */
    @Test
    void testLeaderTransferAfterSnapshot() {
        RaftNetwork nt = network(null, null, null);
        nt.send(hup(1, 1));

        nt.isolate(3);

        nt.send(prop(1, 1, emptyEntry()));
        Raft lead = nt.getRaft(1);
        nextEnts(lead, nt.getStorage(1));
        nt.getStorage(1).createSnapshot(lead.getRaftLog().applied(),
                new ConfState(lead.getTracker().voterNodes(), Collections.emptyList(),
                        Collections.emptyList(), Collections.emptyList(), false), null);
        nt.getStorage(1).compact(lead.getRaftLog().applied());

        nt.recover();
        assertEquals(1, lead.getTracker().getProgress().get(3).getMatch());

        final Message[] filtered = new Message[1];
        // 快照要先被应用，之后才能回 MsgAppResp
        nt.msgHook = m -> {
            if (m.getType() != MessageType.MSG_APP_RESP || m.getFrom() != 3 || m.getReject()) {
                return true;
            }
            filtered[0] = m;
            return false;
        };
        // 3 号节点缺快照时转移领导权
        nt.send(transferLeader(3, 1));
        assertEquals(RaftRole.LEADER, lead.getState());
        assertNotNull(filtered[0]);

        // 应用快照并恢复复制进度
        Raft follower = nt.getRaft(3);
        Snapshot snap = follower.getUnstableSnapshot();
        nt.getStorage(3).applySnapshot(snap);
        follower.appliedSnap(snap);
        nt.msgHook = null;
        nt.send(filtered[0]);

        checkLeaderTransferState(lead, RaftRole.FOLLOWER, 3);
    }

    /**
     * Go: TestLeaderTransferToSelf
     */
    @Test
    void testLeaderTransferToSelf() {
        RaftNetwork nt = network(null, null, null);
        nt.send(hup(1, 1));

        Raft lead = nt.getRaft(1);

        // 把领导权转移给自己，什么也不会发生
        nt.send(transferLeader(1, 1));
        checkLeaderTransferState(lead, RaftRole.LEADER, 1);
    }

    /**
     * Go: TestLeaderTransferToNonExistingNode
     */
    @Test
    void testLeaderTransferToNonExistingNode() {
        RaftNetwork nt = network(null, null, null);
        nt.send(hup(1, 1));

        Raft lead = nt.getRaft(1);
        // 把领导权转移给不存在的节点，什么也不会发生
        nt.send(transferLeader(4, 1));
        checkLeaderTransferState(lead, RaftRole.LEADER, 1);
    }

    /**
     * Go: TestLeaderTransferTimeout
     *
     * <p>转移目标一直不响应时，一个选举超时后 leader 收回领导权。</p>
     */
    @Test
    void testLeaderTransferTimeout() {
        RaftNetwork nt = network(null, null, null);
        nt.send(hup(1, 1));

        nt.isolate(3);

        Raft lead = nt.getRaft(1);

        // 把领导权转移给被隔离的节点，等到超时
        nt.send(transferLeader(3, 1));
        assertEquals(3, lead.getLeadTransferee());

        for (int i = 0; i < lead.getHeartbeatTimeout(); i++) {
            lead.tick();
        }
        assertEquals(3, lead.getLeadTransferee());

        for (int i = 0; i < lead.getElectionTimeout() - lead.getHeartbeatTimeout(); i++) {
            lead.tick();
        }

        checkLeaderTransferState(lead, RaftRole.LEADER, 1);
    }

    /**
     * Go: TestLeaderTransferIgnoreProposal
     *
     * <p>转移进行中，leader 丢弃所有提案。</p>
     */
    @Test
    void testLeaderTransferIgnoreProposal() {
        MemoryStorage s = newTestMemoryStorage(1, 2, 3);
        Raft r = newTestRaft(1, 10, 1, s);
        RaftNetwork nt = network(r, null, null);
        nt.send(hup(1, 1));

        nt.isolate(3);

        Raft lead = nt.getRaft(1);

        nextEnts(r, s); // 处理掉空条目

        // 先把领导权转移给被隔离的节点，让转移处于挂起状态，再发提案
        nt.send(transferLeader(3, 1));
        assertEquals(3, lead.getLeadTransferee());

        nt.send(prop(1, 1, emptyEntry()));
        assertFalse(lead.step(prop(1, 1, emptyEntry())));

        assertEquals(1, lead.getTracker().getProgress().get(1).getMatch());
    }

    /**
     * Go: TestLeaderTransferReceiveHigherTermVote
     *
     * <p>转移期间收到更高任期的投票请求，leader 立刻退位。</p>
     */
    @Test
    void testLeaderTransferReceiveHigherTermVote() {
        RaftNetwork nt = network(null, null, null);
        nt.send(hup(1, 1));

        nt.isolate(3);

        Raft lead = nt.getRaft(1);

        // 先把领导权转移给被隔离的节点，让转移处于挂起状态
        nt.send(transferLeader(3, 1));
        assertEquals(3, lead.getLeadTransferee());

        nt.send(new Message().setFrom(2).setTo(2).setType(MessageType.MSG_HUP)
                .setIndex(1).setTerm(2));

        checkLeaderTransferState(lead, RaftRole.FOLLOWER, 2);
    }

    /**
     * Go: TestLeaderTransferRemoveNode
     *
     * <p>转移目标被移出集群时，转移作废。</p>
     */
    @Test
    void testLeaderTransferRemoveNode() {
        RaftNetwork nt = network(null, null, null);
        nt.send(hup(1, 1));

        nt.ignore(MessageType.MSG_TIMEOUT_NOW);

        Raft lead = nt.getRaft(1);

        // 转移过程中，转移目标被移出集群
        nt.send(transferLeader(3, 1));
        assertEquals(3, lead.getLeadTransferee());

        lead.applyConfChange(new ConfChange(ConfChangeType.REMOVE_NODE, 3, null).asV2());

        checkLeaderTransferState(lead, RaftRole.LEADER, 1);
    }

    /**
     * Go: TestLeaderTransferDemoteNode
     *
     * <p>转移目标被降级成 learner 时，转移作废。</p>
     */
    @Test
    void testLeaderTransferDemoteNode() {
        RaftNetwork nt = network(null, null, null);
        nt.send(hup(1, 1));

        nt.ignore(MessageType.MSG_TIMEOUT_NOW);

        Raft lead = nt.getRaft(1);

        // 转移过程中，转移目标被降级
        nt.send(transferLeader(3, 1));
        assertEquals(3, lead.getLeadTransferee());

        lead.applyConfChange(new ConfChangeV2(ConfChangeTransition.AUTO,
                Arrays.asList(new ConfChangeSingle(ConfChangeType.REMOVE_NODE, 3),
                        new ConfChangeSingle(ConfChangeType.ADD_LEARNER_NODE, 3)),
                null));

        // 让集群提交 LeaveJoint 条目
        lead.applyConfChange(new ConfChangeV2(ConfChangeTransition.AUTO,
                Collections.emptyList(), null));
        checkLeaderTransferState(lead, RaftRole.LEADER, 1);
    }

    /**
     * Go: TestLeaderTransferBack
     *
     * <p>上一次转移还挂着时，可以把领导权转回自己。</p>
     */
    @Test
    void testLeaderTransferBack() {
        RaftNetwork nt = network(null, null, null);
        nt.send(hup(1, 1));

        nt.isolate(3);

        Raft lead = nt.getRaft(1);

        nt.send(transferLeader(3, 1));
        assertEquals(3, lead.getLeadTransferee());

        // 把领导权转回自己
        nt.send(transferLeader(1, 1));

        checkLeaderTransferState(lead, RaftRole.LEADER, 1);
    }

    /**
     * Go: TestLeaderTransferSecondTransferToAnotherNode
     *
     * <p>上一次转移还挂着时，可以改转移到别的节点。</p>
     */
    @Test
    void testLeaderTransferSecondTransferToAnotherNode() {
        RaftNetwork nt = network(null, null, null);
        nt.send(hup(1, 1));

        nt.isolate(3);

        Raft lead = nt.getRaft(1);

        nt.send(transferLeader(3, 1));
        assertEquals(3, lead.getLeadTransferee());

        // 把领导权转移给另一个节点
        nt.send(transferLeader(2, 1));

        checkLeaderTransferState(lead, RaftRole.FOLLOWER, 2);
    }

    /**
     * Go: TestLeaderTransferSecondTransferToSameNode
     *
     * <p>对同一个节点重复发起转移，不会延长转移的超时时间。</p>
     */
    @Test
    void testLeaderTransferSecondTransferToSameNode() {
        RaftNetwork nt = network(null, null, null);
        nt.send(hup(1, 1));

        nt.isolate(3);

        Raft lead = nt.getRaft(1);

        nt.send(transferLeader(3, 1));
        assertEquals(3, lead.getLeadTransferee());

        for (int i = 0; i < lead.getHeartbeatTimeout(); i++) {
            lead.tick();
        }
        // 第二次向同一个节点发起转移
        nt.send(transferLeader(3, 1));

        for (int i = 0; i < lead.getElectionTimeout() - lead.getHeartbeatTimeout(); i++) {
            lead.tick();
        }

        checkLeaderTransferState(lead, RaftRole.LEADER, 1);
    }

    /**
     * Go: TestTransferNonMember
     *
     * <p>已经被移出集群的节点收到 MsgTimeoutNow 时什么也不做，
     * 否则它会在转为 leader 时 panic。</p>
     */
    @Test
    void testTransferNonMember() {
        Raft r = newTestRaft(1, 5, 1, newTestMemoryStorage(2, 3, 4));
        r.step(new Message().setFrom(2).setTo(1).setType(MessageType.MSG_TIMEOUT_NOW));

        r.step(new Message().setFrom(2).setTo(1).setType(MessageType.MSG_VOTE_RESP));
        r.step(new Message().setFrom(3).setTo(1).setType(MessageType.MSG_VOTE_RESP));
        assertEquals(RaftRole.FOLLOWER, r.getState());
    }

    // ------------------------------------------------------------------
    // 成员变更
    // ------------------------------------------------------------------

    /**
     * Go: TestStepConfig
     *
     * <p>leader 收到成员变更提案时，把它追加到日志并记下待处理的位置。</p>
     */
    @Test
    void testStepConfig() {
        // 一个没法推进的 raft
        Raft r = newTestRaft(1, 10, 1, newTestMemoryStorage(1, 2));
        r.becomeCandidate();
        r.becomeLeader();
        long index = r.getRaftLog().lastIndex();
        r.step(confChangeProp(1, EntryType.CONFIG_CHANGE, null));
        assertEquals(index + 1, r.getRaftLog().lastIndex());
        assertEquals(index + 1, r.getPendingConfIndex());
    }

    /**
     * Go: TestStepIgnoreConfig
     *
     * <p>上一条成员变更还没提交时，再来的成员变更被降级成普通条目。</p>
     */
    @Test
    void testStepIgnoreConfig() {
        // 一个没法推进的 raft
        Raft r = newTestRaft(1, 10, 1, newTestMemoryStorage(1, 2));
        r.becomeCandidate();
        r.becomeLeader();
        r.step(confChangeProp(1, EntryType.CONFIG_CHANGE, null));
        long index = r.getRaftLog().lastIndex();
        long pendingConfIndex = r.getPendingConfIndex();
        r.step(confChangeProp(1, EntryType.CONFIG_CHANGE, null));

        List<Entry> ents = r.getRaftLog().entries(index + 1, r.getRaftLog().lastIndex() + 1);
        assertEquals(1, ents.size());
        assertEquals(EntryType.NORMAL, ents.get(0).getType());
        assertEquals(1, ents.get(0).getTerm());
        assertEquals(index + 1, ents.get(0).getIndex());
        // Java 侧把空的 data 统一成空数组，Go 那边是 nil
        assertEquals(0, ents.get(0).getData().length);
        assertEquals(pendingConfIndex, r.getPendingConfIndex());
    }

    /**
     * Go: TestNewLeaderPendingConfig
     *
     * <p>新 leader 根据未提交的日志设置自己的待处理成员变更位置。</p>
     */
    @Test
    void testNewLeaderPendingConfig() {
        long[] expected = {0, 1};
        for (int i = 0; i < 2; i++) {
            Raft r = newTestRaft(1, 10, 1, newTestMemoryStorage(1, 2));
            if (i == 1) {
                mustAppendEntry(r, new Entry(0, 0, EntryType.NORMAL, null));
            }
            r.becomeCandidate();
            r.becomeLeader();
            assertEquals(expected[i], r.getPendingConfIndex(), "#" + i);
        }
    }

    /**
     * Go: TestAddNode
     */
    @Test
    void testAddNode() {
        Raft r = newTestRaft(1, 10, 1, newTestMemoryStorage(1));
        r.applyConfChange(new ConfChange(ConfChangeType.ADD_NODE, 2, new byte[0]).asV2());
        assertEquals(Arrays.asList(1L, 2L), r.getTracker().voterNodes());
    }

    /**
     * Go: TestAddLearner
     */
    @Test
    void testAddLearner() {
        Raft r = newTestRaft(1, 10, 1, newTestMemoryStorage(1));
        // 加入新的 learner
        r.applyConfChange(new ConfChange(ConfChangeType.ADD_LEARNER_NODE, 2, new byte[0]).asV2());
        assertFalse(r.isLearner());
        assertEquals(Collections.singletonList(2L), r.getTracker().learnerNodes());
        assertTrue(r.getTracker().getProgress().get(2).isLearner());

        // 把 learner 提升成投票成员
        r.applyConfChange(new ConfChange(ConfChangeType.ADD_NODE, 2, new byte[0]).asV2());
        assertFalse(r.getTracker().getProgress().get(2).isLearner());

        // 把自己降级成 learner
        r.applyConfChange(new ConfChange(ConfChangeType.ADD_LEARNER_NODE, 1, new byte[0]).asV2());
        assertTrue(r.getTracker().getProgress().get(1).isLearner());
        assertTrue(r.isLearner());

        // 再把自己提升回来
        r.applyConfChange(new ConfChange(ConfChangeType.ADD_NODE, 1, new byte[0]).asV2());
        assertFalse(r.getTracker().getProgress().get(1).isLearner());
        assertFalse(r.isLearner());
    }

    /**
     * Go: TestRemoveNode
     */
    @Test
    void testRemoveNode() {
        Raft r = newTestRaft(1, 10, 1, newTestMemoryStorage(1, 2));
        r.applyConfChange(new ConfChange(ConfChangeType.REMOVE_NODE, 2, new byte[0]).asV2());
        assertEquals(Collections.singletonList(1L), r.getTracker().voterNodes());

        // 移走最后一个投票成员会报错
        assertThrows(RaftException.class,
                () -> r.applyConfChange(new ConfChange(ConfChangeType.REMOVE_NODE, 1, new byte[0]).asV2()));
    }

    /**
     * Go: TestRemoveLearner
     */
    @Test
    void testRemoveLearner() {
        Raft r = newTestLearnerRaft(1, 10, 1,
                newTestMemoryStorageWithLearners(new long[]{1}, new long[]{2}));
        r.applyConfChange(new ConfChange(ConfChangeType.REMOVE_NODE, 2, new byte[0]).asV2());
        assertEquals(Collections.singletonList(1L), r.getTracker().voterNodes());
        assertEquals(Collections.emptyList(), r.getTracker().learnerNodes());

        // 移走最后一个投票成员会报错
        assertThrows(RaftException.class,
                () -> r.applyConfChange(new ConfChange(ConfChangeType.REMOVE_NODE, 1, new byte[0]).asV2()));
    }

    /**
     * Go: TestPromotable
     */
    @Test
    void testPromotable() {
        long id = 1;
        long[][] peers = {{1}, {1, 2, 3}, {}, {2, 3}};
        boolean[] expected = {true, true, false, false};
        for (int i = 0; i < peers.length; i++) {
            Raft r = newTestRaft(id, 5, 1, newTestMemoryStorage(peers[i]));
            assertEquals(expected[i], r.promotable(), "#" + i);
        }
    }

    /**
     * Go: TestRaftNodes
     */
    @Test
    void testRaftNodes() {
        long[][] ids = {{1, 2, 3}, {3, 2, 1}};
        for (int i = 0; i < ids.length; i++) {
            Raft r = newTestRaft(1, 10, 1, newTestMemoryStorage(ids[i]));
            assertEquals(Arrays.asList(1L, 2L, 3L), r.getTracker().voterNodes(), "#" + i);
        }
    }

    /**
     * Go: TestCommitAfterRemoveNode
     *
     * <p>成员变更让多数派要求变小之后，之前挂着的提案才能提交。</p>
     */
    @Test
    void testCommitAfterRemoveNode() {
        // 两个节点的集群
        MemoryStorage s = newTestMemoryStorage(1, 2);
        Raft r = newTestRaft(1, 5, 1, s);
        r.becomeCandidate();
        r.becomeLeader();

        // 开始把第二个节点移出集群
        ConfChange cc = new ConfChange(ConfChangeType.REMOVE_NODE, 2, new byte[0]);
        ConfChangeCodec.EntryTypeAndData td = ConfChangeCodec.marshal(cc);
        r.step(new Message().setType(MessageType.MSG_PROP).setEntries(Collections.singletonList(
                new Entry(0, 0, td.getType(), td.getData()))));

        // 把日志落盘，确认还没有提交
        assertTrue(nextEnts(r, s).isEmpty());
        long ccIndex = r.getRaftLog().lastIndex();

        // 配置变更还没提交时，再提一个普通提案
        r.step(new Message().setType(MessageType.MSG_PROP).setEntries(Collections.singletonList(
                new Entry(0, 0, EntryType.NORMAL, "hello".getBytes()))));

        // 2 号节点确认了配置变更，变更被提交
        r.step(new Message().setFrom(2).setTo(1).setType(MessageType.MSG_APP_RESP).setIndex(ccIndex));
        List<Entry> ents = nextEnts(r, s);
        assertEquals(2, ents.size());
        assertEquals(EntryType.NORMAL, ents.get(0).getType());
        // Java 侧把空的 data 统一成空数组，Go 那边是 nil
        assertEquals(0, ents.get(0).getData().length);
        assertEquals(EntryType.CONFIG_CHANGE, ents.get(1).getType());

        // 应用配置变更，多数派要求变小，挂着的提案现在可以提交
        r.applyConfChange(cc.asV2());
        ents = nextEnts(r, s);
        assertEquals(1, ents.size());
        assertEquals(EntryType.NORMAL, ents.get(0).getType());
        assertArrayEquals("hello".getBytes(), ents.get(0).getData());
    }

    /**
     * Go: TestConfChangeCheckBeforeCampaign
     */
    @Test
    void testConfChangeCheckBeforeCampaign() {
        testConfChangeCheckBeforeCampaign(false);
    }

    /**
     * Go: TestConfChangeV2CheckBeforeCampaign
     */
    @Test
    void testConfChangeV2CheckBeforeCampaign() {
        testConfChangeCheckBeforeCampaign(true);
    }

    /**
     * Go: testConfChangeCheckBeforeCampaign
     *
     * <p>已提交但还没应用的成员变更会阻止节点发起竞选。</p>
     */
    private static void testConfChangeCheckBeforeCampaign(boolean v2) {
        RaftNetwork nt = network(null, null, null);
        Raft n1 = nt.getRaft(1);
        Raft n2 = nt.getRaft(2);
        nt.send(hup(1, 1));
        assertEquals(RaftRole.LEADER, n1.getState());

        // 开始把 2 号节点移出集群
        ConfChange cc = new ConfChange(ConfChangeType.REMOVE_NODE, 2, new byte[0]);
        ConfChangeCodec.EntryTypeAndData td = ConfChangeCodec.marshal(v2 ? cc.asV2() : cc);
        nt.send(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_PROP)
                .setEntries(Collections.singletonList(new Entry(0, 0, td.getType(), td.getData()))));

        // 触发 2 号节点竞选
        for (int i = 0; i < n2.randomizedElectionTimeout(); i++) {
            n2.tick();
        }
        // 因为已提交的成员变更还没应用，它仍然是 follower
        assertEquals(RaftRole.FOLLOWER, n2.getState());

        // 把领导权转移给 2 号节点
        nt.send(transferLeader(2, 1));
        assertEquals(RaftRole.LEADER, n1.getState());
        // 因为已提交的成员变更还没应用，它仍然是 follower
        assertEquals(RaftRole.FOLLOWER, n2.getState());

        // 中止转移
        for (int i = 0; i < n1.getElectionTimeout(); i++) {
            n1.tick();
        }

        // 推进 apply
        nextEnts(n2, nt.getStorage(2));

        // 再次把领导权转移给 2 号节点
        nt.send(transferLeader(2, 1));
        assertEquals(RaftRole.FOLLOWER, n1.getState());
        assertEquals(RaftRole.LEADER, n2.getState());

        nextEnts(n1, nt.getStorage(1));
        // 触发 1 号节点竞选
        for (int i = 0; i < n1.randomizedElectionTimeout(); i++) {
            n1.tick();
        }
        assertEquals(RaftRole.CANDIDATE, n1.getState());
    }

    // ------------------------------------------------------------------
    // 预投票与竞选
    // ------------------------------------------------------------------

    /**
     * Go: TestAddNodeCheckQuorum
     */
    @Test
    void testAddNodeCheckQuorum() {
        Raft r = newTestRaft(1, 10, 1, newTestMemoryStorage(1));
        r.setCheckQuorumForTest(true);

        r.becomeCandidate();
        r.becomeLeader();

        for (int i = 0; i < r.getElectionTimeout() - 1; i++) {
            r.tick();
        }

        r.applyConfChange(new ConfChange(ConfChangeType.ADD_NODE, 2, new byte[0]).asV2());

        // 这一 tick 会到达 electionTimeout，触发多数派检查
        r.tick();

        // 只 tick 一次，节点 1 仍然是 leader
        assertEquals(RaftRole.LEADER, r.getState());

        // 再 tick 一个 electionTimeout 还是没听到节点 2 的消息，节点 1 应该退位
        for (int i = 0; i < r.getElectionTimeout(); i++) {
            r.tick();
        }

        assertEquals(RaftRole.FOLLOWER, r.getState());
    }

    /**
     * Go: TestCampaignWhileLeader
     */
    @Test
    void testCampaignWhileLeader() {
        testCampaignWhileLeader(false);
    }

    /**
     * Go: TestPreCampaignWhileLeader
     */
    @Test
    void testPreCampaignWhileLeader() {
        testCampaignWhileLeader(true);
    }

    /**
     * Go: testCampaignWhileLeader
     */
    private void testCampaignWhileLeader(boolean preVote) {
        RaftConfig cfg = newTestConfig(1, 5, 1, newTestMemoryStorage(1));
        cfg.preVote = preVote;
        Raft r = new Raft(cfg);
        assertEquals(RaftRole.FOLLOWER, r.getState());

        // 不直接调 campaign()，因为它在本函数的状态检查之后
        r.step(hup(1, 1));
        advanceMessagesAfterAppend(r);
        assertEquals(RaftRole.LEADER, r.getState());

        long term = r.getTerm();
        r.step(hup(1, 1));
        advanceMessagesAfterAppend(r);
        assertEquals(RaftRole.LEADER, r.getState());
        assertEquals(term, r.getTerm());
    }

    /**
     * Go: TestNodeWithSmallerTermCanCompleteElection
     */
    @Test
    void testNodeWithSmallerTermCanCompleteElection() {
        Raft n1 = newTestRaft(1, 10, 1, newTestMemoryStorage(1, 2, 3));
        Raft n2 = newTestRaft(2, 10, 1, newTestMemoryStorage(1, 2, 3));
        Raft n3 = newTestRaft(3, 10, 1, newTestMemoryStorage(1, 2, 3));

        n1.becomeFollower(1, Raft.NONE);
        n2.becomeFollower(1, Raft.NONE);
        n3.becomeFollower(1, Raft.NONE);

        n1.setPreVoteForTest(true);
        n2.setPreVoteForTest(true);
        n3.setPreVoteForTest(true);

        // 制造网络分区，把节点 3 隔离出去
        RaftNetwork nt = network(n1, n2, n3);
        nt.cut(1, 3);
        nt.cut(2, 3);

        nt.send(hup(1, 1));

        assertEquals(RaftRole.LEADER, nt.getRaft(1).getState());
        assertEquals(RaftRole.FOLLOWER, nt.getRaft(2).getState());

        nt.send(hup(3, 3));
        assertEquals(RaftRole.PRE_CANDIDATE, nt.getRaft(3).getState());

        nt.send(hup(2, 2));

        // 检查任期
        assertEquals(3, nt.getRaft(1).getTerm());
        assertEquals(3, nt.getRaft(2).getTerm());
        assertEquals(1, nt.getRaft(3).getTerm());

        // 检查状态
        assertEquals(RaftRole.FOLLOWER, nt.getRaft(1).getState());
        assertEquals(RaftRole.LEADER, nt.getRaft(2).getState());
        assertEquals(RaftRole.PRE_CANDIDATE, nt.getRaft(3).getState());

        // 恢复网络，然后立刻隔离当前 leader 2，模拟 2 崩溃
        nt.recover();
        nt.cut(2, 1);
        nt.cut(2, 3);

        // 发起选举
        nt.send(hup(3, 3));
        nt.send(hup(1, 1));

        // 有没有 leader？
        assertTrue(nt.getRaft(1).getState() == RaftRole.LEADER
                || nt.getRaft(3).getState() == RaftRole.LEADER);
    }

    /**
     * Go: TestPreVoteWithSplitVote
     */
    @Test
    void testPreVoteWithSplitVote() {
        Raft n1 = newTestRaft(1, 10, 1, newTestMemoryStorage(1, 2, 3));
        Raft n2 = newTestRaft(2, 10, 1, newTestMemoryStorage(1, 2, 3));
        Raft n3 = newTestRaft(3, 10, 1, newTestMemoryStorage(1, 2, 3));

        n1.becomeFollower(1, Raft.NONE);
        n2.becomeFollower(1, Raft.NONE);
        n3.becomeFollower(1, Raft.NONE);

        n1.setPreVoteForTest(true);
        n2.setPreVoteForTest(true);
        n3.setPreVoteForTest(true);

        RaftNetwork nt = network(n1, n2, n3);
        nt.send(hup(1, 1));

        // 模拟 leader 掉线，followers 开始分裂投票
        nt.isolate(1);
        nt.send(hup(2, 2), hup(3, 3));

        // 检查任期
        assertEquals(3, nt.getRaft(2).getTerm());
        assertEquals(3, nt.getRaft(3).getTerm());

        // 检查状态
        assertEquals(RaftRole.CANDIDATE, nt.getRaft(2).getState());
        assertEquals(RaftRole.CANDIDATE, nt.getRaft(3).getState());

        // 节点 2 的选举超时先到
        nt.send(hup(2, 2));

        // 检查任期
        assertEquals(4, nt.getRaft(2).getTerm());
        assertEquals(4, nt.getRaft(3).getTerm());

        // 检查状态
        assertEquals(RaftRole.LEADER, nt.getRaft(2).getState());
        assertEquals(RaftRole.FOLLOWER, nt.getRaft(3).getState());
    }

    /**
     * Go: TestPreVoteWithCheckQuorum
     */
    @Test
    void testPreVoteWithCheckQuorum() {
        Raft n1 = newTestRaft(1, 10, 1, newTestMemoryStorage(1, 2, 3));
        Raft n2 = newTestRaft(2, 10, 1, newTestMemoryStorage(1, 2, 3));
        Raft n3 = newTestRaft(3, 10, 1, newTestMemoryStorage(1, 2, 3));

        n1.becomeFollower(1, Raft.NONE);
        n2.becomeFollower(1, Raft.NONE);
        n3.becomeFollower(1, Raft.NONE);

        n1.setPreVoteForTest(true);
        n2.setPreVoteForTest(true);
        n3.setPreVoteForTest(true);

        n1.setCheckQuorumForTest(true);
        n2.setCheckQuorumForTest(true);
        n3.setCheckQuorumForTest(true);

        RaftNetwork nt = network(n1, n2, n3);
        nt.send(hup(1, 1));

        // 隔离节点 1，节点 2 和 3 仍然知道 leader 是谁
        nt.isolate(1);

        // 检查状态
        assertEquals(RaftRole.LEADER, nt.getRaft(1).getState());
        assertEquals(RaftRole.FOLLOWER, nt.getRaft(2).getState());
        assertEquals(RaftRole.FOLLOWER, nt.getRaft(3).getState());

        // 节点 2 会忽略节点 3 的预投票
        nt.send(hup(3, 3));
        nt.send(hup(2, 2));

        // 有没有 leader？
        assertTrue(nt.getRaft(2).getState() == RaftRole.LEADER
                || nt.getRaft(3).getState() == RaftRole.FOLLOWER);
    }

    /**
     * Go: TestLearnerCampaign
     */
    @Test
    void testLearnerCampaign() {
        Raft n1 = newTestRaft(1, 10, 1, newTestMemoryStorage(1));
        n1.applyConfChange(new ConfChange(ConfChangeType.ADD_LEARNER_NODE, 2, new byte[0]).asV2());
        Raft n2 = newTestRaft(2, 10, 1, newTestMemoryStorage(1));
        n2.applyConfChange(new ConfChange(ConfChangeType.ADD_LEARNER_NODE, 2, new byte[0]).asV2());

        RaftNetwork nt = network(n1, n2);
        nt.send(hup(2, 2));

        assertTrue(n2.isLearner());
        assertEquals(RaftRole.FOLLOWER, n2.getState());

        nt.send(hup(1, 1));
        assertTrue(n1.getState() == RaftRole.LEADER && n1.getLead() == 1);

        // 转移领导权时已经检查过目标不是 learner，但 learner 可能在检查之后才
        // 变成 learner，这时它会收到 MsgTimeoutNow，这里验证它被忽略
        nt.send(new Message().setFrom(1).setTo(2).setType(MessageType.MSG_TIMEOUT_NOW));
        assertEquals(RaftRole.FOLLOWER, n2.getState());
    }

    /**
     * Go: TestPreVoteMigrationCanCompleteElection
     */
    @Test
    void testPreVoteMigrationCanCompleteElection() {
        RaftNetwork nt = newPreVoteMigrationCluster();

        // n1 是任期 2 的 leader
        // n2 是任期 2 的 follower
        // n3 是任期 4 的预候选者，日志更少
        Raft n2 = nt.getRaft(2);
        Raft n3 = nt.getRaft(3);

        // 模拟 leader 掉线
        nt.isolate(1);

        // n2 和 n3 同时发起选举
        nt.send(hup(3, 3));
        nt.send(hup(2, 2));

        // 检查状态
        assertEquals(RaftRole.FOLLOWER, n2.getState());
        assertEquals(RaftRole.PRE_CANDIDATE, n3.getState());

        nt.send(hup(3, 3));
        nt.send(hup(2, 2));

        // 有没有 leader？
        assertTrue(n2.getState() == RaftRole.LEADER || n3.getState() == RaftRole.FOLLOWER);
    }

    /**
     * Go: TestPreVoteMigrationWithFreeStuckPreCandidate
     */
    @Test
    void testPreVoteMigrationWithFreeStuckPreCandidate() {
        RaftNetwork nt = newPreVoteMigrationCluster();

        Raft n1 = nt.getRaft(1);
        Raft n2 = nt.getRaft(2);
        Raft n3 = nt.getRaft(3);

        nt.send(hup(3, 3));

        assertEquals(RaftRole.LEADER, n1.getState());
        assertEquals(RaftRole.FOLLOWER, n2.getState());
        assertEquals(RaftRole.PRE_CANDIDATE, n3.getState());

        // 再预投票一次，稳妥起见
        nt.send(hup(3, 3));

        assertEquals(RaftRole.LEADER, n1.getState());
        assertEquals(RaftRole.FOLLOWER, n2.getState());
        assertEquals(RaftRole.PRE_CANDIDATE, n3.getState());

        nt.send(new Message().setFrom(1).setTo(3).setType(MessageType.MSG_HEARTBEAT).setTerm(n1.getTerm()));

        // leader 被打断，卡住的节点被放出来
        assertEquals(RaftRole.FOLLOWER, n1.getState());
        assertEquals(n1.getTerm(), n3.getTerm());
    }

    /**
     * Go: TestFastLogRejection
     */
    @Test
    void testFastLogRejection() {
        // leader 能快速找到冲突位置：
        // 先发 MsgApp(index=7, logTerm=4)，被拒之后改发 MsgApp(index=3, logTerm=2)
        assertFastLogRejection(entries(1, 2, 2, 4, 4, 4, 4),
                entries(1, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3),
                0, 3, 7, 2, 3);

        // leader 能快速找到冲突位置：
        // 先发 MsgApp(index=8, logTerm=5)，被拒之后改发 MsgApp(index=4, logTerm=3)
        assertFastLogRejection(entries(1, 2, 2, 3, 4, 4, 4, 5),
                entries(1, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3),
                0, 3, 8, 3, 4);

        // follower 能快速找到冲突位置：
        // 先发 MsgApp(index=4, logTerm=1)，被拒之后改发 MsgApp(index=1, logTerm=1)
        assertFastLogRejection(entries(1, 1, 1, 1),
                entries(1, 2, 2, 4),
                0, 1, 1, 1, 1);

        // 同上，但这次 leader 的未提交日志比 follower 长
        assertFastLogRejection(entries(1, 1, 1, 1, 1, 1),
                entries(1, 2, 2, 4),
                0, 1, 1, 1, 1);

        // 同上，但这次 follower 的未提交日志比 leader 长
        assertFastLogRejection(entries(1, 1, 1, 1),
                entries(1, 2, 2, 4, 4, 4),
                0, 1, 1, 1, 1);

        // 没有日志冲突的正常情况：
        // 先发 MsgApp(index=5, logTerm=5)，被拒之后改发 MsgApp(index=4, logTerm=4)
        assertFastLogRejection(entries(1, 1, 1, 4, 5),
                entries(1, 1, 1, 4),
                0, 4, 4, 4, 4);

        // 取自 stepLeader 里的示例注释
        assertFastLogRejection(entries(2, 5, 5, 5, 5, 5, 5, 5, 5),
                entries(2, 4, 4, 4, 4, 4),
                0, 4, 6, 2, 1);

        // 取自 handleAppendEntries 里的示例注释
        assertFastLogRejection(entries(2, 2, 2, 2, 2),
                entries(2, 4, 4, 4, 4, 4, 4, 4),
                0, 2, 1, 2, 1);

        // leader 发来的过期 MsgApp 到达时，对应的日志已经被压缩：
        // 拒绝时返回 index=3, logTerm=0，leader 回退一条，改发 MsgApp(index=2, logTerm=1)
        assertFastLogRejection(entries(1, 1, 3),
                entries(1, 1, 3, 3, 3),
                5, 0, 3, 1, 2);
    }

    /**
     * Go: TestLogReplicationWithReorderedMessage
     */
    @Test
    void testLogReplicationWithReorderedMessage() {
        Raft r1 = newTestRaft(1, 10, 1, newTestMemoryStorage(1, 2));
        r1.becomeCandidate();
        r1.becomeLeader();
        readMessages(r1);
        r1.getTracker().getProgress().get(2).becomeReplicate();

        Raft r2 = newTestRaft(2, 10, 1, newTestMemoryStorage(1, 2));

        // r1 给 r2 发两条 MsgApp
        mustAppendEntry(r1, entry(0, 0, "somedata"));
        r1.sendAppend(2);
        Message req1 = expectOneMessage(r1);
        mustAppendEntry(r1, entry(0, 0, "somedata"));
        r1.sendAppend(2);
        Message req2 = expectOneMessage(r1);

        // r2 因为乱序先收到第二条 MsgApp
        r2.step(req2);
        Message resp2 = expectOneMessage(r2);
        // r2 拒绝了 req2
        assertTrue(resp2.getReject());
        assertEquals(0, resp2.getRejectHint());
        assertEquals(2, resp2.getIndex());

        // r2 处理第一条 MsgApp 并回复 r1，r1 相应地更新 match index
        r2.step(req1);
        Message m = expectOneMessage(r2);
        assertFalse(m.getReject());
        assertEquals(2, m.getIndex());
        r1.step(m);
        m = expectOneMessage(r1);
        assertEquals(2, r1.getTracker().getProgress().get(2).getMatch());

        // r1 观察到到 r2 的瞬时网络问题，于是转到 probe 状态
        r1.step(new Message().setFrom(2).setTo(1).setType(MessageType.MSG_UNREACHABLE));
        assertEquals(State.PROBE, r1.getTracker().getProgress().get(2).getState());

        // 现在 r1 收到延迟的 resp2
        r1.step(resp2);
        m = expectOneMessage(r1);
        // 即使 resp2 的 reject hint 小于 match index，r1 也要从 match index 重新发 MsgApp
        assertEquals(r1.getTracker().getProgress().get(2).getMatch(), m.getIndex());
    }

    // ------------------------------------------------------------------
    // 公共辅助
    // ------------------------------------------------------------------

    /**
     * Go: newPreVoteMigrationCluster
     *
     * <p>模拟集群滚动升级到预投票：3 个节点 [n1, n2, n3]，
     * n1 是任期 2 的 leader，n2 是任期 2 的 follower，
     * n3 被分区，任期 4 且日志更少，状态是 candidate。</p>
     */
    private RaftNetwork newPreVoteMigrationCluster() {
        Raft n1 = newTestRaft(1, 10, 1, newTestMemoryStorage(1, 2, 3));
        Raft n2 = newTestRaft(2, 10, 1, newTestMemoryStorage(1, 2, 3));
        Raft n3 = newTestRaft(3, 10, 1, newTestMemoryStorage(1, 2, 3));

        n1.becomeFollower(1, Raft.NONE);
        n2.becomeFollower(1, Raft.NONE);
        n3.becomeFollower(1, Raft.NONE);

        n1.setPreVoteForTest(true);
        n2.setPreVoteForTest(true);
        // n3 故意不启用预投票
        n3.setPreVoteForTest(false);

        RaftNetwork nt = network(n1, n2, n3);
        nt.send(hup(1, 1));

        // 制造网络分区，隔离 n3
        nt.isolate(3);
        nt.send(prop(1, 1, "some data"));
        nt.send(hup(3, 3));
        nt.send(hup(3, 3));

        // 检查状态
        assertEquals(RaftRole.LEADER, n1.getState());
        assertEquals(RaftRole.FOLLOWER, n2.getState());
        assertEquals(RaftRole.CANDIDATE, n3.getState());

        // 检查任期
        assertEquals(2, n1.getTerm());
        assertEquals(2, n2.getTerm());
        assertEquals(4, n3.getTerm());

        // 给 n3 打开预投票，然后恢复网络
        n3.setPreVoteForTest(true);
        nt.recover();

        return nt;
    }

    /**
     * Go: TestFastLogRejection 里的单个用例
     *
     * @param leaderLog leader 上的日志
     * @param followerLog follower 上的日志
     * @param followerCompact follower 日志被压缩到的位置，0 表示不压缩
     * @param rejectHintTerm 期望的拒绝回复里的 logTerm
     * @param rejectHintIndex 期望的拒绝回复里的 rejectHint
     * @param nextAppendTerm 被拒之后 leader 重新发 MsgApp 时的 logTerm
     * @param nextAppendIndex 被拒之后 leader 重新发 MsgApp 时的 index
     */
    private void assertFastLogRejection(List<Entry> leaderLog, List<Entry> followerLog,
                                        long followerCompact,
                                        long rejectHintTerm, long rejectHintIndex,
                                        long nextAppendTerm, long nextAppendIndex) {
        ConfState cs = new ConfState(Arrays.asList(1L, 2L, 3L), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), false);

        MemoryStorage s1 = new MemoryStorage(cs);
        s1.append(leaderLog);
        Entry last = leaderLog.get(leaderLog.size() - 1);
        s1.setHardState(new HardState(last.getTerm() - 1, 0, last.getIndex()));

        Raft n1 = newTestRaft(1, 10, 1, s1);
        // bump 到最后一条日志的任期
        n1.becomeCandidate();
        n1.becomeLeader();

        MemoryStorage s2 = new MemoryStorage(cs);
        s2.append(followerLog);
        s2.setHardState(new HardState(last.getTerm(), 1, 0));

        Raft n2 = newTestRaft(2, 10, 1, s2);
        if (followerCompact != 0) {
            s2.compact(followerCompact);
            // NB: 压缩之后 n2 的状态并不真实（commit 还是 0），
            // 这里是为了走一遍「实际不会发生」的边界路径
        }

        n2.step(new Message().setFrom(1).setTo(2).setType(MessageType.MSG_HEARTBEAT));
        List<Message> msgs = readMessages(n2);
        assertEquals(1, msgs.size(), "can't read 1 message from peer 2");
        assertEquals(MessageType.MSG_HEARTBEAT_RESP, msgs.get(0).getType());

        n1.step(msgs.get(0));
        msgs = readMessages(n1);
        assertEquals(1, msgs.size(), "can't read 1 message from peer 1");
        assertEquals(MessageType.MSG_APP, msgs.get(0).getType());

        n2.step(msgs.get(0));
        msgs = readMessages(n2);
        assertEquals(1, msgs.size(), "can't read 1 message from peer 2");
        assertEquals(MessageType.MSG_APP_RESP, msgs.get(0).getType());
        assertTrue(msgs.get(0).getReject(), "expected rejected append response from peer 2");
        assertEquals(rejectHintTerm, msgs.get(0).getLogTerm(), "hint log term mismatch");
        assertEquals(rejectHintIndex, msgs.get(0).getRejectHint(), "hint log index mismatch");

        n1.step(msgs.get(0));
        msgs = readMessages(n1);
        assertEquals(nextAppendTerm, msgs.get(0).getLogTerm());
        assertEquals(nextAppendIndex, msgs.get(0).getIndex());
    }

    /**
     * Go: newNetwork
     */
    private static RaftNetwork network(Object... peers) {
        return RaftNetwork.newNetwork(peers);
    }

    /**
     * Go: newNetworkWithConfig
     */
    private static RaftNetwork networkWithConfig(RaftNetwork.ConfigFunc cfg, Object... peers) {
        return RaftNetwork.newNetworkWithConfig(cfg, peers);
    }

    /**
     * Go: {@code &pb.Message{Type: pb.MsgHup}}
     */
    private static Message hup(long from, long to) {
        return new Message().setFrom(from).setTo(to).setType(MessageType.MSG_HUP);
    }

    /**
     * Go: {@code &pb.Message{Type: pb.MsgBeat}}
     */
    private static Message beat(long from, long to) {
        return new Message().setFrom(from).setTo(to).setType(MessageType.MSG_BEAT);
    }

    /**
     * Go: {@code &pb.Message{Type: pb.MsgProp, Entries: ...}}
     */
    private static Message prop(long from, long to, String data) {
        return prop(from, to, entry(0, 0, data));
    }

    /**
     * Go: {@code &pb.Message{Type: pb.MsgProp, Entries: ...}}
     */
    private static Message prop(long from, long to, byte[] data) {
        return prop(from, to, entryData(0, 0, data));
    }

    /**
     * Go: {@code &pb.Message{Type: pb.MsgProp, Entries: ...}}
     */
    private static Message prop(long from, long to, Entry e) {
        return new Message().setFrom(from).setTo(to).setType(MessageType.MSG_PROP)
                .setEntries(Collections.singletonList(e));
    }

    /**
     * Go: {@code &pb.Message{Type: pb.MsgProp, Entries: ...}}
     */
    private static Message prop(long from, long to, List<Entry> ents) {
        return new Message().setFrom(from).setTo(to).setType(MessageType.MSG_PROP).setEntries(ents);
    }

    /**
     * Go: {@code &pb.Entry{Data: ...}}
     */
    private static Entry entry(long index, long term, String data) {
        return new Entry(index, term, EntryType.NORMAL, data == null ? null : data.getBytes());
    }

    /**
     * Go: {@code &pb.Entry{Data: ...}}
     */
    private static Entry entryData(long index, long term, byte[] data) {
        return new Entry(index, term, EntryType.NORMAL, data);
    }

    /**
     * Go: {@code &pb.Entry{}}，一条没有负载的日志。
     */
    private static Entry emptyEntry() {
        return new Entry(0, 0, EntryType.NORMAL, null);
    }

    /**
     * Go: {@code &pb.Message{Type: pb.MsgTransferLeader}}
     */
    private static Message transferLeader(long from, long to) {
        return new Message().setFrom(from).setTo(to).setType(MessageType.MSG_TRANSFER_LEADER);
    }

    /**
     * Go: checkLeaderTransferState
     */
    private static void checkLeaderTransferState(Raft r, RaftRole state, long lead) {
        assertEquals(state, r.getState());
        assertEquals(lead, r.getLead());
        assertEquals(Raft.NONE, r.getLeadTransferee());
    }

    /**
     * Go: {@code &pb.Message{Type: pb.MsgProp, Entries: []pb.Entry{{Type: pb.EntryConfChange}}}}
     */
    private static Message confChangeProp(long from, EntryType type, byte[] data) {
        return new Message().setFrom(from).setTo(from).setType(MessageType.MSG_PROP)
                .setEntries(Collections.singletonList(new Entry(0, 0, type, data)));
    }

    /**
     * Go: voteRespMsgType
     */
    private static MessageType voteRespMsgType(MessageType t) {
        switch (t) {
            case MSG_VOTE:
                return MessageType.MSG_VOTE_RESP;
            case MSG_PRE_VOTE:
                return MessageType.MSG_PRE_VOTE_RESP;
            default:
                throw new RaftException("unexpected vote type");
        }
    }

    /**
     * Go: TestProgressFlowControl 里的 ackAndVerify 闭包
     */
    private static long ackAndVerify(Raft r, long index, int... expEntries) {
        r.step(new Message().setFrom(2).setTo(1).setType(MessageType.MSG_APP_RESP).setIndex(index));
        List<Message> ms = readMessages(r);
        assertEquals(expEntries.length, ms.size());

        for (int i = 0; i < ms.size(); i++) {
            assertEquals(MessageType.MSG_APP, ms.get(i).getType(), "#" + i);
            assertEquals(expEntries[i], ms.get(i).getEntries().size(), "#" + i);
        }
        List<Entry> last = ms.get(ms.size() - 1).getEntries();
        if (last.isEmpty()) {
            return index;
        }
        return last.get(last.size() - 1).getIndex();
    }

    /**
     * 取一条日志的负载长度，{@code null} 视为空。
     */
    private static int dataLength(Entry e) {
        byte[] data = e.getData();
        return data == null ? 0 : data.length;
    }

    /**
     * 造一段指定长度的字节。
     */
    private static byte[] filled(int size, byte b) {
        byte[] buf = new byte[size];
        Arrays.fill(buf, b);
        return buf;
    }

    /**
     * Go: {@code index(start).terms(...)}
     *
     * <p>造一段连续的日志，index 从 1 开始，任期依次给出。</p>
     */
    private static List<Entry> entries(long... terms) {
        List<Entry> ents = new ArrayList<>();
        for (int i = 0; i < terms.length; i++) {
            ents.add(new Entry(i + 1, terms[i], EntryType.NORMAL, null));
        }
        return ents;
    }

    /**
     * Go: {@code &pb.Message{Type: pb.MsgApp, ...}}
     */
    private static Message msgApp(long term, long logTerm, long index, long commit, Entry... ents) {
        Message m = new Message().setType(MessageType.MSG_APP).setTerm(term)
                .setLogTerm(logTerm).setIndex(index).setCommit(commit);
        if (ents != null && ents.length > 0) {
            m.setEntries(Arrays.asList(ents));
        }
        return m;
    }

    /**
     * Go: TestStepIgnoreOldTermMsg 里的 fakeStep
     *
     * <p>etcd 的 {@code raft.step} 是可替换的函数字段，Java 侧改成重写
     * {@link Raft#stepStateMachine(Message)}。</p>
     */
    private static final class StepSpy extends Raft {

        /** 状态机处理函数是否被调用过 */
        private boolean called;

        StepSpy(RaftConfig cfg) {
            super(cfg);
        }

        @Override
        boolean stepStateMachine(Message m) {
            called = true;
            return true;
        }
    }

    /**
     * Go: {@code newTestRaft(...)} 加上 {@code checkQuorum = true}
     */
    private static Raft newTestRaftWithCheckQuorum(long id, long... peers) {
        RaftConfig cfg = newTestConfig(id, 10, 1, newTestMemoryStorage(peers));
        cfg.checkQuorum = true;
        return new Raft(cfg);
    }

    /**
     * Go: {@code newTestRaft(...)} 加上 {@code readOnly.option = ReadOnlyLeaseBased}
     * 与 {@code checkQuorum = true}
     */
    private static Raft newTestRaftWithLeaseBasedReadOnly(long id) {
        RaftConfig cfg = newTestConfig(id, 10, 1, newTestMemoryStorage(1, 2, 3));
        cfg.readOnlyOption = ReadOnlyOption.READ_ONLY_LEASE_BASED;
        cfg.checkQuorum = true;
        return new Raft(cfg);
    }

    /**
     * Go: {@code &pb.Snapshot{Metadata: &pb.SnapshotMetadata{Index: ..., Term: ..., ConfState: ...}}}
     */
    private static Snapshot snapshot(long index, long term, ConfState cs) {
        return new Snapshot(null, new SnapshotMetadata(cs, index, term));
    }

    /**
     * Go: {@code &pb.Message{Type: pb.MsgReadIndex, Entries: ...}}
     */
    private static Message readIndex(long from, byte[] ctx) {
        return new Message().setFrom(from).setTo(from).setType(MessageType.MSG_READ_INDEX)
                .setEntries(Collections.singletonList(entryData(0, 0, ctx)));
    }
}
