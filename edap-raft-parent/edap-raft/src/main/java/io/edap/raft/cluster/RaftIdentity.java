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
 * 本节点的身份，落在 {@code dataDir/node.json}。
 *
 * <p><b>为什么必须持久化</b>：nodeId 是集群内唯一的逻辑标识，
 * 重启之后必须还是同一个 —— 否则成员表里会出现「两个 id 相同/不同」的混乱，
 * 而且旧的成员变更条目会把新节点当成另一个节点。</p>
 *
 * <p>身份一旦存在，重启就走「恢复」路径：不再重新加入，
 * 而是从 raft 日志里恢复成员配置（{@code Node.restartNode}）。</p>
 */
public class RaftIdentity {

    /** raft 节点 id */
    private long   id;

    /** 本节点的 host */
    private String host;

    /** raft 传输端口 */
    private int    port;

    /** HTTP 管理端口 */
    private int    managerPort;

    /** 本次启动的实例标识（每次进程启动都会变） */
    private String instanceId;

    public RaftIdentity() {
    }

    public RaftIdentity(long id, String host, int port, int managerPort, String instanceId) {
        this.id = id;
        this.host = host;
        this.port = port;
        this.managerPort = managerPort;
        this.instanceId = instanceId;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getManagerPort() {
        return managerPort;
    }

    public void setManagerPort(int managerPort) {
        this.managerPort = managerPort;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    @Override
    public String toString() {
        return "RaftIdentity{" + id + "@" + host + ":" + port
                + ", manager=" + managerPort + "}";
    }
}
