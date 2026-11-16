/*
 * Copyright 2023 The edap Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.edap.container.raft;

import io.edap.Edap;
import io.edap.ServerGroup;
import io.edap.http.server.HttpServerBuilder;
import io.edap.json.Eson;
import io.edap.props.Props;
import io.edap.raft.cluster.RaftClaimReply;
import io.edap.raft.cluster.RaftClusterState;
import io.edap.raft.cluster.RaftClusterView;
import io.edap.raft.cluster.RaftProposalResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容器接线的端到端测试 —— <b>真起 HTTP server、真走 CLAIM 广播、真跑 raft 协议</b>。
 *
 * <p>覆盖的是「容器按配置自动成组」这条链路里编排层单测覆盖不到的部分：</p>
 * <pre>
 *   HttpRaftConnector(JDK HttpClient) ──► edap HttpServer ──► RaftManagerEndpoints
 *        ──► RaftClusterNode ──► NioRaftTransport(edap NIO) ──► 对端 raft 节点
 * </pre>
 *
 * <p>三个场景：</p>
 * <ul>
 *   <li>第一个节点自举成主，第二个节点加入成从（成员表两边都是 2）；</li>
 *   <li>主能通过 {@code POST /raft/propose} 提交载荷并被应用（回执靠 requestId 匹配）；</li>
 *   <li>集群满了以后再起的容器<b>只做客户端</b>，而且<b>不监听 raft 端口</b>。</li>
 * </ul>
 *
 * <p><b>JDK HttpClient 必须显式 HTTP/1.1</b>：默认的 h2c upgrade 探测会让
 * edap 的 HttpServer 挂住（不是本测试的问题，是被测链路本身的约束）。</p>
 */
public class ContainerRaftEndToEndTest {

    private static final String HOST = "127.0.0.1";

    /** 集群上限：两个成员。 */
    private static final int CLUSTER_SIZE = 2;

    @Test
    public void firstNodeBecomesLeaderAndSecondJoins(@TempDir File tmp) throws Exception {
        Node a = new Node(freePort(), freePort(), new File(tmp, "a"));
        Node b = new Node(freePort(), freePort(), new File(tmp, "b"));
        try {
            String seeds = HOST + ":" + a.managerPort + "," + HOST + ":" + b.managerPort;
            a.startManager(seeds, CLUSTER_SIZE);
            b.startManager(seeds, CLUSTER_SIZE);

            // 第一个节点：广播 CLAIM 找不到同伴 ⇒ 自举成主
            a.startRaft(seeds, CLUSTER_SIZE);
            awaitTrue(() -> a.raft.node() != null && a.raft.node().isLeader(), 10000);

            // 第二个节点：广播 CLAIM 拿到 LEADER_ACK ⇒ 加入成从
            b.startRaft(seeds, CLUSTER_SIZE);
            awaitTrue(() -> b.raft.node() != null
                    && b.raft.node().state() == RaftClusterState.FOLLOWER, 10000);

            // 成员表两边都是 2 —— 说明 ConfChange 已经复制到从节点并被应用
            awaitTrue(() -> a.raft.node().membership().size() == CLUSTER_SIZE, 10000);
            awaitTrue(() -> b.raft.node().membership().size() == CLUSTER_SIZE, 10000);
            assertTrue(a.raft.node().isLeader(), "第一个启动的节点应该是主");
            assertFalse(b.raft.node().isLeader(), "后加入的节点应该是从");
        } finally {
            a.close();
            b.close();
        }
    }

    @Test
    public void leaderAcceptsProposalOverHttp(@TempDir File tmp) throws Exception {
        Node a = new Node(freePort(), freePort(), new File(tmp, "a"));
        Node b = new Node(freePort(), freePort(), new File(tmp, "b"));
        try {
            String seeds = HOST + ":" + a.managerPort + "," + HOST + ":" + b.managerPort;
            a.startManager(seeds, CLUSTER_SIZE);
            b.startManager(seeds, CLUSTER_SIZE);
            a.startRaft(seeds, CLUSTER_SIZE);
            awaitTrue(() -> a.raft.node() != null && a.raft.node().isLeader(), 10000);
            b.startRaft(seeds, CLUSTER_SIZE);
            awaitTrue(() -> b.raft.node() != null
                    && b.raft.node().state() == RaftClusterState.FOLLOWER, 10000);

            // 集群视图：客户端靠它发现主
            RaftClusterView view = parse(get(a.managerPort, "/raft/cluster"), RaftClusterView.class);
            assertEquals(a.raft.node().nodeId(), view.getLeaderId(), "集群视图里的主应当是 A");
            assertEquals(HOST, view.getLeaderHost());

            // 提案：只有主处理，回执按 requestId 匹配 ⇒ 返回成功说明载荷已被应用
            String body = "{\"requestId\":42,\"payloadBase64\":\""
                    + Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8))
                    + "\"}";
            RaftProposalResult reply = parse(post(a.managerPort, "/raft/propose", body),
                    RaftProposalResult.class);
            assertTrue(reply.isOk(), "提案应当成功：" + reply);

