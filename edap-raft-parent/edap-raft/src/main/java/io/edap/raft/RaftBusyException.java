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
 * 提案队列已满，提案被拒绝。
 *
 * <p>语义与 dragonboat 的 {@code ErrSystemBusy} 对齐。</p>
 *
 * <p><b>出现范围</b>：</p>
 * <ul>
 *     <li>异步提案 {@link Node#proposeAsync(byte[])}：<b>总是</b>用这个异常
 *         结束 future（异步路径不阻塞调用方，队列满了只能拒绝）</li>
 *     <li>同步提案 {@link Node#propose(byte[])}：只有把
 *         {@link RaftConfig#proposalFullPolicy} 设成
 *         {@link ProposalFullPolicy#REJECT} 才会出现</li>
 * </ul>
 *
 * <p><b>不要把同步路径的默认策略改成 {@code REJECT}</b>：
 * 默认的 {@link ProposalFullPolicy#BLOCK} 与「没有 leader 时提案阻塞」的
 * 现有契约一致，调用方不需要处理异常；改成 REJECT 会让现有代码突然多出
 * 一个必须处理的异常分支。</p>
 */
public class RaftBusyException extends RaftException {

    public RaftBusyException(String message) {
        super(message);
    }

    public RaftBusyException(String message, Throwable cause) {
        super(message, cause);
    }
}
