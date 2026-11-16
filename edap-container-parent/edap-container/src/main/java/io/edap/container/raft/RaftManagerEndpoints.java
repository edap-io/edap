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

import io.edap.http.HttpHandler;
import io.edap.http.HttpRequest;
import io.edap.http.HttpResponse;
import io.edap.json.Eson;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.raft.cluster.RaftClaimMessage;
import io.edap.raft.cluster.RaftClaimReply;
import io.edap.raft.cluster.RaftClusterNode;
import io.edap.raft.cluster.RaftClusterState;
import io.edap.raft.cluster.RaftClusterView;
import io.edap.raft.cluster.RaftJoinRequest;
import io.edap.raft.cluster.RaftJoinResult;
import io.edap.raft.cluster.RaftProposalRequest;
import io.edap.raft.cluster.RaftProposalResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 容器管理端口上的四个 raft 端点。
 *
 * <table>
 *   <caption>端点</caption>
 *   <tr><th>路径</th><th>方法</th><th>作用</th></tr>
 *   <tr><td>{@code /raft/claim}</td><td>POST</td>
 *       <td>启动广播：新节点问「你们谁是主 / 有谁也在启动」。</td></tr>
 *   <tr><td>{@code /raft/join}</td><td>POST</td>
 *       <td>加入集群：只有主处理（分配 nodeId + 提成员变更）。</td></tr>
 *   <tr><td>{@code /raft/cluster}</td><td>GET</td>
 *       <td>集群视图：谁是主、有哪些成员 —— 客户端靠它发现主。</td></tr>
 *   <tr><td>{@code /raft/propose}</td><td>POST</td>
 *       <td>提案：只有主处理（编信封 → 提案 → apply 时回执）。</td></tr>
 * </table>
 *
 * <p><b>raft 节点还没起来时返回 503</b>：管理端口比 raft 节点先 listen
 * （Bootstrap 先建 manager server），这个窗口是正常的。</p>
 */
public class RaftManagerEndpoints {

    private static final Logger log = LoggerManager.getLogger(RaftManagerEndpoints.class);

    /** 当前容器里的 raft 节点；还没起来时是 null。 */
    private volatile RaftClusterNode node;

    /** 节点还没起来时用来应答 CLAIM 的本机地址（host）。 */
    private volatile String selfHost;

    /** 同上：raft 传输端口。 */
    private volatile int selfRaftPort;

    /** 同上：管理端口。 */
    private volatile int selfManagerPort;

    /**
     * 提前登记本机地址，让 CLAIM 在 raft 节点起来之前也能答。
     *
     * <p>★ <b>为什么必须提前</b>：管理端口比 raft 节点先监听
     * （Bootstrap 先建 manager server，容器起来后才拉 raft 节点）。
     * 这段时间里同伴来问「你是不是也在启动」，如果答 503，
     * 同伴就看不见我们 ⇒ 各自自举 ⇒ <b>出现两个主</b>。
     * 所以「我也在启动」这件事不能等 raft 节点就绪。</p>
     */
    public void prepareSelf(String host, int raftPort, int managerPort) {
        this.selfHost = host;
        this.selfRaftPort = raftPort;
        this.selfManagerPort = managerPort;
    }

    /**
     * 把 raft 节点绑上来（容器启动 raft 时调）。
     */
    public void bind(RaftClusterNode node) {
        this.node = node;
    }

    /**
     * 解绑（容器停止 raft 时调）。
     */
    public void unbind() {
        this.node = null;
    }

    /**
     * 当前绑着的 raft 节点。
     */
    public RaftClusterNode node() {
        return node;
    }

    // ------------------------------------------------------------------
    // 端点
    // ------------------------------------------------------------------

    /**
     * {@code POST /raft/claim}：启动广播。
     *
     * <p>应答三态：{@code LEADER_ACK}（我是主，带主的管理地址）、
     * {@code PEER_CLAIM}（我也在启动，带我的地址）、
     * {@code REJECT}（我只做客户端，不带新成员）。</p>
     *
     * <p><b>★ raft 节点还没起来时也答 {@code PEER_CLAIM}</b>：
     * 管理端口先于 raft 节点监听，这段时间里必须让同伴看见我，
     * 否则同时启动的容器互相看不见 ⇒ 各自自举 ⇒ 出现两个主。</p>
     */
    public HttpHandler claimHandler() {
        return this::claim;
    }

    public void claim(HttpRequest req, HttpResponse resp) throws IOException {
        RaftClusterNode n = node;
        if (n == null) {
            if (selfHost == null) {
                writeError(resp, 503, "raft 节点还没起来");
                return;
            }
            RaftClaimReply r = new RaftClaimReply(RaftClaimReply.Type.PEER_CLAIM);
            r.setPeerHost(selfHost);
            r.setPeerPort(selfRaftPort);
            r.setPeerManagerPort(selfManagerPort);
            write(resp, 200, Eson.toJsonBytes(r));
            return;
        }
        RaftClaimMessage message = parse(req, RaftClaimMessage.class);
        if (message == null) {
            writeError(resp, 400, "请求体不是合法的 JSON");
            return;
        }
        RaftClaimReply reply = n.claim(message);
        write(resp, 200, Eson.toJsonBytes(reply));
    }

