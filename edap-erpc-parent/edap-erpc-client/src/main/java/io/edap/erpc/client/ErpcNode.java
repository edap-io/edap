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

package io.edap.erpc.client;

import java.util.Objects;

/**
 * 一个 eRPC 服务节点（服务端地址 + 权重）。
 *
 * <p><b>节点列表不是构造参数，而是从注册中心查出来的</b> ——
 * 见 {@link ErpcNodeProvider}。</p>
 */
public final class ErpcNode {

    private final String host;
    private final int    port;
    private final int    weight;

    /**
     * @param host   服务端地址
     * @param port   服务端端口
     * @param weight 权重（用于加权均衡，必须为正）
     */
    public ErpcNode(String host, int port, int weight) {
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("eRPC 节点地址不能为空");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("eRPC 节点端口非法: " + port);
        }
        if (weight <= 0) {
            throw new IllegalArgumentException("eRPC 节点权重必须为正: " + weight);
        }
        this.host = host;
        this.port = port;
        this.weight = weight;
    }

    /**
     * 默认权重 1。
     */
    public ErpcNode(String host, int port) {
        this(host, port, 1);
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public int getWeight() {
        return weight;
    }

    /**
     * 节点标识 —— {@code host:port}。注册中心的节点列表里同一个标识只会对应一个节点。
     */
    public String id() {
        return host + ":" + port;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ErpcNode)) {
            return false;
        }
        ErpcNode other = (ErpcNode) o;
        return port == other.port && host.equals(other.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port);
    }

    @Override
    public String toString() {
        return "ErpcNode{" + id() + ", weight=" + weight + "}";
    }
}
