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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static io.edap.raft.RaftTestSupport.assertConfStateEquals;
import static io.edap.raft.RaftTestSupport.assertEqualEntries;
import static io.edap.raft.RaftTestSupport.newTestConfig;
import static io.edap.raft.RaftTestSupport.newTestMemoryStorage;
import static io.edap.raft.RaftTestSupport.newTestRawNode;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;

/**
 * etcd {@code rawnode_test.go} 的 Java 实现。
 */
public class RawNodeTest {

    /**
     * Go: TestRawNodeStep
     *
     * <p>确保 RawNode.Step 会忽略本地消息。</p>
     */
    @Test
    // Go: TestRawNodeStep
    void testRawNodeStep() {
        for (MessageType msgt : MessageType.values()) {
            MemoryStorage s = new MemoryStorage();
            s.setHardState(new HardState(1, 0, 1));
            s.append(Collections.singletonList(new Entry(1, 1, EntryType.NORMAL, null)));
            s.applySnapshot(new Snapshot(new byte[0], new SnapshotMetadata(
                    new ConfState(Collections.singletonList(1L), Collections.emptyList(),
                            Collections.emptyList(), Collections.emptyList(), false), 1, 1)));

            RawNode rawNode = new RawNode(newTestConfig(1, 10, 1, s));
            Message m = new Message().setType(msgt);
            if (RaftUtil.isLocalMsg(msgt)) {
                // 本地消息必须被拒绝
                RaftException e = assertThrows(RaftException.class, () -> rawNode.step(m),
                        "message type " + msgt);
                assertEquals("raft: cannot step raft local message", e.getMessage(),
                        "message type " + msgt);
            } else if (RaftUtil.isResponseMsg(msgt)) {
                // 响应消息必须来自已知节点
                RaftException e = assertThrows(RaftException.class, () -> rawNode.step(m),
                        "message type " + msgt);
                assertEquals("raft: cannot step as peer not found", e.getMessage(),
                        "message type " + msgt);
            } else {
                rawNode.step(m);
            }
        }
    }

