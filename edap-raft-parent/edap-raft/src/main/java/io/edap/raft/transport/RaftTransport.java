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

package io.edap.raft.transport;

import io.edap.raft.Peer;
import io.edap.raft.protocol.Message;

/**
 * Raft 节点之间的传输层 SPI。
 *
 * <p>实现必须满足两点：</p>
 * <ol>
 *     <li>{@link #send} 不能阻塞调用方（调用方是 raft 状态机线程），
 *         发送失败只需要丢弃消息，raft 协议本身允许消息丢失</li>
 *     <li>收到消息后通过 {@link RaftMessageHandler} 回调，线程安全由实现保证</li>
 * </ol>
 */
public interface RaftTransport {

    /**
     * 设置入站消息处理器，必须在 {@link #start()} 之前调用
     */
    void setHandler(RaftMessageHandler handler);

    /**
     * 启动传输层
     */
    void start();

    /**
     * 停止传输层，释放端口与线程
     */
    void stop();

    /**
     * 给对端发送一条消息。实现不保证送达，失败时静默丢弃。
     */
    void send(Peer peer, Message message);
}
