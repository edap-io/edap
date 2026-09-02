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

/**
 * 业务侧事务上下文——具体类,合并原 {@code TransactionContext} 接口 +
 * {@code DefaultTransactionContext} impl + {@code TransactionContexts} 静态工具。
 *
 * <p><b>静态 API</b>(绑定 / 查询):</p>
 * <ul>
 *   <li>{@link #current()} —— 必须从 {@code @Transactional} 或 {@code @ManualTransaction}
 *       方法体内调用,无绑定时抛 {@link IllegalStateException}</li>
 *   <li>{@link #currentOrNull()} —— 同上,无绑定时返回 null(业务方主动检测)</li>
 *   <li>{@link #bind(EdapTransactionManager, TransactionStatus)} —— wrapper 入口调;
 *       业务方不应直接调</li>
 *   <li>{@link #unbind()} —— wrapper 出口 finally 调;业务方不应直接调</li>
 * </ul>
 *
 * <p><b>实例 API</b>(业务方法体内调):</p>
 * <ul>
 *   <li>{@link #commit()} / {@link #rollback()} —— {@code @ManualTransaction} 路径下业务方主动调用</li>
 *   <li>{@link #setRollbackOnly()} —— 任意路径下业务方主动标 rollback</li>
 *   <li>{@link #createSavepoint()} / {@link #rollbackTo(Object)} /
 *       {@link #releaseSavepoint(Object)} —— NESTED 路径下业务方手动管理 savepoint</li>
 *   <li>{@link #definition()} —— 取当前事务的 {@link TransactionDefinition}</li>
 * </ul>
 *
 * <p><b>典型用法</b>:</p>
 * <pre>
 *   {@code @ManualTransaction}
 *   public void register(User u) {
 *       TransactionContext ctx = TransactionContext.current();
 *       try {
 *           insert(u);
 *           ctx.commit();
 *       } catch (Exception e) {
 *           ctx.rollback();
 *           throw e;
 *       }
 *   }
 * </pre>
 *
 * <p><b>commit / rollback 守卫</b>:重复 commit / rollback 时抛
 * {@link IllegalTransactionStateException} —— 业务方已经在方法体内调过 commit 后又调
 * rollback 是 bug,应早期暴露。</p>
 *
 * <p><b>非 final</b>:分布式事务的扩展实现可能需要继承添加额外状态(xid / 协调器句柄),
 * 因此保持 non-final 但 {@link #bind} 工厂方法是 ctx 的唯一创建入口。</p>
 */
public class TransactionContext {

    private final EdapTransactionManager manager;
    private final TransactionStatus status;

    protected TransactionContext(EdapTransactionManager manager, TransactionStatus status) {
        if (manager == null) {
            throw new IllegalArgumentException("manager is null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is null");
        }
        this.manager = manager;
        this.status = status;
    }

    // ============ 静态 API ============

    /**
     * 返回当前线程绑定的事务上下文。
     *
     * <p>无绑定时直接抛 {@link IllegalStateException} —— 契约:
     * {@code @Transactional} 或 {@code @ManualTransaction} 方法体内必有 ctx。
     * 无 ctx 时抛异常意味着:(1) 业务方在无注解方法里调,或 (2) wrapper 未绑(框架 bug)。</p>
     *
     * @return 当前线程的 ctx
     * @throws IllegalStateException 当前线程无绑定 ctx
     */
    public static TransactionContext current() {
        TransactionContext ctx = TxScope.current().context();
        if (ctx == null) {
            throw new IllegalStateException(
                    "No active transaction context — "
                    + "TransactionContext.current() must be called within "
                    + "@Transactional or @ManualTransaction method body");
        }
        return ctx;
    }

    /**
     * 返回当前线程绑定的事务上下文,无绑定时返回 null。
     *
     * <p>仅用于业务方主动检测 tx 状态(inspect),不要用于常规 tx 控制——
     * 常规控制请用 {@link #current()}。</p>
     */
    public static TransactionContext currentOrNull() {
        return TxScope.current().context();
    }

