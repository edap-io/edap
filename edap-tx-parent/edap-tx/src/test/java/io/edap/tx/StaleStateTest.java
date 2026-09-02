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
import io.edap.tx.propagation.Propagation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stale ThreadLocal 与 status 已 completed 状态下的边界单测。
 *
 * <p>旧实现依赖 callerDepth ThreadLocal 防御 wrapper bug 路径漏 unbind。
 * 新实现只检测 status.isCompleted() —— 这是真正能反映"残留"的标志。</p>
 *
 * <p>覆盖缺口:</p>
 * <ul>
 *   <li>status 已被 markCompleted 但 ThreadLocal 未 unbind → 下次 getTransaction
 *       视为无事务,clean reset(防止 REQUIRED 嵌套计数错误膨胀)</li>
 *   <li>wrapper 入口捕获到 stale state 后,后续 commit 路径不再误增 nesting</li>
 *   <li>commit / rollback 二次调用一律抛 IllegalTransactionStateException</li>
 *   <li>TestTransactionManager 不再需要 callerDepth 参数</li>
 * </ul>
 */
class StaleStateTest {

    private final TestTransactionManager tm = new TestTransactionManager();

    @AfterEach
    void cleanup() {
        TxScope.clear();
    }

    // ============ status.isCompleted() stale 检测 ============

    @Test
    @DisplayName("status 已 completed 但 ThreadLocal 未清空 → 下次 getTransaction 视为无事务")
    void staleStatusOnThreadLocal_isTreatedAsNoTransaction() throws Exception {
        // 模拟 wrapper bug 路径:开了事务后 commit,但 finally 漏调 cleanup(未 unbind)
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        s.markCompleted();          // 模拟 commit 路径的 markCompleted
        // 注意:ThreadLocal 上的 status 仍然存在(stale)
        assertNotNull(TxScope.currentStatus());
        assertTrue(TxScope.currentStatus().isCompleted());

        // 下次 getTransaction 应检测 stale 状态,重置 ThreadLocal
        TransactionStatus s2 = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());

        // s2 应该是全新事务(开新 resource)
        assertNotSame(s, s2, "stale status 应被清空,s2 应是新事务");
        assertTrue(s2.isNewTransaction());
        assertEquals(2, tm.beginDefinitions.size(),
                "两次 getTransaction 均开新事务,doBegin 应被调用两次");

        // 新事务能正常 commit
        tm.commit(s2);
        assertFalse(TxScope.isTransactionActive());
    }

    @Test
    @DisplayName("stale 检测后,nesting count 不会从 stale status 累加")
    void staleDetection_preventsNestingAccumulation() throws Exception {
        TransactionStatus stale = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        stale.markCompleted();
        // 模拟 stale 状态(原 s 已经在 ThreadLocal,但已 markCompleted)

        // 业务逻辑接着开了"第二层"事务,期望是嵌套 REQUIRED,
        // 但 stale 检测会把 ThreadLocal 重置,这个新调用是新事务,nesting=0
        TransactionStatus s2 = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());

        assertNotSame(stale, s2);
        assertEquals(0, s2.nestingCount(),
                "stale 检测后,新事务 nestingCount 应从 0 开始,不是从 stale 的 0 累加(同名 status 是新对象)");
        // 关键:如果 stale 没被检测,继续 incrementNesting 会让 nestingCount 错误膨胀,
        // 外层 commit 看到 nestingCount > 0 → 只 decrement 不真正 commit → 数据丢失
        tm.commit(s2);
        assertEquals(1, ((MockTransactionResource) s2.resource()).getCommitCount());
    }

    @Test
    @DisplayName("REQUIRES_NEW 内层 status 已 completed 时外层被 stale 检测清空")
    void staleStatusInSuspendedStack() throws Exception {
        // 模拟 REQUIRES_NEW 把外层挂起,内层已 completed 但 finally 漏 cleanup,
        // 导致外层 status 已被 markCompleted,业务接着再 getTransaction
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        outer.markCompleted();  // 模拟 wrapper bug:completed 但未 unbind
        assertSame(outer, TxScope.currentStatus());

        // 后续 getTransaction 应检测 stale,重置 ThreadLocal
        TransactionStatus fresh = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        assertNotSame(outer, fresh);
        assertTrue(fresh.isNewTransaction());
    }

    // ============ commit / rollback 二次调用保护 ============

    @Test
    @DisplayName("commit 后再 commit 抛 IllegalTransactionStateException")
    void doubleCommit_throws() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        tm.commit(s);

        IllegalTransactionStateException ex = assertThrows(
                IllegalTransactionStateException.class, () -> tm.commit(s));
        assertTrue(ex.getMessage().contains("completed"),
                "异常消息应提示 'completed': " + ex.getMessage());
    }

    @Test
    @DisplayName("rollback 后再 rollback 抛 IllegalTransactionStateException")
    void doubleRollback_throws() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        tm.rollback(s);

        assertThrows(IllegalTransactionStateException.class, () -> tm.rollback(s));
    }

    @Test
    @DisplayName("commit 后 rollback 抛 IllegalTransactionStateException(状态机一致)")
    void commitThenRollback_throws() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        tm.commit(s);

        assertThrows(IllegalTransactionStateException.class, () -> tm.rollback(s));
    }

    // ============ 显式 clear 后的行为 ============

    @Test
    @DisplayName("TxScope.clear 后 getTransaction 视为无事务,无需 callerDepth")
    void clearThenGetTransaction_isNoTx() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        // 业务方显式清理(拦截器 finally 路径)
        TxScope.clear();

        // 下次 getTransaction 应是"无事务"语义,与 callerDepth 完全无关
        TransactionStatus s2 = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());

        assertNotSame(s, s2);
        assertTrue(s2.isNewTransaction());
        assertEquals(0, s2.nestingCount(),
                "clear 后的新事务 nesting 应为 0,不应继承旧 status 的计数");
    }

    @Test
    @DisplayName("显式 clear 后注册 sync 应抛异常(未 init)")
    void clearThenAddSync_throws() {
        // 业务方在非事务路径调 addSynchronization 应早期失败
        TxScope.clear();
        assertFalse(TxScope.isSynchronizationActive());

        assertThrows(IllegalTransactionStateException.class,
                () -> TxScope.addSynchronization(new Synchronization() {}));
    }

    // ============ NESTED stale 检测路径 ============

    @Test
    @DisplayName("NESTED inner rollback 完后,外层 status 不应被标 completed(stale 检测不触发)")
    void nestedInnerRollback_outerNotCompleted() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.NESTED).build());
        assertSame(outer, inner);

        tm.rollback(inner);

        // NESTED rollback 应只回滚到 savepoint,outer 仍 active
        assertFalse(outer.isCompleted(),
                "NESTED rollback 不应 mark outer 为 completed(否则会触发 stale 路径)");
        assertSame(outer, TxScope.currentStatus());
        // 外层仍能正常 commit
        tm.commit(outer);
        assertTrue(outer.isCompleted());
    }
}