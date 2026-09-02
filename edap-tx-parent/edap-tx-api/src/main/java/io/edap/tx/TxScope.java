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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 事务作用域——单 ThreadLocal 持有 {@link TxSnapshot},替代原
 * {@code TransactionSynchronizationManager} 的 6 个散装 ThreadLocal。
 *
 * <p><b>核心 API</b>:</p>
 * <ul>
 *   <li>{@link #current()} / {@link #setCurrent(TxSnapshot)} / {@link #clear()}——直接读写</li>
 *   <li>{@link #swap(TxSnapshot)}——原子交换,返回旧 snapshot;用于 suspend / resume 模式</li>
 *   <li>{@link #currentStatus()} / {@link #currentSynchronizations()} / {@link #currentResources()}
 *       / {@link #currentXid()}——便捷读取,空时返回 null / 空集合,不抛</li>
 * </ul>
 *
 * <p><b>synchronizations 列表管理</b>:</p>
 * <ul>
 *   <li>{@link #initSynchronization()}——开新事务时调用,新建空 list 写回 snapshot</li>
 *   <li>{@link #addSynchronization(Synchronization)}——业务方/resource 注册回调;未 init 时抛异常</li>
 *   <li>{@link #clearSynchronization()}——commit/rollback 后取出并清空列表</li>
 *   <li>{@link #isSynchronizationActive()}——是否已 init(true 表示 list 已就绪,可能为空)</li>
 * </ul>
 *
 * <p><b>关键变化 vs 旧 TSM</b>:</p>
 * <ul>
 *   <li>移除 {@code suspend()} / {@code resumeSuspended()} / {@code SuspendedResources}
 *       —— 由 manager 直接通过 {@link #swap(TxSnapshot)} 原子交换实现</li>
 *   <li>移除 {@code getWrapperDepth()} / {@code setWrapperDepth(int)}——
 *       wrapper 调用链深度不再需要 ThreadLocal 计数,proper wrapper discipline 不留 stale state</li>
 *   <li>移除 {@code bindResource} / {@code unbindResource} / {@code hasResource}——
 *       resources 通过 snapshot.withResources() 派生新 snapshot 实现</li>
 * </ul>
 */
public final class TxScope {

    /**
     * 单 ThreadLocal 持有当前活跃 snapshot。ThreadLocal.get() 返回 null 表示
     * 当前线程无任何事务上下文(snapshot 视同 {@link TxSnapshot#empty()})。
     */
    private static final ThreadLocal<TxSnapshot> CURRENT = new ThreadLocal<>();

    private TxScope() {
        // 工具类,不允许实例化
    }

    // ============ 直接读写 ============

    /**
     * 取当前线程 snapshot;无绑定时返回 {@link TxSnapshot#empty()}() 而非 null,
     * 调用方无需 null 检查。
     */
    public static TxSnapshot current() {
        TxSnapshot s = CURRENT.get();
        return s == null ? TxSnapshot.empty() : s;
    }

    /**
     * 设置当前线程 snapshot。manager / 业务侧一般通过 {@link #swap(TxSnapshot)}
     * 间接修改;此方法保留供 wrapper 入口直接覆盖(ThreadLocal 干净状态启动时)。
     */
    public static void setCurrent(TxSnapshot snapshot) {
        if (snapshot == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(snapshot);
        }
    }

    /**
     * 原子交换——返回旧 snapshot(无绑定时返回 {@link TxSnapshot#empty()}),设置新 snapshot。
     *
     * <p>这是 suspend / resume 的核心原语:</p>
     * <pre>
     *   // suspend:保存当前、清空 ThreadLocal
     *   TxSnapshot suspended = TxScope.swap(TxSnapshot.empty());
     *   status.setSuspendedSnapshot(suspended);
     *
     *   // resume:从 status 取回保存的 snapshot,还原
     *   TxScope.swap(status.getSuspendedSnapshot());
     *   status.setSuspendedSnapshot(null);
     * </pre>
     */
    public static TxSnapshot swap(TxSnapshot next) {
        TxSnapshot prev = CURRENT.get();
        if (next == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(next);
        }
        return prev == null ? TxSnapshot.empty() : prev;
    }

    /**
     * 清空 ThreadLocal——拦截器 finally / 测试 tearDown 用,避免线程复用导致的上下文泄漏。
     */
    public static void clear() {
        CURRENT.remove();
    }

    // ============ 便捷读取(不抛异常的纯 getter) ============

    /**
     * 当前活跃事务 status。无事务上下文时返回 null。{@link TxConnectionHolder} 等
     * 外部模块用此判断"是否在事务中"以决定是否走共享连接路径。
     */
    public static TransactionStatus currentStatus() {
        return current().status();
    }

    /**
     * 当前同步点列表。null 表示"未初始化",空 list 表示"已初始化但无回调"。
     * 用 {@link #isSynchronizationActive()} 区分这两种情况。
     */
    public static List<Synchronization> currentSynchronizations() {
        return current().synchronizations();
    }

    public static Map<Object, Object> currentResources() {
        return current().resourcesOrEmpty();
    }

    public static String currentXid() {
        return current().xid();
    }

    /**
     * 当前是否存在活跃事务——status 不为 null 且有真实 resource。
     * SUPPORTS + 无外层 tx 的"非事务帧"返回 false。
     */
    public static boolean isTransactionActive() {
        return current().hasTransaction();
    }

    // ============ synchronizations 列表管理 ============

    /**
     * 是否已初始化 synchronizations 列表(开新事务路径上调一次)。
     * list 可能为空(已初始化但还没 add 过),不能与"未初始化"混为一谈。
     */
    public static boolean isSynchronizationActive() {
        return current().synchronizations() != null;
    }

    /**
     * 初始化 synchronizations 列表——开新事务时由 manager 在
     * {@code bindStatus(status)} 后调一次。若已初始化,直接返回(不覆盖,保留已注册的回调)。
     */
    public static void initSynchronization() {
        if (isSynchronizationActive()) {
            return;
        }
        CURRENT.set(current().withSynchronizations(new ArrayList<>()));
    }

    /**
     * 注册同步点回调。{@link #isSynchronizationActive()} == false 时抛异常
     * —— 业务方未通过 wrapper 路径直接调本方法通常是框架 bug,早期暴露。
     */
    public static void addSynchronization(Synchronization sync) {
        if (!isSynchronizationActive()) {
            throw new IllegalTransactionStateException(
                    "Synchronization not active — call initSynchronization() first "
                    + "(typically done by manager when starting a new transaction)");
        }
        List<Synchronization> list = current().synchronizations();
        list.add(sync);
    }

    /**
     * 取出当前 synchronizations 列表并清空。manager 在 commit / rollback 时调,触发回调。
     * 未初始化时返回空列表,不抛。
     */
    public static List<Synchronization> clearSynchronization() {
        List<Synchronization> list = current().synchronizations();
        CURRENT.set(current().withSynchronizations(null));
        return list == null ? Collections.emptyList() : list;
    }
}