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

import io.edap.tx.exception.TransactionException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 1 测试用 mock — 不引入 java.sql.*,只在内存里跟踪 commit / rollback /
 * savepoint / synchronization 调用。
 *
 * <p>关键能力:</p>
 * <ul>
 *   <li>记录 commit / rollback 调用次数和顺序</li>
 *   <li>支持 savepoint(createSavepoint 返回字符串,release/rollback no-op)</li>
 *   <li>维护 synchronization 列表供 manager 在 commit/rollback 时回调</li>
 *   <li>可注入"commit 时抛异常"等行为,模拟故障场景</li>
 * </ul>
 *
 * <p><b>为什么 Phase 1 不直接 mock Connection</b>:目标是把传播决策矩阵单测透,不需要
 * 真 JDBC;一旦引入 JDBC mock,测试就耦合到 PG/MySQL 行为差异。Phase 2 真 JDBC 资源
 * 在 {@code JdbcTransactionResource} 里独立写。</p>
 */
public class MockTransactionResource implements TransactionResource {

    private final List<Synchronization> synchronizations = new ArrayList<>();
    private final AtomicInteger commitCount = new AtomicInteger();
    private final AtomicInteger rollbackCount = new AtomicInteger();
    private final AtomicInteger savepointCount = new AtomicInteger();
    private final List<String> savepointNames = new ArrayList<>();

    private volatile boolean rollbackOnly;
    private final List<String> events = new ArrayList<>();   // 调试用:append 事件字符串

    /** 故障注入:commit 时抛异常。 */
    private boolean failOnCommit;
    /** 故障注入:rollback 时抛异常。 */
    private boolean failOnRollback;

    public void setFailOnCommit(boolean fail) { this.failOnCommit = fail; }
    public void setFailOnRollback(boolean fail) { this.failOnRollback = fail; }

    public int getCommitCount()   { return commitCount.get(); }
    public int getRollbackCount() { return rollbackCount.get(); }
    public int getSavepointCount() { return savepointCount.get(); }
    public List<String> getSavepointNames() { return savepointNames; }
    public List<String> getEvents() { return events; }

    @Override
    public void commit() throws TransactionException {
        events.add("commit@" + System.nanoTime());
        commitCount.incrementAndGet();
        if (failOnCommit) {
            throw new TransactionException("mock: forced commit failure");
        }
    }

    @Override
    public void rollback() throws TransactionException {
        events.add("rollback@" + System.nanoTime());
        rollbackCount.incrementAndGet();
        if (failOnRollback) {
            throw new TransactionException("mock: forced rollback failure");
        }
    }

    @Override
    public void registerSynchronization(Synchronization sync) throws TransactionException {
        // resource 级 sync 委托给 ThreadLocal manager,与 Spring 行为一致:
        // manager 在 commit/rollback 时从 ThreadLocal 取同步点列表统一触发
        synchronizations.add(sync);
        TransactionSynchronizationManager.addSynchronization(sync);
    }

    @Override
    public boolean isRollbackOnly() {
        return rollbackOnly;
    }

    public void markRollbackOnly() {
        rollbackOnly = true;
    }

    @Override
    public Object createSavepoint() throws TransactionException {
        String name = "sp_" + savepointCount.incrementAndGet();
        savepointNames.add(name);
        events.add("createSavepoint:" + name);
        return name;
    }

    @Override
    public void releaseSavepoint(Object savepoint) throws TransactionException {
        events.add("releaseSavepoint:" + savepoint);
    }

    @Override
    public void rollbackToSavepoint(Object savepoint) throws TransactionException {
        events.add("rollbackToSavepoint:" + savepoint);
    }

    public List<Synchronization> getSynchronizations() {
        return synchronizations;
    }
}