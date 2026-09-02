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

package io.edap.tx.jdbc;

import io.edap.tx.Synchronization;
import io.edap.tx.TransactionResource;
import io.edap.tx.TxScope;
import io.edap.tx.exception.TransactionSystemException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;

/**
 * JDBC 事务资源——{@link TransactionResource} 的真实实现,持有一个由
 * {@link DataSourceTransactionManager} 从池中拿到的 {@link Connection}。
 *
 * <p><b>生命周期</b>(commit / rollback / savepoint 路径收尾):</p>
 * <pre>
 *   setAutoCommit(true)   ← 还原连接原始状态(归还池前)
 *   close()               ← 池化时 HikariCP 自动回池,非池化时物理关闭
 * </pre>
 *
 * <p><b>连接归还幂等</b>:{@code setAutoCommit} 与 {@code close} 各自 try-catch,
 * 避免 close 失败连带"二次 commit"的语义错乱(已 commit 的事务不应被 close 失败回滚)。</p>
 *
 * <p><b>rollbackOnly 语义</b>:用本地 {@code volatile} 标志,不动
 * {@link Connection#setRollbackOnly} —— Connection 自己的 rollbackOnly 标志在不同
 * driver 语义不一致(部分驱动仅建议、不强制)。{@link DataSourceTransactionManager}
 * 的 commit 路径走 {@link #isRollbackOnly} 判定后改走 rollback。</p>
 *
 * <p><b>异常包装</b>:所有 {@link SQLException} → {@link TransactionSystemException},
 * 原 SQLException 作 cause 保留 —— 与 Phase 1 {@code DefaultEdapTransactionManager}
 * 内部异常语义对齐。</p>
 */
public class JdbcTransactionResource implements TransactionResource {

    private final Connection connection;
    private final DataSource dataSource;
    private volatile boolean rollbackOnly;

    public JdbcTransactionResource(Connection connection, DataSource dataSource) {
        this.connection = connection;
        this.dataSource = dataSource;
    }

    /**
     * 当前事务持有的 JDBC 连接。业务代码如需绕过 manager 直接执行 SQL,
     * 可通过 {@code ((JdbcTransactionResource) status.resource()).getConnection()}
     * 拿到(典型场景:Repository / DAO)。
     */
    public Connection getConnection() {
        return connection;
    }

    /**
     * 持有本资源的 DataSource —— 多数据源场景下作为资源 key。
     */
    public DataSource getDataSource() {
        return dataSource;
    }

    /**
     * 显式标记回滚 —— 业务代码主动调用,manager 在 commit 时检查后改走 rollback。
     * 与 {@link #setRollbackOnly} 不同的是,这是 boolean getter。
     */
    public void markRollbackOnly() {
        this.rollbackOnly = true;
    }

    @Override
    public void commit() throws TransactionSystemException {
        try {
            connection.commit();
        } catch (SQLException e) {
            // 失败不释放连接 —— Phase 1 {@code processCommit} catch 块会调
            // {@code processRollback} 复用此连接做 rollback,然后再释放;
            // 这里提前释放会导致 processRollback 拿不到连接抛二次异常,掩盖原 commit 失败根因
            throw new TransactionSystemException("JDBC commit failed", e);
        }
        releaseConnection();
    }

    @Override
    public void rollback() throws TransactionSystemException {
        try {
            connection.rollback();
        } catch (SQLException e) {
            throw new TransactionSystemException("JDBC rollback failed", e);
        }
        releaseConnection();
    }

    @Override
    public void registerSynchronization(Synchronization sync) {
        // 与 edap-tx 模块的 MockTransactionResource 一致:resource 级 sync 委托
        // 单 ThreadLocal(TxScope)统一持有 synchronizations 列表,manager 在
        // commit/rollback 时从 TxScope.currentSynchronizations() 取列表触发回调
        TxScope.addSynchronization(sync);
    }

    @Override
    public boolean isRollbackOnly() {
        return rollbackOnly;
    }

    @Override
    public Object createSavepoint() throws TransactionSystemException {
        try {
            return connection.setSavepoint();
        } catch (SQLException e) {
            throw new TransactionSystemException("setSavepoint failed", e);
        }
    }

    @Override
    public void releaseSavepoint(Object savepoint) throws TransactionSystemException {
        try {
            connection.releaseSavepoint((Savepoint) savepoint);
        } catch (SQLException e) {
            throw new TransactionSystemException("releaseSavepoint failed", e);
        }
    }

    @Override
    public void rollbackToSavepoint(Object savepoint) throws TransactionSystemException {
        try {
            connection.rollback((Savepoint) savepoint);
        } catch (SQLException e) {
            throw new TransactionSystemException("rollback(savepoint) failed", e);
        }
    }

    /**
     * 归还连接到池:setAutoCommit(true) 还原 + close() 触发池回收。
     *
     * <p>两个动作各自 try-catch SQLException —— close 失败不应回滚已 commit 的事务。
     * 模式参照
     * {@code edap-data-parent/edap-data-jdbc-dao/.../ConnectionHolder.SimpleConnectionHolder#releaseConnection()}。</p>
     */
    private void releaseConnection() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignore) {
            // 连接可能已被外部 close,但不影响 commit/rollback 已完成的事实
        }
        try {
            connection.close();
        } catch (SQLException ignore) {
            // 同上:close 失败不回滚已提交事务
        }
    }
}