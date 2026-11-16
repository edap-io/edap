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
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.edap.raft;

import io.edap.raft.protocol.Message;
import io.edap.raft.protocol.MessageType;
import io.edap.raft.storage.MemoryStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「提交即通知」（{@code edap.raft.notifyOnCommit=true}）的行为。
 *
 * <p>开关打开后，异步提案的完成点从「下一次 Ready 组装」提前到
 * 「commit 指针推进」那一刻。这里测的是这条新路径上两个容易出错的分支：</p>
 *
 * <ol>
 *   <li><b>提案被新 leader 提交</b>：旧 leader 追加了提案、复制给了多数派，
 *       却在收到 ACK 之前失去了领导权。新 leader 提交了这条提案，
 *       旧 leader 也要把「提交成功」通知给客户端 ——
 *       否则客户端拿到的是超时（假失败），而提案其实成功了。</li>
 *   <li><b>提案被新 leader 覆盖</b>：旧 leader 的提案没复制出去就失去了领导权，
 *       新 leader 在同一个索引上写了自己的条目。这条提案已经丢了，
 *       客户端<b>不能</b>拿到「提交成功」—— 那是丢数据。</li>
 * </ol>
 *
 * <p>两个分支都要靠「读一格日志的任期」来分辨：
 * 索引 i 上的条目任期还是当初那个 ⇒ 那条就是本提案；
 * 任期变了 ⇒ 索引被复用了，提案早没了。</p>
 */
@Timeout(180)
public class NodeNotifyOnCommitTest {

    /**
     * 一个消息投递受控的小集群。
     *
     * <p>与 {@link NodeAsyncProposalTest} 的差别：消息不自动投递给对端，
     * 而是先进 outbox，由投递线程按当前的 {@link #dropper} 规则决定投不投 ——
     * leader 切换的时序必须能精确控制，否则测不出「提交发生在领导权变化之后」。</p>
     */
    private static final class ManualCluster implements AutoCloseable {

        static final class Member {
            final long          id;
            final Node          node;
            final MemoryStorage storage;

            Member(long id, Node node, MemoryStorage storage) {
                this.id = id;
                this.node = node;
                this.storage = storage;
            }
        }

        final Map<Long, Member> members = new LinkedHashMap<>();
        final AtomicBoolean    stop = new AtomicBoolean();
        final List<Message>    outbox = Collections.synchronizedList(new ArrayList<>());

        /** 丢弃规则：返回 true 的消息不投出去 */
        volatile Predicate<Message> dropper = msg -> false;

