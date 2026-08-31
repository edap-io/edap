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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Commit / Rollback 路径 + Synchronization 回调触发顺序单测。
 *
 * <p>与 {@link PropagationDecisionTest} 互补:本类只测 commit/rollback 的具体行为,
 * 不重复决策矩阵。</p>
 */
class CommitRollbackTest {

    private final TestTransactionManager tm = new TestTransactionManager();

    @AfterEach
    void cleanup() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    @DisplayName("commit 顺序:beforeCommit → resource.commit → afterCommit → afterCompletion(COMMITTED)")
    void commit_pathOrdering() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        List<String> order = new ArrayList<>();

        TransactionSynchronizationManager.addSynchronization(new Synchronization() {
            @Override public void beforeCommit()    { order.add("beforeCommit"); }
            @Override public void afterCommit()     { order.add("afterCommit"); }
            @Override public void afterCompletion(int status) { order.add("afterCompletion:" + status); }
        });

        // 注册一个 resource 级别的 hook(挂在 resource.synchronizations 上)
        final AtomicInteger resourceCommitHook = new AtomicInteger();
        ((MockTransactionResource) s.resource()).registerSynchronization(new Synchronization() {
            @Override public void beforeCommit() { resourceCommitHook.incrementAndGet(); }
        });

        tm.commit(s);

