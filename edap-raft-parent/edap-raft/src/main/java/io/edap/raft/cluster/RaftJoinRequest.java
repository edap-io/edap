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
 * 加入集群的请求体。
 *
 * <p>请求里带的是<b>请求方的 raft 传输地址</b>：主需要知道往哪儿发
 * {@code MSG_APP}，也需要把它编进 {@code ConfChange} 的 context，
 * 好让其它成员也学到这个地址。</p>
 */
public class RaftJoinRequest {

    /** 请求方的 host */
    private String host;

    /** 请求方的 raft 传输端口 */
    private int    port;

    /** 请求方的 HTTP 管理端口 */
    private int    managerPort;

    /** 请求方的启动实例标识 */
    private String instanceId;

    public RaftJoinRequest() {
    }

    public RaftJoinRequest(String host, int port, int managerPort, String instanceId) {
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
     * 请求方的 raft 传输地址，形如 {@code host:port}。
     */
    public String raftAddress() {
        return host + ":" + port;
    }

    /**
     * 请求方的 HTTP 管理地址，形如 {@code host:managerPort}。
     */
    public String managerAddress() {
        return host + ":" + managerPort;
    }

    @Override
    public String toString() {
        return "RaftJoinRequest{" + raftAddress() + ", manager=" + managerAddress() + "}";
    }
}
