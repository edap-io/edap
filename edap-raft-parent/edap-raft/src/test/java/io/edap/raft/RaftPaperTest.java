/*
 * Copyright 2023 The edap Project
 *
 * The NetPy Project licenses this file to you under the Apache License,
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
import io.edap.raft.protocol.HardState;
import io.edap.raft.protocol.Message;
import io.edap.raft.protocol.MessageType;
import io.edap.raft.storage.MemoryStorage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.edap.raft.RaftTestSupport.advanceMessagesAfterAppend;
import static io.edap.raft.RaftTestSupport.diffLogs;
import static io.edap.raft.RaftTestSupport.mustAppendEntry;
import static io.edap.raft.RaftTestSupport.newTestConfig;
import static io.edap.raft.RaftTestSupport.newTestMemoryStorage;
import static io.edap.raft.RaftTestSupport.newTestRaft;
import static io.edap.raft.RaftTestSupport.readMessages;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * etcd {@code raft_paper_test.go} 的 Java 实现。
 *
 * <p>这个文件里的用例逐条验证 Raft 论文
 * （<a href="https://raft.github.io/raft.pdf">raft.pdf</a>）里描述的场景，
 * 每个用例对应论文里的几句话，可以挡住大部分实现层面的 bug。</p>
 *
 * <p>每个用例分三段：init（造出初始状态）、test（用 Step 驱动出场景）、
 * check（检查发出去的消息与状态机状态）。</p>
 */
public class RaftPaperTest {

    // ------------------------------------------------------------------
    // §5.1 任期
    // ------------------------------------------------------------------

    /**
     * Go: TestFollowerUpdateTermFromMessage
     */
    @Test
    // Go: TestFollowerUpdateTermFromMessage
    void testFollowerUpdateTermFromMessage() {
        testUpdateTermFromMessage(RaftRole.FOLLOWER);
    }

    /**
     * Go: TestCandidateUpdateTermFromMessage
     */
    @Test
    // Go: TestCandidateUpdateTermFromMessage
    void testCandidateUpdateTermFromMessage() {
        testUpdateTermFromMessage(RaftRole.CANDIDATE);
    }

    /**
     * Go: TestLeaderUpdateTermFromMessage
     */
    @Test
    // Go: TestLeaderUpdateTermFromMessage
    void testLeaderUpdateTermFromMessage() {
        testUpdateTermFromMessage(RaftRole.LEADER);
    }

