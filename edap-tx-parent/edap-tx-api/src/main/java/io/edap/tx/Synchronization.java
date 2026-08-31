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

package io.edap.tx;

/**
 * 事务同步点——事务生命周期的回调钩子(详见 TX_DESIGN.md §2.8)。
 *
 * <p>分布式事务的事件通知(消息发件箱/缓存清理/二阶段补偿)依赖此接口,
 * 因此 Phase 1 即定义,后续 manager 实现必须正确触发回调。</p>
 *
 * <p>回调顺序(commit 路径):</p>
 * <ol>
 *   <li>{@link #beforeCommit} — 所有同步点的提交前钩子(顺序注册顺序调用)</li>
 *   <li>resource commit()</li>
 *   <li>{@link #afterCommit} — 所有同步点的提交后钩子</li>
 *   <li>{@link #afterCompletion} — 所有同步点的完成钩子(status=COMMITTED)</li>
 * </ol>
 *
 * <p>rollback 路径:跳过 beforeCommit / afterCommit,只调用
 * {@link #afterCompletion(int)} status=ROLLED_BACK。</p>
 */
public interface Synchronization {

    /** 事务提交结束后状态:已提交。 */
    int STATUS_COMMITTED = 0;

    /** 事务回滚后状态:已回滚。 */
    int STATUS_ROLLED_BACK = 1;

    /** 事务状态未知(例如协调器挂掉后恢复)。 */
    int STATUS_UNKNOWN = 2;

    /**
     * 提交前钩子。抛出异常将阻止 commit 并触发 rollback。
     */
    default void beforeCommit() {}

    /**
     * 提交后钩子。已成功 commit 后调用;此处抛出的异常不影响已提交的事务状态,
     * 但会被 {@link io.edap.tx.exception.TransactionSystemException}
     * 包装后向上传递。
     */
    default void afterCommit() {}

    /**
     * 事务完成钩子(commit/rollback 后都调用)。
     *
     * @param status {@link #STATUS_COMMITTED} / {@link #STATUS_ROLLED_BACK} /
     *               {@link #STATUS_UNKNOWN}
     */
    default void afterCompletion(int status) {}
}
