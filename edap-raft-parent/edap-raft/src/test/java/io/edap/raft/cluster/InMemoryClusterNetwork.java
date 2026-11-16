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

import io.edap.raft.transport.InMemoryNetwork;
import io.edap.raft.transport.InMemoryRaftTransport;
import io.edap.raft.transport.RaftTransport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 单 JVM 内的「集群网络」：把编排层的 HTTP 动作与 raft 的消息都放在内存里。
 *
 * <p>单测不依赖 socket，也不依赖 HTTP —— 编排层的两个 SPI
 * （{@link RaftClusterDirectory} 与 {@link RaftClusterConnector}）
 * 在这里都有内存实现。</p>
 *
 * <p><b>可见性</b>：{@link #setVisibility} 可以让某个节点「看不见」某些同伴，
 * 用来验证「部分可见也不分裂」。</p>
 */
public class InMemoryClusterNetwork {

    /** 管理地址 → 节点 */
    private final Map<String, RaftClusterNode> nodes = new ConcurrentHashMap<>();

    /** raft 传输网络 */
    private final InMemoryNetwork transportNetwork = new InMemoryNetwork();

    /** 可见性：地址 → 可见的地址集合；没有记录就是全可见 */
    private final Map<String, Set<String>> visibility = new ConcurrentHashMap<>();

    /** 广播扇出用的线程池 */
    private final ExecutorService fanout = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "raft-test-fanout");
        t.setDaemon(true);
        return t;
    });

    public void register(String address, RaftClusterNode node) {
        nodes.put(address, node);
    }

    public void unregister(String address) {
        nodes.remove(address);
    }

    public RaftClusterNode node(String address) {
        return nodes.get(address);
    }

    /**
     * 让 {@code from} 只能看见 {@code to} 里的地址。
     */
    public void setVisibility(String from, String... to) {
        visibility.put(from, new HashSet<>(Arrays.asList(to)));
    }

    public boolean visible(String from, String to) {
        Set<String> v = visibility.get(from);
        return v == null || v.contains(to);
    }

    public void shutdown() {
        fanout.shutdownNow();
    }

    /**
     * 传输层工厂：所有节点共享一个 {@link InMemoryNetwork}。
     */
    public RaftTransportFactory transportFactory() {
        return new RaftTransportFactory() {
            @Override
            public RaftTransport create(long nodeId, String host, int port) {
                return new InMemoryRaftTransport(transportNetwork, nodeId);
            }
        };
    }

    /**
     * 出站 SPI 的内存实现。
     */
    public RaftClusterConnector connector() {
        return new RaftClusterConnector() {

            @Override
            public List<RaftClaimReply> broadcastClaim(RaftClaimMessage request,
                                                       List<String> candidates) {
                if (candidates == null || candidates.isEmpty()) {
                    return Collections.emptyList();
                }
                String from = request == null ? null : request.managerAddress();
                List<Future<RaftClaimReply>> futures = new ArrayList<>();
                for (String addr : candidates) {
                    // 发起方看不见的候选：当作没应答（等价于网络不通）
                    if (from != null && !visible(from, addr)) {
                        continue;
                    }
                    futures.add(fanout.submit(() -> {
                        RaftClusterNode n = nodes.get(addr);
                        if (n == null) {
                            return null;
                        }
                        return n.claim(request);
                    }));
                }
                List<RaftClaimReply> out = new ArrayList<>();
                for (Future<RaftClaimReply> f : futures) {
                    try {
                        RaftClaimReply r = f.get(5, TimeUnit.SECONDS);
                        if (r != null) {
                            out.add(r);
                        }
                    } catch (Exception e) {
                        // 没应答是常态
                    }
                }
                return out;
            }

            @Override
            public RaftJoinResult join(String address, RaftJoinRequest request) {
                RaftClusterNode n = nodes.get(address);
                if (n == null) {
                    return null;
                }
                return n.join(request);
            }

            @Override
            public RaftClusterView view(String address) {
                RaftClusterNode n = nodes.get(address);
                return n == null ? null : n.view();
            }

            @Override
            public RaftProposalResult propose(String address, RaftProposalRequest request) {
                RaftClusterNode n = nodes.get(address);
                if (n == null) {
                    return null;
                }
                if (!n.isLeader()) {
                    RaftProposalResult r = RaftProposalResult.failure("不是主");
                    RaftClusterView v = n.view();
                    if (v != null && v.leaderManagerAddress() != null) {
                        r.setMessage("不是主；主在 " + v.leaderManagerAddress());
                    }
                    return r;
                }
                byte[] payload = request.getPayloadBase64() == null
                        ? new byte[0]
                        : Base64.getDecoder().decode(request.getPayloadBase64());
                try {
                    n.proposeWithAck(request.getRequestId(), payload).get(10, TimeUnit.SECONDS);
                    return RaftProposalResult.success(false);
                } catch (Exception e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    return RaftProposalResult.failure(String.valueOf(cause.getMessage()));
                }
            }
        };
    }
}
