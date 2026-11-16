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

/**
 * 集群目录的<b>入站</b>侧：回答「别的节点问过来」的请求。
 *
 * <p>实现者是 {@link RaftClusterNode} 自己 —— 它就是那个「被问的一方」。
 * 容器侧把它挂到 HTTP 路由上（{@code /raft/claim}、{@code /raft/join}），
 * 测试里直接调。</p>
 *
 * <p><b>与 {@link RaftClusterConnector} 的分工</b>：入站只管「我被问到时怎么答」，
 * 出站（扇出广播、请求加入）走 {@link RaftClusterConnector}。
 * 两个方向拆成两个接口，是为了容器侧能用 HTTP 实现出站、
 * 而入站只是几个纯内存方法 —— 不用让容器侧实现一个「既收又发」的胖接口。</p>
 */
public interface RaftClusterDirectory {

    /**
     * 回答一次启动广播（CLAIM）。
     *
     * <p>调用方是别的节点，它想知道「你已经有主了吗」。</p>
     *
     * @param request 广播方宣告的地址
     * @return 三态应答：我是主 / 我也在启动 / 集群已满
     */
    RaftClaimReply claim(RaftClaimMessage request);

    /**
     * 回答一次加入请求（JOIN）。
     *
     * <p>只有主会真正处理；非主返回 {@link RaftJoinResult.Status#NOT_LEADER}
     * 并带上真正的主在哪。</p>
     *
     * @param request 加入方的地址
     * @return 加入结果
     */
    RaftJoinResult join(RaftJoinRequest request);
}
