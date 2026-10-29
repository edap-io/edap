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
import io.edap.raft.storage.MemoryStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异步提案 API 的行为。
 *
 * <p>异步提案靠条目里带的请求 id 与「已提交条目」对上：提案被提交时
 * future 正常完成，被丢弃 / 停止 / 超时时异常完成。
 * 请求 id 随条目一起复制到各个节点，所以客户端向 follower 提案时
 * （follower 转发给 leader）future 依然能在 follower 上正常完成 ——
 * 这是「index → future」方案做不到的，见 {@link io.edap.raft.protocol.Entry}。</p>
 */
@Timeout(180)
public class NodeAsyncProposalTest {

    /**
     * 一个跑起来的集群。
     *
     * <p>每个节点一个应用线程，流程与 etcd 的 {@code raftNode.start()} 一致：
     * 取 Ready → 落盘 → 发消息 → 应用已提交条目 → advance。</p>
     */
    private static final class Cluster implements AutoCloseable {

        static final class Member {
            final long         id;
            final Node         node;
            final MemoryStorage storage;

            Member(long id, Node node, MemoryStorage storage) {
                this.id = id;
                this.node = node;
                this.storage = storage;
            }
        }

        final Map<Long, Member> members = new LinkedHashMap<>();
        final AtomicBoolean    stop = new AtomicBoolean();
        final AtomicLong       leaderId = new AtomicLong(Raft.NONE);

