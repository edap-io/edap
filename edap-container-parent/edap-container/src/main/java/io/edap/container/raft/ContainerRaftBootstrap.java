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

package io.edap.container.raft;

import io.edap.Edap;
import io.edap.ServerGroup;
import io.edap.http.HttpHandler;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.props.Props;
import io.edap.raft.cluster.RaftClusterConfig;
import io.edap.raft.cluster.RaftClusterNode;
import io.edap.raft.cluster.RaftSeedSet;
import io.edap.raft.transport.NioRaftTransport;

import java.io.File;
import java.util.List;

/**
 * 容器的进程级 raft：把 {@code container.raft.*} 配置映射成
 * {@link RaftClusterConfig}，然后拉起一个 {@link RaftClusterNode}。
 *
 * <p><b>使用者不需要写任何注解</b>：容器启动时按配置决定是否起 raft 节点，
 * 节点之间靠「启动广播 + 确定性仲裁」自动成组。</p>
 *
 * <p><b>★ 为什么是进程级而不是 app 级</b>：raft 节点的身份（nodeId）与
 * 容器绑定，不与某个 EAR 绑定 —— 容器重启后 nodeId 必须不变，
 * 否则集群里会出现两个「同地址不同 id」的节点。</p>
 *
 * <p><b>配置</b>（都在 {@code container} 下面）：</p>
 * <pre>
 * container.raft.enabled            = true            # 默认 false：不开就不起 raft
 * container.raft.nodes              = 4               # 集群总节点数（1 主 + 3 从）
 * container.raft.seeds              = 192.168.0.*:1111 # 候选地址，支持网段通配
 * container.raft.host               = 10.0.0.7        # 本节点对外地址；缺省自动探测
 * container.raft.port               = 9092            # raft 传输端口
 * container.raft.dataDir            = data/raft       # 节点身份 + raft 日志目录
 * container.raft.tickMillis         = 100
 * container.raft.electionTick       = 10
 * container.raft.heartbeatTick      = 1
 * container.raft.joinTimeoutMillis  = 20000
 * container.raft.probeTimeoutMillis = 200
 * container.raft.probeConcurrency   = 32
 * container.raft.maxCandidates      = 256
 * container.raft.claimWindowMillis  = 2000
 * container.raft.proposalTimeoutMillis = 20000
 * </pre>
 */
public class ContainerRaftBootstrap {

    private static final Logger log = LoggerManager.getLogger(ContainerRaftBootstrap.class);

    /** 管理端口上的四个 raft 端点。 */
    private final RaftManagerEndpoints endpoints = new RaftManagerEndpoints();

    private volatile RaftClusterNode   node;

    private volatile HttpRaftConnector connector;

    /**
     * 没有配置时用它（测试与嵌入式场景）。
     */
    public ContainerRaftBootstrap() {
    }

    /**
     * 用容器的根配置构造。
     *
     * <p>★ <b>为什么要提前拿配置</b>：管理端口比 raft 节点先监听，
     * 这段时间里同伴会来问「你是不是也在启动」——
     * 端点必须已经知道本机地址，才能答「是」（否则同伴看不见我们，
     * 同时启动的容器就可能各自自举 ⇒ 出现两个主）。</p>
     *
     * @param rootProps 容器的根配置（{@code container.raft.*} 在它下面）
     */
    public ContainerRaftBootstrap(Props rootProps) {
        if (rootProps == null) {
            return;
        }
        int managerPort = rootProps.getInt("container.manager.port", 1111);
        int raftPort = rootProps.getInt("container.raft.port", 9092);
        List<String> seeds = RaftSeedSet.parse(
                rootProps.getString("container.raft.seeds", ""));
        String host;
        try {
            host = RaftSeedSet.detectSelfHost(
                    rootProps.getString("container.raft.host", null), seeds);
        } catch (Throwable t) {
            // 探测不到本机地址：节点起来之前 CLAIM 只能返回 503（老行为）
            log.warn("raft: 探测不到本机地址，raft 节点起来之前 CLAIM 会返回 503", l -> l.threw(t));
            return;
        }
        endpoints.prepareSelf(host, raftPort, managerPort);
    }

    // ------------------------------------------------------------------
    // HTTP 端点
    // ------------------------------------------------------------------

    /**
     * {@code POST /raft/claim}：启动广播。
     */
    public HttpHandler claimHandler() {
        return endpoints.claimHandler();
    }

    /**
     * {@code POST /raft/join}：加入集群。
     */
    public HttpHandler joinHandler() {
        return endpoints.joinHandler();
    }

    /**
     * {@code GET /raft/cluster}：集群视图。
     */
    public HttpHandler clusterHandler() {
        return endpoints.clusterHandler();
    }

    /**
     * {@code POST /raft/propose}：提案。
     */
    public HttpHandler proposeHandler() {
        return endpoints.proposeHandler();
    }

