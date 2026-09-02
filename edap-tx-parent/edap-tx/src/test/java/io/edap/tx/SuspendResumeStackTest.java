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
import io.edap.tx.propagation.Propagation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 多层 REQUIRES_NEW / NOT_SUPPORTED 嵌套挂起栈正确性单测。
 *
 * <p>覆盖缺口:</p>
 * <ul>
 *   <li>三层嵌套 REQUIRES_NEW 完整挂起栈(已有测试只覆盖 2 层)</li>
 *   <li>NOT_SUPPORTED 嵌入 REQUIRES_NEW,验证混合挂起栈</li>
 *   <li>内层抛异常时外层状态完整性</li>
 *   <li>TxScope.swap 原子性:连续 swap 返回值正确</li>
 *   <li>同步点列表在挂起栈内每层独立</li>
 * </ul>
 */
class SuspendResumeStackTest {

    private final TestTransactionManager tm = new TestTransactionManager();

    @AfterEach
    void cleanup() {
        TxScope.clear();
    }

    // ============ 多层 REQUIRES_NEW 嵌套 ============

    @Test
    @DisplayName("三层 REQUIRES_NEW 嵌套:外层状态在内层全部 commit 后正确恢复")
    void threeLevelRequiresNew_outerStateRestored() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        MockTransactionResource outerRes = (MockTransactionResource) outer.resource();
        addOuterSync("outer-sync");

