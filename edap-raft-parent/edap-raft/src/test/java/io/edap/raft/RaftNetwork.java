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

import io.edap.raft.protocol.Message;
import io.edap.raft.protocol.MessageType;
import io.edap.raft.storage.MemoryStorage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * etcd {@code raft_test.go} 里的 {@code network} 测试脚手架。
 *
 * <p>etcd 的 network 是一组「状态机」（{@code *raft} 或 {@code *blackHole}），
 * 消息在节点之间转发，可以按比例丢包、切断、隔离、忽略某类消息。
 * 论文测试、节点交互测试都靠它搭场景。</p>
 *
 * <p>节点编号固定为 {@code 1..n}（Go: {@code idsBySize}）。</p>
 */
public final class RaftNetwork {

    /**
     * Go: stateMachine
     */
    public interface StateMachine {

        /**
         * Go: Step
         */
        boolean step(Message m);

        /**
         * Go: advanceMessagesAfterAppend
         */
        void advanceMessagesAfterAppend();

        /**
         * Go: readMessages
         */
        List<Message> readMessages();
    }

    /**
     * Go: blackHole
     *
     * <p>吞掉所有消息的空节点。</p>
     */
    public static final class BlackHole implements StateMachine {

        @Override
        public boolean step(Message m) {
            return true;
        }

        @Override
        public void advanceMessagesAfterAppend() {
        }

        @Override
        public List<Message> readMessages() {
            return Collections.emptyList();
        }
    }

    /**
     * Go: msgHook
     *
     * <p>每条消息发出前都会过一遍钩子，返回 false 就把消息丢掉。</p>
     */
    public interface MessageHook {
        boolean apply(Message m);
    }

    /**
     * Go: newNetworkWithConfig 的 configFunc
     */
    public interface ConfigFunc {
        void apply(RaftConfig cfg);
    }

    /**
     * Go: connem
     */
    private static final class ConnEm {

        private final long from;
        private final long to;

