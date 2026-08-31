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

import io.edap.tx.exception.IllegalTransactionStateException;
import io.edap.tx.exception.TransactionException;
import io.edap.tx.exception.TransactionSystemException;
import io.edap.tx.propagation.Propagation;

import java.util.List;

/**
 * Phase 1 默认事务管理器——仅本地事务,无 JDBC 依赖。
 *
 * <p><b>职责</b>:</p>
 * <ol>
 *   <li>实现 7×3=21 个传播决策矩阵场景(见 {@link #getTransaction})</li>
 *   <li>commit / rollback 嵌套计数管理 + 同步点回调触发</li>
 *   <li>REQUIRES_NEW / NOT_SUPPORTED 路径的挂起-恢复</li>
 * </ol>
 *
 * <p><b>Phase 1 边界</b>:本实现不绑定具体资源,资源由子类提供 {@link #doBegin} 实现;
 * Phase 2 的 {@code DataSourceTransactionManager} 重写 {@code doBegin} 返回真 JDBC 连接。
 * 这样 21 个决策矩阵测试可在本类上独立验证,不被 JDBC 行为差异污染。</p>
 *
 * <p><b>commit 路径顺序</b>(与 Spring 一致):</p>
 * <pre>
 *   1. beforeCommit (sync 列表顺序触发,任一抛异常 → rollback)
 *   2. resource.commit()            ← 物理 commit
 *   3. afterCommit                  ← 已提交,异常不阻止
 *   4. afterCompletion(STATUS_COMMITTED)
 * </pre>
 *
 * <p><b>rollback 路径</b>:跳过 beforeCommit / afterCommit,只调用
 * {@code afterCompletion(STATUS_ROLLED_BACK)}。</p>
 */
public class DefaultEdapTransactionManager implements EdapTransactionManager {

    /**
     * 资源创建钩子——子类实现,Phase 1 mock 返回 {@link MockTransactionResource},
     * Phase 2 真 JDBC 返回 {@code JdbcTransactionResource}。
     *
     * <p>返回 {@link TransactionResource} 接口而非具体类型,让本管理器只关心
     * 抽象语义,不被 JDBC API 锁死。</p>
     */
    protected TransactionResource doBegin(TransactionDefinition definition,
                                          Object resourceKey) throws TransactionException {
        // Phase 1 默认实现:抛错,迫使子类必须重写。语义上"没有资源的 manager" 不应存在。
        throw new TransactionSystemException(
                "DefaultEdapTransactionManager.doBegin() not implemented — "
                + "Phase 1 测试请使用 TestTransactionManager / 子类");
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition)
            throws TransactionException {
        if (definition == null) {
            // 没传定义视为 REQUIRED + 默认隔离 —— 与 Spring 行为一致
            definition = TransactionDefinition.defaultDefinition();
        }
        TransactionStatus current = TransactionSynchronizationManager.getCurrentStatus();
        Propagation prop = definition.propagation();

        if (current == null) {
            // === 无当前事务:走第一行决策 ===
            return handleNoExistingTransaction(definition, prop);
        } else {
            // === 有当前事务:走第二行决策 ===
            return handleExistingTransaction(definition, current, prop);
        }
    }

    private TransactionStatus handleNoExistingTransaction(
            TransactionDefinition definition, Propagation prop) throws TransactionException {
        switch (prop) {
            case REQUIRED:
            case REQUIRES_NEW:
            case NESTED:
                // 开新事务
                return beginNewTransaction(definition);
            case SUPPORTS:
            case NOT_SUPPORTED:
            case NEVER:
                // 非事务
                return new TransactionStatus(definition, null,
                        false, false, definition.readOnly());
            case MANDATORY:
                throw new IllegalTransactionStateException(
                        "No existing transaction found for transaction marked with propagation '"
                        + prop + "'");
            default:
                throw new IllegalTransactionStateException("Unknown propagation: " + prop);
        }
    }