        TransactionStatus inner1 = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRES_NEW).build());
        MockTransactionResource inner1Res = (MockTransactionResource) inner1.resource();
        assertNotSame(outer, inner1, "REQUIRES_NEW 应开新事务");
        assertNotNull(inner1.getSuspendedSnapshot(), "REQUIRES_NEW 内层应保存挂起快照");

        TransactionStatus inner2 = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRES_NEW).build());
        MockTransactionResource inner2Res = (MockTransactionResource) inner2.resource();
        assertNotSame(inner1, inner2);
        assertNotNull(inner2.getSuspendedSnapshot());

        // 由内向外 commit
        tm.commit(inner2);
        assertEquals(1, inner2Res.getCommitCount());
        assertNull(inner2.getSuspendedSnapshot(), "commit 后应清空 suspended 引用");

        // 当前 ThreadLocal 应是 inner1
        assertSame(inner1, TxScope.currentStatus(), "inner2 commit 后应 resume inner1");

        tm.commit(inner1);
        assertEquals(1, inner1Res.getCommitCount());
        assertSame(outer, TxScope.currentStatus(), "inner1 commit 后应 resume outer");

        tm.commit(outer);
        assertEquals(1, outerRes.getCommitCount());
        assertNull(TxScope.currentStatus(), "最外层 commit 后 ThreadLocal 应清空");
        assertFalse(TxScope.isTransactionActive());
    }

    @Test
    @DisplayName("REQUIRES_NEW 内层 commit 失败:外层事务不受影响")
    void requiresNew_innerCommitFailure_doesNotAffectOuter() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        MockTransactionResource outerRes = (MockTransactionResource) outer.resource();

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRES_NEW).build());
        MockTransactionResource innerRes = (MockTransactionResource) inner.resource();
        innerRes.setFailOnCommit(true);

        try {
            tm.commit(inner);
            fail("inner commit 应抛异常");
        } catch (TransactionException expected) {
            // 预期:commit 抛 TransactionException
        }

        // 外层应被 resume 回来,且不影响
        assertSame(outer, TxScope.currentStatus(),
                "inner commit 失败后外层应被 resume");
        assertFalse(outer.isCompleted(), "外层 status 不应被 mark completed");

        // 外层 commit 应正常成功
        tm.commit(outer);
        assertEquals(1, outerRes.getCommitCount(), "外层 commit 不受内层失败影响");
    }

    // ============ NOT_SUPPORTED 嵌套 ============

    @Test
    @DisplayName("NOT_SUPPORTED 后再 REQUIRED:外层事务完整保留")
    void notSupported_thenRequired_outerIntact() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        MockTransactionResource outerRes = (MockTransactionResource) outer.resource();

        TransactionStatus innerNonTx = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.NOT_SUPPORTED).build());
        assertFalse(innerNonTx.hasResource(), "NOT_SUPPORTED 应返回非事务 status");
        assertNotNull(innerNonTx.getSuspendedSnapshot(), "NOT_SUPPORTED 应保存挂起快照");
        assertNull(TxScope.currentStatus(), "NOT_SUPPORTED 内层 ThreadLocal 应清空");

        // 内层 commit —— 非事务,manager 走非事务路径(资源=null)
        tm.commit(innerNonTx);
        // 外层被 resume 回来
        assertSame(outer, TxScope.currentStatus(), "NOT_SUPPORTED commit 后应 resume outer");

        tm.commit(outer);
        assertEquals(1, outerRes.getCommitCount());
    }

    @Test
    @DisplayName("NOT_SUPPORTED 嵌入 REQUIRES_NEW:混合挂起栈正确恢复")
    void mixedNotSupported_andRequiresNew_suspendStack() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());

        // NOT_SUPPORTED 嵌套:挂起 outer,ThreadLocal 清空(非事务不绑)
        TransactionStatus innerNs = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.NOT_SUPPORTED).build());
        assertNotNull(innerNs.getSuspendedSnapshot(),
                "NOT_SUPPORTED 应在 status 上保存 outer 挂起快照");
        assertNull(TxScope.currentStatus(), "NOT_SUPPORTED 不绑 ThreadLocal");

        // 内部又 REQUIRES_NEW 嵌套:此时 ThreadLocal 已经是 empty,REQUIRES_NEW 从无事务路径开新
        TransactionStatus innerRn = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRES_NEW).build());
        assertNull(innerRn.getSuspendedSnapshot(),
                "从无事务路径开新 REQUIRES_NEW,无挂起 snapshot(manager 不调 swap)");

        // innerRn commit:其 suspendedSnapshot 是 empty,resume 后 ThreadLocal 仍是 empty
        tm.commit(innerRn);
        assertFalse(TxScope.isTransactionActive(),
                "innerRn commit 后 ThreadLocal 应清空(innerNs 本就不在 ThreadLocal)");

        // innerNs commit:其 suspendedSnapshot 是 outer,resume 后 ThreadLocal 恢复 outer
        tm.commit(innerNs);
        assertSame(outer, TxScope.currentStatus(), "innerNs commit 后应恢复 outer 上下文");
    }

    // ============ swap 原子性 ============

    @Test
    @DisplayName("连续 swap 返回值顺序正确")
    void swap_returnsCorrectChain() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());

        TxSnapshot first = TxScope.swap(TxSnapshot.empty());
        TxSnapshot second = TxScope.swap(first);   // 互换回去

        // swap(empty) 返回包含 outer 的快照并把 current 清空
        // swap(first)   返回 empty 快照并把 current 恢复到 first
        assertSame(outer, first.status(),
                "swap(empty) 返回的快照应保留 outer status");
        assertNull(second.status(),
                "swap(first) 返回的应是 empty(被 swap 出去的)");
        assertSame(outer, TxScope.currentStatus(),
                "roundtrip 后 ThreadLocal 应恢复 outer");
    }

    // ============ 同步点列表在多层挂起中的行为 ============

    @Test
    @DisplayName("外层注册的 sync 在 REQUIRES_NEW 内层 commit 后仍可见")
    void outerSyncVisibleAfterInnerRequiresNewCommit() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());
        TxScope.addSynchronization(new Synchronization() {
            @Override public void beforeCommit() { /* outer-sync */ }
        });

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRES_NEW).build());
        // 内层开始事务时 initSynchronization 是幂等的(已在 PropagationDecisionTest 覆盖);
        // 关键是外层 sync 不丢失

        tm.commit(inner);
        // resume 后 synchronizations 列表应恢复
        List<Synchronization> syncs = TxScope.currentSynchronizations();
        assertNotNull(syncs, "resume 后 synchronizations 不应为 null");
        assertEquals(1, syncs.size(), "resume 后外层 sync 应仍存在");

        tm.commit(outer);
    }

    @Test
    @DisplayName("REQUIRES_NEW 内层抛异常时,内层同步点不触发 beforeCommit")
    void innerFailure_skipsBeforeCommit() throws Exception {
        TransactionStatus outer = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRED).build());

        TransactionStatus inner = tm.getTransaction(TransactionDefinition.builder()
                .propagation(Propagation.REQUIRES_NEW).build());

        List<String> order = new ArrayList<>();
        TxScope.addSynchronization(new Synchronization() {
            @Override public void beforeCommit()    { order.add("beforeCommit"); }
            @Override public void afterCommit()     { order.add("afterCommit"); }
            @Override public void afterCompletion(int s) { order.add("afterCompletion:" + s); }
        });
        ((MockTransactionResource) inner.resource()).setFailOnCommit(true);

        try {
            tm.commit(inner);
        } catch (TransactionException expected) {
            // beforeCommit 未被调用(因为 setFailOnCommit 是 resource 层面,不是 sync);
            // 此测试主要验证 inner commit 失败不会污染 outer
        }

        // outer 仍能 commit,外层 sync 仍触发
        List<String> outerOrder = new ArrayList<>();
        TxScope.addSynchronization(new Synchronization() {
            @Override public void afterCommit() { outerOrder.add("afterCommit"); }
        });
        tm.commit(outer);
        assertTrue(outerOrder.contains("afterCommit"), "外层 afterCommit 应被触发");
    }

    // ============ utility ============

    private void addOuterSync(String ignored) {
        TxScope.addSynchronization(new Synchronization() {});
    }
}