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

package io.edap.raft;

/**
 * 节点在集群中的角色
 */
public enum RaftRole {

    /**
     * 跟随者，被动接收 leader 的日志
     */
    FOLLOWER("StateFollower"),

    /**
     * 候选者，正在发起选举
     */
    CANDIDATE("StateCandidate"),

    /**
     * 主节点，负责接收提案并复制给其他节点
     */
    LEADER("StateLeader"),

    /**
     * 预候选者，开启预投票时选举的第一阶段，此时任期还没有加一
     */
    PRE_CANDIDATE("StatePreCandidate");

    /**
     * 与 etcd 的 {@code StateType.String()} 一致的名字。
     */
    private final String label;

    RaftRole(String label) {
        this.label = label;
    }

    /**
     * 与 etcd 一致的角色名，例如 {@code StateLeader}。
     *
     * <p>etcd 的 {@code StateType.String()} 返回的就是这个名字，日志与调试输出
     * 都靠它，这里保持一致。</p>
     */
    @Override
    public String toString() {
        return label;
    }
}
