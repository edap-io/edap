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

import java.util.ArrayList;
import java.util.List;

/**
 * 加入集群的应答。
 *
 * <p>三种状态：</p>
 * <ul>
 *   <li>{@link Status#ACCEPTED}：加入了。{@code nodeId} 是分到的节点 id，
 *       {@code leader*} 是现任主的地址（加入方要靠它回 {@code MsgAppResp}），
 *       {@code members} 是当前成员表；</li>
 *   <li>{@link Status#REJECTED_FULL}：集群已满 ⇒ 调用方应当转成 client-only；</li>
 *   <li>{@link Status#NOT_LEADER}：问错了人 ⇒ 带上真正的主在哪，让调用方改投。</li>
 * </ul>
 */
public class RaftJoinResult {

    public enum Status {
        /** 加入成功 */
        ACCEPTED,
        /** 集群已满 */
        REJECTED_FULL,
        /** 对方不是主 */
        NOT_LEADER
    }

    private Status           status;

    /** 分到的节点 id（ACCEPTED 时有效） */
    private long             nodeId;

    /** 主的 host */
    private String           leaderHost;

    /** 主的 raft 传输端口 */
    private int              leaderPort;

    /** 主的 HTTP 管理端口 */
    private int              leaderManagerPort;

    /** 当前成员表 */
    private List<RaftMember> members = new ArrayList<>();

    /** 成员数上限 */
    private int              capacity;

    public RaftJoinResult() {
    }

    public RaftJoinResult(Status status) {
        this.status = status;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public long getNodeId() {
        return nodeId;
    }

    public void setNodeId(long nodeId) {
        this.nodeId = nodeId;
    }

    public String getLeaderHost() {
        return leaderHost;
    }

    public void setLeaderHost(String leaderHost) {
        this.leaderHost = leaderHost;
    }

    public int getLeaderPort() {
        return leaderPort;
    }

    public void setLeaderPort(int leaderPort) {
        this.leaderPort = leaderPort;
    }

    public int getLeaderManagerPort() {
        return leaderManagerPort;
    }

    public void setLeaderManagerPort(int leaderManagerPort) {
        this.leaderManagerPort = leaderManagerPort;
    }

    public List<RaftMember> getMembers() {
        return members;
    }

    public void setMembers(List<RaftMember> members) {
        this.members = members == null ? new ArrayList<>() : members;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    /**
     * 主的 HTTP 管理地址，形如 {@code host:managerPort}。没有时返回 null。
     */
    public String leaderManagerAddress() {
        if (leaderHost == null || leaderHost.isEmpty()) {
            return null;
        }
        return leaderHost + ":" + leaderManagerPort;
    }

    /**
     * 主的 raft 传输地址，形如 {@code host:port}。没有时返回 null。
     */
    public String leaderAddress() {
        if (leaderHost == null || leaderHost.isEmpty()) {
            return null;
        }
        return leaderHost + ":" + leaderPort;
    }

    @Override
    public String toString() {
        return "RaftJoinResult{" + status + ", nodeId=" + nodeId
                + ", leader=" + leaderAddress() + ", members=" + members.size() + "}";
    }
}
