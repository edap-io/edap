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

import io.edap.raft.protocol.Message;

/**
 * 入站消息的回调。
 *
 * <p>实现方（{@link io.edap.raft.Node}）收到消息后必须尽快返回，不能在回调里做阻塞操作，
 * 应当投递到自己的状态机线程上处理。</p>
 */
public interface RaftMessageHandler {

    /**
     * 收到一条来自其他节点的消息
     */
    void onMessage(Message message);
}
