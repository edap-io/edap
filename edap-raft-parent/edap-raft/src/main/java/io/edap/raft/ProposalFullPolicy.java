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

package io.edap.raft;

/**
 * 提案队列满时的处理方式。
 *
 * <p>对应 {@link RaftConfig#proposalFullPolicy}，只影响<b>同步</b>提案路径
 * （{@link Node#propose(byte[])} / {@link Node#step(io.edap.raft.protocol.Message)}）。
 * 异步提案路径（{@link Node#proposeAsync(byte[])}）永远拒绝，
 * 传输层回调路径（{@link Node#onMessage(io.edap.raft.protocol.Message)}）
 * 永远丢弃 —— 后两条路径不能阻塞调用方。</p>
 */
public enum ProposalFullPolicy {

    /**
     * 队列满时阻塞等待腾出位置，默认值。
     *
     * <p>提案不会丢，只是被推迟。这与「没有 leader 时提案会阻塞」的现有契约一致。</p>
     */
    BLOCK,

    /**
     * 队列满时立刻拒绝，抛 {@link RaftBusyException}。
     *
     * <p>语义与 dragonboat 的 {@code ErrSystemBusy} 对齐：调用方拿到明确的
     * 「系统繁忙」，可以自己决定重试还是退避。</p>
     */
    REJECT
}