    private TransactionStatus handleExistingTransaction(
            TransactionDefinition definition, TransactionStatus current, Propagation prop)
            throws TransactionException {
        switch (prop) {
            case REQUIRED:
                // 复用外层 + nesting +1
                current.incrementNesting();
                return current;
            case REQUIRES_NEW:
                // 挂起外层,开新事务
                return handleRequiresNew(definition, current);
            case NESTED:
                // 复用外层 + 创建 savepoint
                return handleNested(definition, current);
            case SUPPORTS:
                return current;
            case NOT_SUPPORTED:
                return handleNotSupported(definition, current);
            case MANDATORY:
                return current;
            case NEVER:
                throw new IllegalTransactionStateException(
                        "Existing transaction found for transaction marked with propagation '"
                        + prop + "'");
            default:
                throw new IllegalTransactionStateException("Unknown propagation: " + prop);
        }
    }

    private TransactionStatus beginNewTransaction(TransactionDefinition definition)
            throws TransactionException {
        TransactionResource resource = doBegin(definition, null);
        TransactionStatus status = new TransactionStatus(
                definition, resource, true, true, definition.readOnly());
        TransactionSynchronizationManager.bindStatus(status);
        // 新事务开启时初始化同步点列表,这样 beforeCommit / afterCommit 等回调能正确触发
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.initSynchronization();
        }
        return status;
    }

    private TransactionStatus handleRequiresNew(TransactionDefinition definition,
                                                 TransactionStatus current)
            throws TransactionException {
        TransactionSynchronizationManager.SuspendedResources suspended =
                TransactionSynchronizationManager.suspend();
        TransactionStatus newStatus = beginNewTransaction(definition);
        newStatus.setSuspendedResources(suspended);
        return newStatus;
    }

    private TransactionStatus handleNested(TransactionDefinition definition,
                                           TransactionStatus current)
            throws TransactionException {
        if (!current.hasResource()) {
            // 现有 status 是 "非事务" (SUPPORTS 等复用路径) — 不支持嵌套
            throw new IllegalTransactionStateException(
                    "Nested transaction requested but no physical transaction exists");
        }
        // 由资源创建 savepoint;失败时(资源不支持 savepoint)抛 NestedTransactionNotSupportedException
        Object savepoint = current.resource().createSavepoint();
        // 复用外层 status,只是把 savepoint 引用挂上去 —— 与 REQUIRED 复用语义对齐,
        // 内层 commit 不真正 commit,rollback 走 rollbackToSavepoint 路径
        current.setSavepoint(savepoint);
        return current;
    }

    private TransactionStatus handleNotSupported(TransactionDefinition definition,
                                                 TransactionStatus current)
            throws TransactionException {
        TransactionSynchronizationManager.SuspendedResources suspended =
                TransactionSynchronizationManager.suspend();
        TransactionStatus nonTx = new TransactionStatus(
                definition, null, false, false, definition.readOnly());
        nonTx.setSuspendedResources(suspended);
        return nonTx;
    }

    // ============ commit ============

    @Override
    public void commit(TransactionStatus status) throws TransactionException {
        if (status == null) {
            throw new IllegalTransactionStateException(
                    "commit() called with null status");
        }
        if (status.isCompleted()) {
            throw new IllegalTransactionStateException(
                    "commit() called on completed transaction");
        }

        // 嵌套内层调用 → 只 decrement,跳过实际 commit
        // count == 0 表示"我开的新事务",count > 0 表示"我加入外层"
        if (status.nestingCount() > 0) {
            status.decrementNesting();
            return;
        }

        // rollbackOnly 标记 → 改走 rollback 路径
        if (status.isRollbackOnly() || status.resource() != null && status.resource().isRollbackOnly()) {
            processRollback(status, true);
            return;
        }

        processCommit(status);
    }

    private void processCommit(TransactionStatus status) throws TransactionException {
        try {
            // 1. beforeCommit(抛异常 → 触发 rollback 而非 commit)
            try {
                triggerBeforeCommit(status);
            } catch (RuntimeException | Error ex) {
                throw new TransactionSystemException(
                        "beforeCommit synchronization threw — aborting commit", ex);
            }

            // 2. 物理 commit(嵌套计数==1 时一定有 resource,但 resource 可能为 null 表示 SUPPORTS 非事务)
            if (status.hasResource()) {
                status.resource().commit();
            }

            // 3. afterCommit(异常不影响已提交的事务状态,但会包装后向上抛)
            try {
                triggerAfterCommit(status);
            } catch (RuntimeException | Error ex) {
                throw new TransactionSystemException(
                        "afterCommit synchronization threw — transaction was committed",
                        ex);
            }

            // 4. afterCompletion(STATUS_COMMITTED)
            triggerAfterCompletion(status, Synchronization.STATUS_COMMITTED);

        } catch (TransactionException ex) {
            // commit 路径中途出错(物理 commit 失败 / beforeCommit 异常) → 改走 rollback
            processRollback(status, true);
            throw ex;
        } finally {
            cleanupAfterCompletion(status);
        }
    }

    // ============ rollback ============

    @Override
    public void rollback(TransactionStatus status) throws TransactionException {
        if (status == null) {
            throw new IllegalTransactionStateException(
                    "rollback() called with null status");
        }
        if (status.isCompleted()) {
            throw new IllegalTransactionStateException(
                    "rollback() called on completed transaction");
        }
        processRollback(status, false);
    }

    private void processRollback(TransactionStatus status, boolean unexpected)
            throws TransactionException {
        // 如果是 NESTED 嵌套调用,优先回滚到 savepoint —— 不 mark completed,
        // 外层事务保持活跃,外层 commit 仍可触发物理 commit
        if (status.getSavepoint() != null) {
            if (status.hasResource()) {
                status.resource().rollbackToSavepoint(status.getSavepoint());
            }
            triggerAfterCompletion(status, Synchronization.STATUS_ROLLED_BACK);
            // 清空 savepoint 引用,但 status 自身不标 completed(外层还要 commit)
            status.setSavepoint(null);
            return;
        }

        try {
            // 跳过 beforeCommit / afterCommit,直接进 afterCompletion
            triggerAfterCompletion(status, Synchronization.STATUS_ROLLED_BACK);

            // 物理 rollback
            if (status.hasResource()) {
                status.resource().rollback();
            }
        } finally {
            cleanupAfterCompletion(status);
        }
    }

    // ============ 同步点回调 ============

    private void triggerBeforeCommit(TransactionStatus status) throws TransactionException {
        List<Synchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        if (syncs == null) return;
        for (Synchronization sync : syncs) {
            sync.beforeCommit();
        }
    }

    private void triggerAfterCommit(TransactionStatus status) {
        List<Synchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        if (syncs == null) return;
        for (Synchronization sync : syncs) {
            sync.afterCommit();
        }
    }

    private void triggerAfterCompletion(TransactionStatus status, int completionStatus) {
        List<Synchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        if (syncs == null) return;
        for (Synchronization sync : syncs) {
            try {
                sync.afterCompletion(completionStatus);
            } catch (Throwable t) {
                // afterCompletion 异常被吞掉 —— 已提交 / 已回滚的状态不可被回调改变
                // 真实实现可能打日志,Phase 1 mock 简化为静默
            }
        }
    }

    /**
     * commit / rollback 完成后清理:
     * 1. markCompleted
     * 2. 清空同步点列表(因为新事务可能复用 ThreadLocal)
     * 3. 解绑 status(只有"newTransaction=true"的 status 才会绑到 ThreadLocal)
     * 4. 如果有挂起的外层事务,resume —— 必须最后做,因为 resume 会重新 bind 外层 status,
     *    顺序错会先 bind 再 unbind,导致 ThreadLocal 被错误清空
     */
    private void cleanupAfterCompletion(TransactionStatus status) {
        status.markCompleted();

        if (status.isNewSynchronization()) {
            TransactionSynchronizationManager.clearSynchronization();
        }

        if (status.isNewTransaction()) {
            TransactionSynchronizationManager.unbindStatus();
        }

        // 恢复挂起的外层事务(REQUIRES_NEW / NOT_SUPPORTED)
        if (status.getSuspendedResources() != null) {
            TransactionSynchronizationManager.resumeSuspended(
                    status.getSuspendedResources());
        }
    }

    // ============ hasResource ============

    @Override
    public boolean hasResource() {
        TransactionStatus s = TransactionSynchronizationManager.getCurrentStatus();
        return s != null && s.hasResource();
    }
}