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
 * edap 事务管理器默认实现——基于 {@link TxScope} 单 ThreadLocal,
  替代原 Spring 风格 6 ThreadLocal + callerDepth 防御层设计。
 *
 * <p><b>职责</b>:</p>
 * <ol>
 *   <li>实现 7×3=21 个传播决策矩阵场景(见 {@link #getTransaction})</li>
 *   <li>commit / rollback 嵌套计数管理 + 同步点回调触发</li>
 *   <li>REQUIRES_NEW / NOT_SUPPORTED 路径的挂起-恢复(通过 {@link TxScope#swap})</li>
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
 *
 * <p><b>stale state 检测</b>:本实现不再依赖 callerDepth 参数,而是检测
 * {@link TransactionStatus#isCompleted()} 状态——若 ThreadLocal 上的 status
 * 已被 {@link TransactionStatus#markCompleted()} 但未 unbind(框架 bug 路径),
 * 视为残留,清空后按"无事务"处理,避免 REQUIRED 嵌套计数错误膨胀。</p>
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
        TxSnapshot snap = TxScope.current();
        TransactionStatus current = snap.status();

        // 防 stale ThreadLocal。两种残留场景都视为无事务,清掉:
        // 1. current 已被 markCompleted 但 ThreadLocal 未 unbind(框架 bug 路径)
        // 2. "半完成" 残留:wrapper 接到了 status(isNewTransaction=true)但 commit/rollback
        //    没真正跑到 cleanup 阶段 —— completed=false 且无挂起 / savepoint。
        //    不清掉的话,本次 getTransaction 会命中 REQUIRED 嵌套路径 +1,
        //    commit 时 count > 0 → 只 decrement 不真正 commit → 数据丢失。
        if (current != null && isStaleStatus(current)) {
            TxScope.setCurrent(snap.withStatus(null));
            current = null;
        }

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

    /**
     * 判断 ThreadLocal 上的 status 是否是上次请求留下的残留。两种形态视为残留:
     *
     * <ol>
     *   <li>{@link TransactionStatus#isCompleted()} 为 true —— markCompleted 跑了但
     *       cleanup 漏了(框架 bug 路径,如 commit 提前 return 后未触发 finally)
     *   <li>"半完成":{@link TransactionStatus#isNewTransaction()} 为 true 且未 completed,
     *       且无挂起 snapshot / savepoint。说明 wrapper 接到了 status(创建事务成功),
     *       但 commit/rollback 都没真正跑到 cleanup 阶段 —— 这种 status 一定是残留,
     *       因为正常完成的事务要么被 markCompleted(走完 processCommit/Rollback),要么
     *       不可能还在 ThreadLocal 上(beginNewTransaction 后立即接 wrapper commit)。
     *       </li>
     * </ol>
     *
     * <p>不清掉的代价:本次 getTransaction 会命中 REQUIRED 嵌套路径 incrementNesting +1,
     * wrapper commit 时 count > 0 → 只 decrement 不真正 commit → 数据丢失。</p>
     */
    private static boolean isStaleStatus(TransactionStatus status) {
        if (status.isCompleted()) {
            return true;
        }
        if (status.isNewTransaction()
                && status.getSuspendedSnapshot() == null
                && status.getSavepoint() == null) {
            // 半完成:isNewTransaction=true + 未 completed + 无挂起/savepoint
            // —— 正常 beginNewTransaction 后 wrapper 立即 commit,不会跨请求停在 ThreadLocal
            return true;
        }
        return false;
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
        // 写回 snapshot;若此前 snapshot 有其他字段(resources / xid 等),保留。
        TxScope.setCurrent(TxScope.current().withStatus(status));
        // 新事务开启时初始化同步点列表,这样 beforeCommit / afterCommit 等回调能正确触发
        if (!TxScope.isSynchronizationActive()) {
            TxScope.initSynchronization();
        }
        return status;
    }

    private TransactionStatus handleRequiresNew(TransactionDefinition definition,
                                                 TransactionStatus current)
            throws TransactionException {
        // 挂起外层:把当前 snapshot 原子交换成 empty,旧 snapshot 保存到新 status
        TxSnapshot suspended = TxScope.swap(TxSnapshot.empty());
        TransactionStatus newStatus = beginNewTransaction(definition);
        newStatus.setSuspendedSnapshot(suspended);
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
        // 挂起外层:原子交换成 empty,旧 snapshot 保存到非事务 status
        TxSnapshot suspended = TxScope.swap(TxSnapshot.empty());
        TransactionStatus nonTx = new TransactionStatus(
                definition, null, false, false, definition.readOnly());
        nonTx.setSuspendedSnapshot(suspended);
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
        if (status.isRollbackOnly()
                || (status.resource() != null && status.resource().isRollbackOnly())) {
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
        List<Synchronization> syncs = TxScope.currentSynchronizations();
        if (syncs == null) return;
        for (Synchronization sync : syncs) {
            sync.beforeCommit();
        }
    }

    private void triggerAfterCommit(TransactionStatus status) {
        List<Synchronization> syncs = TxScope.currentSynchronizations();
        if (syncs == null) return;
        for (Synchronization sync : syncs) {
            sync.afterCommit();
        }
    }

    private void triggerAfterCompletion(TransactionStatus status, int completionStatus) {
        List<Synchronization> syncs = TxScope.currentSynchronizations();
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
     * <ol>
     *   <li>markCompleted</li>
     *   <li>清空同步点列表(因为新事务可能复用 ThreadLocal)</li>
     *   <li>解绑 status(只有"newTransaction=true"的 status 才会绑到 ThreadLocal)</li>
     *   <li>如果有挂起的外层事务,resume —— 必须最后做,因为 resume 会重新 bind 外层 status,
     *       顺序错会先 bind 再 unbind,导致 ThreadLocal 被错误清空</li>
     * </ol>
     *
     * <p><b>幂等性</b>:本方法可能被调用两次 —— commit 失败时 processCommit 的 catch 会调
     * processRollback,processRollback 的 finally 调一次 cleanup,processCommit 自己的 finally
     * 又调一次。第二次调用会把刚 resume 回 ThreadLocal 的外层 status 又清空,造成外层
     * commit 路径找不到 status。{@code isCompleted()} 一旦置位就不再清,所以用其作为幂等 guard。</p>
     */
    private void cleanupAfterCompletion(TransactionStatus status) {
        if (status.isCompleted()) {
            return;
        }
        status.markCompleted();

        if (status.isNewSynchronization()) {
            TxScope.clearSynchronization();
        }

        if (status.isNewTransaction()) {
            TxScope.setCurrent(TxScope.current().withStatus(null));
        }

        // 恢复挂起的外层事务(REQUIRES_NEW / NOT_SUPPORTED)
        if (status.getSuspendedSnapshot() != null) {
            TxScope.swap(status.getSuspendedSnapshot());
            status.setSuspendedSnapshot(null);
        }
    }

    // ============ hasResource ============

    @Override
    public boolean hasResource() {
        return TxScope.isTransactionActive();
    }
}