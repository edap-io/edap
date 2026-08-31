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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TransactionSynchronizationManager} 的 ThreadLocal 隔离 / suspend / resume
 * 行为单测。
 *
 * <p>本类聚焦于 ThreadLocal 工具本身的语义——决策矩阵不在这里覆盖
 * (见 {@code edap-tx} 模块的 {@code PropagationDecisionTest})。</p>
 */
class TransactionSynchronizationManagerTest {

    @AfterEach
    void cleanup() {
        TransactionSynchronizationManager.clear();
    }

    /** API 测试用的最小 TransactionResource 实现 —— 不依赖 edap-tx impl 的 MockTransactionResource。 */
    private static final class NoopResource implements TransactionResource {
        @Override public void commit() {}
        @Override public void rollback() {}
        @Override public void registerSynchronization(Synchronization sync) {}
        @Override public boolean isRollbackOnly() { return false; }
    }

    @Test
    @DisplayName("bindStatus / getCurrentStatus / unbindStatus 单线程顺序")
    void basicBindGetUnbind() {
        assertNull(TransactionSynchronizationManager.getCurrentStatus());

        TransactionStatus s = new TransactionStatus(
                TransactionDefinition.builder().build(), null, true, true, false);
        TransactionSynchronizationManager.bindStatus(s);
        assertSame(s, TransactionSynchronizationManager.getCurrentStatus());

        TransactionStatus returned = TransactionSynchronizationManager.unbindStatus();
        assertSame(s, returned);
        assertNull(TransactionSynchronizationManager.getCurrentStatus());
    }

    @Test
    @DisplayName("isTransactionActive 在无 status 时为 false,在 status 有 resource 时为 true")
    void isTransactionActive_reflectsResource() {
        assertFalse(TransactionSynchronizationManager.isTransactionActive());

        // resource=null → 非事务
        TransactionStatus nonTx = new TransactionStatus(
                TransactionDefinition.builder().build(), null, false, false, false);
        TransactionSynchronizationManager.bindStatus(nonTx);
        assertFalse(TransactionSynchronizationManager.isTransactionActive(),
                "resource=null 时 isTransactionActive 应为 false");

        // 有 resource → 是事务
        TransactionStatus tx = new TransactionStatus(
                TransactionDefinition.builder().build(),
                new NoopResource(), true, true, false);
        TransactionSynchronizationManager.bindStatus(tx);
        assertTrue(TransactionSynchronizationManager.isTransactionActive());

        TransactionSynchronizationManager.clear();
    }

    @Test
    @DisplayName("synchronizations 列表 init / add / clear")
    void synchronizationsLifecycle() {
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
        assertNull(TransactionSynchronizationManager.getSynchronizations());

        TransactionSynchronizationManager.initSynchronization();
        assertTrue(TransactionSynchronizationManager.isSynchronizationActive());

        // 未 init 时 add 应抛错
        TransactionSynchronizationManager.clear();
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
        assertThrows(IllegalStateException.class,
                () -> TransactionSynchronizationManager.addSynchronization(new Synchronization() {}));

        // init 后 add 正常
        TransactionSynchronizationManager.initSynchronization();
        Synchronization sync = new Synchronization() {};
        TransactionSynchronizationManager.addSynchronization(sync);
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());

        // clearSynchronization 取出并清空
        assertEquals(java.util.Collections.singletonList(sync),
                TransactionSynchronizationManager.clearSynchronization());
        assertNull(TransactionSynchronizationManager.getSynchronizations());
    }

    @Test
    @DisplayName("bindResource / hasResource / unbindResource 多资源 key 隔离")
    void resourceMapMultipleKeys() {
        Object key1 = new Object();
        Object key2 = new Object();
        Object value1 = new Object();
        Object value2 = new Object();

        assertFalse(TransactionSynchronizationManager.hasResource(key1));

        TransactionSynchronizationManager.bindResource(key1, value1);
        TransactionSynchronizationManager.bindResource(key2, value2);

        assertSame(value1, TransactionSynchronizationManager.getResource(key1));
        assertSame(value2, TransactionSynchronizationManager.getResource(key2));

        // 取一个 key 的值,其他 key 不受影响
        assertSame(value1, TransactionSynchronizationManager.unbindResource(key1));
        assertFalse(TransactionSynchronizationManager.hasResource(key1));
        assertTrue(TransactionSynchronizationManager.hasResource(key2));

        // 取最后一个 key,map 应清空
        assertSame(value2, TransactionSynchronizationManager.unbindResource(key2));
        assertFalse(TransactionSynchronizationManager.hasResource(key2));
        assertNull(TransactionSynchronizationManager.getResourceMap());
    }

    @Test
    @DisplayName("suspend / resumeSuspended 完整快照往返")
    void suspendResumeFullRoundTrip() {
        TransactionStatus s = new TransactionStatus(
                TransactionDefinition.builder().propagation(Propagation.REQUIRES_NEW).build(),
                new NoopResource(), true, true, false);
        TransactionSynchronizationManager.bindStatus(s);
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.addSynchronization(new Synchronization() {});
        TransactionSynchronizationManager.bindResource("k", "v");
        TransactionSynchronizationManager.bindCurrentXid("xid-001");

        TransactionSynchronizationManager.SuspendedResources snap =
                TransactionSynchronizationManager.suspend();

        assertNotNull(snap);
        // suspend 后 ThreadLocal 已清空
        assertNull(TransactionSynchronizationManager.getCurrentStatus());
        assertNull(TransactionSynchronizationManager.getSynchronizations());
        assertNull(TransactionSynchronizationManager.getResourceMap());
        assertNull(TransactionSynchronizationManager.getCurrentXid());

        // resume 应还原
        TransactionSynchronizationManager.resumeSuspended(snap);
        assertSame(s, TransactionSynchronizationManager.getCurrentStatus());
        assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
        assertSame("v", TransactionSynchronizationManager.getResource("k"));
        assertEquals("xid-001", TransactionSynchronizationManager.getCurrentXid());
    }

    @Test
    @DisplayName("suspend 在无 ThreadLocal 状态时返回 null")
    void suspendNoStateReturnsNull() {
        assertNull(TransactionSynchronizationManager.suspend());
    }

    @Test
    @DisplayName("resumeSuspended(null) 不抛异常(no-op)")
    void resumeSuspendedNull_isNoOp() {
        // 不应抛
        TransactionSynchronizationManager.resumeSuspended(null);
        assertNull(TransactionSynchronizationManager.getCurrentStatus());
    }

    @Test
    @DisplayName("clear 清空所有 ThreadLocal")
    void clear_wipesEverything() {
        TransactionSynchronizationManager.bindStatus(new TransactionStatus(
                TransactionDefinition.builder().build(), null, true, true, false));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.addSynchronization(new Synchronization() {});
        TransactionSynchronizationManager.bindResource("k", "v");
        TransactionSynchronizationManager.bindCurrentXid("xid");

        TransactionSynchronizationManager.clear();

        assertNull(TransactionSynchronizationManager.getCurrentStatus());
        assertNull(TransactionSynchronizationManager.getSynchronizations());
        assertNull(TransactionSynchronizationManager.getResourceMap());
        assertNull(TransactionSynchronizationManager.getCurrentXid());
        assertFalse(TransactionSynchronizationManager.isTransactionActive());
    }
}