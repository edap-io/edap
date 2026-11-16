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
 * 集群成员视图：id + 两个端口。
 *
 * <p>两个端口的分工：</p>
 * <ul>
 *   <li><b>{@code port}</b>：raft 传输层端口，节点之间传 raft 消息用；</li>
 *   <li><b>{@code managerPort}</b>：HTTP 管理端口，节点之间做「发现 / 加入 /
 *       提案」这类编排动作时用。</li>
 * </ul>
 *
 * <p>用标准 JavaBean 写法：容器侧要把它直接序列化成 JSON 传给对端。</p>
 */
public class RaftMember {

    private long   id;
    private String host;
    private int    port;
    private int    managerPort;

    public RaftMember() {
    }

    public RaftMember(long id, String host, int port, int managerPort) {
        this.id = id;
        this.host = host;
        this.port = port;
        this.managerPort = managerPort;
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

    /**
     * raft 传输地址，形如 {@code host:port}。
     */
    public String raftAddress() {
        return host + ":" + port;
    }

    /**
     * HTTP 管理地址，形如 {@code host:managerPort}。
     */
    public String managerAddress() {
        return host + ":" + managerPort;
    }

    @Override
    public String toString() {
        return "RaftMember{" + id + "@" + raftAddress() + ", manager=" + managerAddress() + "}";
    }
}
