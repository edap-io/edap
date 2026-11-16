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
 * 启动广播的请求体：新节点上线时向候选地址宣告「我在这儿，我要入集群」。
 *
 * <p>语义上是「广播」，实现上��可靠传输（HTTP 扇出）——
 * UDP 广播在容器网络里常被 iptables 拦掉，而 TCP 一定通。</p>
 */
public class RaftClaimMessage {

    /** 本节点的 raft 传输地址里的 host */
    private String host;

    /** 本节点的 raft 传输端口 */
    private int    port;

    /** 本节点的 HTTP 管理端口 */
    private int    managerPort;

    /**
     * 本次启动的唯一标识。
     *
     * <p>用来识别「同一个进程重复广播」与「另一个进程在广播」——
     * 同一次启动重试时 instanceId 不变。</p>
     */
    private String instanceId;

    public RaftClaimMessage() {
    }

    public RaftClaimMessage(String host, int port, int managerPort, String instanceId) {
        this.host = host;
        this.port = port;
        this.managerPort = managerPort;
        this.instanceId = instanceId;
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

    /**
     * 本节点的 HTTP 管理地址，形如 {@code host:managerPort}。
     */
    public String managerAddress() {
        return host + ":" + managerPort;
    }

    @Override
    public String toString() {
        return "RaftClaimMessage{" + managerAddress() + ", instance=" + instanceId + "}";
    }
}