    /**
     * Go: TestRawNodeProposeAndConfChange
     *
     * <p>测试配置变更机制：每个用例发一次配置变更（简单或联合），
     * 验证它能被应用、产生的 ConfState 与预期一致，联合配置还能正常退出。</p>
     */
    @Test
    // Go: TestRawNodeProposeAndConfChange
    void testRawNodeProposeAndConfChange() {
        List<Object[]> cases = new ArrayList<>();
        // v1 配置变更
        cases.add(new Object[]{
                new ConfChange(ConfChangeType.ADD_NODE, 2, new byte[0]),
                confState(1, 2),
                null});
        // 用 v2 提交同样的变更，效果一样，但不会进入联合状态
        cases.add(new Object[]{
                new ConfChangeV2(ConfChangeTransition.AUTO,
                        Collections.singletonList(new ConfChangeSingle(ConfChangeType.ADD_NODE, 2)),
                        new byte[0]),
                confState(1, 2),
                null});
        // 作为 learner 加入
        cases.add(new Object[]{
                new ConfChangeV2(ConfChangeTransition.AUTO,
                        Collections.singletonList(new ConfChangeSingle(ConfChangeType.ADD_LEARNER_NODE, 2)),
                        new byte[0]),
                confStateWithLearner(1, 2),
                null});
        // 显式要求联合共识
        cases.add(new Object[]{
                new ConfChangeV2(ConfChangeTransition.JOINT_EXPLICIT,
                        Collections.singletonList(new ConfChangeSingle(ConfChangeType.ADD_LEARNER_NODE, 2)),
                        new byte[0]),
                confStateJoint(1, 2, false),
                confStateWithLearner(1, 2)});
        // 隐式联合共识（由测试框架检查）
        cases.add(new Object[]{
                new ConfChangeV2(ConfChangeTransition.JOINT_IMPLICIT,
                        Collections.singletonList(new ConfChangeSingle(ConfChangeType.ADD_LEARNER_NODE, 2)),
                        new byte[0]),
                confStateJoint(1, 2, true),
                confStateWithLearner(1, 2)});
        // 加入一个新节点并把 n1 降级，需要真正的联合配置与 LearnersNext
        cases.add(new Object[]{
                new ConfChangeV2(ConfChangeTransition.AUTO, Arrays.asList(
                        new ConfChangeSingle(ConfChangeType.ADD_NODE, 2),
                        new ConfChangeSingle(ConfChangeType.ADD_LEARNER_NODE, 1),
                        new ConfChangeSingle(ConfChangeType.ADD_LEARNER_NODE, 3)),
                        new byte[0]),
                confStateDemote(),
                confStateDemoted()});
        // 同上，显式联合
        cases.add(new Object[]{
                new ConfChangeV2(ConfChangeTransition.JOINT_EXPLICIT, Arrays.asList(
                        new ConfChangeSingle(ConfChangeType.ADD_NODE, 2),
                        new ConfChangeSingle(ConfChangeType.ADD_LEARNER_NODE, 1),
                        new ConfChangeSingle(ConfChangeType.ADD_LEARNER_NODE, 3)),
                        new byte[0]),
                confStateDemote(),
                confStateDemoted()});
        // 同上，隐式联合
        cases.add(new Object[]{
                new ConfChangeV2(ConfChangeTransition.JOINT_IMPLICIT, Arrays.asList(
                        new ConfChangeSingle(ConfChangeType.ADD_NODE, 2),
                        new ConfChangeSingle(ConfChangeType.ADD_LEARNER_NODE, 1),
                        new ConfChangeSingle(ConfChangeType.ADD_LEARNER_NODE, 3)),
                        new byte[0]),
                confStateDemote(),
                confStateDemoted()});

        for (Object[] tc : cases) {
            ConfChangeI cc = (ConfChangeI) tc[0];
            ConfState exp = (ConfState) tc[1];
            ConfState exp2 = (ConfState) tc[2];

            MemoryStorage s = newTestMemoryStorage(1);
            RawNode rawNode = new RawNode(newTestConfig(1, 10, 1, s));

            rawNode.campaign();
            boolean proposed = false;
            long lastIndex = 0;
            byte[] ccdata = null;
            // 提交 ConfChange，等它被应用，保存产生的 ConfState
            ConfState cs = null;
            while (cs == null) {
                Ready rd = rawNode.ready();
                s.append(rd.getEntries());
                for (Entry ent : rd.getCommittedEntries()) {
                    ConfChangeI applied = null;
                    if (ent.getType() == EntryType.CONFIG_CHANGE) {
                        applied = ConfChangeCodec.unmarshal(ent.getType(), ent.getData());
                    } else if (ent.getType() == EntryType.CONFIG_CHANGE_V2) {
                        applied = ConfChangeCodec.unmarshal(ent.getType(), ent.getData());
                    }
                    if (applied != null) {
                        cs = rawNode.applyConfChange(applied);
                    }
                }
                rawNode.advance(rd);
                // 成为 leader 之后，提交一条命令与一次 ConfChange
                if (!proposed && rd.getSoftState() != null
                        && rd.getSoftState().getLead() == rawNode.getRaft().getId()) {
                    rawNode.propose("somedata".getBytes());
                    if (cc.asV1() != null) {
                        ccdata = ConfChangeCodec.marshal(cc.asV1()).getData();
                        rawNode.proposeConfChange(cc.asV1());
                    } else {
                        ccdata = ConfChangeCodec.marshal(cc.asV2()).getData();
                        rawNode.proposeConfChange(cc.asV2());
                    }
                    proposed = true;
                }
            }

            // 最后一条日志必须正好是刚才提交的 ConfChange。
            // 注意这里读的是 Storage，反映的是已落盘的日志。
            lastIndex = s.lastIndex();
            List<Entry> entries = s.entries(lastIndex - 1, lastIndex + 1);
            assertEquals(2, entries.size(), "entries length");
            assertArrayEquals("somedata".getBytes(), entries.get(0).getData(), "entries[0].data");

            EntryType typ = cc.asV1() != null ? EntryType.CONFIG_CHANGE : EntryType.CONFIG_CHANGE_V2;
            assertEquals(typ, entries.get(1).getType(), "entries[1].type");
            assertArrayEquals(ccdata, entries.get(1).getData(), "entries[1].data");

            assertConfStateEquals(exp, cs);

            long maybePlusOne = 0;
            boolean[] joint = cc.asV2().enterJoint();
            if (joint[0]) {
                // 自动退出的联合配置会额外追加一条退出条目，
                // 所以 pendingConfIndex 的基准要加一
                maybePlusOne = 1;
            }
            assertEquals(lastIndex + maybePlusOne, rawNode.getRaft().getPendingConfIndex(),
                    "pendingConfIndex");

            // 继续推进 RawNode。如果 ConfChange 是简单的，就不应该再有别的动作；
            // 否则说明处于联合状态，要么自动退出，要么手工退出。
            Ready rd = rawNode.ready();
            byte[] context = null;
            if (!exp.isAutoLeave()) {
                assertTrue(rd.getEntries().isEmpty(), "rd.Entries should be empty");
                rawNode.advance(rd);
                if (exp2 == null) {
                    return;
                }
                context = "manual".getBytes();
                rawNode.proposeConfChange(new ConfChangeV2(ConfChangeTransition.AUTO,
                        Collections.emptyList(), context));
                rd = rawNode.ready();
            }

            // 检查出来的 ConfChange 是否正确
            assertEquals(1, rd.getEntries().size(), "rd.Entries length");
            assertEquals(EntryType.CONFIG_CHANGE_V2, rd.getEntries().get(0).getType(),
                    "rd.Entries[0].type");
            ConfChangeV2 leave = (ConfChangeV2) ConfChangeCodec.unmarshal(
                    EntryType.CONFIG_CHANGE_V2, rd.getEntries().get(0).getData());
            assertEquals(context == null ? new byte[0] : context, leave.getContext(), "cc.Context");

            // 假装这次 ConfChange 被应用了（单节点跑不满联合仲裁）
            cs = rawNode.applyConfChange(leave);
            assertConfStateEquals(exp2, cs);

            rawNode.advance(rd);
        }
    }