        /**
         * @param size 节点数
         * @param disableForwarding 逐节点：是否禁止转发提案（用来触发「提案被丢弃」）
         * @param electionTicks 逐节点的选举超时（tick 数），用来保证某个节点一直当 follower
         */
        Cluster(int size, boolean[] disableForwarding, int[] electionTicks) throws Exception {
            Peer[] peers = new Peer[size];
            for (int i = 0; i < size; i++) {
                peers[i] = new Peer(i + 1, "127.0.0.1", 19000 + i);
            }
            for (int i = 0; i < size; i++) {
                MemoryStorage storage = new MemoryStorage();
                RaftConfig cfg = RaftTestSupport.newTestConfig(i + 1, electionTicks[i], 1, storage);
                cfg.disableProposalForwarding = disableForwarding != null && disableForwarding[i];
                Node node = Node.startNode(cfg, peers);
                members.put((long) (i + 1), new Member(i + 1, node, storage));
            }
            for (Member m : members.values()) {
                Thread t = new Thread(() -> runApp(m), "raft-app-" + m.id);
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
            }, "raft-ticker");
            ticker.setDaemon(true);
            ticker.start();
        }

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
                    // 先发复制类消息，再落盘，最后发确认类消息
                    // —— 确认类消息的意思是「我已经持久化了这些日志」
                    sendMessages(m, rd, false);
                    if (!rd.getEntries().isEmpty() || rd.getHardState() != null) {
                        m.storage.save(rd.getHardState(), rd.getEntries());
                    }
                    sendMessages(m, rd, true);
                    m.node.advance();
                } catch (RaftException e) {
                    if (stop.get()) {
                        return;
                    }
                }
            }
        }

        private void sendMessages(Member m, Ready rd, boolean gated) {
            for (Message msg : rd.getMessages()) {
                if (Raft.isPersistGated(msg.getType()) != gated) {
                    continue;
                }
                Member target = members.get(msg.getTo());
                if (target != null) {
                    target.node.onMessage(msg);
                }
            }
        }

        /**
         * 等集群选出主。
         *
         * @return leader 的节点 id
         */
        long waitForLeader() throws Exception {
            long deadline = System.currentTimeMillis() + 20000;
            while (leaderId.get() == Raft.NONE && System.currentTimeMillis() < deadline) {
                for (Member m : members.values()) {
                    try {
                        long lead = m.node.status().getSoftState().getLead();
                        if (lead != Raft.NONE) {
                            leaderId.set(lead);
                            break;
                        }
                    } catch (RuntimeException e) {
                        // 节点停止时会抛，忽略
                    }
                }
                Thread.sleep(20);
            }
            return leaderId.get();
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
     * 正常提交：提案被提交之后 future 正常完成。
     */
    @Test
    public void asyncProposalCompletesWhenCommitted() throws Exception {
        try (Cluster c = new Cluster(3, null, new int[]{10, 10, 10})) {
            long lead = c.waitForLeader();
            assertNotEquals(Raft.NONE, lead, "cluster should have a leader");
            Node leader = c.members.get(lead).node;

            CompletableFuture<Void> f = leader.proposeAsync(new byte[]{1, 2, 3}, 20000);
            f.get(20, TimeUnit.SECONDS);

            assertEquals(0, leader.getPendingProposalCount(),
                    "the pending table should be empty once the proposal is committed");
        }
    }

    /**
     * 提案被丢弃：future 异常完成，文案与同步路径一致。
     *
     * <p>第 3 个节点禁止转发提案。它有 leader（所以提案会被处理），
     * 但转发被拒 → raft 丢弃提案 → future 异常完成。
     * 它的选举超时被调得很长，保证它一直当 follower。</p>
     */
    @Test
    public void droppedProposalCompletesExceptionally() throws Exception {
        boolean[] noForward = {false, false, true};
        int[] ticks = {10, 10, 200};
        try (Cluster c = new Cluster(3, noForward, ticks)) {
            long lead = c.waitForLeader();
            assertNotEquals(Raft.NONE, lead, "cluster should have a leader");
            assertNotEquals(3L, lead, "the node with forwarding disabled must stay a follower");

            Node follower = c.members.get(3L).node;
            CompletableFuture<Void> f = follower.proposeAsync(new byte[]{1}, 20000);

            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> f.get(20, TimeUnit.SECONDS));
            assertTrue(e.getCause() instanceof RaftException,
                    "unexpected failure: " + e.getCause());
            assertEquals("raft proposal dropped", e.getCause().getMessage());
            assertEquals(0, follower.getPendingProposalCount(),
                    "a dropped proposal must be removed from the pending table");
        }
    }

    /**
     * 节点停止：还在等着的 future 全部异常完成。
     */
    @Test
    public void stoppedNodeFailsPendingProposals() throws Exception {
        // 一个从不选主的节点：提案会一直排在队列里，直到节点停止
        Node node = new Node(new RawNode(RaftTestSupport.newTestRaft(1, 10, 1, new MemoryStorage())));
        try {
            CompletableFuture<Void> f = node.proposeAsync(new byte[]{1}, 60000);
            Thread.sleep(200);
            assertFalse(f.isDone(), "the proposal should still be waiting for a leader");

            node.stop();

            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> f.get(10, TimeUnit.SECONDS));
            assertTrue(e.getCause() instanceof RaftException,
                    "unexpected failure: " + e.getCause());
            assertEquals("raft: stopped", e.getCause().getMessage());
            assertEquals(0, node.getPendingProposalCount(),
                    "a stopped node must not leave anything in the pending table");
        } finally {
            node.stop();
        }
    }

    /**
     * 超时：future 以 {@link TimeoutException} 结束，并且从待完成表里摘掉。
     */
    @Test
    public void timedOutProposalFailsAndLeavesThePendingTable() throws Exception {
        Node node = new Node(new RawNode(RaftTestSupport.newTestRaft(1, 10, 1, new MemoryStorage())));
        try {
            CompletableFuture<Void> f = node.proposeAsync(new byte[]{1}, 300);

            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> f.get(10, TimeUnit.SECONDS));
            assertTrue(e.getCause() instanceof TimeoutException,
                    "unexpected failure: " + e.getCause());

            long deadline = System.currentTimeMillis() + 5000;
            while (node.getPendingProposalCount() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(0, node.getPendingProposalCount(),
                    "a timed out proposal must be removed from the pending table");
        } finally {
            node.stop();
        }
    }

    /**
     * 客户端向 follower 提案：follower 转发给 leader，leader 提交，
     * <b>follower 上的 future 正常完成</b>。
     *
     * <p>这是请求 id 方案的决定性用例：「index → future」的映射在 follower 上
     * 根本拿不到 index（本地没有提交），而且条目被截断覆盖后会误报提交成功。</p>
     */
    @Test
    public void proposalToFollowerCompletesOnTheFollower() throws Exception {
        try (Cluster c = new Cluster(3, null, new int[]{10, 10, 10})) {
            long lead = c.waitForLeader();
            assertNotEquals(Raft.NONE, lead, "cluster should have a leader");

            Node follower = null;
            for (Cluster.Member m : c.members.values()) {
                if (m.id != lead) {
                    follower = m.node;
                    break;
                }
            }
            assertNotEquals(null, follower, "there must be a follower");

            CompletableFuture<Void> f = follower.proposeAsync(new byte[]{7, 7}, 20000);
            f.get(20, TimeUnit.SECONDS);

            assertEquals(0, follower.getPendingProposalCount(),
                    "the pending table should be empty once the proposal is committed");
        }
    }

    /**
     * 多个异步提案并发提交，全部正常完成。
     */
    @Test
    public void concurrentAsyncProposalsAllComplete() throws Exception {
        try (Cluster c = new Cluster(3, null, new int[]{10, 10, 10})) {
            long lead = c.waitForLeader();
            assertNotEquals(Raft.NONE, lead, "cluster should have a leader");
            Node leader = c.members.get(lead).node;

            int n = 50;
            CompletableFuture<?>[] futures = new CompletableFuture[n];
            for (int i = 0; i < n; i++) {
                futures[i] = leader.proposeAsync(new byte[]{(byte) i}, 20000);
            }
            for (CompletableFuture<?> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
            assertEquals(0, leader.getPendingProposalCount());
        }
    }
}
