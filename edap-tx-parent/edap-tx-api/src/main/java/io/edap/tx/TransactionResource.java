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

import io.edap.tx.exception.NestedTransactionNotSupportedException;
import io.edap.tx.exception.TransactionException;

/**
 * 事务资源抽象——代表一个事务持有的物理资源(本地事务时是 JDBC Connection,
 * XA 时是 {@code XA Xid + Connection},分布式事务时是 TCC 句柄等)。
 *
 * <p><b>为什么抽象资源而非直接传 Connection</b>:这是给分布式事务扩展的关键预制件——
 * {@code SeataTccResource} / {@code XaResource} 实现此接口即可复用所有传播模型
 * 和同步点逻辑(详见 TX_DESIGN.md §2.5)。</p>
 *
 * <p><b>默认方法语义</b>:本地事务资源无需关心 XA,默认方法已抛
 * {@code UnsupportedOperationException};XA 资源选择性实现。</p>
 */
public interface TransactionResource {

    /**
     * XA 一阶段: PREPARE。本地事务时此方法为空操作(默认实现)。
     *
     * <p>XA 实现:在绑定的 Connection 上执行 {@code XA PREPARE 'xid'},
     * 等所有分支 PREPARE 完成后由协调器通知 COMMIT。</p>
     */
    default void prepare() throws TransactionException {
        throw new UnsupportedOperationException(
                "not an XA resource: " + getClass().getName());
    }

    /**
     * 提交事务。本地事务时为 {@code Connection.commit()},
     * XA 时为 {@code XA COMMIT 'xid'}。
     */
    void commit() throws TransactionException;

    /**
     * 回滚事务。本地事务时为 {@code Connection.rollback()},
     * XA 时为 {@code XA ROLLBACK 'xid'} 或回滚到 savepoint。
     */
    void rollback() throws TransactionException;

    /**
     * 注册事务同步回调(afterCommit / afterCompletion)。
     *
     * <p>分布式事务事件通知(消息发件箱/缓存清理)依赖此钩子。</p>
     */
    void registerSynchronization(Synchronization sync) throws TransactionException;

    /**
     * 资源是否已被标记为 setRollbackOnly。
     */
    boolean isRollbackOnly();

    /**
     * 是否 XA 资源(本地事务返回 false)。
     */
    default boolean isXa() {
        return false;
    }

    /**
     * 当前资源是否已 PREPARE(XA 专用)。本地事务返回 false。
     */
    default boolean isPrepared() {
        return false;
    }

    /**
     * 创建 savepoint — NESTED 传播模型依赖。
     *
     * <p>本地 JDBC 资源:调用 {@code Connection.setSavepoint(name)} 并返回;
     * XA 资源:本方法不适用(XA 不支持嵌套,分布式事务的"嵌套"语义由协调器层 savepoint 处理)。</p>
     *
     * <p>默认抛 {@link NestedTransactionNotSupportedException}——
     * 仅支持 savepoint 的资源(本地 JDBC)应实现此方法。Phase 1 mock 实现可重写为 no-op
     * 以便测试 7×3 决策矩阵中 NESTED 的两条路径。</p>
     */
    default Object createSavepoint() throws TransactionException {
        throw new NestedTransactionNotSupportedException(
                "resource does not support savepoints: " + getClass().getName());
    }

    /**
     * 释放 savepoint — 提交该 savepoint 之后的逻辑变更。
     *
     * <p>本地 JDBC: {@code Connection.releaseSavepoint(savepoint)}。</p>
     */
    default void releaseSavepoint(Object savepoint) throws TransactionException {
        throw new NestedTransactionNotSupportedException(
                "resource does not support savepoints: " + getClass().getName());
    }

    /**
     * 回滚到指定 savepoint — 内层 NESTED 调用抛异常时,只回滚到该 savepoint,外层不受影响。
     *
     * <p>本地 JDBC: {@code Connection.rollback(savepoint)}。</p>
     */
    default void rollbackToSavepoint(Object savepoint) throws TransactionException {
        throw new NestedTransactionNotSupportedException(
                "resource does not support savepoints: " + getClass().getName());
    }
}