    /**
     * Go: TestRawNodeJointAutoLeave
     *
     * <p>leader 丢失领导权时，配置变更也要能自动退出联合状态。</p>
     */
    @Test
    // Go: TestRawNodeJointAutoLeave
    void testRawNodeJointAutoLeave() {
        ConfChangeV2 testCc = new ConfChangeV2(ConfChangeTransition.JOINT_IMPLICIT,
                Collections.singletonList(new ConfChangeSingle(ConfChangeType.ADD_LEARNER_NODE, 2)),
                new byte[0]);
        ConfState expCs = confStateJoint(1, 2, true);
        ConfState exp2Cs = new ConfState(Collections.singletonList(1L),
                Collections.singletonList(2L), Collections.emptyList(),
                Collections.emptyList(), false);

        MemoryStorage s = newTestMemoryStorage(1);
        RawNode rawNode = new RawNode(newTestConfig(1, 10, 1, s));

        rawNode.campaign();
        boolean proposed = false;
        long lastIndex = 0;
        byte[] ccdata = null;
        ConfState cs = null;
        while (cs == null) {
            Ready rd = rawNode.ready();
            s.append(rd.getEntries());
            for (Entry ent : rd.getCommittedEntries()) {
                ConfChangeI applied = null;
                if (ent.getType() == EntryType.CONFIG_CHANGE_V2) {
                    applied = ConfChangeCodec.unmarshal(ent.getType(), ent.getData());
                }
                if (applied != null) {
                    // 强制退位
                    rawNode.step(new Message().setType(MessageType.MSG_HEARTBEAT_RESP)
                            .setFrom(1).setTerm(rawNode.getRaft().getTerm() + 1));
                    cs = rawNode.applyConfChange(applied);
                }
            }
            rawNode.advance(rd);
            if (!proposed && rd.getSoftState() != null
                    && rd.getSoftState().getLead() == rawNode.getRaft().getId()) {
                rawNode.propose("somedata".getBytes());
                ccdata = ConfChangeCodec.marshal(testCc).getData();
                rawNode.proposeConfChange(testCc);
                proposed = true;
            }
        }

        lastIndex = s.lastIndex();
        List<Entry> entries = s.entries(lastIndex - 1, lastIndex + 1);
        assertEquals(2, entries.size(), "entries length");
        assertArrayEquals("somedata".getBytes(), entries.get(0).getData(), "entries[0].data");
        assertEquals(EntryType.CONFIG_CHANGE_V2, entries.get(1).getType(), "entries[1].type");
        assertArrayEquals(ccdata, entries.get(1).getData(), "entries[1].data");

        assertConfStateEquals(expCs, cs);
        assertEquals(0, rawNode.getRaft().getPendingConfIndex(), "pendingConfIndex");

        // 继续推进 RawNode。此时是 follower，不应该自动退出联合状态
        Ready rd = rawNode.readyWithoutAccept();
        assertTrue(rd.getEntries().isEmpty(), "rd.Entries should be empty");

        // 重新当 leader，移动 apply 位置之后应该自动退出联合状态
        rawNode.campaign();
        rd = rawNode.ready();
        s.append(rd.getEntries());
        rawNode.advance(rd);
        rd = rawNode.ready();
        s.append(rd.getEntries());
        rawNode.advance(rd);
        rd = rawNode.ready();
        s.append(rd.getEntries());
        rawNode.advance(rd);
        rd = rawNode.ready();
        s.append(rd.getEntries());
        // 检查出来的 ConfChange 是否正确
        assertEquals(1, rd.getEntries().size(), "rd.Entries length");
        assertEquals(EntryType.CONFIG_CHANGE_V2, rd.getEntries().get(0).getType(), "rd.Entries[0].type");
        ConfChangeV2 leave = (ConfChangeV2) ConfChangeCodec.unmarshal(
                EntryType.CONFIG_CHANGE_V2, rd.getEntries().get(0).getData());
        assertEquals(0, leave.getChanges().size(), "cc.Changes length");
        assertEquals(0, leave.getContext().length, "cc.Context length");
        // 假装这次 ConfChange 被应用了（单节点跑不满联合仲裁）
        cs = rawNode.applyConfChange(leave);
        assertConfStateEquals(exp2Cs, cs);
    }