        ConnEm(long from, long to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof ConnEm)) {
                return false;
            }
            ConnEm other = (ConnEm) o;
            return from == other.from && to == other.to;
        }

        @Override
        public int hashCode() {
            return (int) (from * 31 + to);
        }
    }

    /** Go: peers */
    private final Map<Long, StateMachine> peers = new HashMap<>();

    /** Go: storage */
    private final Map<Long, MemoryStorage> storage = new HashMap<>();

    /** Go: dropm */
    private final Map<ConnEm, Double> dropm = new HashMap<>();

    /** Go: ignorem */
    private final Set<MessageType> ignorem = new HashSet<>();

    /** Go: msgHook */
    public MessageHook msgHook;

    private RaftNetwork() {
    }

    /**
     * Go: newNetwork
     *
     * @param peers 每个位置可以是 {@code null}（新建节点）、{@link Raft}（现成的节点）
     *              或 {@link BlackHole}
     */
    public static RaftNetwork newNetwork(Object... peers) {
        return newNetworkWithConfig(null, peers);
    }

    /**
     * Go: newNetworkWithConfig
     */
    public static RaftNetwork newNetworkWithConfig(ConfigFunc configFunc, Object... peers) {
        RaftNetwork nw = new RaftNetwork();
        int size = peers.length;
        long[] peerAddrs = idsBySize(size);

        for (int j = 0; j < size; j++) {
            long id = peerAddrs[j];
            Object p = peers[j];
            if (p == null) {
                MemoryStorage s = RaftTestSupport.newTestMemoryStorage(peerAddrs);
                RaftConfig cfg = RaftTestSupport.newTestConfig(id, 10, 1, s);
                if (configFunc != null) {
                    configFunc.apply(cfg);
                }
                nw.storage.put(id, s);
                nw.peers.put(id, RaftNetwork.of(new Raft(cfg)));
            } else if (p instanceof Raft) {
                Raft v = (Raft) p;
                // Go 里 network 会把已存在的 raft 的 tracker 整个换掉：
                // 网络里的节点按 voter / learner 重新登记，复制进度清零
                Set<Long> learners = new LinkedHashSet<>();
                for (Long l : v.getTracker().getConfig().getLearners()) {
                    learners.add(l);
                }
                // Go 里这里还会把状态机的 id 改成它在网络里的位置
                v.setIdForTest(id);
                v.resetTrackerForTest(peerAddrs, learners);
                nw.peers.put(id, RaftNetwork.of(v));
            } else if (p instanceof BlackHole) {
                nw.peers.put(id, (BlackHole) p);
            } else {
                throw new RaftException("unexpected state machine type: " + p.getClass().getName());
            }
        }
        return nw;
    }

    /**
     * 把一个 {@link Raft} 包装成 network 里的状态机。
     */
    public static StateMachine of(Raft r) {
        return new RaftStateMachine(r);
    }

    /**
     * Go: network 里装着 {@code *raft} 的那个状态机。
     */
    public static final class RaftStateMachine implements StateMachine {

        /** 被包装的 raft 状态机 */
        private final Raft raft;

        RaftStateMachine(Raft raft) {
            this.raft = raft;
        }

        /**
         * 被包装的 raft 状态机。
         */
        public Raft getRaft() {
            return raft;
        }

        @Override
        public boolean step(Message m) {
            return raft.step(m);
        }

        @Override
        public void advanceMessagesAfterAppend() {
            RaftTestSupport.advanceMessagesAfterAppend(raft);
        }

        @Override
        public List<Message> readMessages() {
            return RaftTestSupport.readMessages(raft);
        }
    }

    /**
     * Go: idsBySize
     */
    public static long[] idsBySize(int size) {
        long[] ids = new long[size];
        for (int i = 0; i < size; i++) {
            ids[i] = i + 1;
        }
        return ids;
    }

    /**
     * Go: preVoteConfig
     */
    public static void preVoteConfig(RaftConfig c) {
        c.preVote = true;
    }

    /**
     * 取某个节点的存储，{@code null} 表示这个节点不是本 network 新建的
     */
    public MemoryStorage getStorage(long id) {
        return storage.get(id);
    }

    /**
     * 取某个节点
     */
    public StateMachine getPeer(long id) {
        return peers.get(id);
    }

    /**
     * 取某个节点对应的 raft 状态机，节点不是 raft 时返回 null。
     */
    public Raft getRaft(long id) {
        StateMachine p = peers.get(id);
        return p instanceof RaftStateMachine ? ((RaftStateMachine) p).getRaft() : null;
    }

    /**
     * 网络里全部节点的 id，按升序。
     */
    public List<Long> peerIds() {
        List<Long> ids = new ArrayList<>(peers.keySet());
        Collections.sort(ids);
        return ids;
    }

    /**
     * Go: (*network).send
     */
    public void send(Message... msgs) {
        List<Message> pending = new ArrayList<>(Arrays.asList(msgs));
        while (!pending.isEmpty()) {
            Message m = pending.remove(0);
            StateMachine p = peers.get(m.getTo());
            if (p == null) {
                throw new RaftException("no peer for message to " + m.getTo());
            }
            p.step(m);
            p.advanceMessagesAfterAppend();
            pending.addAll(filter(p.readMessages()));
        }
    }

    /**
     * Go: (*network).drop
     *
     * @param perc 丢包比例，大于等于 1 表示全丢
     */
    public void drop(long from, long to, double perc) {
        dropm.put(new ConnEm(from, to), perc);
    }

    /**
     * Go: (*network).cut
     */
    public void cut(long one, long other) {
        drop(one, other, 2.0);
        drop(other, one, 2.0);
    }

    /**
     * Go: (*network).isolate
     */
    public void isolate(long id) {
        for (int i = 0; i < peers.size(); i++) {
            long nid = i + 1;
            if (nid != id) {
                drop(id, nid, 1.0);
                drop(nid, id, 1.0);
            }
        }
    }

    /**
     * Go: (*network).ignore
     */
    public void ignore(MessageType t) {
        ignorem.add(t);
    }

    /**
     * Go: (*network).recover
     */
    public void recover() {
        dropm.clear();
        ignorem.clear();
    }

    /**
     * Go: (*network).filter
     */
    public List<Message> filter(List<Message> msgs) {
        List<Message> mm = new ArrayList<>();
        for (Message m : msgs) {
            if (ignorem.contains(m.getType())) {
                continue;
            }
            if (m.getType() == MessageType.MSG_HUP) {
                // hup 消息不会走网络，出现就说明逻辑有问题
                throw new RaftException("unexpected msgHup");
            }
            Double perc = dropm.get(new ConnEm(m.getFrom(), m.getTo()));
            if (perc != null && ThreadLocalRandom.current().nextDouble() < perc) {
                continue;
            }
            if (msgHook != null && !msgHook.apply(m)) {
                continue;
            }
            mm.add(m);
        }
        return mm;
    }

    /**
     * Go: (*network).filter 里的 msgsAfterAppend 处理：
     * 取某个节点当前排队的消息（含等待落盘的消息）
     */
    public static List<Message> pending(Raft r) {
        List<Message> msgs = new ArrayList<>(r.getMessages());
        msgs.addAll(r.getMessagesAfterAppend());
        return msgs;
    }
}
