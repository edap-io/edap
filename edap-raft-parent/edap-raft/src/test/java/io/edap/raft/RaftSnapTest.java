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

import io.edap.raft.protocol.ConfState;
import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.EntryType;
import io.edap.raft.protocol.Message;
import io.edap.raft.protocol.MessageType;
import io.edap.raft.protocol.Snapshot;
import io.edap.raft.protocol.SnapshotMetadata;
import io.edap.raft.storage.MemoryStorage;
import io.edap.raft.tracker.Progress;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static io.edap.raft.RaftTestSupport.newTestMemoryStorage;
import static io.edap.raft.RaftTestSupport.newTestRaft;
import static io.edap.raft.RaftTestSupport.readMessages;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * etcd {@code raft_snap_test.go} 的 Java 实现。
 *
 * <p>覆盖 leader 侧「发快照」这条路径上的几个关键状态转换：</p>
 * <ul>
 *     <li>follower 落后太多时进入快照状态，并记下待发快照的 index</li>
 *     <li>快照在途时暂停向该 follower 复制</li>
 *     <li>快照成功 / 失败 / 被后续追加确认打断时，进度怎么回退</li>
 * </ul>
 */
public class RaftSnapTest {

    /**
     * Go: testingSnap —— index/term 都是 11 的「魔法数字」，成员是 1 和 2。
     */
    private static Snapshot testingSnap() {
        ConfState cs = new ConfState(Arrays.asList(1L, 2L), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), false);
        return new Snapshot(new byte[0], new SnapshotMetadata(cs, 11, 11));
    }

    /**
     * Go: TestSendingSnapshotSetPendingSnapshot
     */
    @Test
    void testSendingSnapshotSetPendingSnapshot() {
        MemoryStorage storage = newTestMemoryStorage(1);
        Raft sm = newTestRaft(1, 10, 1, storage);
        sm.restore(testingSnap());

        sm.becomeCandidate();
        sm.becomeLeader();

        // 强制把节点 2 的 next 设成 firstIndex，让它需要快照
        Progress pr2 = sm.getTracker().getProgress().get(2);
        pr2.setNext(sm.getRaftLog().firstIndex());

        long rejected = pr2.getNext() - 1;
        sm.step(new Message().setFrom(2).setTo(1).setType(MessageType.MSG_APP_RESP)
                .setIndex(rejected).setReject(true));

        assertEquals(11, pr2.getPendingSnapshot());
    }

    /**
     * Go: TestPendingSnapshotPauseReplication
     */
    @Test
    void testPendingSnapshotPauseReplication() {
        MemoryStorage storage = newTestMemoryStorage(1, 2);
        Raft sm = newTestRaft(1, 10, 1, storage);
        sm.restore(testingSnap());

        sm.becomeCandidate();
        sm.becomeLeader();

        sm.getTracker().getProgress().get(2).becomeSnapshot(11);

        sm.step(new Message().setFrom(1).setTo(1).setType(MessageType.MSG_PROP)
                .setEntries(Collections.singletonList(
                        new Entry(0, 0, EntryType.NORMAL, "somedata".getBytes(StandardCharsets.UTF_8)))));

        List<Message> msgs = readMessages(sm);
        assertTrue(msgs.isEmpty(), "expect no message, but got " + msgs);
    }

    /**
     * Go: TestSnapshotFailure
     */
    @Test
    void testSnapshotFailure() {
        MemoryStorage storage = newTestMemoryStorage(1, 2);
        Raft sm = newTestRaft(1, 10, 1, storage);
        sm.restore(testingSnap());

        sm.becomeCandidate();
        sm.becomeLeader();

        Progress pr2 = sm.getTracker().getProgress().get(2);
        pr2.setNext(1);
        pr2.becomeSnapshot(11);

        sm.step(new Message().setFrom(2).setTo(1).setType(MessageType.MSG_SNAP_STATUS).setReject(true));

        assertEquals(0, pr2.getPendingSnapshot());
        assertEquals(1, pr2.getNext());
        assertTrue(pr2.isMsgAppFlowPaused());
    }

    /**
     * Go: TestSnapshotSucceed
     */
    @Test
    void testSnapshotSucceed() {
        MemoryStorage storage = newTestMemoryStorage(1, 2);
        Raft sm = newTestRaft(1, 10, 1, storage);
        sm.restore(testingSnap());

        sm.becomeCandidate();
        sm.becomeLeader();

        Progress pr2 = sm.getTracker().getProgress().get(2);
        pr2.setNext(1);
        pr2.becomeSnapshot(11);

        sm.step(new Message().setFrom(2).setTo(1).setType(MessageType.MSG_SNAP_STATUS).setReject(false));

        assertEquals(0, pr2.getPendingSnapshot());
        assertEquals(12, pr2.getNext());
        assertTrue(pr2.isMsgAppFlowPaused());
    }

    /**
     * Go: TestSnapshotAbort
     */
    @Test
    void testSnapshotAbort() {
        MemoryStorage storage = newTestMemoryStorage(1, 2);
        Raft sm = newTestRaft(1, 10, 1, storage);
        sm.restore(testingSnap());

        sm.becomeCandidate();
        sm.becomeLeader();

        Progress pr2 = sm.getTracker().getProgress().get(2);
        pr2.setNext(1);
        pr2.becomeSnapshot(11);

        // 一条 index 不小于待发快照的成功确认会打断待发快照
        sm.step(new Message().setFrom(2).setTo(1).setType(MessageType.MSG_APP_RESP).setIndex(11));

        assertEquals(0, pr2.getPendingSnapshot());
        // follower 进入复制状态，leader 发出追加并乐观地推进了进度
        // （所以这里是 13 而不是 12）。之所以还有东西可追加，是因为
        // leader 当选时在 index 12 上补了一条空日志。
        assertEquals(13, pr2.getNext());
        assertEquals(1, pr2.getInflights().count());
    }
}