    /**
     * Go: TestRawNodeProposeAddDuplicateNode
     *
     * <p>两次添加同一个节点不应影响之后添加新节点。</p>
     */
    @Test
    // Go: TestRawNodeProposeAddDuplicateNode
    void testRawNodeProposeAddDuplicateNode() {
        MemoryStorage s = newTestMemoryStorage(1);
        RawNode rawNode = new RawNode(newTestConfig(1, 10, 1, s));

        Ready rd = rawNode.ready();
        s.append(rd.getEntries());
        rawNode.advance(rd);

        rawNode.campaign();
        while (true) {
            rd = rawNode.ready();
            s.append(rd.getEntries());
            rawNode.advance(rd);
            if (rd.getSoftState() != null
                    && rd.getSoftState().getLead() == rawNode.getRaft().getId()) {
                break;
            }
        }

        ConfChange cc1 = new ConfChange(ConfChangeType.ADD_NODE, 1, new byte[0]);
        byte[] ccdata1 = ConfChangeCodec.marshal(cc1).getData();
        proposeConfChangeAndApply(rawNode, s, cc1);

        // 再把同一个节点加一遍
        proposeConfChangeAndApply(rawNode, s, cc1);

        // 加入新节点应该没问题
        ConfChange cc2 = new ConfChange(ConfChangeType.ADD_NODE, 2, new byte[0]);
        byte[] ccdata2 = ConfChangeCodec.marshal(cc2).getData();
        proposeConfChangeAndApply(rawNode, s, cc2);

        long lastIndex = s.lastIndex();
        // 最后三条应该是 cc1、cc1、cc2
        List<Entry> entries = s.entries(lastIndex - 2, lastIndex + 1);
        assertEquals(3, entries.size(), "entries length");
        assertArrayEquals(ccdata1, entries.get(0).getData(), "entries[0].data");
        assertArrayEquals(ccdata2, entries.get(2).getData(), "entries[2].data");
    }

