/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.raft.cluster;

import io.edap.raft.ApplyHandler;
import io.edap.raft.Status;
import io.edap.raft.protocol.Entry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 编排层的端到端单测：自动成组、自举、加入、拒绝、换主、幂等。
 *
 * <p>全部在单 JVM 内跑：编排动作走内存实现，raft 消息走
 * {@code InMemoryNetwork}，不碰 socket。</p>
 */
public class RaftClusterNodeTest {

    /** 收业务的 apply 载荷 */
    private static final class CollectingHandler implements ApplyHandler {
        private final List<byte[]> applied = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void apply(List<Entry> entries) {
            for (Entry e : entries) {
                applied.add(e.getData());
            }
        }

        int count() {
            return applied.size();
        }
    }

    private InMemoryClusterNetwork network;
    private final List<RaftClusterNode> nodes = new ArrayList<>();
    private final List<Path> dataDirs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        network = new InMemoryClusterNetwork();
    }

    @AfterEach
    void tearDown() {
        for (RaftClusterNode n : nodes) {
            try {
                n.stop();
            } catch (Throwable ignored) {
                // 测试收尾，忽略
            }
        }
        nodes.clear();
        network.shutdown();
    }

    // ------------------------------------------------------------------
    // 场景
    // ------------------------------------------------------------------

    /**
     * 第一个起来的容器一定是主。
     */
    @Test
    void firstNodeBecomesLeader() throws Exception {
        RaftClusterNode n1 = newNode(1111, 3);
        n1.start();
        await(() -> n1.isLeader(), 10_000, "节点 1 当选主");
        assertTrue(n1.isLeader());
        assertEquals(RaftClusterState.LEADER, n1.state());
        assertEquals(1, n1.membership().size());
    }

    /**
     * 后起来的节点加入集群，成为从。
     */
    @Test
    void laterNodesJoinAsFollowers() throws Exception {
        RaftClusterNode n1 = newNode(1111, 3);
        n1.start();
        await(() -> n1.isLeader(), 10_000, "节点 1 当选主");

        RaftClusterNode n2 = newNode(1112, 3);
        n2.start();
        RaftClusterNode n3 = newNode(1113, 3);
        n3.start();

        await(() -> n1.membership().size() == 3, 15_000, "集群凑齐 3 个成员");
        assertFalse(n2.isLeader());
        assertFalse(n3.isLeader());
        assertEquals(RaftClusterState.FOLLOWER, n2.state());
        assertEquals(RaftClusterState.FOLLOWER, n3.state());
        // 从节点也知道主是谁
        await(() -> n2.membership().leaderId() == 1L, 10_000, "从节点学到 leader");
    }

    /**
     * ★ 并发启动不分裂脑：4 个节点同时起，只有 1 个主。
     */
    @Test
    void concurrentStartProducesSingleLeader() throws Exception {
        int count = 4;
        List<RaftClusterNode> all = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            all.add(newNode(1111 + i, count));
        }
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (RaftClusterNode n : all) {
            Thread t = new Thread(() -> {
                try {
                    go.await();
                    n.start();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
        go.countDown();
        for (Thread t : threads) {
            t.join(60_000);
        }

        await(() -> {
            int leaders = 0;
            for (RaftClusterNode n : all) {
                if (n.isLeader()) {
                    leaders++;
                }
            }
            return leaders == 1;
        }, 30_000, "并发启动后恰好一个主");

        int leaders = 0;
        for (RaftClusterNode n : all) {
            if (n.isLeader()) {
                leaders++;
            }
        }
        assertEquals(1, leaders, "并发启动只能有一个主");
    }

    /**
     * ★ 并发加入<b>不丢成员</b>：4 个节点同时起，最后 4 个都得进集群。
     *
     * <p>这条测的是一个真 bug：内核在提案时如果发现「上一条成员变更还没 apply」，
     * 会把这条成员变更<b>降级成普通条目</b>（{@code Raft#handleProposal}），
     * 而且不告诉调用方 —— 表面上提案成功了，成员却永远进不了集群。
     * 并发加入最容易踩：第一个成员变更还没 apply，第二个就被降级了。</p>
     *
     * <p>判据用<b>raft 内核的投票成员数</b>而不是编排层的成员表：
     * 编排层的地址表是「乐观」的（提案时就写进去了），
     * 只有内核的 voter 集才说明成员真的进了集群。</p>
     */
    @Test
    void concurrentStartKeepsEveryMember() throws Exception {
        int count = 4;
        List<RaftClusterNode> all = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            all.add(newNode(1111 + i, count));
        }
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (RaftClusterNode n : all) {
            Thread t = new Thread(() -> {
                try {
                    go.await();
                    n.start();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            t.setDaemon(true);
            threads.add(t);
            t.start();
        }
        go.countDown();
        for (Thread t : threads) {
            t.join(60_000);
        }

        for (RaftClusterNode n : all) {
            final RaftClusterNode node = n;
            await(() -> voterCount(node) == count, 30_000,
                    "节点 " + n.nodeId() + " 的投票成员应当有 " + count + " 个，实际 "
                            + voterCount(node));
            await(() -> node.membership().leaderId() != 0, 30_000,
                    "节点 " + n.nodeId() + " 应当知道主是谁");
        }
    }

    /**
     * ★ 部分可见也不分裂：看得见同伴的等同伴，看不见同伴的自举 ⇒ 仍然只有一个主。
     *
     * <p>可见性：1111 谁都看不见（自举成主），1112/1113 只看得见 1111
     * （地址更小 ⇒ 等它成主 ⇒ 再加入）。</p>
     */
    @Test
    void partialVisibilityStillProducesSingleLeader() throws Exception {
        network.setVisibility("127.0.0.1:1111");
        network.setVisibility("127.0.0.1:1112", "127.0.0.1:1111");
        network.setVisibility("127.0.0.1:1113", "127.0.0.1:1111");

        RaftClusterNode a = newNode(1111, 3);
        RaftClusterNode b = newNode(1112, 3);
        RaftClusterNode c = newNode(1113, 3);

        a.start();
        b.start();
        c.start();

        await(() -> {
            int leaders = 0;
            for (RaftClusterNode n : Arrays.asList(a, b, c)) {
                if (n.isLeader()) {
                    leaders++;
                }
            }
            return leaders == 1;
        }, 30_000, "部分可见下恰好一个主");

        // 看不见同伴的那个自举成了主；看得见的两个都加入了它
        assertTrue(a.isLeader(), "看不见同伴的节点应当自举成主");
        assertFalse(b.isLeader());
        assertFalse(c.isLeader());
    }

    /**
     * ★ 集群成员数不超过上限：第 N+1 个容器只做客户端，不起 raft 节点。
     */
    @Test
    void extraNodeBecomesClientOnly() throws Exception {
        RaftClusterNode n1 = newNode(1111, 2);
        n1.start();
        await(() -> n1.isLeader(), 10_000, "节点 1 当选主");

        RaftClusterNode n2 = newNode(1112, 2);
        n2.start();
        await(() -> n1.membership().size() == 2, 15_000, "集群凑齐 2 个成员");

        RaftClusterNode n3 = newNode(1113, 2);
        n3.start();
        await(() -> n3.state() == RaftClusterState.CLIENT_ONLY, 15_000, "第 3 个节点转 client-only");
        assertEquals(RaftClusterState.CLIENT_ONLY, n3.state());
        assertFalse(n3.isLeader());
        // 集群规模没被突破
        assertEquals(2, n1.membership().size());
    }

    /**
     * ★ 只做客户端的节点也能干活：知道主在哪，提案转给主。
     *
     * <p>这是「集群满员之后启动的容器」的行为 —— 它不是成员，
     * 但业务照样通过它提交提案。</p>
     */
    @Test
    void clientOnlyNodeForwardsProposalsToLeader() throws Exception {
        CollectingHandler handler = new CollectingHandler();
        RaftClusterNode n1 = newNode(1111, 1, handler);   // 上限 1 ⇒ 第 2 个只能当客户端
        n1.start();
        await(() -> n1.isLeader(), 10_000, "节点 1 当选主");

        RaftClusterNode n2 = newNode(1112, 1);
        n2.start();
        await(() -> n2.state() == RaftClusterState.CLIENT_ONLY, 15_000, "节点 2 转 client-only");

        // 视图里能看出谁是主
        RaftClusterView view = n2.view();
        assertNotNull(view.leaderManagerAddress(), "client-only 节点应当知道主在哪");
        assertEquals("127.0.0.1:1111", view.leaderManagerAddress());

        // 提案转给主 ⇒ 主提交并应用 ⇒ 业务状态机收到载荷
        byte[] payload = "from client".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        n2.proposeWithAck(999L, payload).get(20, java.util.concurrent.TimeUnit.SECONDS);
        await(() -> handler.count() == 1, 10_000, "业务状态机收到载荷");
    }

    /**
     * ★ nodeId 跨重启不变。
     */
    @Test
    void nodeIdSurvivesRestart() throws Exception {
        RaftClusterNode n1 = newNode(1111, 3);
        n1.start();
        await(() -> n1.isLeader(), 10_000, "节点 1 当选主");
        long id = n1.nodeId();
        assertNotEquals(0L, id);

        n1.stop();
        nodes.remove(n1);

        RaftClusterNode again = newNode(1111, 3);
        again.start();
        assertEquals(id, again.nodeId(), "重启后 nodeId 必须不变");
    }

    /**
     * ★ 客户端不填 raft 地址，只靠 seeds 就能发现主并提案。
     */
    @Test
    void clientDiscoversLeaderAndProposes() throws Exception {
        CollectingHandler handler = new CollectingHandler();
        RaftClusterNode n1 = newNode(1111, 3, handler);
        n1.start();
        await(() -> n1.isLeader(), 10_000, "节点 1 当选主");

        RaftClient client = newRaftClient();
        byte[] payload = "hello raft".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(client.propose(payload).isOk());

        await(() -> handler.count() == 1, 10_000, "业务状态机收到载荷");
    }

    /**
     * ★ 同一个 requestId 重试，业务状态机只应用一次。
     */
    @Test
    void sameRequestIdIsAppliedOnce() throws Exception {
        CollectingHandler handler = new CollectingHandler();
        RaftClusterNode n1 = newNode(1111, 3, handler);
        n1.start();
        await(() -> n1.isLeader(), 10_000, "节点 1 当选主");

        byte[] payload = "once".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long requestId = 12345L;
        n1.proposeWithAck(requestId, payload).get(10, java.util.concurrent.TimeUnit.SECONDS);
        await(() -> handler.count() == 1, 10_000, "第一次应用");
        assertFalse(n1.isDuplicateProposal(requestId), "第一次是新应用，不该报重复");

        // 重试：同一个 requestId
        n1.proposeWithAck(requestId, payload).get(10, java.util.concurrent.TimeUnit.SECONDS);
        Thread.sleep(300);
        assertEquals(1, handler.count(), "同一个 requestId 只应用一次");
        assertTrue(n1.isDuplicateProposal(requestId), "重试的回执要如实标明是重复");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private RaftClusterNode newNode(int managerPort, int capacity) throws Exception {
        return newNode(managerPort, capacity, new CollectingHandler());
    }

    private RaftClusterNode newNode(int managerPort, int capacity, ApplyHandler handler)
            throws Exception {
        Path dir = Files.createTempDirectory("raft-cluster-test");
        dataDirs.add(dir);
        RaftClusterConfig cfg = new RaftClusterConfig();
        cfg.selfHost = "127.0.0.1";
        cfg.selfPort = 19000 + managerPort;
        cfg.managerPort = managerPort;
        cfg.dataDir = dir.toString();
        cfg.capacity = capacity;
        cfg.seeds = new ArrayList<>(Arrays.asList(
                "127.0.0.1:1111", "127.0.0.1:1112", "127.0.0.1:1113", "127.0.0.1:1114"));
        cfg.tickMillis = 20;
        cfg.electionTick = 10;
        cfg.heartbeatTick = 1;
        cfg.claimWindowMillis = 1500;
        cfg.joinTimeoutMillis = 20_000;
        cfg.proposalTimeoutMillis = 20_000;
        RaftClusterNode node = new RaftClusterNode(cfg, network.connector(),
                network.transportFactory(), handler);
        network.register("127.0.0.1:" + managerPort, node);
        nodes.add(node);
        return node;
    }

    private RaftClient newRaftClient() {
        RaftClusterConfig cfg = new RaftClusterConfig();
        cfg.managerPort = 1111;
        cfg.seeds = new ArrayList<>(Arrays.asList("127.0.0.1:1111"));
        cfg.maxCandidates = 16;
        cfg.proposalTimeoutMillis = 20_000;
        return new RaftClient(cfg, network.connector());
    }

    private static void await(BooleanSupplier cond, long timeoutMillis, String what)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        fail("等待超时：" + what);
    }

    /**
     * raft 内核当前的投票成员数。
     *
     * <p>这是「成员真的进了集群」的判据 —— 编排层的成员表是乐观的
     * （提案时就写进去了地址）。</p>
     */
    private static int voterCount(RaftClusterNode node) {
        Status st = node.status();
        if (st == null || st.getConfig() == null || st.getConfig().getVoters() == null) {
            return 0;
        }
        return st.getConfig().getVoters().ids().size();
    }
}