    /**
     * wrapper 在 {@code tm.getTransaction} 之后调,把 ctx 写到当前 snapshot。
     *
     * <p>由 ASM 生成的 proxy 字节码调用,故为 {@code public};业务方不应直接调。
     * 本方法不会覆盖 snapshot 中的 status —— status 由 manager 在
     * {@code getTransaction} 时绑定,这里仅追加 ctx 引用,保证 ctx.status 与
     * TxScope.current().status() 指向同一对象。</p>
     *
     * @return 刚绑定的 ctx(便于 wrapper 在 finally 块 unbind 时直接丢弃)
     */
    public static TransactionContext bind(EdapTransactionManager manager,
                                          TransactionStatus status) {
        TransactionContext ctx = new TransactionContext(manager, status);
        TxSnapshot snap = TxScope.current();
        TxScope.setCurrent(snap
                .withStatus(status)
                .withContext(ctx));
        return ctx;
    }

    /**
     * wrapper 在 finally 块调,从当前 snapshot 移除 ctx 引用。
     *
     * <p>由 ASM 生成的 proxy 字节码调用,故为 {@code public};业务方不应直接调。
     * 不动 snapshot 中的 status —— status 由 manager 在 commit/rollback 时清理。</p>
     */
    public static void unbind() {
        TxSnapshot snap = TxScope.current();
        if (snap.context() != null) {
            TxScope.setCurrent(snap.withContext(null));
        }
    }

    // ============ 实例 API ============

    public boolean isActive() {
        return !status.isCompleted();
    }

    public boolean isRollbackOnly() {
        return status.isRollbackOnly();
    }

    public void setRollbackOnly() {
        status.setRollbackOnly();
    }

    public void commit() {
        ensureActive();
        try {
            manager.commit(status);
        } catch (TransactionException e) {
            throw new IllegalTransactionStateException(
                    "commit() failed: " + e.getMessage(), e);
        }
    }

    public void rollback() {
        ensureActive();
        try {
            manager.rollback(status);
        } catch (TransactionException e) {
            throw new IllegalTransactionStateException(
                    "rollback() failed: " + e.getMessage(), e);
        }
    }

    public Object createSavepoint() {
        ensureActive();
        ensureHasResource();
        try {
            return status.resource().createSavepoint();
        } catch (TransactionException e) {
            throw new IllegalTransactionStateException(
                    "createSavepoint() failed: " + e.getMessage(), e);
        }
    }

    public void rollbackTo(Object savepoint) {
        ensureActive();
        ensureHasResource();
        try {
            status.resource().rollbackToSavepoint(savepoint);
        } catch (TransactionException e) {
            throw new IllegalTransactionStateException(
                    "rollbackTo() failed: " + e.getMessage(), e);
        }
    }

    public void releaseSavepoint(Object savepoint) {
        ensureActive();
        ensureHasResource();
        try {
            status.resource().releaseSavepoint(savepoint);
        } catch (TransactionException e) {
            throw new IllegalTransactionStateException(
                    "releaseSavepoint() failed: " + e.getMessage(), e);
        }
    }

    public TransactionDefinition definition() {
        return status.definition();
    }

    /**
     * 当前 ctx 持有的 status —— 主要供 manager 实现内部使用,业务方一般无需访问。
     */
    public TransactionStatus status() {
        return status;
    }

    /**
     * 当前 ctx 持有的 manager —— 主要供 manager 实现内部使用。
     */
    public EdapTransactionManager manager() {
        return manager;
    }

    private void ensureActive() {
        if (status.isCompleted()) {
            throw new IllegalTransactionStateException(
                    "transaction is already completed — cannot commit/ rollback");
        }
    }

    private void ensureHasResource() {
        if (!status.hasResource()) {
            throw new IllegalTransactionStateException(
                    "no physical transaction resource bound — "
                    + "savepoint operations require a real transaction");
        }
    }
}