        ManualCluster(int size) throws Exception {
            Peer[] peers = new Peer[size];
            for (int i = 0; i < size; i++) {
                peers[i] = new Peer(i + 1, "127.0.0.1", 19100 + i);
            }
            for (int i = 0; i < size; i++) {
                MemoryStorage storage = new MemoryStorage();
                RaftConfig cfg = RaftTestSupport.newTestConfig(i + 1, 5, 1, storage);
                Node node = Node.startNode(cfg, peers);
                members.put((long) (i + 1), new Member(i + 1, node, storage));
            }
            for (Member m : members.values()) {
                Thread t = new Thread(() -> runApp(m), "notify-app-" + m.id);
                t.setDaemon(true);
                t.start();
            }
            Thread ticker = new Thread(() -> {
                while (!stop.get()) {
                    for (Member m : members.values()) {
                        m.node.tick();
                    }
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "notify-ticker");
            ticker.setDaemon(true);
            ticker.start();
            // 投递线程：消息出了 outbox 就投出去，投不投由 dropper 决定。
            // 测试随时可以换 dropper 来改投递规则。
            Thread pump = new Thread(() -> {
                while (!stop.get()) {
                    pump();
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "notify-pump");
            pump.setDaemon(true);
            pump.start();
        }

        /**
         * 应用线程：取 Ready → 复制类消息进 outbox → 落盘 →
         * 确认类消息进 outbox → advance。
         *
         * <p>消息的投递由投递线程负责，但「确认类消息必须晚于落盘」这个顺序
         * 在这里保证了 —— 确认类消息是落盘之后才进 outbox 的。</p>
         */
        private void runApp(Member m) {
            while (!stop.get()) {
                Ready rd;
                try {
                    rd = m.node.ready(20, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (rd == null) {
                    continue;
                }
                try {
                    for (Message msg : rd.getMessages()) {
                        if (!Raft.isPersistGated(msg.getType())) {
                            outbox.add(msg);
                        }
                    }
                    if (!rd.getEntries().isEmpty() || rd.getHardState() != null) {
                        m.storage.save(rd.getHardState(), rd.getEntries());
                    }
                    for (Message msg : rd.getMessages()) {
                        if (Raft.isPersistGated(msg.getType())) {
                            outbox.add(msg);
                        }
                    }
                    m.node.advance();
                } catch (RaftException e) {
                    if (stop.get()) {
                        return;
                    }
                }
            }
        }

        /** 把 outbox 里的消息投出去 */
        void pump() {
            List<Message> batch;
            synchronized (outbox) {
                batch = new ArrayList<>(outbox);
                outbox.clear();
            }
            for (Message msg : batch) {
                if (dropper.test(msg)) {
                    continue;
                }
                Member t = members.get(msg.getTo());
                if (t != null) {
                    t.node.onMessage(msg);
                }
            }
        }

        /** 等集群选出主 */
        long waitForLeader() throws InterruptedException {
            long deadline = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < deadline) {
                long lead = currentLeader();
                if (lead != Raft.NONE) {
                    return lead;
                }
                Thread.sleep(10);
            }
            return Raft.NONE;
        }

        /** 现在谁是 leader */
        long currentLeader() {
            for (Member m : members.values()) {
                try {
                    long lead = m.node.status().getSoftState().getLead();
                    if (lead != Raft.NONE) {
                        return lead;
                    }
                } catch (RuntimeException e) {
                    // 节点停止时会抛，忽略
                }
            }
            return Raft.NONE;
        }

        /** 某个节点的日志最后一条索引 */
        long lastIndex(long id) {
            return members.get(id).node.getRawNode().getRaft().getRaftLog().lastIndex();
        }

        /**
         * 等其它节点把 leader 的日志复制齐。
         *
         * <p>用来确认「提案已经复制给多数派」—— 这是第一个测试的前提：
         * 提案确实在多数派上，只是旧 leader 自己还不知道。</p>
         *
         * <p>判定必须取一致的快照：先读 leader 的 lastIndex，再读其它节点的，
         * 最后再读一次 leader 的 —— 三次读之间 leader 不能追加。
         * 否则会读到「提案追加之前」的 lastIndex，而那个值（当选时补的空条目）
         * 其它节点早就有了，于是「已经复制」这个前提被一条根本没复制的提案
         * 蒙混过关 —— 这个用例就是这么 flaky 的。</p>
         */
        boolean waitReplicated(long lead, long timeoutMillis) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                long leadLast = lastIndex(lead);
                boolean caught = true;
                for (Member m : members.values()) {
                    if (m.id == lead) {
                        continue;
                    }
                    if (lastIndex(m.id) < leadLast) {
                        caught = false;
                        break;
                    }
                }
                if (caught && lastIndex(lead) == leadLast) {
                    return true;
                }
                Thread.sleep(5);
            }
            return false;
        }

        /**
         * 等提案进了 leader 的日志。
         *
         * @param preLast 提案之前日志的最后一条索引
         */
        boolean waitAppended(long lead, long preLast, long timeoutMillis) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                if (lastIndex(lead) > preLast) {
                    return true;
                }
                Thread.sleep(5);
            }
            return false;
        }

        /** 等 leader 换人 */
        long waitNewLeader(long old) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < deadline) {
                long l = currentLeader();
                if (l != Raft.NONE && l != old) {
                    return l;
                }
                Thread.sleep(10);
            }
            return Raft.NONE;
        }