    /**
     * Go: TestRawNodeReadIndex
     *
     * <p>确保 RawNode.ReadIndex 会把 MsgReadIndex 发给底层 raft，
     * 并且 ReadState 能被读出来。</p>
     */
    @Test
    // Go: TestRawNodeReadIndex
    void testRawNodeReadIndex() {
        List<ReadState> wrs = Collections.singletonList(new ReadState(1, "somedata".getBytes()));

        MemoryStorage s = newTestMemoryStorage(1);
        RawNode rawNode = new RawNode(newTestConfig(1, 10, 1, s));

        rawNode.getRaft().getReadStates().add(wrs.get(0));
        // ReadState 能被读出来
        assertTrue(rawNode.hasReady(), "hasReady");
        Ready rd = rawNode.ready();
        assertEquals(wrs, rd.getReadStates(), "rd.ReadStates");
        s.append(rd.getEntries());
        rawNode.advance(rd);
        // advance 之后 raft.readStates 会被清空
        assertTrue(rawNode.getRaft().getReadStates().isEmpty(), "raft.readStates should be empty");

        byte[] wrequestCtx = "somedata2".getBytes();
        rawNode.campaign();
        while (true) {
            rd = rawNode.ready();
            s.append(rd.getEntries());
            if (rd.getSoftState() != null
                    && rd.getSoftState().getLead() == rawNode.getRaft().getId()) {
                rawNode.advance(rd);
                // 成为 leader 之后发起一次 ReadIndex
                rawNode.readIndex(wrequestCtx);
                break;
            }
            rawNode.advance(rd);
        }
        // 确认读请求已经交给了底层 raft 并被处理。
        // Go 用 "替换 raft.step 函数" 的测试钩子截住 MsgReadIndex，Java 没有这个钩子，
        // MsgReadIndex 会被 raft 直接消费掉，因此只能观察它产生的 ReadState。
        List<ReadState> readStates = rawNode.getRaft().getReadStates();
        assertEquals(1, readStates.size(), "raft.readStates length");
        assertArrayEquals(wrequestCtx, readStates.get(0).getRequestCtx(), "raft.readStates[0].RequestCtx");
    }

    /**
     * Go: TestRawNodeStart
     *
     * <p>确保节点能正常启动。RawNode 要求应用层自己引导状态，
     * 它不接受 peers，也不会造出假的配置变更条目。</p>
     */
    @Test
    // Go: TestRawNodeStart
    void testRawNodeStart() {
        List<Entry> entries = Arrays.asList(
                new Entry(2, 1, EntryType.NORMAL, null),   // 空条目
                new Entry(3, 1, EntryType.NORMAL, "foo".getBytes())); // 非空条目

        MemoryStorage storage = new MemoryStorage();

        // 用快照引导状态：快照点必须在日志位置 1 之后，
        // 这样 follower 必须先拿到 ConfState 才能离开日志位置 1。
        bootstrap(storage, new ConfState(Collections.singletonList(1L), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), false));

        RawNode rawNode = new RawNode(newTestConfig(1, 10, 1, storage));
        assertFalse(rawNode.hasReady(), "hasReady");

        rawNode.campaign();
        Ready rd = rawNode.ready();
        storage.append(rd.getEntries());
        rawNode.advance(rd);
        rawNode.propose("foo".getBytes());
        assertTrue(rawNode.hasReady(), "hasReady");

        rd = rawNode.ready();
        assertEqualEntries(entries, rd.getEntries());
        storage.append(rd.getEntries());
        rawNode.advance(rd);

        assertTrue(rawNode.hasReady(), "hasReady");
        rd = rawNode.ready();
        assertTrue(rd.getEntries().isEmpty(), "rd.Entries should be empty");
        assertFalse(rd.isMustSync(), "rd.MustSync");
        rawNode.advance(rd);

