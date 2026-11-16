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
 * 集群节点在本编排层的角色。
 *
 * <p>与 {@link io.edap.raft.RaftRole} 的区别：raft 协议内部还有
 * CANDIDATE / PRE_CANDIPATE 这两个过渡态，对外暴露没有意义 ——
 * 编排层只关心「我是不是主」「我是集群成员吗」。</p>
 */
public enum RaftClusterState {

    /**
     * 正在启动：还没决定自己是主还是从。
     */
    BOOTSTRAPPING,

    /**
     * 集群主。
     */
    LEADER,

    /**
     * 集群成员（跟随者）。
     */
    FOLLOWER,

    /**
     * <b>不是</b>集群成员：集群已满（或被拒绝加入），本节点只做客户端。
     *
     * <p>这种节点不监听 raft 端口，也不参与投票，只通过 leader 的
     * HTTP 入口提交提案。</p>
     */
    CLIENT_ONLY
}
