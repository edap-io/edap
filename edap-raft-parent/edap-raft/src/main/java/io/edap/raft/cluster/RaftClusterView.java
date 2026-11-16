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
 * 集群视图：谁是主、有哪些成员、上限是多少。
 *
 * <p>客户端靠它发现 leader 并提交提案（见 {@link RaftClient}）。</p>
 */
public class RaftClusterView {

    private long              leaderId;
    private String            leaderHost;
    private int               leaderPort;
    private String            leaderManagerHost;
    private int               leaderManagerPort;
    private List<RaftMember>  members = new ArrayList<>();
    private int               capacity;

    public long getLeaderId() {
        return leaderId;
    }

    public void setLeaderId(long leaderId) {
        this.leaderId = leaderId;
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

    public String getLeaderManagerHost() {
        return leaderManagerHost;
    }

    public void setLeaderManagerHost(String leaderManagerHost) {
        this.leaderManagerHost = leaderManagerHost;
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
     * leader 的 HTTP 管理地址，形如 {@code host:managerPort}。
     * 没有 leader 时返回 null。
     */
    public String leaderManagerAddress() {
        if (leaderManagerHost == null || leaderManagerHost.isEmpty()) {
            return null;
        }
        return leaderManagerHost + ":" + leaderManagerPort;
    }

    /**
     * leader 的 raft 传输地址，形如 {@code host:port}。没有 leader 时返回 null。
     */
    public String leaderAddress() {
        if (leaderHost == null || leaderHost.isEmpty()) {
            return null;
        }
        return leaderHost + ":" + leaderPort;
    }

    @Override
    public String toString() {
        return "RaftClusterView{leader=" + leaderId + "@" + leaderAddress()
                + ", manager=" + leaderManagerAddress() + ", members=" + members.size()
                + ", capacity=" + capacity + "}";
    }
}