        // Go: rd.SoftState, want.SoftState = nil, nil
        // 软状态在第一次 Ready 里已经报过了，这里没有变化，所以是空的
        assertNull(rd.getSoftState(), "rd.SoftState");
        assertEquals(new HardState(1, 1, 3), rd.getHardState(), "rd.HardState");
        assertEqualEntries(entries, rd.getCommittedEntries());
        assertFalse(rawNode.hasReady(), "hasReady");
    }

    /**
     * Go: TestRawNodeRestart
     */
    @Test
    // Go: TestRawNodeRestart
    void testRawNodeRestart() {
        List<Entry> entries = Arrays.asList(
                new Entry(1, 1, EntryType.NORMAL, null),
                new Entry(2, 1, EntryType.NORMAL, "foo".getBytes()));
        HardState st = new HardState(1, 0, 1);

        MemoryStorage storage = newTestMemoryStorage(1);
        storage.setHardState(st);
        storage.append(entries);

        RawNode rawNode = new RawNode(newTestConfig(1, 10, 1, storage));
        Ready rd = rawNode.ready();
        assertNull(rd.getHardState(), "rd.HardState");
        // 提交到 st.Commit 为止
        assertEqualEntries(entries.subList(0, (int) st.getCommit()), rd.getCommittedEntries());
        assertFalse(rd.isMustSync(), "rd.MustSync");
        rawNode.advance(rd);
        assertFalse(rawNode.hasReady(), "hasReady");
    }

    /**
     * Go: TestRawNodeRestartFromSnapshot
     */
    @Test
    // Go: TestRawNodeRestartFromSnapshot
    void testRawNodeRestartFromSnapshot() {
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

        RawNode rawNode = new RawNode(newTestConfig(1, 10, 1, s));
        Ready rd = rawNode.ready();
        assertNull(rd.getHardState(), "rd.HardState");
        // 提交到 st.Commit 为止
        assertEqualEntries(entries, rd.getCommittedEntries());
        assertFalse(rd.isMustSync(), "rd.MustSync");
        rawNode.advance(rd);
        assertFalse(rawNode.hasReady(), "hasReady");
    }

    /**
     * Go: TestRawNodeStatus
     */
    @Test
    // Go: TestRawNodeStatus
    void testRawNodeStatus() {
        MemoryStorage s = newTestMemoryStorage(1);
        RawNode rn = new RawNode(newTestConfig(1, 10, 1, s));
        assertTrue(rn.status().getProgress().isEmpty(), "status.Progress should be empty");
        rn.campaign();

        Ready rd = rn.ready();
        s.append(rd.getEntries());
        rn.advance(rd);
        Status status = rn.status();
        assertEquals(1, status.getSoftState().getLead(), "status.Lead");
        assertEquals(RaftRole.LEADER, status.getSoftState().getRaftState(), "status.RaftState");

        Progress expected = rn.getRaft().getTracker().getProgress().get(1L);
        Progress actual = status.getProgress().get(1L);
        assertNotNull(actual, "status.Progress[1]");
        assertEquals(expected.getMatch(), actual.getMatch(), "progress.Match");
        assertEquals(expected.getNext(), actual.getNext(), "progress.Next");
        assertEquals(expected.getState(), actual.getState(), "progress.State");
        assertEquals(expected.isLearner(), actual.isLearner(), "progress.IsLearner");

        assertEquals(Collections.singletonList(1L), status.getConfig().getVoters().getIncoming().slice(),
                "status.Config.Voters");
        assertTrue(status.getConfig().getVoters().getOutgoing().slice().isEmpty(),
                "status.Config.VotersOutgoing");
    }

    /**
     * Go: TestRawNodeCommitPaginationAfterRestart
     *
     * <p>重启后分页提交已提交条目时不能丢条目。</p>
     */
    @Test
    // Go: TestRawNodeCommitPaginationAfterRestart
    void testRawNodeCommitPaginationAfterRestart() {
        MemoryStorage s = newTestMemoryStorage(1);
        HardState persistedHardState = new HardState(1, 1, 10);

        List<Entry> ents = new ArrayList<>();
        long size = 0;
        for (int i = 0; i < 10; i++) {
            Entry ent = new Entry(i + 1, 1, EntryType.NORMAL, "a".getBytes());
            ents.add(ent);
            size += io.edap.raft.protocol.RaftCodec.entrySize(ent);
        }
        s.setHardState(persistedHardState);
        s.append(ents);

        RaftConfig cfg = newTestConfig(1, 10, 1, s);
        // 设置一个 MaxSizePerMsg，让 Raft 认为最后一条已提交条目不该出现在第一个
        // Ready 里；但我们的存储会忽略这个提示，照样把它返回出来
        //（提交位置一开始就是 10 就是这么来的）。
        cfg.maxSizePerMsg = size - io.edap.raft.protocol.RaftCodec.entrySize(ents.get(ents.size() - 1)) - 1;

        s.append(Collections.singletonList(
                new Entry(11, 1, EntryType.NORMAL, "boom".getBytes())));

        RawNode rawNode = new RawNode(cfg);

        long highestApplied = 0;
        while (highestApplied != 11) {
            Ready rd = rawNode.ready();
            int n = rd.getCommittedEntries().size();
            assertTrue(n != 0, "stopped applying entries at index " + highestApplied);
            long next = rd.getCommittedEntries().get(0).getIndex();
            assertTrue(highestApplied == 0 || highestApplied + 1 == next,
                    "attempting to apply index " + next + " after index " + highestApplied
                            + ", leaving a gap");
            highestApplied = rd.getCommittedEntries().get(n - 1).getIndex();
            rawNode.advance(rd);
            rawNode.step(new Message()
                    .setType(MessageType.MSG_HEARTBEAT)
                    .setTo(1)
                    .setFrom(2) // 非法，但这里能糊弄过去
                    .setTerm(1)
                    .setCommit(11));
        }
    }

    /**
     * Go: TestRawNodeBoundedLogGrowthWithPartition
     *
     * <p>leader 与多数派失联时，未提交日志不能无限增长，
     * 这个保护由 MaxUncommittedEntriesSize 提供。</p>
     */
    @Test
    // Go: TestRawNodeBoundedLogGrowthWithPartition
    void testRawNodeBoundedLogGrowthWithPartition() {
        int maxEntries = 16;
        byte[] data = "testdata".getBytes();
        Entry testEntry = new Entry(0, 0, EntryType.NORMAL, data);
        // 上限按 payload 计算，不含条目的元数据，与 etcd 的 payloadSize 一致
        long maxEntrySize = maxEntries * Raft.payloadsSize(Collections.singletonList(testEntry));

        MemoryStorage s = newTestMemoryStorage(1);
        RaftConfig cfg = newTestConfig(1, 10, 1, s);
        cfg.maxUncommittedEntriesSize = maxEntrySize;
        RawNode rawNode = new RawNode(cfg);

        // 当选 leader 并应用空条目
        rawNode.campaign();
        while (true) {
            Ready rd = rawNode.ready();
            s.append(rd.getEntries());
            rawNode.advance(rd);
            if (!rd.getCommittedEntries().isEmpty()) {
                break;
            }
        }

        // 模拟网络分区：一直不提交任何东西，
        // 提案不应该让 leader 的日志无限增长
        for (int i = 0; i < 1024; i++) {
            rawNode.propose(data);
        }

        // 检查 leader 未提交日志尾部的大小，不应超过上限
        assertEquals(maxEntrySize, rawNode.getRaft().getUncommittedSize(), "uncommittedSize");

        // 分区恢复，未提交的日志尾部应该随着提交而消失
        Ready rd = rawNode.ready();
        assertEquals(maxEntries, rd.getEntries().size(), "rd.Entries length");
        s.append(rd.getEntries());
        rawNode.advance(rd);

        // 条目被追加了，但还没被应用
        assertEquals(maxEntrySize, rawNode.getRaft().getUncommittedSize(), "uncommittedSize");

        rd = rawNode.ready();
        assertTrue(rd.getEntries().isEmpty(), "rd.Entries should be empty");
        assertEquals(maxEntries, rd.getCommittedEntries().size(), "rd.CommittedEntries length");
        rawNode.advance(rd);

        assertEquals(0, rawNode.getRaft().getUncommittedSize(), "uncommittedSize");
    }

    /**
     * Go: TestRawNodeConsumeReady
     *
     * <p>readyWithoutAccept() 不会调用 acceptReady（后者会清空消息），
     * 而 Ready() 会。</p>
     */
    @Test
    // Go: TestRawNodeConsumeReady
    void testRawNodeConsumeReady() {
        MemoryStorage s = newTestMemoryStorage(1);
        RawNode rn = newTestRawNode(1, 3, 1, s);
        Message m1 = new Message().setContext("foo".getBytes());
        Message m2 = new Message().setContext("bar".getBytes());

        // 注入第一条消息，确认 readyWithoutAccept 能看到它
        rn.getRaft().getMessages().add(m1);
        Ready rd = rn.readyWithoutAccept();
        assertEquals(1, rd.getMessages().size(), "rd.Messages length");
        assertEquals(m1, rd.getMessages().get(0), "rd.Messages[0]");
        assertEquals(1, rn.getRaft().getMessages().size(), "raft.msgs length");
        assertEquals(m1, rn.getRaft().getMessages().get(0), "raft.msgs[0]");

        // 现在调用 Ready()，消息应该从 raft 里被取走
        rd = rn.ready();
        assertTrue(rn.getRaft().getMessages().isEmpty(), "raft.msgs should be empty");
        assertEquals(1, rd.getMessages().size(), "rd.Messages length");
        assertEquals(m1, rd.getMessages().get(0), "rd.Messages[0]");

        // 再加一条消息，确认 Advance() 不会把它弄丢
        rn.getRaft().getMessages().add(m2);
        rn.advance(rd);
        assertEquals(1, rn.getRaft().getMessages().size(), "raft.msgs length");
        assertEquals(m2, rn.getRaft().getMessages().get(0), "raft.msgs[0]");
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    /**
     * Go: rawNode.ProposeConfChange + 应用变更的闭包
     */
    private static void proposeConfChangeAndApply(RawNode rawNode, MemoryStorage s, ConfChange cc) {
        rawNode.proposeConfChange(cc);
        Ready rd = rawNode.ready();
        s.append(rd.getEntries());
        for (Entry entry : rd.getCommittedEntries()) {
            if (entry.getType() == EntryType.CONFIG_CHANGE) {
                ConfChange decoded = (ConfChange) ConfChangeCodec.unmarshal(
                        entry.getType(), entry.getData());
                rawNode.applyConfChange(decoded);
            }
        }
        rawNode.advance(rd);
    }

    /**
     * Go: TestRawNodeStart 里的 bootstrap 闭包
     */
    private static void bootstrap(MemoryStorage storage, ConfState cs) {
        assertFalse(cs.getVoters().isEmpty(), "no voters specified");

        HardState hs = storage.initialState();
        assertTrue(RaftUtil.isEmptyHardState(hs), "hard state should be empty");
        assertTrue(storage.getConfState().getVoters().isEmpty(), "initial voters should be empty");

        // 引导：把成员配置写到 index=1 的快照里，
        // 日志保留区间的起点因此变成 2，follower 必须先拿到 ConfState 才能离开日志位置 1。
        // Go 里是靠直接改 storage.ents[0].Index 造出这个状态，
        // Java 的 MemoryStorage 没有暴露这个入口，用等价的快照代替。
        storage.applySnapshot(new Snapshot(new byte[0], new SnapshotMetadata(cs, 1, 0)));

        long fi = storage.firstIndex();
        assertTrue(fi >= 2, "FirstIndex >= 2 is prerequisite for bootstrap");

        assertThrows(RaftException.class, () -> storage.entries(fi, fi),
                "should not have been able to load first index");

        long li = storage.lastIndex();
        assertThrows(RaftException.class, () -> storage.entries(li, li),
                "should not have been able to load last index");
    }

    private static ConfState confState(long... voters) {
        List<Long> list = new ArrayList<>();
        for (long v : voters) {
            list.add(v);
        }
        return new ConfState(list, Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), false);
    }

    private static ConfState confStateWithLearner(long voter, long learner) {
        return new ConfState(Collections.singletonList(voter),
                Collections.singletonList(learner), Collections.emptyList(),
                Collections.emptyList(), false);
    }

    private static ConfState confStateJoint(long voter, long learner, boolean autoLeave) {
        return new ConfState(Collections.singletonList(voter),
                Collections.singletonList(learner),
                Collections.singletonList(voter), Collections.emptyList(), autoLeave);
    }

    private static ConfState confStateDemote() {
        // voters=[2] votersOutgoing=[1] learners=[3] learnersNext=[1] autoLeave=true
        return new ConfState(Collections.singletonList(2L),
                Collections.singletonList(3L),
                Collections.singletonList(1L),
                Collections.singletonList(1L), true);
    }

    private static ConfState confStateDemoted() {
        // voters=[2] learners=[1,3]
        return new ConfState(Collections.singletonList(2L),
                Arrays.asList(1L, 3L), Collections.emptyList(),
                Collections.emptyList(), false);
    }
}