        @Override
        public void close() {
            stop.set(true);
            for (Member m : members.values()) {
                try {
                    m.node.stop();
                } catch (RuntimeException e) {
                    // 忽略
                }
            }
        }
    }

    /**
     * 提案被新 leader 提交：旧 leader 也要通知客户端「提交成功」。
     *
     * <p>时序：</p>
     * <ol>
     *   <li>旧 leader A 当选，把提案 P 复制给 B、C；</li>
     *   <li>所有发给 A 的消息都被丢掉 ⇒ A 拿不到 ACK ⇒ A 不知道 P 被提交了
     *       （P 其实已经在多数派上了）；</li>
     *   <li>A 停发心跳 ⇒ B 超时发起选举 ⇒ A 失去领导权；</li>
     *   <li>新 leader 提交 P（P 在它的日志里），并把 commit 指针告诉 A；</li>
     *   <li>A 的 commit 指针推过 P 的索引 ⇒ A 通知客户端「提交成功」。</li>
     * </ol>
     */
    @Test
    public void proposalCommittedByNewLeaderStillCompletes() throws Exception {
        System.setProperty("edap.raft.notifyOnCommit", "true");
        try (ManualCluster c = new ManualCluster(3)) {
            long lead = c.waitForLeader();
            assertNotEquals(Raft.NONE, lead, "cluster should have a leader");
            Node oldLeader = c.members.get(lead).node;

            // 挡住所有发给旧 leader 的消息：它拿不到 ACK，也就不会提交提案
            c.dropper = msg -> msg.getTo() == lead;
            long preLast = c.lastIndex(lead);
            CompletableFuture<Void> f = oldLeader.proposeAsync(new byte[]{1, 2, 3}, 60000);
            assertTrue(c.waitAppended(lead, preLast, 5000),
                    "the proposal should have been appended to the old leader's log");
            assertTrue(c.waitReplicated(lead, 5000),
                    "the proposal should have been replicated to the other nodes");
            assertFalse(f.isDone(),
                    "the old leader never saw the acks, so it must not have completed the proposal");

            // 旧 leader 停发心跳：其它节点超时发起选举，旧 leader 失去领导权
            c.dropper = msg -> msg.getFrom() == lead;
            long newLead = c.waitNewLeader(lead);
            assertNotEquals(Raft.NONE, newLead, "the old leader should have lost the leadership");

            // 恢复投递：新 leader 会提交 P 并把 commit 指针告诉旧 leader
            c.dropper = msg -> false;
            f.get(20, TimeUnit.SECONDS);
            assertTrue(f.isDone(), "the proposal was committed, the client must be notified");
        } finally {
            System.clearProperty("edap.raft.notifyOnCommit");
        }
    }

    /**
     * 提案被新 leader 覆盖：客户端不能拿到「提交成功」。
     *
     * <p>旧 leader A 只把提案留在自己的日志里（一条复制消息都没发出去）就失去了领导权，
     * 新 leader 在同一个索引上写了自己的条目 ⇒ 提案已经没了。
     * 这时通知客户端「提交成功」就是丢数据。</p>
     */
    @Test
    public void overwrittenProposalDoesNotCompleteSuccessfully() throws Exception {
        System.setProperty("edap.raft.notifyOnCommit", "true");
        try (ManualCluster c = new ManualCluster(3)) {
            long lead = c.waitForLeader();
            assertNotEquals(Raft.NONE, lead, "cluster should have a leader");
            Node oldLeader = c.members.get(lead).node;

            // 旧 leader 什么复制消息都不发：提案只留在它自己的日志里
            c.dropper = msg -> msg.getFrom() == lead && msg.getType() == MessageType.MSG_APP;
            long preLast = c.lastIndex(lead);
            CompletableFuture<Void> f = oldLeader.proposeAsync(new byte[]{1, 2, 3}, 2000);
            // 等提案确实进了旧 leader 的日志（但没复制出去）
            assertTrue(c.waitAppended(lead, preLast, 5000),
                    "the proposal should have been appended to the old leader's log");

            // 心跳也不发：其它节点超时发起选举，旧 leader 失去领导权，
            // 新 leader 在同一个索引上写了自己的条目
            c.dropper = msg -> msg.getFrom() == lead;
            long newLead = c.waitNewLeader(lead);
            assertNotEquals(Raft.NONE, newLead, "the old leader should have lost the leadership");

            // 提案已经丢了：客户端只能拿到失败（超时），不能拿到「提交成功」
            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> f.get(10, TimeUnit.SECONDS),
                    "an overwritten proposal must not complete successfully");
            assertTrue(e.getCause() instanceof TimeoutException,
                    "the proposal should end as a timeout, got: " + e.getCause());
        } finally {
            System.clearProperty("edap.raft.notifyOnCommit");
        }
    }

    /**
     * 常规路径：leader 不变时，提案在提交点被通知。
     */
    @Test
    public void proposalCompletesAtCommitPoint() throws Exception {
        System.setProperty("edap.raft.notifyOnCommit", "true");
        try (ManualCluster c = new ManualCluster(3)) {
            long lead = c.waitForLeader();
            assertNotEquals(Raft.NONE, lead, "cluster should have a leader");
            Node leader = c.members.get(lead).node;

            CompletableFuture<Void> f = leader.proposeAsync(new byte[]{1, 2, 3}, 20000);
            f.get(20, TimeUnit.SECONDS);
            assertTrue(f.isDone());
        } finally {
            System.clearProperty("edap.raft.notifyOnCommit");
        }
    }
}
