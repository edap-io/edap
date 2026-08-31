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
 * 7×3 传播决策矩阵单测 —— 验证 {@link DefaultEdapTransactionManager#getTransaction}
 * 在 7 种传播模型 × 3 种前置状态(无 tx / 有 tx / 有 tx 且 rollbackOnly)
 * 下的行为正确性。
 *
 * <p>每条用例断言:返回 status 的关键属性 + ThreadLocal 状态 + doBegin 调用次数。
 * 真实 commit / rollback 由 {@link CommitRollbackTest} 覆盖。</p>
 */
class PropagationDecisionTest {

    private final TestTransactionManager tm = new TestTransactionManager();

    @AfterEach
    void cleanup() {
        // 拦截器 finally 块调用的清理 —— 测试间隔离避免 ThreadLocal 串
        TransactionSynchronizationManager.clear();
    }

    // ============ 行 1: 无现有事务 ============

    @Test
    @DisplayName("[无 tx] REQUIRED → 开新事务")
    void noExisting_required_opensNew() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());

        assertNotNull(s, "REQUIRED no-tx 应返回 status");
        assertTrue(s.hasResource(), "REQUIRED no-tx 应有 resource");
        assertTrue(s.isNewTransaction(), "REQUIRED no-tx 应标记 newTransaction");
        assertSame(s, TransactionSynchronizationManager.getCurrentStatus(),
                "新事务应绑到 ThreadLocal");
        assertEquals(1, tm.beginDefinitions.size(), "doBegin 应被调用 1 次");
    }

    @Test
    @DisplayName("[无 tx] REQUIRES_NEW → 开新事务(无挂起)")
    void noExisting_requiresNew_opensNew() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRES_NEW).build());

        assertTrue(s.hasResource());
        assertTrue(s.isNewTransaction());
        assertNull(s.getSuspendedResources(), "无外层事务时,挂起快照应为 null");
    }

    @Test
    @DisplayName("[无 tx] NESTED → 开新事务(无 savepoint)")
    void noExisting_nested_opensNew() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.NESTED).build());

        assertTrue(s.hasResource());
        assertTrue(s.isNewTransaction());
        assertNull(s.getSavepoint(), "无外层事务时,NESTED 等价于新事务,不挂 savepoint");
    }

    @Test
    @DisplayName("[无 tx] SUPPORTS → 非事务")
    void noExisting_supports_nonTx() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.SUPPORTS).build());

        assertFalse(s.hasResource(), "SUPPORTS no-tx 应返回非事务 status");
        assertFalse(s.isNewTransaction());
        assertEquals(0, tm.beginDefinitions.size(), "SUPPORTS no-tx 不应触发 doBegin");
    }

    @Test
    @DisplayName("[无 tx] NOT_SUPPORTED → 非事务")
    void noExisting_notSupported_nonTx() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.NOT_SUPPORTED).build());

        assertFalse(s.hasResource());
        assertFalse(s.isNewTransaction());
        assertEquals(0, tm.beginDefinitions.size());
    }

    @Test
    @DisplayName("[无 tx] MANDATORY → 抛异常")
    void noExisting_mandatory_throws() {
        assertThrows(IllegalTransactionStateException.class, () ->
                tm.getTransaction(TransactionDefinition.builder()
                        .propagation(Propagation.MANDATORY).build()),
                "MANDATORY 无外层事务应抛 IllegalTransactionStateException");
    }

    @Test
    @DisplayName("[无 tx] NEVER → 非事务")
    void noExisting_never_nonTx() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.NEVER).build());

        assertFalse(s.hasResource());
        assertEquals(0, tm.beginDefinitions.size());
    }

    // ============ 行 2: 有现有事务 ============

    @Test
    @DisplayName("[有 tx] REQUIRED → 复用 + nesting+1")
    void existing_required_reuse() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        int beforeBegin = tm.beginDefinitions.size();

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());

        assertSame(outer, inner, "REQUIRED 复用应返回同一 status");
        assertEquals(1, inner.nestingCount(), "nesting 应 +1");
        assertEquals(beforeBegin, tm.beginDefinitions.size(), "复用不应触发 doBegin");
        // inner 就是 outer(同一对象),newTransaction 反映"原始创建时是否新开",
        // 不是"这次 getTransaction 调用是否新开"。outer's newTransaction=true。
        assertTrue(inner.isNewTransaction(),
                "REQUIRED 复用同对象,newTransaction 沿用外层(true)");
    }

    @Test
    @DisplayName("[有 tx] REQUIRES_NEW → 挂起 + 开新")
    void existing_requiresNew_suspendsAndOpensNew() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRES_NEW).build());

        assertNotSame(outer, inner, "REQUIRES_NEW 应返回新 status");
        assertTrue(inner.isNewTransaction());
        assertNotNull(inner.getSuspendedResources(), "应记录挂起快照");
        // 当前 ThreadLocal 应是新事务(外层已被 suspend)
        assertSame(inner, TransactionSynchronizationManager.getCurrentStatus());
    }

    @Test
    @DisplayName("[有 tx] NESTED → 复用 + 创建 savepoint")
    void existing_nested_createsSavepoint() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        MockTransactionResource outerRes = (MockTransactionResource) outer.resource();

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.NESTED).build());

        assertSame(outer, inner, "NESTED 复用应返回同一 status");
        assertNotNull(inner.getSavepoint(), "应挂 savepoint 引用");
        assertEquals(1, outerRes.getSavepointCount(), "resource.createSavepoint 应被调用 1 次");
    }

    @Test
    @DisplayName("[有 tx] SUPPORTS → 复用")
    void existing_supports_reuse() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.SUPPORTS).build());

        assertSame(outer, inner);
        assertEquals(0, inner.nestingCount(), "SUPPORTS 不增加 nesting");
    }

    @Test
    @DisplayName("[有 tx] NOT_SUPPORTED → 挂起 + 非事务")
    void existing_notSupported_suspendsAndNonTx() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.NOT_SUPPORTED).build());

        assertFalse(inner.hasResource(), "NOT_SUPPORTED 应返回非事务 status");
        assertNotNull(inner.getSuspendedResources());
        // 当前 ThreadLocal 应无 status(not_supported 是非事务)
        assertNull(TransactionSynchronizationManager.getCurrentStatus(),
                "NOT_SUPPORTED 应清除 ThreadLocal 中的 status");
    }

    @Test
    @DisplayName("[有 tx] MANDATORY → 复用")
    void existing_mandatory_reuse() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.MANDATORY).build());

        assertSame(outer, inner);
    }

    @Test
    @DisplayName("[有 tx] NEVER → 抛异常")
    void existing_never_throws() throws Exception {
        tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());

        assertThrows(IllegalTransactionStateException.class, () ->
                tm.getTransaction(TransactionDefinition.builder()
                        .propagation(Propagation.NEVER).build()),
                "NEVER 在有事务时应抛 IllegalTransactionStateException");
    }

    // ============ 行 3: 有现有事务且 rollbackOnly(行为变化点) ============

    @Test
    @DisplayName("[rollbackOnly] REQUIRED + status.setRollbackOnly → 复用,但 commit 走 rollback")
    void existing_required_rollbackOnly_joinStill() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        outer.setRollbackOnly();

        // 嵌套内层调用
        TransactionStatus inner = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());

        assertSame(outer, inner);
        MockTransactionResource res = (MockTransactionResource) outer.resource();
        // 内层 commit:只 decrement 计数(不真正提交)
        tm.commit(inner);
        assertEquals(0, res.getCommitCount(), "rollbackOnly 时内层 commit 不应触发物理 commit");
        assertEquals(0, res.getRollbackCount(), "rollbackOnly 时内层 commit 不触发物理 rollback");

        // 外层 commit 走 rollback 路径(rollbackOnly 标志判定)
        tm.commit(outer);
        assertEquals(1, res.getRollbackCount(), "rollbackOnly 应在外层 commit 时触发物理 rollback");
    }

    @Test
    @DisplayName("[rollbackOnly] SUPPORTS + outer rollbackOnly → 复用,commit 走 rollback")
    void existing_supports_rollbackOnly() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        outer.setRollbackOnly();

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.SUPPORTS).build());

        assertSame(outer, inner);
        MockTransactionResource res = (MockTransactionResource) outer.resource();
        tm.commit(inner);
        assertEquals(0, res.getCommitCount());
        assertEquals(1, res.getRollbackCount());
    }

    @Test
    @DisplayName("[rollbackOnly] REQUIRES_NEW → 新事务不受 outer rollbackOnly 影响")
    void existing_rollbackOnly_requiresNew_ignores() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        outer.setRollbackOnly();

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRES_NEW).build());
        MockTransactionResource innerRes = (MockTransactionResource) inner.resource();

        tm.commit(inner);
        assertEquals(1, innerRes.getCommitCount(), "REQUIRES_NEW 是独立事务,应正常 commit");
        assertEquals(0, innerRes.getRollbackCount());

        // 提交内层后,外层 resume 回来(rollbackOnly 状态被带回来)
        assertSame(outer, TransactionSynchronizationManager.getCurrentStatus());
        // 外层 commit 也走 rollback
        MockTransactionResource outerRes = (MockTransactionResource) outer.resource();
        tm.commit(outer);
        assertEquals(1, outerRes.getRollbackCount(), "resume 后 outer 仍 rollbackOnly,应 rollback");
    }

    @Test
    @DisplayName("[rollbackOnly] NESTED → 创建 savepoint,commit 走 rollbackToSavepoint")
    void existing_nested_rollbackOnly_rollbackToSavepoint() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        MockTransactionResource outerRes = (MockTransactionResource) outer.resource();
        int outerCommitsBefore = outerRes.getCommitCount();

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.NESTED).build());
        // 让内层在 commit 时判定为 rollbackOnly
        // 因为 NESTED 共享 resource 的 isRollbackOnly 状态,直接设外层即可
        outerRes.markRollbackOnly();

        tm.commit(inner);
        // NESTED 路径:rollbackToSavepoint 被调用,resource.rollback() 不被调用
        assertTrue(outerRes.getEvents().stream()
                        .anyMatch(e -> e.startsWith("rollbackToSavepoint:")),
                "rollbackOnly 时 NESTED 应触发 rollbackToSavepoint,而不是 resource.rollback()");
        assertEquals(outerCommitsBefore, outerRes.getCommitCount(),
                "rollbackOnly 不应触发 commit");
    }

    @Test
    @DisplayName("[rollbackOnly] MANDATORY → 复用,但 commit 走 rollback")
    void existing_mandatory_rollbackOnly() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        outer.setRollbackOnly();

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.MANDATORY).build());

        MockTransactionResource res = (MockTransactionResource) outer.resource();
        tm.commit(inner);
        assertEquals(1, res.getRollbackCount());
    }

    // ============ 边界场景 ============

    @Test
    @DisplayName("definition 为 null → 使用默认(REQUIRED)")
    void nullDefinition_defaultsToRequired() throws Exception {
        TransactionStatus s = tm.getTransaction(null);
        assertTrue(s.hasResource());
        assertTrue(s.isNewTransaction());
    }

    @Test
    @DisplayName("嵌套 REQUIRED 三层 → nesting count = 2")
    void nested_threeLevels_countIsTwo() throws Exception {
        TransactionStatus s1 = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        TransactionStatus s2 = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        TransactionStatus s3 = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());

        assertSame(s1, s2);
        assertSame(s2, s3);
        assertEquals(2, s3.nestingCount());
    }

    @Test
    @DisplayName("inner commit 不真正提交(嵌套语义)")
    void nestedInnerCommit_doesNotCommitPhysically() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        MockTransactionResource res = (MockTransactionResource) outer.resource();

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());

        tm.commit(inner);
        assertEquals(0, res.getCommitCount(), "嵌套内层 commit 不应真正 commit");
        assertEquals(0, outer.isCompleted() ? 1 : 0, "嵌套内层 commit 不应 mark 外层 completed");
        assertFalse(outer.isCompleted());

        tm.commit(outer);
        assertEquals(1, res.getCommitCount(), "外层 commit 才真正 commit");
        assertTrue(outer.isCompleted());
    }

    @Test
    @DisplayName("suspend/resume 后,commit 外层会触发 rollback resume 后再 outer.commit 失败? — 校验状态完整恢复")
    void suspendResume_stateFullyRestored() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        TransactionSynchronizationManager.addSynchronization(new Synchronization() {});

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRES_NEW).build());

        // 内层结束,resume 外层
        tm.commit(inner);
        // 恢复后 ThreadLocal 应是 outer
        assertSame(outer, TransactionSynchronizationManager.getCurrentStatus(),
                "内层 commit 后应 resume 外层 status");
        // suspend 前注册的 sync 应仍在
        assertNotNull(TransactionSynchronizationManager.getSynchronizations(),
                "resume 后 synchronizations 列表应恢复");
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
    }
}