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

import java.util.List;

/**
 * 集群目录的<b>出站</b>侧：向别的节点发请求。
 *
 * <p>容器侧用 HTTP 实现（见 {@code io.edap.container.raft.HttpRaftConnector}），
 * 测试用内存实现 —— 单测不依赖网络。</p>
 *
 * <p>地址一律是 {@code host:managerPort}（HTTP 管理端口），
 * 不是 raft 传输端口：编排动作都走 HTTP。</p>
 */
public interface RaftClusterConnector {

    /**
     * 向一批候选地址广播 CLAIM。
     *
     * <p>实现必须并发扇出，且<b>不能因为某个候选没应答就整体失败</b> ——
     * 没应答是常态（网段里大部分地址根本没人在跑）。</p>
     *
     * @param request   广播内容
     * @param candidates 候选的 HTTP 管理地址（{@code host:managerPort}）
     * @return 收到的应答（没应答的候选不出现在结果里）
     */
    List<RaftClaimReply> broadcastClaim(RaftClaimMessage request, List<String> candidates);

    /**
     * 向某个地址发 JOIN。
     *
     * @param address 主的 HTTP 管理地址（{@code host:managerPort}）
     * @param request 加入请求
     * @return 加入结果；通信失败时返回 {@code null}
     */
    RaftJoinResult join(String address, RaftJoinRequest request);

    /**
     * 查询某个地址的集群视图（谁是主）。
     *
     * @param address 目标节点的 HTTP 管理地址
     * @return 视图；通信失败时返回 {@code null}
     */
    RaftClusterView view(String address);

    /**
     * 向主提交一次提案。
     *
     * <p><b>只有主会真正处理</b>：非主会回「主在哪」，调用方改投。</p>
     *
     * @param address 主（或任意成员）的 HTTP 管理地址
     * @param request 提案请求（带 requestId 与 base64 载荷）
     * @return 提案结果；通信失败时返回 {@code null}
     */
    RaftProposalResult propose(String address, RaftProposalRequest request);
}