    /**
     * Go: testUpdateTermFromMessage
     *
     * <p>如果一个节点的当前任期比别人的小，它就把自己的任期更新成更大的那个；
     * 如果 candidate 或 leader 发现自己的任期过时了，就立刻退回 follower。
     * 论文 §5.1。</p>
     */
    private void testUpdateTermFromMessage(RaftRole state) {
        Raft r = newTestRaft(1, 10, 1, newTestMemoryStorage(1, 2, 3));
        switch (state) {
            case FOLLOWER:
                r.becomeFollower(1, 2);
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

        r.step(new Message().setType(MessageType.MSG_APP).setTerm(2));

        assertEquals(2, r.getTerm());
        assertEquals(RaftRole.FOLLOWER, r.getState());
    }

    /**
     * Go: TestRejectStaleTermMessage
     *
     * <p>收到任期更小的请求要拒绝掉（etcd 的做法是直接忽略）。
     * 论文 §5.1。</p>
     */
    @Test
    // Go: TestRejectStaleTermMessage
    void testRejectStaleTermMessage() {
        StepSpy r = new StepSpy(newTestConfig(1, 10, 1, newTestMemoryStorage(1, 2, 3)));
        r.loadState(new HardState(2, 0, 0));

        r.step(new Message().setType(MessageType.MSG_APP).setTerm(r.getTerm() - 1));

        assertFalse(r.called);
    }

    /**
     * Go: TestStartAsFollower
     *
     * <p>节点启动时都是 follower。论文 §5.2。</p>
     */
    @Test
    // Go: TestStartAsFollower
    void testStartAsFollower() {
        Raft r = newTestRaft(1, 10, 1, newTestMemoryStorage(1, 2, 3));
        assertEquals(RaftRole.FOLLOWER, r.getState());
    }

    // ------------------------------------------------------------------
    // §5.2 leader 选举
    // ------------------------------------------------------------------

    /**
     * Go: TestLeaderBcastBeat
     *
     * <p>leader 收到心跳 tick 时，给所有 follower 发一条 {@code MsgHeartbeat}：
     * index = 0、logTerm = 0、不带日志条目。论文 §5.2。</p>
     */
    @Test
    // Go: TestLeaderBcastBeat
    void testLeaderBcastBeat() {
        int hi = 1;
        Raft r = newTestRaft(1, 10, hi, newTestMemoryStorage(1, 2, 3));
        r.becomeCandidate();
        r.becomeLeader();
        for (int i = 0; i < 10; i++) {
            mustAppendEntry(r, new Entry(i + 1, 0, EntryType.NORMAL, null));
        }

        for (int i = 0; i < hi; i++) {
            r.tick();
        }

        List<Message> msgs = readMessages(r);
        sortMessages(msgs);
        assertEquals(Arrays.asList(
                new Message().setFrom(1).setTo(2).setTerm(1).setType(MessageType.MSG_HEARTBEAT),
                new Message().setFrom(1).setTo(3).setTerm(1).setType(MessageType.MSG_HEARTBEAT)),
                msgs);
    }

    /**
     * Go: TestFollowerStartElection
     */
    @Test
    // Go: TestFollowerStartElection
    void testFollowerStartElection() {
        testNonleaderStartElection(RaftRole.FOLLOWER);
    }

    /**
     * Go: TestCandidateStartNewElection
     */
    @Test
    // Go: TestCandidateStartNewElection
    void testCandidateStartNewElection() {
        testNonleaderStartElection(RaftRole.CANDIDATE);
    }

    /**
     * Go: testNonleaderStartElection
     *
     * <p>follower 一段时间没收到消息就开始选举：任期加一，转成 candidate，
     * 投自己一票，并行地向其它节点发 RequestVote。
     * candidate 没拿到多数票时也会超时，再加一个任期重新选举。
     * 论文 §5.2。</p>
     */
    private void testNonleaderStartElection(RaftRole state) {
        int et = 10;
        Raft r = newTestRaft(1, et, 1, newTestMemoryStorage(1, 2, 3));
        switch (state) {
            case FOLLOWER:
                r.becomeFollower(1, 2);
                break;
            case CANDIDATE:
                r.becomeCandidate();
                break;
            default:
                break;
        }

        for (int i = 1; i < 2 * et; i++) {
            r.tick();
        }
        advanceMessagesAfterAppend(r);

        assertEquals(2, r.getTerm());
        assertEquals(RaftRole.CANDIDATE, r.getState());
        assertTrue(r.getTracker().votes.get(r.getId()));

        List<Message> msgs = readMessages(r);
        sortMessages(msgs);
        assertEquals(Arrays.asList(
                new Message().setFrom(1).setTo(2).setTerm(2).setType(MessageType.MSG_VOTE),
                new Message().setFrom(1).setTo(3).setTerm(2).setType(MessageType.MSG_VOTE)),
                msgs);
    }

    /**
     * Go: TestLeaderElectionInOneRoundRPC
     *
     * <p>一轮 RequestVote RPC 里可能发生的三种情况：
     * a) 赢得选举；b) 输掉选举；c) 结果还不明。
     * 论文 §5.2。</p>
     */
    @Test
    // Go: TestLeaderElectionInOneRoundRPC
    void testLeaderElectionInOneRoundRPC() {
        // size、投票结果、期望的状态
        Object[][] tests = {
                // 拿到多数票就当上 leader
                {1, new LinkedHashMap<Long, Boolean>(), RaftRole.LEADER},
                {3, votes(2, true, 3, true), RaftRole.LEADER},
                {3, votes(2, true), RaftRole.LEADER},
                {5, votes(2, true, 3, true, 4, true, 5, true), RaftRole.LEADER},
                {5, votes(2, true, 3, true, 4, true), RaftRole.LEADER},
                {5, votes(2, true, 3, true), RaftRole.LEADER},

                // 多数票拒绝就退回 follower
                {3, votes(2, false, 3, false), RaftRole.FOLLOWER},
                {5, votes(2, false, 3, false, 4, false, 5, false), RaftRole.FOLLOWER},
                {5, votes(2, true, 3, false, 4, false, 5, false), RaftRole.FOLLOWER},

                // 没拿到多数票就还是 candidate
                {3, new LinkedHashMap<Long, Boolean>(), RaftRole.CANDIDATE},
                {5, votes(2, true), RaftRole.CANDIDATE},
                {5, votes(2, false, 3, false), RaftRole.CANDIDATE},
                {5, new LinkedHashMap<Long, Boolean>(), RaftRole.CANDIDATE},
        };

        for (int i = 0; i < tests.length; i++) {
            int size = (Integer) tests[i][0];
            @SuppressWarnings("unchecked")
            Map<Long, Boolean> votes = (Map<Long, Boolean>) tests[i][1];
            RaftRole want = (RaftRole) tests[i][2];

            Raft r = newTestRaft(1, 10, 1, newTestMemoryStorage(RaftNetwork.idsBySize(size)));

            r.step(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_HUP));
            advanceMessagesAfterAppend(r);
            for (Map.Entry<Long, Boolean> v : votes.entrySet()) {
                r.step(new Message().setFrom(v.getKey()).setTo(1).setTerm(r.getTerm())
                        .setType(MessageType.MSG_VOTE_RESP).setReject(!v.getValue()));
            }

            assertEquals(want, r.getState(), "#" + i);
            assertEquals(1, r.getTerm(), "#" + i);
        }
    }

