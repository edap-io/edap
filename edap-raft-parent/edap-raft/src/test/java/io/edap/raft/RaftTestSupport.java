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
import io.edap.raft.protocol.ConfState;
import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.Message;
import io.edap.raft.storage.MemoryStorage;
import io.edap.raft.storage.Storage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * etcd {@code raft_test.go} 里的公共测试脚手架。
 *
 * <p>Go 里这些辅助函数直接写在 {@code raft_test.go} 里，Java 没有办法把
 * 「测试专用的方法」挂到 {@link Raft} 上（会污染主源码的可见性），
 * 因此统一收敛到本类里，方法名与 Go 保持一致，便于逐条对照。</p>
 */
public final class RaftTestSupport {

    private RaftTestSupport() {
    }

    /**
     * Go: newTestConfig
     */
    public static RaftConfig newTestConfig(long id, int election, int heartbeat, Storage storage) {
        RaftConfig cfg = new RaftConfig();
        cfg.id = id;
        cfg.electionTick = election;
        cfg.heartbeatTick = heartbeat;
        cfg.storage = storage;
        cfg.maxSizePerMsg = RaftConfig.NO_LIMIT;
        cfg.maxInflightMsgs = 256;
        return cfg;
    }

    /**
     * Go: newTestMemoryStorage(withPeers(peers...))
     */
    public static MemoryStorage newTestMemoryStorage(long... peers) {
        List<Long> voters = new ArrayList<>(peers.length);
        for (long p : peers) {
            voters.add(p);
        }
        return new MemoryStorage(new ConfState(voters, Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), false));
    }

    /**
     * Go: newTestMemoryStorage(withPeers(...), withLearners(...))
     */
    public static MemoryStorage newTestMemoryStorageWithLearners(long[] voters, long[] learners) {
        List<Long> voterList = new ArrayList<>(voters.length);
        for (long p : voters) {
            voterList.add(p);
        }
        List<Long> learnerList = new ArrayList<>(learners.length);
        for (long p : learners) {
            learnerList.add(p);
        }
        return new MemoryStorage(new ConfState(voterList, learnerList,
                Collections.emptyList(), Collections.emptyList(), false));
    }

    /**
     * Go: newTestRaft
     */
    public static Raft newTestRaft(long id, int election, int heartbeat, Storage storage) {
        return new Raft(newTestConfig(id, election, heartbeat, storage));
    }

    /**
     * Go: newTestLearnerRaft
     *
     * <p>与 {@link #newTestRaft} 一样，learner 的身份来自存储里的成员配置。</p>
     */
    public static Raft newTestLearnerRaft(long id, int election, int heartbeat, Storage storage) {
        return new Raft(newTestConfig(id, election, heartbeat, storage));
    }

    /**
     * Go: newTestRawNode
     */
    public static RawNode newTestRawNode(long id, int election, int heartbeat, Storage storage) {
        return new RawNode(newTestConfig(id, election, heartbeat, storage));
    }

    /**
     * Go: mustAppendEntry
     */
    public static void mustAppendEntry(Raft r, Entry... ents) {
        if (!r.appendEntry(Arrays.asList(ents))) {
            throw new RaftException("entry unexpectedly dropped");
        }
    }

    /**
     * Go: nextEnts
     *
     * <p>把未落盘的条目写进存储，跑一遍「落盘之后」的动作，返回已提交的条目。</p>
     */
    public static List<Entry> nextEnts(Raft r, Storage s) {
        RaftLog log = r.getRaftLog();
        // 把未落盘的条目追加到存储
        if (s instanceof MemoryStorage) {
            ((MemoryStorage) s).append(log.nextUnstableEnts());
        }
        log.stableTo(log.lastIndex(), log.lastTerm());
        // 跑落盘之后的消息处理
        advanceMessagesAfterAppend(r);
        // 返回已提交的条目
        List<Entry> ents = log.nextCommittedEnts(true);
        log.appliedTo(log.committed(), 0);
        return ents;
    }

    /**
     * Go: (*raft).readMessages
     */
    public static List<Message> readMessages(Raft r) {
        advanceMessagesAfterAppend(r);
        List<Message> msgs = r.getMessages();
        List<Message> result = new ArrayList<>(msgs);
        r.clearMessages();
        return result;
    }

    /**
     * Go: (*raft).advanceMessagesAfterAppend
     *
     * <p>etcd 里这是测试专用的方法：未落盘状态持久化之后，把「等落盘之后再发」
     * 的消息取出来，发给自己的直接 {@code step}，发给别人的放进普通消息队列。</p>
     */
    public static void advanceMessagesAfterAppend(Raft r) {
        while (true) {
            List<Message> msgs = takeMessagesAfterAppend(r);
            if (msgs.isEmpty()) {
                break;
            }
            stepOrSend(r, msgs);
        }
    }

    /**
     * Go: (*raft).takeMessagesAfterAppend
     */
    public static List<Message> takeMessagesAfterAppend(Raft r) {
        List<Message> msgs = new ArrayList<>(r.getMessagesAfterAppend());
        r.clearMessagesAfterAppend();
        return msgs;
    }

    /**
     * Go: (*raft).stepOrSend
     */
    public static void stepOrSend(Raft r, List<Message> msgs) {
        for (Message m : msgs) {
            if (m.getTo() == r.getId()) {
                r.step(m);
            } else {
                r.getMessages().add(m);
            }
        }
    }

    /**
     * Go: entsWithConfig
     *
     * <p>造一个带若干条日志的状态机，节点 id 固定是 1，任期取最后一条日志的任期。</p>
     */
    public static Raft entsWithConfig(RaftNetwork.ConfigFunc configFunc, long... terms) {
        MemoryStorage storage = new MemoryStorage();
        for (int i = 0; i < terms.length; i++) {
            storage.append(Collections.singletonList(
                    new Entry(i + 1, terms[i], io.edap.raft.protocol.EntryType.NORMAL, null)));
        }
        RaftConfig cfg = newTestConfig(1, 5, 1, storage);
        if (configFunc != null) {
            configFunc.apply(cfg);
        }
        Raft sm = new Raft(cfg);
        sm.reset(terms[terms.length - 1]);
        return sm;
    }

    /**
     * Go: votedWithConfig
     *
     * <p>造一个投过票但没有日志的状态机：任期与投票都来自给定的硬状态。</p>
     */
    public static Raft votedWithConfig(RaftNetwork.ConfigFunc configFunc, long vote, long term) {
        MemoryStorage storage = new MemoryStorage();
        storage.setHardState(new io.edap.raft.protocol.HardState(term, vote, 0));
        RaftConfig cfg = newTestConfig(1, 5, 1, storage);
        if (configFunc != null) {
            configFunc.apply(cfg);
        }
        Raft sm = new Raft(cfg);
        sm.reset(term);
        return sm;
    }

    /**
     * Go: expectOneMessage
     */
    public static Message expectOneMessage(Raft r) {
        List<Message> msgs = readMessages(r);
        assertEquals(1, msgs.size(), "expect one message");
        return msgs.get(0);
    }

    /**
     * Go: requireEqualConfState（etcd 用 protobuf 的 Equal 比较）
     */
    public static void assertConfStateEquals(ConfState expected, ConfState actual) {
        if (expected == null) {
            assertNull(actual, "expected null ConfState");
            return;
        }
        assertNotNull(actual, "expected ConfState " + expected + " but got null");
        assertEquals(expected.getVoters(), actual.getVoters(), "voters");
        assertEquals(expected.getLearners(), actual.getLearners(), "learners");
        assertEquals(expected.getVotersOutgoing(), actual.getVotersOutgoing(), "votersOutgoing");
        assertEquals(expected.getLearnersNext(), actual.getLearnersNext(), "learnersNext");
        assertEquals(expected.isAutoLeave(), actual.isAutoLeave(), "autoLeave");
    }

    /**
     * Go: requireEqualEntries
     */
    public static void assertEqualEntries(List<Entry> expected, List<Entry> actual) {
        assertNotNull(actual, "entries must not be null");
        assertEquals(expected.size(), actual.size(), "entries length");
        for (int i = 0; i < expected.size(); i++) {
            Entry e = expected.get(i);
            Entry a = actual.get(i);
            assertEquals(e.getIndex(), a.getIndex(), "entry[" + i + "].index");
            assertEquals(e.getTerm(), a.getTerm(), "entry[" + i + "].term");
            assertEquals(e.getType(), a.getType(), "entry[" + i + "].type");
            assertArrayEquals(e.getData(), a.getData(), "entry[" + i + "].data");
        }
    }

    /**
     * Go: ltoa —— 把日志渲染成一段可比较的文本。
     */
    public static String logToString(RaftLog l) {
        StringBuilder buf = new StringBuilder();
        buf.append("lastIndex: ").append(l.lastIndex()).append('\n');
        buf.append("applied:  ").append(l.applied()).append('\n');
        buf.append("applying:  ").append(l.applying()).append('\n');
        List<Entry> ents = l.allEntries();
        for (int i = 0; i < ents.size(); i++) {
            Entry e = ents.get(i);
            buf.append('#').append(i).append(": ")
                    .append("index=").append(e.getIndex())
                    .append(" term=").append(e.getTerm())
                    .append(" type=").append(e.getType())
                    .append(" data=").append(Arrays.toString(e.getData()))
                    .append('\n');
        }
        return buf.toString();
    }

    /**
     * Go: diffu —— 比较两段日志的渲染结果，返回空串表示一致。
     */
    public static String diffLogs(RaftLog expected, RaftLog actual) {
        String a = logToString(expected);
        String b = logToString(actual);
        if (a.equals(b)) {
            return "";
        }
        return "expected:\n" + a + "actual:\n" + b;
    }

    /**
     * Go: {@code newLog(&MemoryStorage{ents: ...}, nil)}
     *
     * <p>用给定的条目造一个「期望的日志」，用来和真实节点的日志做比较。
     * 条目的 index 必须从 1 开始连续，index 0 的占位条目由
     * {@link MemoryStorage} 自己带上。</p>
     */
    public static RaftLog newLog(Entry... ents) {
        MemoryStorage storage = new MemoryStorage();
        storage.append(new ArrayList<>(Arrays.asList(ents)));
        return new RaftLog(storage);
    }
}
