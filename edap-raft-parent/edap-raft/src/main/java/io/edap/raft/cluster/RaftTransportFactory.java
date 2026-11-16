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

import io.edap.raft.transport.RaftTransport;

/**
 * 传输层的创建工厂。
 *
 * <p>生产用 {@code io.edap.raft.transport.NioRaftTransport}，
 * 单测用 {@code io.edap.raft.transport.InMemoryRaftTransport}
 * （多个节点共享一个 {@code InMemoryNetwork}，不走 socket）。</p>
 *
 * <p>做成工厂而不是在编排层里 new，是为了让编排层<b>不认识任何具体传输实现</b> ——
 * 否则 raft 模块就把「socket」这件事写死了，测不出多节点交互。</p>
 */
public interface RaftTransportFactory {

    /**
     * 创建一个传输层。
     *
     * @param nodeId 本节点的 raft 节点 id
     * @param host   监听地址
     * @param port   监听端口（raft 传输端口，不是 HTTP 管理端口）
     * @return 传输层；调用方负责 {@code setHandler} / {@code start} / {@code stop}
     */
    RaftTransport create(long nodeId, String host, int port);
}