    /**
     * Go: TestFollowerVote
     *
     * <p>每个 follower 在一个任期内最多投一票，先到先得。论文 §5.2。</p>
     */
    @Test
    // Go: TestFollowerVote
    void testFollowerVote() {
        // 已经投给谁、谁来拉票、是否拒绝
        Object[][] tests = {
                {0L, 2L, false},
                {0L, 3L, false},
                {2L, 2L, false},
                {3L, 3L, false},
                {2L, 3L, true},
                {3L, 2L, true},
        };

        for (int i = 0; i < tests.length; i++) {
            long vote = (Long) tests[i][0];
            long nvote = (Long) tests[i][1];
            boolean wreject = (Boolean) tests[i][2];

            Raft r = newTestRaft(1, 10, 1, newTestMemoryStorage(1, 2, 3));
            r.loadState(new HardState(1, vote, 0));

            r.step(new Message().setFrom(nvote).setTo(1).setTerm(1).setType(MessageType.MSG_VOTE));

            assertEquals(Arrays.asList(new Message().setFrom(1).setTo(nvote).setTerm(1)
                    .setType(MessageType.MSG_VOTE_RESP).setReject(wreject)),
                    r.getMessagesAfterAppend(), "#" + i);
        }
    }

    /**
     * Go: TestCandidateFallback
     *
     * <p>candidate 等待选票时收到别的节点发来的 AppendEntries，
     * 只要对方的任期不小于自己，就承认对方是合法的 leader 并退回 follower。
     * 论文 §5.2。</p>
     */
    @Test
    // Go: TestCandidateFallback
    void testCandidateFallback() {
        Message[] tests = {
                new Message().setFrom(2).setTo(1).setTerm(1).setType(MessageType.MSG_APP),
                new Message().setFrom(2).setTo(1).setTerm(2).setType(MessageType.MSG_APP),
        };

        for (int i = 0; i < tests.length; i++) {
            Raft r = newTestRaft(1, 10, 1, newTestMemoryStorage(1, 2, 3));
            r.step(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_HUP));
            assertEquals(RaftRole.CANDIDATE, r.getState(), "#" + i);

            r.step(tests[i]);

            assertEquals(RaftRole.FOLLOWER, r.getState(), "#" + i);
            assertEquals(tests[i].getTerm(), r.getTerm(), "#" + i);
        }
    }

    /**
     * Go: TestFollowerElectionTimeoutRandomized
     */
    @Test
    // Go: TestFollowerElectionTimeoutRandomized
    void testFollowerElectionTimeoutRandomized() {
        testNonleaderElectionTimeoutRandomized(RaftRole.FOLLOWER);
    }

    /**
     * Go: TestCandidateElectionTimeoutRandomized
     */
    @Test
    // Go: TestCandidateElectionTimeoutRandomized
    void testCandidateElectionTimeoutRandomized() {
        testNonleaderElectionTimeoutRandomized(RaftRole.CANDIDATE);
    }

    /**
     * Go: testNonleaderElectionTimeoutRandomized
     *
     * <p>follower 或 candidate 的选举超时是随机的。论文 §5.2。</p>
     */
    private void testNonleaderElectionTimeoutRandomized(RaftRole state) {
        int et = 10;
        Raft r = newTestRaft(1, et, 1, newTestMemoryStorage(1, 2, 3));
        Set<Integer> timeouts = new HashSet<>();
        for (int round = 0; round < 50 * et; round++) {
            switch (state) {
                case FOLLOWER:
                    r.becomeFollower(r.getTerm() + 1, 2);
                    break;
                case CANDIDATE:
                    r.becomeCandidate();
                    break;
                default:
                    break;
            }

            int time = 0;
            while (readMessages(r).isEmpty()) {
                r.tick();
                time++;
            }
            timeouts.add(time);
        }

        for (int d = et; d < 2 * et; d++) {
            assertTrue(timeouts.contains(d), "timeout in " + d + " ticks should happen");
        }
    }

    /**
     * Go: TestFollowersElectionTimeoutNonconflict
     */
    @Test
    // Go: TestFollowersElectionTimeoutNonconflict
    void testFollowersElectionTimeoutNonconflict() {
        testNonleadersElectionTimeoutNonconflict(RaftRole.FOLLOWER);
    }

    /**
     * Go: TestCandidatesElectionTimeoutNonconflict
     */
    @Test
    // Go: TestCandidatesElectionTimeoutNonconflict
    void testCandidatesElectionTimeoutNonconflict() {
        testNonleadersElectionTimeoutNonconflict(RaftRole.CANDIDATE);
    }

    /**
     * Go: testNonleadersElectionTimeoutNonconflict
     *
     * <p>多数情况下只有一个节点会超时，这样新一轮选举出现平票的概率就小。
     * 论文 §5.2。</p>
     */
    private void testNonleadersElectionTimeoutNonconflict(RaftRole state) {
        int et = 10;
        int size = 5;
        Raft[] rs = new Raft[size];
        long[] ids = RaftNetwork.idsBySize(size);
        for (int k = 0; k < rs.length; k++) {
            rs[k] = newTestRaft(ids[k], et, 1, newTestMemoryStorage(ids));
        }
        int conflicts = 0;
        for (int round = 0; round < 1000; round++) {
            for (Raft r : rs) {
                switch (state) {
                    case FOLLOWER:
                        r.becomeFollower(r.getTerm() + 1, 0);
                        break;
                    case CANDIDATE:
                        r.becomeCandidate();
                        break;
                    default:
                        break;
                }
            }

            int timeoutNum = 0;
            while (timeoutNum == 0) {
                for (Raft r : rs) {
                    r.tick();
                    if (!readMessages(r).isEmpty()) {
                        timeoutNum++;
                    }
                }
            }
            // 有几个 raft 在同一个 tick 上超时
            if (timeoutNum > 1) {
                conflicts++;
            }
        }

        assertTrue((double) conflicts / 1000 <= 0.3,
                "conflicts ratio " + ((double) conflicts / 1000) + " should be <= 0.3");
    }

    // ------------------------------------------------------------------
    // §5.3 日志复制
    // ------------------------------------------------------------------

    /**
     * Go: TestLeaderStartReplication
     *
     * <p>leader 收到客户端提案时，把提案作为新条目追加到自己的日志，
     * 然后并行地给其它节点发 AppendEntries；发的时候带上新条目前一条的
     * index 与 term，并且把新条目写进稳定存储。论文 §5.3。</p>
     */
    @Test
    // Go: TestLeaderStartReplication
    void testLeaderStartReplication() {
        MemoryStorage s = newTestMemoryStorage(1, 2, 3);
        Raft r = newTestRaft(1, 10, 1, s);
        r.becomeCandidate();
        r.becomeLeader();
        commitNoopEntry(r, s);
        long li = r.getRaftLog().lastIndex();

        r.step(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_PROP)
                .setEntries(dataEntries(0, 0, "some data")));

        assertEquals(li + 1, r.getRaftLog().lastIndex());
        assertEquals(li, r.getRaftLog().committed());
        List<Message> msgs = readMessages(r);
        sortMessages(msgs);
        List<Entry> wents = dataEntries(li + 1, 1, "some data");
        assertEquals(Arrays.asList(
                new Message().setFrom(1).setTo(2).setTerm(1).setType(MessageType.MSG_APP)
                        .setIndex(li).setLogTerm(1).setEntries(wents).setCommit(li),
                new Message().setFrom(1).setTo(3).setTerm(1).setType(MessageType.MSG_APP)
                        .setIndex(li).setLogTerm(1).setEntries(wents).setCommit(li)),
                msgs);
        assertEquals(wents, r.getRaftLog().nextUnstableEnts());
    }

    /**
     * Go: TestLeaderCommitEntry
     *
     * <p>条目被安全复制之后，leader 把它交给状态机应用；
     * 同时 leader 记住自己知道的最大提交位置，并在之后的 AppendEntries 里带上，
     * 让其它节点最终也知道。论文 §5.3。</p>
     */
    @Test
    // Go: TestLeaderCommitEntry
    void testLeaderCommitEntry() {
        MemoryStorage s = newTestMemoryStorage(1, 2, 3);
        Raft r = newTestRaft(1, 10, 1, s);
        r.becomeCandidate();
        r.becomeLeader();
        commitNoopEntry(r, s);
        long li = r.getRaftLog().lastIndex();
        r.step(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_PROP)
                .setEntries(dataEntries(0, 0, "some data")));

        for (Message m : readMessages(r)) {
            r.step(acceptAndReply(m));
        }

        assertEquals(li + 1, r.getRaftLog().committed());
        assertEquals(dataEntries(li + 1, 1, "some data"), r.getRaftLog().nextCommittedEnts(true));
        List<Message> msgs = readMessages(r);
        sortMessages(msgs);
        for (int i = 0; i < msgs.size(); i++) {
            Message m = msgs.get(i);
            assertEquals(i + 2, m.getTo());
            assertEquals(MessageType.MSG_APP, m.getType());
            assertEquals(li + 1, m.getCommit());
        }
    }

    /**
     * Go: TestLeaderAcknowledgeCommit
     *
     * <p>leader 创建的条目在多数派节点上复制过之后才算提交。论文 §5.3。</p>
     */
    @Test
    // Go: TestLeaderAcknowledgeCommit
    void testLeaderAcknowledgeCommit() {
        // 集群规模、哪些 follower 会接受、期望是否提交
        Object[][] tests = {
                {1, null, true},
                {3, null, false},
                {3, votes(2, true), true},
                {3, votes(2, true, 3, true), true},
                {5, null, false},
                {5, votes(2, true), false},
                {5, votes(2, true, 3, true), true},
                {5, votes(2, true, 3, true, 4, true), true},
                {5, votes(2, true, 3, true, 4, true, 5, true), true},
        };

        for (int i = 0; i < tests.length; i++) {
            int size = (Integer) tests[i][0];
            @SuppressWarnings("unchecked")
            Map<Long, Boolean> acceptors = (Map<Long, Boolean>) tests[i][1];
            boolean wack = (Boolean) tests[i][2];

            MemoryStorage s = newTestMemoryStorage(RaftNetwork.idsBySize(size));
            Raft r = newTestRaft(1, 10, 1, s);
            r.becomeCandidate();
            r.becomeLeader();
            commitNoopEntry(r, s);
            long li = r.getRaftLog().lastIndex();
            r.step(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_PROP)
                    .setEntries(dataEntries(0, 0, "some data")));
            advanceMessagesAfterAppend(r);
            // Go: for _, m := range r.msgs —— Go 的 range 迭代的是切片长度快照，
            // 循环体内 r.Step 追加消息不会影响本次遍历，Java 里要先拷贝一份。
            for (Message m : new ArrayList<>(r.getMessages())) {
                if (acceptors != null && Boolean.TRUE.equals(acceptors.get(m.getTo()))) {
                    r.step(acceptAndReply(m));
                }
            }

            assertEquals(wack, r.getRaftLog().committed() > li, "#" + i);
        }
    }

    /**
     * Go: TestLeaderCommitPrecedingEntries
     *
     * <p>leader 提交一条日志时，它日志里所有在它之前的条目也一起提交，
     * 包括前任 leader 留下的条目；并且按日志顺序应用到本地状态机。
     * 论文 §5.3。</p>
     */
    @Test
    // Go: TestLeaderCommitPrecedingEntries
    void testLeaderCommitPrecedingEntries() {
        List<List<Entry>> tests = Arrays.asList(
                new ArrayList<>(),
                entries(1, 2),
                entries(1, 1, 2),
                entries(1, 1));

        for (int i = 0; i < tests.size(); i++) {
            List<Entry> tt = tests.get(i);
            MemoryStorage storage = newTestMemoryStorage(1, 2, 3);
            storage.append(tt);
            Raft r = newTestRaft(1, 10, 1, storage);
            r.loadState(new HardState(2, 0, 0));
            r.becomeCandidate();
            r.becomeLeader();
            r.step(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_PROP)
                    .setEntries(dataEntries(0, 0, "some data")));

            for (Message m : readMessages(r)) {
                r.step(acceptAndReply(m));
            }

            long li = tt.size();
            List<Entry> want = new ArrayList<>(tt);
            want.add(new Entry(li + 1, 3, EntryType.NORMAL, null));
            want.add(new Entry(li + 2, 3, EntryType.NORMAL, "some data".getBytes()));
            assertEquals(want, r.getRaftLog().nextCommittedEnts(true), "#" + i);
        }
    }

    /**
     * Go: TestFollowerCommitEntry
     *
     * <p>follower 知道某条日志已经提交后，按日志顺序把它应用到本地状态机。
     * 论文 §5.3。</p>
     */
    @Test
    // Go: TestFollowerCommitEntry
    void testFollowerCommitEntry() {
        // 待追加的条目、提交位置
        Object[][] tests = {
                {dataEntries(1, 1, "some data"), 1L},
                {dataEntries(1, 1, "some data", "some data2"), 2L},
                {dataEntries(1, 1, "some data2", "some data"), 2L},
                {dataEntries(1, 1, "some data", "some data2"), 1L},
        };

        for (int i = 0; i < tests.length; i++) {
            @SuppressWarnings("unchecked")
            List<Entry> ents = (List<Entry>) tests[i][0];
            long commit = (Long) tests[i][1];

            Raft r = newTestRaft(1, 10, 1, newTestMemoryStorage(1, 2, 3));
            r.becomeFollower(1, 2);

            r.step(new Message().setFrom(2).setTo(1).setType(MessageType.MSG_APP).setTerm(1)
                    .setEntries(ents).setCommit(commit));

            assertEquals(commit, r.getRaftLog().committed(), "#" + i);
            assertEquals(ents.subList(0, (int) commit), r.getRaftLog().nextCommittedEnts(true), "#" + i);
        }
    }

    /**
     * Go: TestFollowerCheckMsgApp
     *
     * <p>如果 follower 在自己的日志里找不到与 AppendEntries 里 index、term
     * 都相同的条目，就拒绝新条目；否则回复接受。论文 §5.3。</p>
     */
    @Test
    // Go: TestFollowerCheckMsgApp
    void testFollowerCheckMsgApp() {
        List<Entry> ents = entries(1, 1, 2);
        // term、index、期望的回复 index、是否拒绝、回退提示、日志任期
        long[][] tests = {
                // 与已提交的条目匹配
                {0, 0, 1, 0, 0, 0},
                {ents.get(0).getTerm(), ents.get(0).getIndex(), 1, 0, 0, 0},
                // 与未提交的条目匹配
                {ents.get(1).getTerm(), ents.get(1).getIndex(), 2, 0, 0, 0},
                // 与已有条目不匹配
                {ents.get(0).getTerm(), ents.get(1).getIndex(), ents.get(1).getIndex(), 1, 1, 1},
                // 本地没有这条日志
                {ents.get(1).getTerm() + 1, ents.get(1).getIndex() + 1, ents.get(1).getIndex() + 1, 1, 2, 2},
        };

        for (int i = 0; i < tests.length; i++) {
            long term = tests[i][0];
            long index = tests[i][1];
            long windex = tests[i][2];
            boolean wreject = tests[i][3] != 0;
            long wrejectHint = tests[i][4];
            long wlogterm = tests[i][5];

            MemoryStorage storage = newTestMemoryStorage(1, 2, 3);
            storage.append(ents);
            Raft r = newTestRaft(1, 10, 1, storage);
            r.loadState(new HardState(0, 0, 1));
            r.becomeFollower(2, 2);

            r.step(new Message().setFrom(2).setTo(1).setType(MessageType.MSG_APP).setTerm(2)
                    .setLogTerm(term).setIndex(index));

            assertEquals(Arrays.asList(new Message().setFrom(1).setTo(2).setType(MessageType.MSG_APP_RESP)
                    .setTerm(2).setIndex(windex).setReject(wreject)
                    .setRejectHint(wrejectHint).setLogTerm(wlogterm)),
                    readMessages(r), "#" + i);
        }
    }

    /**
     * Go: TestFollowerAppendEntries
     *
     * <p>AppendEntries RPC 合法时，follower 删掉冲突的旧条目及其之后的条目，
     * 并追加新条目；同时把新条目写进稳定存储。论文 §5.3。</p>
     */
    @Test
    // Go: TestFollowerAppendEntries
    void testFollowerAppendEntries() {
        // 前置 index、前置 term、新条目、期望的日志、期望的未落盘条目
        Object[][] tests = {
                {2L, 2L, entries(3, 3), entries(1, 1, 2, 3), entries(3, 3)},
                {1L, 1L, entries(2, 3, 4), entries(1, 1, 3, 4), entries(2, 3, 4)},
                {0L, 0L, entries(1, 1), entries(1, 1, 2), new ArrayList<Entry>()},
                {0L, 0L, entries(1, 3), entries(1, 3), entries(1, 3)},
        };

        for (int i = 0; i < tests.length; i++) {
            long index = (Long) tests[i][0];
            long term = (Long) tests[i][1];
            @SuppressWarnings("unchecked")
            List<Entry> ents = (List<Entry>) tests[i][2];
            @SuppressWarnings("unchecked")
            List<Entry> wents = (List<Entry>) tests[i][3];
            @SuppressWarnings("unchecked")
            List<Entry> wunstable = (List<Entry>) tests[i][4];

            MemoryStorage storage = newTestMemoryStorage(1, 2, 3);
            storage.append(entries(1, 1, 2));
            Raft r = newTestRaft(1, 10, 1, storage);
            r.becomeFollower(2, 2);

            r.step(new Message().setFrom(2).setTo(1).setType(MessageType.MSG_APP).setTerm(2)
                    .setLogTerm(term).setIndex(index).setEntries(ents));

            assertEquals(wents, r.getRaftLog().allEntries(), "#" + i);
            assertEquals(wunstable, r.getRaftLog().nextUnstableEnts(), "#" + i);
        }
    }

    /**
     * Go: TestLeaderSyncFollowerLog
     *
     * <p>leader 能把 follower 的日志拉齐成自己的样子。论文 §5.3 图 7。</p>
     */
    @Test
    // Go: TestLeaderSyncFollowerLog
    void testLeaderSyncFollowerLog() {
        List<Entry> ents = entries(0, 0, 1, 1, 1, 4, 4, 5, 5, 6, 6, 6);
        long term = 8;
        List<List<Entry>> tests = Arrays.asList(
                entries(0, 0, 1, 1, 1, 4, 4, 5, 5, 6, 6),
                entries(0, 0, 1, 1, 1, 4, 4),
                entries(0, 0, 1, 1, 1, 4, 4, 5, 5, 6, 6, 6, 6),
                entries(0, 0, 1, 1, 1, 4, 4, 5, 5, 6, 6, 6, 7, 7),
                entries(0, 0, 1, 1, 1, 4, 4, 4, 4),
                entries(0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 3, 3));

        for (int i = 0; i < tests.size(); i++) {
            MemoryStorage leadStorage = newTestMemoryStorage(1, 2, 3);
            leadStorage.append(ents);
            Raft lead = newTestRaft(1, 10, 1, leadStorage);
            lead.loadState(new HardState(term, 0, lead.getRaftLog().lastIndex()));
            MemoryStorage followerStorage = newTestMemoryStorage(1, 2, 3);
            followerStorage.append(tests.get(i));
            Raft follower = newTestRaft(2, 10, 1, followerStorage);
            follower.loadState(new HardState(term - 1, 0, 0));
            // 必须是三个节点的集群：第二个节点的日志可能比第一个新，
            // 第一个节点要拿到第三个节点的选票才能当上 leader。
            RaftNetwork n = RaftNetwork.newNetwork(lead, follower, new RaftNetwork.BlackHole());
            n.send(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_HUP));
            // 这次选举发生在上面 loadState 之后的那个任期
            n.send(new Message().setFrom(3).setTo(1).setType(MessageType.MSG_VOTE_RESP).setTerm(term + 1));

            n.send(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_PROP)
                    .setEntries(entries(0, 0)));

            assertEquals("", diffLogs(lead.getRaftLog(), follower.getRaftLog()), "#" + i);
        }
    }

    // ------------------------------------------------------------------
    // §5.4.1 投票
    // ------------------------------------------------------------------

    /**
     * Go: TestVoteRequest
     *
     * <p>拉票请求里要带上 candidate 的日志信息，并且发给其它所有节点。
     * 论文 §5.4.1。</p>
     */
    @Test
    // Go: TestVoteRequest
    void testVoteRequest() {
        // candidate 的日志、期望的任期
        Object[][] tests = {
                {entries(1, 1), 2L},
                {entries(1, 1, 2), 3L},
        };

        for (int j = 0; j < tests.length; j++) {
            @SuppressWarnings("unchecked")
            List<Entry> ents = (List<Entry>) tests[j][0];
            long wterm = (Long) tests[j][1];

            Raft r = newTestRaft(1, 10, 1, newTestMemoryStorage(1, 2, 3));
            r.step(new Message().setFrom(2).setTo(1).setType(MessageType.MSG_APP).setTerm(wterm - 1)
                    .setLogTerm(0).setIndex(0).setEntries(ents));
            readMessages(r);

            for (int i = 1; i < r.getElectionTimeout() * 2; i++) {
                r.tickElection();
            }

            List<Message> msgs = readMessages(r);
            sortMessages(msgs);
            assertEquals(2, msgs.size(), "#" + j);
            for (int i = 0; i < msgs.size(); i++) {
                Message m = msgs.get(i);
                assertEquals(MessageType.MSG_VOTE, m.getType(), "#" + j + "." + i);
                assertEquals(i + 2, m.getTo(), "#" + j + "." + i);
                assertEquals(wterm, m.getTerm(), "#" + j + "." + i);
                assertEquals(ents.get(ents.size() - 1).getIndex(), m.getIndex(), "#" + j + "." + i);
                assertEquals(ents.get(ents.size() - 1).getTerm(), m.getLogTerm(), "#" + j + "." + i);
            }
        }
    }

    /**
     * Go: TestVoter
     *
     * <p>如果投票节点自己的日志比 candidate 的更新，它就拒绝投票。
     * 论文 §5.4.1。</p>
     */
    @Test
    // Go: TestVoter
    void testVoter() {
        // 投票节点的日志、candidate 的日志任期、candidate 的日志位置、是否拒绝
        Object[][] tests = {
                // 日志任期相同
                {entries(1, 1), 1L, 1L, false},
                {entries(1, 1), 1L, 2L, false},
                {entries(1, 1, 1), 1L, 1L, true},
                // candidate 的日志任期更高
                {entries(1, 1), 2L, 1L, false},
                {entries(1, 1), 2L, 2L, false},
                {entries(1, 1, 1), 2L, 1L, false},
                // 投票节点的日志任期更高
                {entries(1, 2), 1L, 1L, true},
                {entries(1, 2), 1L, 2L, true},
                {entries(1, 2, 2), 1L, 1L, true},
                {entries(1, 1, 1), 1L, 1L, true},
        };

        for (int i = 0; i < tests.length; i++) {
            @SuppressWarnings("unchecked")
            List<Entry> ents = (List<Entry>) tests[i][0];
            long logterm = (Long) tests[i][1];
            long index = (Long) tests[i][2];
            boolean wreject = (Boolean) tests[i][3];

            MemoryStorage storage = newTestMemoryStorage(1, 2);
            storage.append(ents);
            Raft r = newTestRaft(1, 10, 1, storage);

            r.step(new Message().setFrom(2).setTo(1).setType(MessageType.MSG_VOTE).setTerm(3)
                    .setLogTerm(logterm).setIndex(index));

            List<Message> msgs = readMessages(r);
            assertEquals(1, msgs.size(), "#" + i);
            Message m = msgs.get(0);
            assertEquals(MessageType.MSG_VOTE_RESP, m.getType(), "#" + i);
            assertEquals(wreject, m.getReject(), "#" + i);
        }
    }

    // ------------------------------------------------------------------
    // §5.4.2 提交
    // ------------------------------------------------------------------

    /**
     * Go: TestLeaderOnlyCommitsLogFromCurrentTerm
     *
     * <p>leader 只能通过数副本的方式提交「当前任期」的日志条目。
     * 论文 §5.4.2。</p>
     */
    @Test
    // Go: TestLeaderOnlyCommitsLogFromCurrentTerm
    void testLeaderOnlyCommitsLogFromCurrentTerm() {
        List<Entry> ents = entries(1, 1, 2);
        // follower 确认到的位置、期望的提交位置
        long[][] tests = {
                // 不提交上一个任期的日志
                {1, 0},
                {2, 0},
                // 提交当前任期的日志
                {3, 3},
        };

        for (int i = 0; i < tests.length; i++) {
            long index = tests[i][0];
            long wcommit = tests[i][1];

            MemoryStorage storage = newTestMemoryStorage(1, 2);
            storage.append(ents);
            Raft r = newTestRaft(1, 10, 1, storage);
            r.loadState(new HardState(2, 0, 0));
            // 在任期 3 当上 leader
            r.becomeCandidate();
            r.becomeLeader();
            readMessages(r);
            // 提一条当前任期的日志
            r.step(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_PROP)
                    .setEntries(entries(0, 0)));

            r.step(new Message().setFrom(2).setTo(1).setType(MessageType.MSG_APP_RESP)
                    .setTerm(r.getTerm()).setIndex(index));
            advanceMessagesAfterAppend(r);
            assertEquals(wcommit, r.getRaftLog().committed(), "#" + i);
        }
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /**
     * Go: commitNoopEntry
     *
     * <p>让 leader 当上之后追加的那条空日志被提交掉。</p>
     */
    private static void commitNoopEntry(Raft r, MemoryStorage s) {
        if (r.getState() != RaftRole.LEADER) {
            throw new RaftException("it should only be used when it is the leader");
        }
        r.bcastAppend();
        // 模拟 MsgApp 的回复
        for (Message m : readMessages(r)) {
            if (m.getType() != MessageType.MSG_APP || m.getEntries() == null
                    || m.getEntries().size() != 1 || m.getEntries().get(0).getData().length != 0) {
                throw new RaftException("not a message to append noop entry");
            }
            r.step(acceptAndReply(m));
        }
        // 后面的消息只是用来刷新 follower 的提交位置，忽略
        readMessages(r);
        s.append(r.getRaftLog().nextUnstableEnts());
        r.getRaftLog().appliedTo(r.getRaftLog().committed(), 0);
        r.getRaftLog().stableTo(r.getRaftLog().lastIndex(), r.getRaftLog().lastTerm());
    }

    /**
     * Go: acceptAndReply
     */
    private static Message acceptAndReply(Message m) {
        if (m.getType() != MessageType.MSG_APP) {
            throw new RaftException("type should be MsgApp");
        }
        return new Message()
                .setFrom(m.getTo())
                .setTo(m.getFrom())
                .setTerm(m.getTerm())
                .setType(MessageType.MSG_APP_RESP)
                .setIndex(m.getIndex() + m.getEntries().size());
    }

    /**
     * Go: index(start).terms(...)
     *
     * <p>造一批日志：第 i 条的 index 是 {@code start + i}，term 是 terms[i]。
     * data 为空；带 data 的用 {@link #entries(long, long, String...)}。</p>
     */
    private static List<Entry> entries(long start, long... terms) {
        Entry[] ents = new Entry[terms.length];
        for (int i = 0; i < terms.length; i++) {
            ents[i] = new Entry(start + i, terms[i], EntryType.NORMAL, null);
        }
        return Arrays.asList(ents);
    }

    /**
     * Go: index(start).terms(...) 带数据
     *
     * <p>每条日志的 data 就是对应的字符串。</p>
     */
    private static List<Entry> dataEntries(long start, long term, String... datas) {
        Entry[] ents = new Entry[datas.length];
        for (int i = 0; i < datas.length; i++) {
            ents[i] = new Entry(start + i, term, EntryType.NORMAL, datas[i].getBytes());
        }
        return Arrays.asList(ents);
    }

    /**
     * 造一个「节点 id → 投票结果」的表
     */
    private static Map<Long, Boolean> votes(Object... pairs) {
        Map<Long, Boolean> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(((Number) pairs[i]).longValue(), (Boolean) pairs[i + 1]);
        }
        return map;
    }

    /**
     * Go: sort.Sort(messageSlice(msgs))
     *
     * <p>etcd 是按消息的字符串表示排序的，实际效果就是先按 from 再按 to。</p>
     */
    private static void sortMessages(List<Message> msgs) {
        msgs.sort(Comparator.comparingLong(Message::getFrom).thenComparingLong(Message::getTo));
    }

    /**
     * Go: 替换掉 {@code r.step} 的假实现，用来观察状态机有没有被调用
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
}
