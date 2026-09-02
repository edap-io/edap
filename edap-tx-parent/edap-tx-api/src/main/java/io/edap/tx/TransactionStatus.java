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
 * 事务运行时状态——可变。代表当前活跃事务的运行时信息,
 * 由 {@link EdapTransactionManager#getTransaction} 返回。
 *
 * <p>关键字段:</p>
 * <ul>
 *   <li>{@link #definition} — 事务定义(不可变)</li>
 *   <li>{@link #resource} — 关联的事务资源(本地事务时为 {@code JdbcConnectionResource};
 *       XA 时为 {@code XaTransactionResource} 等);null 表示"非事务帧"</li>
 *   <li>{@link #newTransaction} — 本次调用是否新建事务(区别于复用外层)</li>
 *   <li>{@link #newSynchronization} — 是否本次新建同步点列表</li>
 *   <li>{@link #readOnly} — 只读标记</li>
 *   <li>{@link #completed} — 已 commit/rollback 后置 true,防止双重提交</li>
 *   <li>{@link #rollbackOnly} — 强制标记回滚(可由业务代码主动调用)</li>
 *   <li>{@link #nestingCount} — REQUIRED 嵌套层数,commit 只在最外层(==1)真正提交</li>
 *   <li>{@link #suspendedSnapshot} — REQUIRES_NEW / NOT_SUPPORTED 场景下挂起的外层事务
 *       快照;本事务结束时由 manager 恢复</li>
 *   <li>{@link #savepoint} — NESTED 场景下挂的 savepoint 引用,rollback 时回滚到该 savepoint</li>
 * </ul>
 *
 * <p><b>线程安全</b>:本类的可变字段并非线程安全——每个事务状态只属于一个线程,
 * 由 {@link TxScope} 通过单 ThreadLocal 保证不跨线程共享。</p>
 *
 * <p><b>suspendedSnapshot vs 旧 SuspendedResources</b>:新设计改用 {@link TxSnapshot}
 * 替代原 {@code TransactionSynchronizationManager.SuspendedResources},把
 * status + synchronizations + resources + xid 一次性快照;manager 在
 * {@code getTransaction} 的 REQUIRES_NEW / NOT_SUPPORTED 路径用
 * {@link TxScope#swap(TxSnapshot)} 原子交换后,把旧 snapshot 存到新 status 的
 * {@code suspendedSnapshot} 字段;commit/rollback 完成后 swap 回去。</p>
 */
public final class TransactionStatus {

    private final TransactionDefinition definition;
    private final TransactionResource resource;       // null 表示非事务(SUPPORTS+无 tx 等)
    private final boolean newTransaction;
    private final boolean newSynchronization;
    private final boolean readOnly;

    // 嵌套计数:REQUIRED 复用外层时 +1;commit 只在 count==1 时真正提交,内层只 decrement
    private int nestingCount;

    private boolean rollbackOnly;
    private boolean completed;

    // REQUIRES_NEW / NOT_SUPPORTED 场景下挂起的外层事务快照,本事务结束时由 manager 恢复
    private TxSnapshot suspendedSnapshot;

    // NESTED 场景下挂的 savepoint 引用,rollback 时回滚到该 savepoint
    private Object savepoint;

    public TransactionStatus(TransactionDefinition definition,
                             TransactionResource resource,
                             boolean newTransaction,
                             boolean newSynchronization,
                             boolean readOnly) {
        this.definition = definition;
        this.resource = resource;
        this.newTransaction = newTransaction;
        this.newSynchronization = newSynchronization;
        this.readOnly = readOnly;
    }

    public TransactionDefinition definition()        { return definition; }
    public TransactionResource resource()            { return resource; }
    public boolean isNewTransaction()                { return newTransaction; }
    public boolean isNewSynchronization()            { return newSynchronization; }
    public boolean isReadOnly()                       { return readOnly; }

    /**
     * 是否实际开启了事务。{@link #resource} 不为 null 表示有真实事务资源绑定。
     */
    public boolean hasResource()                     { return resource != null; }

    public int nestingCount()                         { return nestingCount; }

    /**
     * REQUIRED 嵌套调用时计数 +1,由 {@link EdapTransactionManager} 在复用路径调用。
     */
    public void incrementNesting() {
        if (nestingCount == Integer.MAX_VALUE) {
            throw new IllegalStateException("nesting count overflow");
        }
        nestingCount++;
    }

    /**
     * REQUIRED 嵌套调用的内层结束时 decrement;只在计数降到 0 时由 manager 真正 commit。
     */
    public void decrementNesting() {
        if (nestingCount <= 0) {
            throw new IllegalStateException("nesting count underflow: " + nestingCount);
        }
        nestingCount--;
    }

    public boolean isRollbackOnly()                  { return rollbackOnly; }

    /**
     * 强制标记回滚——业务代码可主动调用,事务提交时即便无异常也 rollback。
     */
    public void setRollbackOnly() {
        rollbackOnly = true;
    }

    public boolean isCompleted()                     { return completed; }

    /**
     * 标记事务已完成(commit 或 rollback 后)。由 {@link EdapTransactionManager#commit}
     * 或 {@link EdapTransactionManager#rollback} 调用,后续再次调用将抛
     * {@link io.edap.tx.exception.IllegalTransactionStateException}。
     */
    public void markCompleted() {
        completed = true;
    }

    /**
     * 本事务挂起的外层事务快照(REQUIRES_NEW / NOT_SUPPORTED 时挂起,本事务结束时恢复)。
     */
    public TxSnapshot getSuspendedSnapshot() {
        return suspendedSnapshot;
    }

    /**
     * 由 manager 在 REQUIRES_NEW / NOT_SUPPORTED 路径上设置挂起快照。
     */
    public void setSuspendedSnapshot(TxSnapshot suspendedSnapshot) {
        this.suspendedSnapshot = suspendedSnapshot;
    }

    /**
     * NESTED 场景下由 manager 挂的 savepoint 引用,rollback 时回滚到该 savepoint。
     */
    public Object getSavepoint() {
        return savepoint;
    }

    /**
     * 由 manager 在 NESTED 路径上设置。
     */
    public void setSavepoint(Object savepoint) {
        this.savepoint = savepoint;
    }

    @Override
    public String toString() {
        return "TransactionStatus{def=" + definition
                + ", new=" + newTransaction
                + ", nesting=" + nestingCount
                + ", rollbackOnly=" + rollbackOnly
                + ", completed=" + completed
                + (suspendedSnapshot != null ? ", hasSuspended=true" : "")
                + (savepoint != null ? ", savepoint=" + savepoint : "")
                + '}';
    }
}