            // 同样的 requestId 再提一次 —— 幂等，不能重复应用
            RaftProposalResult again = parse(post(a.managerPort, "/raft/propose", body),
                    RaftProposalResult.class);
            assertTrue(again.isOk(), "重复提案应当仍然成功：" + again);
        } finally {
            a.close();
            b.close();
        }
    }

    @Test
    public void extraNodeBecomesClientOnlyAndDoesNotBindRaftPort(@TempDir File tmp) throws Exception {
        Node a = new Node(freePort(), freePort(), new File(tmp, "a"));
        Node b = new Node(freePort(), freePort(), new File(tmp, "b"));
        Node c = new Node(freePort(), freePort(), new File(tmp, "c"));
        try {
            String seeds = HOST + ":" + a.managerPort + "," + HOST + ":" + b.managerPort
                    + "," + HOST + ":" + c.managerPort;
            a.startManager(seeds, CLUSTER_SIZE);
            b.startManager(seeds, CLUSTER_SIZE);
            c.startManager(seeds, CLUSTER_SIZE);
            a.startRaft(seeds, CLUSTER_SIZE);
            awaitTrue(() -> a.raft.node() != null && a.raft.node().isLeader(), 10000);
            b.startRaft(seeds, CLUSTER_SIZE);
            awaitTrue(() -> b.raft.node() != null
                    && b.raft.node().state() == RaftClusterState.FOLLOWER, 10000);

            // 第三个节点：集群满了 ⇒ 只做客户端
            c.startRaft(seeds, CLUSTER_SIZE);
            assertNotNull(c.raft.node());
            assertEquals(RaftClusterState.CLIENT_ONLY, c.raft.node().state());
            // 只做客户端 ⇒ 不起 raft 节点 ⇒ 不监听 raft 端口
            assertFalse(portListening(c.raftPort), "client-only 的容器不该监听 raft 端口");

            // ★ 但它照样能干活：视图里看得到主，提案转给主
            RaftClusterView view = parse(get(c.managerPort, "/raft/cluster"), RaftClusterView.class);
            assertEquals(HOST + ":" + a.managerPort, view.leaderManagerAddress(),
                    "client-only 的容器应当知道主在哪");
            String body = "{\"requestId\":7,\"payloadBase64\":\""
                    + Base64.getEncoder().encodeToString("via client".getBytes(StandardCharsets.UTF_8))
                    + "\"}";
            RaftProposalResult reply = parse(post(c.managerPort, "/raft/propose", body),
                    RaftProposalResult.class);
            assertTrue(reply.isOk(), "client-only 的容器应当能把提案转给主：" + reply);
        } finally {
            a.close();
            b.close();
            c.close();
        }
    }

    /**
     * ★ 管理端口先于 raft 节点监听 —— 这段时间里 CLAIM 也得答「我也在启动」。
     *
     * <p>否则同时启动的容器在这个窗口里互相看不见 ⇒ 各自自举 ⇒ <b>两个主</b>。
     * 这里只起管理端口、不起 raft 节点，直接验这个窗口的行为。</p>
     */
    @Test
    public void claimAnswersBeforeRaftNodeIsUp(@TempDir File tmp) throws Exception {
        Node a = new Node(freePort(), freePort(), new File(tmp, "a"));
        try {
            String seeds = HOST + ":" + a.managerPort;
            a.startManager(seeds, CLUSTER_SIZE);
            // raft 节点还没起（也没绑上去）
            assertNull(a.raft.node(), "这个用例的前提就是 raft 节点还没起来");

            String body = "{\"host\":\"" + HOST + "\",\"port\":" + a.raftPort
                    + ",\"managerPort\":" + a.managerPort + ",\"instanceId\":\"probe\"}";
            RaftClaimReply reply = parse(post(a.managerPort, "/raft/claim", body),
                    RaftClaimReply.class);
            assertEquals(RaftClaimReply.Type.PEER_CLAIM, reply.getType(),
                    "节点还没起来也要应答「我也在启动」");
            assertEquals(HOST + ":" + a.managerPort, reply.peerManagerAddress(),
                    "应答里要带上自己的管理地址，同伴才知道往哪找我");
        } finally {
            a.close();
        }
    }

    // ------------------------------------------------------------------
    // 测试脚手架：一个「容器」的 raft 侧
    // ------------------------------------------------------------------

    /**
     * 一个容器角色的最小复刻：自己的 Edap（供 raft 传输层复用）+ 自己的管理 HTTP 端口
     * + 自己的 raft 启动器。
     */
    static class Node {

        final int managerPort;
        final int raftPort;
        final File dataDir;

        Edap edap;
        ContainerRaftBootstrap raft;

        /** 容器这一节的配置（startRaft 用）。 */
        Props envProps;

        /** 根配置（startManager 传给启动器用）。 */
        Props rootProps;

        Node(int managerPort, int raftPort, File dataDir) {
            this.managerPort = managerPort;
            this.raftPort = raftPort;
            this.dataDir = dataDir;
        }

        /** 按生产同构的格式生成容器配置（root 里挂 container 那一节）。 */
        void buildProps(String seeds, int clusterSize) {
            Map<String, Object> raftProps = new HashMap<>();
            raftProps.put("enabled", true);
            raftProps.put("nodes", clusterSize);
            raftProps.put("seeds", seeds);
            raftProps.put("host", HOST);
            raftProps.put("port", raftPort);
            raftProps.put("dataDir", dataDir.getAbsolutePath());
            raftProps.put("tickMillis", 50);
            raftProps.put("electionTick", 10);
            raftProps.put("heartbeatTick", 1);
            raftProps.put("claimWindowMillis", 1000);
            raftProps.put("joinTimeoutMillis", 15000);
            raftProps.put("proposalTimeoutMillis", 10000);

            Map<String, Object> manager = new HashMap<>();
            manager.put("port", managerPort);

            Map<String, Object> container = new HashMap<>();
            container.put("raft", raftProps);
            container.put("manager", manager);

            Map<String, Object> root = new HashMap<>();
            root.put("container", container);

            this.envProps = new Props(container);
            this.rootProps = new Props(root);
        }

        /**
         * 起管理端口 + 四个 raft 端点（与 Bootstrap 里一样是静态注册）。
         *
         * <p>★ 配置要传进启动器：管理端口先于 raft 节点监听，
         * 这段时间里 CLAIM 也得能答「我也在启动」。</p>
         */
        void startManager(String seeds, int clusterSize) throws IOException {
            edap = new Edap();
            buildProps(seeds, clusterSize);
            raft = new ContainerRaftBootstrap(rootProps);
            HttpServerBuilder builder = new HttpServerBuilder();
            builder.listen(managerPort);
            builder.post("/raft/claim", raft.claimHandler());
            builder.post("/raft/join", raft.joinHandler());
            builder.get("/raft/cluster", raft.clusterHandler());
            builder.post("/raft/propose", raft.proposeHandler());
            ServerGroup group = new ServerGroup();
            group.setName("manager-" + managerPort);
            group.addServer(builder.build());
            edap.addServerGroup(group);
            edap.run();
        }

        /** 按配置拉起 raft 节点（配置与生产同构：container.raft.* 那一节）。 */
        void startRaft(String seeds, int clusterSize) throws Exception {
            if (envProps == null) {
                buildProps(seeds, clusterSize);
            }
            raft.start(edap, envProps, dataDir.getParentFile());
        }

        void close() {
            if (raft != null) {
                try {
                    raft.stop();
                } catch (Throwable ignored) {
                    // 测试收尾，异常不重要
                }
            }
            if (edap != null) {
                try {
                    edap.stop();
                } catch (Throwable ignored) {
                    // 同上
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private static String get(int port, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://" + HOST + ":" + port + path))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "GET " + path + " 应当成功");
        return resp.body();
    }

    private static String post(int port, String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://" + HOST + ":" + port + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "POST " + path + " 应当成功");
        return resp.body();
    }

    private static void awaitTrue(java.util.function.BooleanSupplier cond, long timeoutMillis)
            throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertTrue(cond.getAsBoolean(), "等待超时（" + timeoutMillis + "ms）");
    }

    /** 解析 JSON 应答。 */
    private static <T> T parse(String body, Class<T> type) {
        return Eson.parseObject(body.getBytes(StandardCharsets.UTF_8), type);
    }

    /** 找一个空闲端口。 */
    private static int freePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            ss.setReuseAddress(true);
            return ss.getLocalPort();
        }
    }

    /** 端口上有没有人在听。 */
    private static boolean portListening(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(HOST, port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
