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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TxScope} 单 ThreadLocal + swap 行为单测。
 *
 * <p>覆盖:</p>
 * <ul>
 *   <li>{@link TxScope#current()} 在无 ThreadLocal 时返回 empty()</li>
 *   <li>{@link TxScope#swap(TxSnapshot)} 原子交换语义(suspend / resume)</li>
 *   <li>{@link TxScope#setCurrent(TxSnapshot)} / {@link TxScope#clear()} 基本读写</li>
 *   <li>synchronizations 列表生命周期(init / add / clear)</li>
 *   <li>便捷 getter 在 null 字段上不抛</li>
 * </ul>
 *
 * <p>不覆盖 (在 {@code edap-tx} 模块的 manager 测试里):
 * suspend / resume 跨 manager 调用、nestingCount 与 snapshot 协同、savepoint 路径。</p>
 */
class TxScopeTest {

    @AfterEach
    void cleanup() {
        TxScope.clear();
    }

    /** 最小 TransactionResource stub,仅用于构造 status。 */
    private static final class NoopResource implements TransactionResource {
        @Override public void commit() {}
        @Override public void rollback() {}
        @Override public void registerSynchronization(Synchronization sync) {}
        @Override public boolean isRollbackOnly() { return false; }
    }

    // ============ basic read/write ============

    @Test
    @DisplayName("current() 在无 ThreadLocal 时返回 empty()(而非 null)")
    void current_returnsEmptyWhenUnbound() {
        TxSnapshot snap = TxScope.current();
        assertNotNull(snap, "current() 不应返回 null,应返回 empty()");
        assertNull(snap.status(), "empty() 的 status 应为 null");
        assertNull(snap.synchronizations(), "empty() 的 synchronizations 应为 null (未初始化)");
        assertFalse(TxScope.isTransactionActive(), "empty() 上 isTransactionActive 为 false");
    }

    @Test
    @DisplayName("setCurrent(snap) → current() 返回同对象")
    void setCurrent_roundtrip() {
        TransactionStatus s = new TransactionStatus(
                TransactionDefinition.builder().build(),
                new NoopResource(), true, true, false);
        TxScope.setCurrent(TxScope.current().withStatus(s));

        assertSame(s, TxScope.currentStatus());
        assertTrue(TxScope.isTransactionActive());
    }

    @Test
    @DisplayName("setCurrent(null) 等价于 clear()")
    void setCurrent_nullIsClear() {
        TxScope.setCurrent(TxScope.current().withStatus(
                new TransactionStatus(TransactionDefinition.builder().build(),
                        new NoopResource(), true, true, false)));
        TxScope.setCurrent(null);
        assertNull(TxScope.currentStatus());
    }

    @Test
    @DisplayName("clear() 后回到 empty() 状态")
    void clear_wipesBackToEmpty() {
        TxScope.setCurrent(TxScope.current().withStatus(
                new TransactionStatus(TransactionDefinition.builder().build(),
                        new NoopResource(), true, true, false)));
        TxScope.initSynchronization();
        TxScope.clear();

        TxSnapshot snap = TxScope.current();
        assertNull(snap.status());
        assertNull(snap.synchronizations());
        assertFalse(TxScope.isSynchronizationActive());
    }

    // ============ swap ============

    @Test
    @DisplayName("swap(next) 返回旧 snapshot,设置新 snapshot")
    void swap_returnsPrevSetsNext() {
        TransactionStatus s = new TransactionStatus(
                TransactionDefinition.builder().build(),
                new NoopResource(), true, true, false);
        TxSnapshot first = TxScope.current().withStatus(s);
        TxScope.setCurrent(first);

        TxSnapshot next = TxSnapshot.empty().withStatus(
                new TransactionStatus(TransactionDefinition.builder().build(),
                        new NoopResource(), true, true, false));
        TxSnapshot returned = TxScope.swap(next);

        assertSame(first, returned, "swap 应返回旧 snapshot");
        assertSame(next, TxScope.current(), "swap 应设置新 snapshot");
        assertNotSame(first, TxScope.current(), "新 snapshot 应替换旧 snapshot");
    }

    @Test
    @DisplayName("swap 在无 ThreadLocal 时返回 empty()(非 null)")
    void swap_returnsEmptyWhenUnbound() {
        TxSnapshot returned = TxScope.swap(TxSnapshot.empty());
        assertNotNull(returned);
        assertNull(returned.status(), "无 ThreadLocal 时 swap 返回的 prev 应是 empty()");
    }

    @Test
    @DisplayName("swap(empty) 等价于 suspend 到无事务")
    void swap_empty_clearsCurrent() {
        TxScope.setCurrent(TxScope.current().withStatus(
                new TransactionStatus(TransactionDefinition.builder().build(),
                        new NoopResource(), true, true, false)));
        TxSnapshot suspended = TxScope.swap(TxSnapshot.empty());

        assertNotNull(suspended.status(), "suspend 应保留旧 status");
        assertNull(TxScope.currentStatus(), "suspend 后 ThreadLocal 应无 status");
    }

    @Test
    @DisplayName("连续 swap 模拟多层 REQUIRES_NEW 嵌套挂起栈")
    void swap_multiLevelSuspendStack() {
        TransactionStatus outer = new TransactionStatus(
                TransactionDefinition.builder().build(),
                new NoopResource(), true, true, false);
        TxSnapshot outerSnap = TxScope.current().withStatus(outer);
        TxScope.setCurrent(outerSnap);

        // 第一层 inner1:挂起 outer(swap 返回 outerSnap 并把 current 清空)
        TxSnapshot suspendedOuter = TxScope.swap(TxSnapshot.empty());
        assertSame(outerSnap, suspendedOuter, "swap(empty) 应返回 current 当时的 snapshot(outerSnap)");
        assertNull(TxScope.currentStatus(), "swap(empty) 后 current 应无 status");

        TransactionStatus inner1 = new TransactionStatus(
                TransactionDefinition.builder().build(),
                new NoopResource(), true, true, false);
        TxSnapshot inner1Snap = TxScope.current().withStatus(inner1);
        TxScope.setCurrent(inner1Snap);
        inner1.setSuspendedSnapshot(suspendedOuter);

        // 第二层 inner2:挂起 inner1
        TxSnapshot suspendedInner1 = TxScope.swap(TxSnapshot.empty());
        assertSame(inner1Snap, suspendedInner1, "swap(empty) 应返回 inner1Snap");

        TransactionStatus inner2 = new TransactionStatus(
                TransactionDefinition.builder().build(),
                new NoopResource(), true, true, false);
        TxSnapshot inner2Snap = TxScope.current().withStatus(inner2);
        TxScope.setCurrent(inner2Snap);
        inner2.setSuspendedSnapshot(suspendedInner1);

        // resume inner1:swap(inner2.suspendedSnapshot) 把 inner1Snap 恢复回 current,
        // 返回的是 swap 之前的 current(inner2Snap)
        TxSnapshot inner2SnapBefore = TxScope.current();
        TxSnapshot resumed1 = TxScope.swap(inner2.getSuspendedSnapshot());
        inner2.setSuspendedSnapshot(null);
        assertSame(inner2SnapBefore, resumed1, "swap 恢复应返回 swap 前的 current(inner2Snap)");
        assertSame(inner1, TxScope.currentStatus(), "resume 后 ThreadLocal 应是 inner1");

        // resume outer:同理,swap 前先存 inner1Snap,swap 后断言 current 是 outer
        TxSnapshot inner1SnapBefore = TxScope.current();
        TxSnapshot resumed0 = TxScope.swap(inner1.getSuspendedSnapshot());
        inner1.setSuspendedSnapshot(null);
        assertSame(inner1SnapBefore, resumed0, "swap 恢复应返回 swap 前的 current(inner1Snap)");
        assertSame(outer, TxScope.currentStatus(), "resume 后 ThreadLocal 应是 outer");
    }

    // ============ convenience getters ============

    @Test
    @DisplayName("currentStatus 在无 status 时返回 null(不抛)")
    void currentStatus_nullSafe() {
        assertNull(TxScope.currentStatus());
    }

    @Test
    @DisplayName("currentResources 在无 resources 时返回空 map(不抛)")
    void currentResources_emptyWhenUnbound() {
        assertNotNull(TxScope.currentResources());
        assertTrue(TxScope.currentResources().isEmpty());
    }

    @Test
    @DisplayName("currentSynchronizations 在未 init 时返回 null(区分于空 list)")
    void currentSynchronizations_nullWhenUninitialized() {
        assertNull(TxScope.currentSynchronizations());
    }

    @Test
    @DisplayName("currentXid 在无 xid 时返回 null")
    void currentXid_nullWhenUnset() {
        assertNull(TxScope.currentXid());
    }

    // ============ synchronization lifecycle ============

    @Test
    @DisplayName("未 init 时 addSynchronization 抛 IllegalTransactionStateException")
    void addSyncWithoutInit_throws() {
        assertFalse(TxScope.isSynchronizationActive());
        assertThrows(IllegalTransactionStateException.class,
                () -> TxScope.addSynchronization(new Synchronization() {}));
    }

    @Test
    @DisplayName("initSynchronization 后 list 可 add,可 clear")
    void synchronizationLifecycle() {
        TxScope.initSynchronization();
        assertTrue(TxScope.isSynchronizationActive(), "init 后 isSynchronizationActive 应为 true");

        List<Synchronization> expected = new ArrayList<>();
        Synchronization s1 = new Synchronization() {};
        Synchronization s2 = new Synchronization() {};
        TxScope.addSynchronization(s1);
        TxScope.addSynchronization(s2);
        expected.add(s1);
        expected.add(s2);
        assertEquals(expected, TxScope.currentSynchronizations());

        List<Synchronization> cleared = TxScope.clearSynchronization();
        assertEquals(expected, cleared, "clearSynchronization 应返回所有已注册 callback");
        assertNull(TxScope.currentSynchronizations(), "clear 后 list 应回到 null(未初始化)");
        assertFalse(TxScope.isSynchronizationActive());
    }

    @Test
    @DisplayName("initSynchronization 重复调用幂等")
    void initSynchronization_idempotent() {
        TxScope.initSynchronization();
        Synchronization s = new Synchronization() {};
        TxScope.addSynchronization(s);

        TxScope.initSynchronization();   // 二次 init 不应清空已有 list
        assertEquals(1, TxScope.currentSynchronizations().size(),
                "二次 init 不应覆盖已有 callback 列表");
    }

    @Test
    @DisplayName("clearSynchronization 在未 init 时返回空 list(不抛)")
    void clearSync_uninitialized_returnsEmpty() {
        assertFalse(TxScope.isSynchronizationActive());
        List<Synchronization> cleared = TxScope.clearSynchronization();
        assertNotNull(cleared);
        assertTrue(cleared.isEmpty());
    }
}