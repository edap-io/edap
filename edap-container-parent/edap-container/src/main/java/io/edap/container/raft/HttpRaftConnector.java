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

import io.edap.json.Eson;
import io.edap.log.Logger;
import io.edap.log.LoggerManager;
import io.edap.raft.cluster.RaftClaimMessage;
import io.edap.raft.cluster.RaftClaimReply;
import io.edap.raft.cluster.RaftClusterConfig;
import io.edap.raft.cluster.RaftClusterConnector;
import io.edap.raft.cluster.RaftClusterView;
import io.edap.raft.cluster.RaftJoinRequest;
import io.edap.raft.cluster.RaftJoinResult;
import io.edap.raft.cluster.RaftProposalRequest;
import io.edap.raft.cluster.RaftProposalResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 编排层出站 SPI 的 HTTP 实现。
 *
 * <p>地址一律是 {@code host:managerPort}（容器的管理端口，默认 1111）——
 * 编排动作（广播 CLAIM、加入、查视图、提案）都走 HTTP，
 * raft 协议本身走另一个端口（9092）。</p>
 *
 * <p><b>★ 必须用 HTTP/1.1</b>：JDK 的 HttpClient 默认会发 h2c upgrade 探测，
 * 而 edap 的 HttpServer 不做 HTTP/2 ⇒ 探测请求会让服务端挂住。
 * （见 memory {@code project_edap_httpclient_vs_edap_keepalive}）</p>
 *
 * <p><b>广播要并发</b>：一个 C 段网段展开出 254 个候选，
 * 串行探测最坏 254 × 200ms = 50 秒；并发 32 时是 1.6 秒。</p>
 */
public class HttpRaftConnector implements RaftClusterConnector {

    private static final Logger log = LoggerManager.getLogger(HttpRaftConnector.class);

    private final RaftClusterConfig config;

    private final HttpClient client;

    /** 广播扇出的线程池。 */
    private final ExecutorService fanout;

    public HttpRaftConnector(RaftClusterConfig config) {
        this.config = config;
        this.client = HttpClient.newBuilder()
                // ★ HTTP/1.1：JDK 默认的 h2c upgrade 会让 edap 的 HttpServer 挂住
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(Math.max(1, config.probeTimeoutMillis)))
                .build();
        int concurrency = Math.max(1, config.probeConcurrency);
        this.fanout = Executors.newFixedThreadPool(concurrency, r -> {
            Thread t = new Thread(r, "raft-http-fanout");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public List<RaftClaimReply> broadcastClaim(RaftClaimMessage request, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new ArrayList<>();
        }
        long timeout = Math.max(1, config.probeTimeoutMillis);
        // ★ 用 CompletionService：按「完成顺序」取结果，而不是按提交顺序。
        //   提交顺序取的话，主在第 200 个候选上应答，也要等前 200 个都超时（1.6 秒）
        //   才轮得到它 —— 「已经有主」这种最常见的情况反而最慢。
        ExecutorCompletionService<RaftClaimReply> ecs = new ExecutorCompletionService<>(fanout);
        List<Future<RaftClaimReply>> submitted = new ArrayList<>(candidates.size());
        for (String addr : candidates) {
            submitted.add(ecs.submit(() -> post(addr, "/raft/claim", request,
                    RaftClaimReply.class, timeout)));
        }
        List<RaftClaimReply> out = new ArrayList<>();
        for (int i = 0; i < submitted.size(); i++) {
            Future<RaftClaimReply> f;
            try {
                f = ecs.poll(timeout + 500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (f == null) {
                // 这一轮后面的候选还在排队，不等了
                break;
            }
            RaftClaimReply r;
            try {
                r = f.get();
            } catch (Exception e) {
                // 没应答是常态：网段里大部分地址根本没人在跑
                continue;
            }
            if (r == null) {
                continue;
            }
            out.add(r);
            if (r.getType() == RaftClaimReply.Type.LEADER_ACK) {
                // ★ 已经有主了：其余候选不用再等（扫完一个 C 段要 1.6 秒），
                //   而且要把还在排队的探测撤掉 —— 否则下一轮广播要排在它们后面。
                for (Future<RaftClaimReply> s : submitted) {
                    if (!s.isDone()) {
                        s.cancel(true);
                    }
                }
                return out;
            }
        }
        return out;
    }

    @Override
    public RaftJoinResult join(String address, RaftJoinRequest request) {
        return post(address, "/raft/join", request, RaftJoinResult.class,
                Math.max(1, config.probeTimeoutMillis));
    }

    @Override
    public RaftClusterView view(String address) {
        return get(address, "/raft/cluster", RaftClusterView.class,
                Math.max(1, config.probeTimeoutMillis));
    }

    @Override
    public RaftProposalResult propose(String address, RaftProposalRequest request) {
        // 提案要等主提交 + 应用，默认 20 秒
        return post(address, "/raft/propose", request, RaftProposalResult.class,
                Math.max(1, config.proposalTimeoutMillis));
    }

    /**
     * 关掉扇出线程池。
     */
    public void shutdown() {
        fanout.shutdownNow();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private <T> T post(String address, String path, Object body, Class<T> type, long timeoutMillis) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + address + path))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(Eson.toJsonBytes(body)))
                    .build();
            return handle(client.send(req, HttpResponse.BodyHandlers.ofString()), type);
        } catch (Exception e) {
            // ★ 带上目标地址：网段扫描里失败是常态，没有地址根本没法定位
            log.debug("raft.http: POST {} {} 失败：{}", l -> l.arg(path).arg(address).arg(e.getMessage()));
            return null;
        }
    }

    private <T> T get(String address, String path, Class<T> type, long timeoutMillis) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://" + address + path))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .GET()
                    .build();
            return handle(client.send(req, HttpResponse.BodyHandlers.ofString()), type);
        } catch (Exception e) {
            log.debug("raft.http: GET {} {} 失败：{}", l -> l.arg(path).arg(address).arg(e.getMessage()));
            return null;
        }
    }

    private <T> T handle(HttpResponse<String> resp, Class<T> type) {
        int code = resp.statusCode();
        if (code / 100 != 2) {
            log.debug("raft.http: 状态码 {}", l -> l.arg(code));
            return null;
        }
        String body = resp.body();
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            return Eson.parseObject(body.getBytes(StandardCharsets.UTF_8), type);
        } catch (Exception e) {
            log.warn("raft.http: 解析应答失败：{}", l -> l.arg(e.getMessage()));
            return null;
        }
    }
}