    /**
     * {@code POST /raft/join}：加入集群。
     *
     * <p>非主会回「主在哪」，调用方改投。</p>
     */
    public HttpHandler joinHandler() {
        return this::join;
    }

    public void join(HttpRequest req, HttpResponse resp) throws IOException {
        RaftClusterNode n = node;
        if (n == null) {
            writeError(resp, 503, "raft 节点还没起来");
            return;
        }
        RaftJoinRequest request = parse(req, RaftJoinRequest.class);
        if (request == null) {
            writeError(resp, 400, "请求体不是合法的 JSON");
            return;
        }
        RaftJoinResult result;
        try {
            result = n.join(request);
        } catch (Throwable t) {
            log.warn("raft.http: 处理加入请求失败", l -> l.threw(t));
            writeError(resp, 500, "加入失败：" + t.getMessage());
            return;
        }
        write(resp, 200, Eson.toJsonBytes(result));
    }

    /**
     * {@code GET /raft/cluster}：集群视图。
     *
     * <p>客户端靠它发现主 —— 所以应答里必须带<b>主的管理地址</b>
     * （不只是 raft 的 9092）。</p>
     */
    public HttpHandler clusterHandler() {
        return this::cluster;
    }

    public void cluster(HttpRequest req, HttpResponse resp) throws IOException {
        RaftClusterNode n = node;
        if (n == null) {
            writeError(resp, 503, "raft 节点还没起来");
            return;
        }
        RaftClusterView view = n.view();
        write(resp, 200, Eson.toJsonBytes(view));
    }

    /**
     * {@code POST /raft/propose}：提案。
     *
     * <p><b>只有主处理</b>：非主回「不是主；主在哪」，客户端改投。
     * 重试用<b>同一个</b> requestId ⇒ 主按它去重，业务状态机只应用一次。</p>
     */
    public HttpHandler proposeHandler() {
        return this::propose;
    }

    public void propose(HttpRequest req, HttpResponse resp) throws IOException {
        RaftClusterNode n = node;
        if (n == null) {
            writeError(resp, 503, "raft 节点还没起来");
            return;
        }
        RaftProposalRequest request = parse(req, RaftProposalRequest.class);
        if (request == null) {
            writeError(resp, 400, "请求体不是合法的 JSON");
            return;
        }
        if (!n.isLeader() && n.state() != RaftClusterState.CLIENT_ONLY) {
            RaftProposalResult r = RaftProposalResult.failure("不是主");
            RaftClusterView view = n.view();
            if (view != null && view.leaderManagerAddress() != null) {
                r.setMessage("不是主；主在 " + view.leaderManagerAddress());
            }
            write(resp, 200, Eson.toJsonBytes(r));
            return;
        }
        // ★ 只做客户端的节点（集群满员之后启动的容器）也能提案：
        //   它把提案转给主（同一个 requestId ⇒ 幂等），主提交并应用后回执。
        byte[] payload;
        try {
            payload = request.getPayloadBase64() == null
                    ? new byte[0]
                    : java.util.Base64.getDecoder().decode(request.getPayloadBase64());
        } catch (IllegalArgumentException e) {
            writeError(resp, 400, "payload 不是合法的 base64");
            return;
        }
        RaftProposalResult result;
        try {
            java.util.concurrent.CompletableFuture<byte[]> future =
                    n.proposeWithAck(request.getRequestId(), payload);
            // 比节点自己的提案超时稍长一点：让节点的超时先触发，错误信息才准确
            future.get(n.proposalTimeoutMillis() + 1000, java.util.concurrent.TimeUnit.MILLISECONDS);
            // ★ duplicate 如实回：这个 requestId 早先就应用过了（重试命中）⇒ 业务只应用过一次
            result = RaftProposalResult.success(n.isDuplicateProposal(request.getRequestId()));
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            result = RaftProposalResult.failure(String.valueOf(cause.getMessage()));
        }
        write(resp, 200, Eson.toJsonBytes(result));
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private <T> T parse(HttpRequest req, Class<T> type) {
        try {
            byte[] body = req.getBody() == null ? null : req.getBody().toByteArray();
            if (body == null || body.length == 0) {
                return null;
            }
            return Eson.parseObject(body, type);
        } catch (Exception e) {
            log.warn("raft.http: 解析请求体失败", l -> l.threw(e));
            return null;
        }
    }

    private void write(HttpResponse resp, int code, byte[] data) throws IOException {
        resp.header("Content-Type", "application/json");
        resp.write(code, data);
    }

    private void writeError(HttpResponse resp, int code, String message) throws IOException {
        write(resp, code, ("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8));
    }
}
