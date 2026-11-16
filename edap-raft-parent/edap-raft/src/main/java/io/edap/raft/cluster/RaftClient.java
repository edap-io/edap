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

import io.edap.raft.RaftException;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 非成员客户端：只靠 seeds 就能发现主并提交提案。
 *
 * <p><b>为什么不直连 raft 端口</b>：raft 协议层没有「提案回执」消息
 * （{@code MessageType} 里只有 {@code MSG_PROP}），客户端拿不到「已提交」的确认；
 * 而且客户端不在成员表里，主的复制进度表里也没有它。
 * 所以客户端一律走主的 HTTP 管理端口。</p>
 *
 * <p><b>★ 幂等重试</b>：一次提案用<b>同一个</b> requestId。
 * 失败/超时后重新发现主再重试，主按 requestId 去重 ⇒ 业务状态机只应用一次。</p>
 *
 * <p><b>★ 换主能自动跟上</b>：提案失败说明主可能已经换了 ——
 * 这时丢掉缓存的主地址，重新发现，再重试。</p>
 */
public class RaftClient {

    private final RaftClusterConfig    config;
    private final RaftClusterConnector connector;

    /** 缓存的主的管理地址。 */
    private volatile String leaderAddress;

    /** 缓存的主的集群视图（谁在成员表里、主是谁）。 */
    private volatile RaftClusterView leaderView;

    public RaftClient(RaftClusterConfig config, RaftClusterConnector connector) {
        this.config = config;
        this.connector = connector;
    }

    /**
     * 已知主的管理地址。
     */
    public String leaderAddress() {
        return leaderAddress;
    }

    /**
     * 直接指定主的管理地址（省掉一次发现）。
     *
     * <p>「集群已满只能当客户端」的节点在加入应答里已经知道主在哪，
     * 用这个把它灌进来 —— 提案就不用再撒网了。</p>
     */
    public void setLeaderAddress(String address) {
        this.leaderAddress = address;
    }

    /**
     * 缓存的主的集群视图；还没拿到时是 null。
     */
    public RaftClusterView leaderView() {
        return leaderView;
    }

    /**
     * 拉一次主的集群视图。
     *
     * <p><b>只在已知主地址时调用</b>：地址不对就是一次失败的请求，
     * 不会像撒网那样把整个网段扫一遍。</p>
     */
    public RaftClusterView fetchLeaderView() {
        String addr = leaderAddress;
        if (addr == null) {
            return null;
        }
        RaftClusterView v = connector.view(addr);
        if (v != null && v.leaderManagerAddress() != null) {
            leaderView = v;
        }
        return leaderView;
    }

    /**
     * 发现主。
     *
     * <p>顺序是「先问已知的、再并发广播、最后逐个兜底」——
     * 网段里有几百个候选时，逐个问会拖到几十秒，并发广播只要一两秒。</p>
     *
     * @return 主的管理地址；没找到时返回 {@code null}
     */
    public String discoverLeader() {
        // 1) 已知主：先问它（一次请求，最快）
        String known = leaderAddress;
        if (known != null) {
            RaftClusterView v = connector.view(known);
            if (v != null && v.leaderManagerAddress() != null) {
                leaderView = v;
                return known;
            }
            // 主可能已经换了
            leaderAddress = null;
        }
        List<String> candidates = RaftSeedSet.expand(config.seeds, config.managerPort,
                null, 0, config.maxCandidates);
        // 2) 并发广播 CLAIM：主会回 LEADER_ACK（带主的管理地址）
        RaftClaimMessage claim = new RaftClaimMessage(null, 0, config.managerPort, null);
        for (RaftClaimReply r : connector.broadcastClaim(claim, candidates)) {
            if (r != null && r.getType() == RaftClaimReply.Type.LEADER_ACK
                    && r.leaderManagerAddress() != null) {
                leaderAddress = r.leaderManagerAddress();
                RaftClusterView v = connector.view(leaderAddress);
                if (v != null) {
                    leaderView = v;
                }
                return leaderAddress;
            }
        }
        // 3) 兜底：逐个问视图
        for (String addr : candidates) {
            RaftClusterView v = connector.view(addr);
            if (v != null && v.leaderManagerAddress() != null) {
                leaderAddress = v.leaderManagerAddress();
                leaderView = v;
                return leaderAddress;
            }
        }
        return null;
    }

    /**
     * 提交一次提案。
     *
     * <p>失败会自动重试：重新发现主 → <b>同一个 requestId</b> 再提交。
     * 主按 requestId 去重，所以重试不会让业务状态机应用两次。</p>
     *
     * @param payload 业务载荷
     * @return 主给的应答（{@code duplicate=true} 表示这个 requestId 早先就应用过了）
     * @throws RaftException 超时仍未成功
     */
    public RaftProposalResult propose(byte[] payload) throws RaftException {
        return propose(nextRequestId(), payload);
    }

    /**
     * 提交一次提案，<b>requestId 由调用方给</b>。
     *
     * <p>调用方自己带 requestId 的意义：重试时用同一个 id，
     * 主按 id 去重 ⇒ 业务状态机只应用一次。
     * 容器管理端口的 {@code /raft/propose} 就是这么用的 ——
     * 客户端的 id 必须原样传到主那里。</p>
     *
     * @param requestId 提案 id（重试时用同一个）
     * @param payload   业务载荷
     * @return 主给的应答（{@code duplicate=true} 表示这个 requestId 早先就应用过了）
     * @throws RaftException 超时仍未成功
     */
    public RaftProposalResult propose(long requestId, byte[] payload) throws RaftException {
        String base64 = Base64.getEncoder().encodeToString(payload == null ? new byte[0] : payload);
        long deadline = System.currentTimeMillis() + config.proposalTimeoutMillis;
        Throwable last = null;
        while (System.currentTimeMillis() < deadline) {
            String leader = leaderAddress;
            if (leader == null) {
                leader = discoverLeader();
            }
            if (leader != null) {
                RaftProposalResult r = connector.propose(leader,
                        new RaftProposalRequest(requestId, base64));
                if (r != null && r.isOk()) {
                    return r;
                }
                if (r != null) {
                    last = new RaftException("提案失败：" + r.getMessage());
                } else {
                    last = new RaftException("提案失败：连不上主 " + leader);
                }
                // 主可能已经换了：丢掉缓存，重新发现
                leaderAddress = null;
            } else {
                last = new RaftException("找不到主");
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RaftException("提案被中断", e);
            }
        }
        throw new RaftException("提案超时（" + config.proposalTimeoutMillis + "ms）："
                + (last == null ? "未知原因" : last.getMessage()), last);
    }

    /**
     * 客户端的 requestId：最高位恒为 0，与本地提案的空间隔开。
     */
    private long nextRequestId() {
        return ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE;
    }
}
