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
 * 启动广播的应答，有三种形态。
 *
 * <ul>
 *   <li>{@link Type#LEADER_ACK}：我是主，来找我加入；</li>
 *   <li>{@link Type#PEER_CLAIM}：我也在启动（还没主），记下我的地址参与仲裁；</li>
 *   <li>{@link Type#REJECT}：集群已满，别加入。</li>
 * </ul>
 */
public class RaftClaimReply {

    public enum Type {
        /** 我是主 */
        LEADER_ACK,
        /** 我也在启动 */
        PEER_CLAIM,
        /** 集群已满 */
        REJECT
    }

    private Type   type;

    /** leader 的 host（LEADER_ACK / REJECT 时有值） */
    private String leaderHost;

    /** leader 的 raft 传输端口 */
    private int    leaderPort;

    /** leader 的 HTTP 管理端口 */
    private int    leaderManagerPort;

    /** 同伴的 host（PEER_CLAIM 时有值） */
    private String peerHost;

    /** 同伴的 raft 传输端口 */
    private int    peerPort;

    /** 同伴的 HTTP 管理端口 */
    private int    peerManagerPort;

    public RaftClaimReply() {
    }

    public RaftClaimReply(Type type) {
        this.type = type;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
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

    public String getPeerHost() {
        return peerHost;
    }

    public void setPeerHost(String peerHost) {
        this.peerHost = peerHost;
    }

    public int getPeerPort() {
        return peerPort;
    }

    public void setPeerPort(int peerPort) {
        this.peerPort = peerPort;
    }

    public int getPeerManagerPort() {
        return peerManagerPort;
    }

    public void setPeerManagerPort(int peerManagerPort) {
        this.peerManagerPort = peerManagerPort;
    }

    /**
     * leader 的 HTTP 管理地址，形如 {@code host:managerPort}。没有时返回 null。
     */
    public String leaderManagerAddress() {
        if (leaderHost == null || leaderHost.isEmpty()) {
            return null;
        }
        return leaderHost + ":" + leaderManagerPort;
    }

    /**
     * 同伴的 HTTP 管理地址，形如 {@code host:managerPort}。没有时返回 null。
     */
    public String peerManagerAddress() {
        if (peerHost == null || peerHost.isEmpty()) {
            return null;
        }
        return peerHost + ":" + peerManagerPort;
    }

    @Override
    public String toString() {
        return "RaftClaimReply{" + type + ", leader=" + leaderManagerAddress()
                + ", peer=" + peerHost + "}";
    }
}