    /**
     * 当前容器里的 raft 节点；没起 raft 时是 null。
     */
    public RaftClusterNode node() {
        return node;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 按配置拉起 raft 节点。
     *
     * @param edap    宿主的 Edap —— raft 传输层复用它的 reactor，不另起线程组
     * @param env     {@code container} 这一节的配置
     * @param baseDir 相对路径的基准目录（一般是容器所在目录）
     */
    public void start(Edap edap, Props env, File baseDir) throws Exception {
        if (!env.getBoolean("raft.enabled", false)) {
            log.info("raft 未启用（container.raft.enabled=false），容器不起 raft 节点");
            return;
        }
        RaftClusterConfig cfg = toConfig(env, baseDir);
        log.info("raft 启动中：节点上限 {}，seeds {}", l -> l.arg(cfg.capacity).arg(cfg.seeds));

        // raft 入站 server 挂一个自己的 ServerGroup。
        // ★ 必须自己 run()：ServerGroup.addServerAndBind 只在 run() 缓存过 acceptor
        //   之后才会真的 bind —— 没 run 过的组只会把 server 入队，端口永远不监听
        //   （共享宿主 Edap 时宿主的组早就 run 完了，不会顺带把这个组也 run）。
        ServerGroup raftGroup = new ServerGroup();
        raftGroup.setName("raft");
        edap.addServerGroup(raftGroup);
        raftGroup.run();

        this.connector = new HttpRaftConnector(cfg);
        RaftClusterNode n = new RaftClusterNode(cfg, connector,
                (nodeId, host, port) -> new NioRaftTransport(host, port, edap, raftGroup),
                this::onApply);
        // ★ 先绑端点、再启动节点：节点的启动过程本身就是「广播 CLAIM 找同伴」，
        //   这段时间里我们必须已经能应答同伴的 CLAIM（回 PEER_CLAIM），
        //   否则同时启动的容器互相看不见 ⇒ 各自自举 ⇒ 出现两个主。
        //   （节点此时还没有 raft 成员，claim() 会自然回 PEER_CLAIM，不需要 raft 已就绪。）
        endpoints.bind(n);
        try {
            n.start();
        } catch (Throwable t) {
            endpoints.unbind();
            throw t;
        }
        this.node = n;
        log.info("raft 节点已启动：{}，状态 {}", l -> l.arg(n.raftAddress()).arg(n.state()));
    }

    /**
     * 停掉 raft 节点。
     */
    public void stop() {
        endpoints.unbind();
        RaftClusterNode n = node;
        node = null;
        if (n != null) {
            try {
                n.stop();
            } catch (Throwable t) {
                log.warn("停止 raft 节点时出错", l -> l.threw(t));
            }
        }
        HttpRaftConnector c = connector;
        connector = null;
        if (c != null) {
            c.shutdown();
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 业务状态机：容器这一层还没有对外的业务 apply 协议，
     * 先把「有载荷被应用了」这件事记下来（每批一行）。
     */
    private void onApply(java.util.List<io.edap.raft.protocol.Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        log.info("raft 应用了 {} 条业务载荷", l -> l.arg(entries.size()));
    }

    /**
     * 配置映射。
     */
    private RaftClusterConfig toConfig(Props env, File baseDir) {
        RaftClusterConfig cfg = new RaftClusterConfig();
        cfg.managerPort = env.getInt("manager.port", 1111);
        cfg.selfPort = env.getInt("raft.port", 9092);
        cfg.selfHost = env.getString("raft.host", null);
        cfg.capacity = Math.max(1, env.getInt("raft.nodes", 3));
        cfg.tickMillis = env.getLong("raft.tickMillis", 100L);
        cfg.electionTick = env.getInt("raft.electionTick", 10);
        cfg.heartbeatTick = env.getInt("raft.heartbeatTick", 1);
        cfg.joinTimeoutMillis = env.getLong("raft.joinTimeoutMillis", 20_000L);
        cfg.probeTimeoutMillis = env.getLong("raft.probeTimeoutMillis", 200L);
        cfg.probeConcurrency = env.getInt("raft.probeConcurrency", 32);
        cfg.maxCandidates = env.getInt("raft.maxCandidates", 256);
        cfg.claimWindowMillis = env.getLong("raft.claimWindowMillis", 2000L);
        cfg.proposalTimeoutMillis = env.getLong("raft.proposalTimeoutMillis", 20_000L);
        cfg.dataDir = resolveDataDir(env.getString("raft.dataDir", "data/raft"), baseDir);

        List<String> seeds = RaftSeedSet.parse(env.getString("raft.seeds", ""));
        cfg.seeds = seeds;
        if (seeds.isEmpty()) {
            // 没配 seeds：找不到任何同伴 ⇒ 只能自成一个单节点集群。
            // 这不是错误，但值得提醒 —— 多容器部署忘了配 seeds 就只会各自成主。
            log.warn("container.raft.seeds 没配，本节点只能自成一个单节点集群"
                    + "（多容器部署请配置 seeds，例如 192.168.0.*:1111）");
        }
        return cfg;
    }

    /**
     * 相对路径的 dataDir 按容器所在目录解析 —— 容器换目录部署时，
     * 节点身份（node.json）要跟着走，不能散落在进程工作目录里。
     */
    private String resolveDataDir(String configured, File baseDir) {
        if (configured == null || configured.trim().isEmpty()) {
            configured = "data/raft";
        }
        File dir = new File(configured.trim());
        if (dir.isAbsolute() || baseDir == null) {
            return dir.getAbsolutePath();
        }
        return new File(baseDir, configured.trim()).getAbsolutePath();
    }
}