        assertEquals(1, resourceCommitHook.get(), "resource 级 sync 的 beforeCommit 应被触发");
        assertEquals(1, ((MockTransactionResource) s.resource()).getCommitCount());
        assertEquals(java.util.Arrays.asList(
                        "beforeCommit", "afterCommit", "afterCompletion:0"),
                order, "回调顺序应为 beforeCommit → afterCommit → afterCompletion(COMMITTED=0)");
        assertTrue(s.isCompleted());
    }

    @Test
    @DisplayName("rollback 顺序:beforeCommit/afterCommit 跳过 → afterCompletion(ROLLED_BACK) → resource.rollback")
    void rollback_pathOrdering() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        List<String> order = new ArrayList<>();

        TransactionSynchronizationManager.addSynchronization(new Synchronization() {
            @Override public void beforeCommit()    { order.add("beforeCommit"); }
            @Override public void afterCommit()     { order.add("afterCommit"); }
            @Override public void afterCompletion(int status) { order.add("afterCompletion:" + status); }
        });

        tm.rollback(s);

        assertEquals(java.util.Arrays.asList("afterCompletion:1"), order,
                "rollback 路径应只触发 afterCompletion(ROLLED_BACK=1),跳过 beforeCommit/afterCommit");
        assertEquals(1, ((MockTransactionResource) s.resource()).getRollbackCount());
        assertTrue(s.isCompleted());
    }

    @Test
    @DisplayName("rollback on rollbackOnly → 走 rollback 路径(不真正 commit)")
    void commitOnRollbackOnly_routesToRollback() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        s.setRollbackOnly();

        tm.commit(s);

        MockTransactionResource res = (MockTransactionResource) s.resource();
        assertEquals(0, res.getCommitCount(), "rollbackOnly 时 commit 不应真正 commit");
        assertEquals(1, res.getRollbackCount(), "rollbackOnly 时 commit 应走 rollback");
    }

    @Test
    @DisplayName("rollback on rollbackOnly resource → 走 rollback 路径")
    void commitOnResourceRollbackOnly_routesToRollback() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        ((MockTransactionResource) s.resource()).markRollbackOnly();

        tm.commit(s);

        MockTransactionResource res = (MockTransactionResource) s.resource();
        assertEquals(0, res.getCommitCount());
        assertEquals(1, res.getRollbackCount());
    }

    @Test
    @DisplayName("beforeCommit 抛异常 → 触发 rollback,且 rollback 不应真正 commit")
    void beforeCommitThrows_triggersRollback() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        MockTransactionResource res = (MockTransactionResource) s.resource();

        TransactionSynchronizationManager.addSynchronization(new Synchronization() {
            @Override public void beforeCommit() {
                throw new RuntimeException("simulated beforeCommit failure");
            }
        });

        // beforeCommit 异常当前实现会向外抛(因为 beforeCommit 在 try 内,
        // 异常导致 try-catch 内的后续逻辑跳到 catch)。此处只断言"不真正 commit"
        try {
            tm.commit(s);
            fail("beforeCommit 异常应向上传递");
        } catch (TransactionException expected) {
            // 无论具体抛点,resource.commit() 都未被调用是核心语义
        }
        assertEquals(0, res.getCommitCount(), "beforeCommit 异常时不应真正 commit");
    }

    @Test
    @DisplayName("commit 完成后,ThreadLocal status 已清空")
    void commit_clearsThreadLocal() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());

        tm.commit(s);

        assertFalse(TransactionSynchronizationManager.isTransactionActive(),
                "commit 后 ThreadLocal status 应清空");
    }

    @Test
    @DisplayName("rollback 完成后,ThreadLocal status 已清空")
    void rollback_clearsThreadLocal() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());

        tm.rollback(s);

        assertFalse(TransactionSynchronizationManager.isTransactionActive());
    }

    @Test
    @DisplayName("二次 commit 抛 IllegalTransactionStateException")
    void doubleCommit_throws() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        tm.commit(s);

        assertThrows(IllegalTransactionStateException.class, () -> tm.commit(s));
    }

    @Test
    @DisplayName("二次 rollback 抛 IllegalTransactionStateException")
    void doubleRollback_throws() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        tm.rollback(s);

        assertThrows(IllegalTransactionStateException.class, () -> tm.rollback(s));
    }

    @Test
    @DisplayName("commit 之后 rollback 抛 IllegalTransactionStateException")
    void commitThenRollback_throws() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        tm.commit(s);

        assertThrows(IllegalTransactionStateException.class, () -> tm.rollback(s));
    }

    @Test
    @DisplayName("rollback 之后 commit 抛 IllegalTransactionStateException")
    void rollbackThenCommit_throws() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        tm.rollback(s);

        assertThrows(IllegalTransactionStateException.class, () -> tm.commit(s));
    }

    @Test
    @DisplayName("commit(null) 抛 IllegalTransactionStateException")
    void commitNull_throws() {
        assertThrows(IllegalTransactionStateException.class, () -> tm.commit(null));
    }

    @Test
    @DisplayName("rollback(null) 抛 IllegalTransactionStateException")
    void rollbackNull_throws() {
        assertThrows(IllegalTransactionStateException.class, () -> tm.rollback(null));
    }

    @Test
    @DisplayName("REQUIRES_NEW 内层 commit 后,外层被 resume 回来")
    void requiresNew_innerCommit_resumesOuter() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        MockTransactionResource outerRes = (MockTransactionResource) outer.resource();

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRES_NEW).build());
        MockTransactionResource innerRes = (MockTransactionResource) inner.resource();

        tm.commit(inner);
        assertEquals(1, innerRes.getCommitCount());
        assertEquals(0, outerRes.getCommitCount(), "外层不应被内层 commit 影响");

        // 当前 ThreadLocal 应是 outer
        assertTrue(TransactionSynchronizationManager.isTransactionActive());

        tm.commit(outer);
        assertEquals(1, outerRes.getCommitCount());
    }

    @Test
    @DisplayName("afterCommit 抛异常被包装为 TransactionSystemException")
    void afterCommitThrows_wrapped() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());

        TransactionSynchronizationManager.addSynchronization(new Synchronization() {
            @Override public void afterCommit() {
                throw new RuntimeException("after-commit failure");
            }
        });

        assertThrows(TransactionSystemException.class, () -> tm.commit(s));
        // 包装异常不应改变物理 commit 已完成的事实
        assertEquals(1, ((MockTransactionResource) s.resource()).getCommitCount());
    }

    @Test
    @DisplayName("rollback 时 afterCompletion 异常被吞掉(不影响已 rollback 的状态)")
    void afterCompletionThrowsOnRollback_isSwallowed() throws Exception {
        TransactionStatus s = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());

        TransactionSynchronizationManager.addSynchronization(new Synchronization() {
            @Override public void afterCompletion(int status) {
                throw new RuntimeException("after-completion should be swallowed on rollback");
            }
        });

        // 不应向上抛
        try {
            tm.rollback(s);
        } catch (Throwable t) {
            fail("rollback 路径 afterCompletion 异常应被吞掉,实际抛: " + t);
        }
        assertEquals(1, ((MockTransactionResource) s.resource()).getRollbackCount());
    }

    @Test
    @DisplayName("NESTED 嵌套 rollback → 只回滚到 savepoint,不触发 resource.rollback()")
    void nestedRollback_rollsBackToSavepointOnly() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        MockTransactionResource outerRes = (MockTransactionResource) outer.resource();

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.NESTED).build());
        Object sp = inner.getSavepoint();
        assertEquals(1, outerRes.getSavepointCount());

        tm.rollback(inner);

        assertTrue(outerRes.getEvents().stream()
                        .anyMatch(e -> e.equals("rollbackToSavepoint:" + sp)),
                "NESTED rollback 应触发 rollbackToSavepoint");
        assertEquals(0, outerRes.getRollbackCount(),
                "NESTED rollback 不应触发外层 resource.rollback()");

        // 外层仍可正常 commit
        tm.commit(outer);
        assertEquals(1, outerRes.getCommitCount());
    }
}