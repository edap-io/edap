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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 事务同步管理器——集中管理 ThreadLocal 状态(详见 TX_DESIGN.md §2.7)。
 *
 * <p>为什么独立一个工具类:与 Spring 同构,集中管理 ThreadLocal 避免每个 manager
 * 各自实现散落各处,跨 CL 注入时不会因 ThreadLocal 实例不一致导致状态丢失。</p>
 *
 * <p><b>资源句柄抽象</b>:ThreadLocal 里存的是 {@link TransactionStatus}(含
 * {@link TransactionResource}),业务代码拿到 connection 的方式由 manager 实现决定
 * ——本地事务时 resource 是 {@code JdbcConnectionResource},XA 时是 XA 包装。</p>
 *
 * <p><b>资源 key-value 绑定</b>({@link #bindResource}):XA 多数据源场景下,
 * 多个 connection 按 key(通常是 {@code DataSource} 实例)分别绑到 ThreadLocal,
 * 不互相干扰。</p>
 *
 * <p><b>同步点</b>({@link #bindSynchronization}):分布式事务的事件通知钩子,
 * 由 {@link Synchronization#afterCommit} / {@link Synchronization#afterCompletion} 实现。</p>
 */
public final class TransactionSynchronizationManager {

    private static final ThreadLocal<TransactionStatus> currentStatus = new ThreadLocal<>();
    private static final ThreadLocal<List<Synchronization>> synchronizations = new ThreadLocal<>();
    private static final ThreadLocal<Map<Object, Object>> resources = new ThreadLocal<>();
    private static final ThreadLocal<String> currentXid = new ThreadLocal<>();
    private static final ThreadLocal<List<Object>> suspendedXids = new ThreadLocal<>();

    private TransactionSynchronizationManager() {
        // 工具类,不允许实例化
    }

    // ============ 当前事务状态 ============

    public static TransactionStatus getCurrentStatus() {
        return currentStatus.get();
    }

    /**
     * 绑定事务状态到当前线程。由 {@link EdapTransactionManager#getTransaction}
     * 在"开新事务"路径上调用。
     */
    public static void bindStatus(TransactionStatus status) {
        currentStatus.set(status);
    }

    /**
     * 解绑当前事务状态。由 manager 在 commit/rollback finally 块中调用。
     */
    public static TransactionStatus unbindStatus() {
        TransactionStatus s = currentStatus.get();
        currentStatus.remove();
        return s;
    }

    /**
     * 当前是否存在活跃事务。等价于
     * {@code getCurrentStatus() != null && getCurrentStatus().hasResource()}。
     */
    public static boolean isTransactionActive() {
        TransactionStatus s = currentStatus.get();
        return s != null && s.hasResource();
    }

    // ============ 同步点(回调列表) ============

    public static List<Synchronization> getSynchronizations() {
        return synchronizations.get();
    }

    public static boolean isSynchronizationActive() {
        return synchronizations.get() != null;
    }

    public static void initSynchronization() {
        synchronizations.set(new ArrayList<>());
    }

    public static void addSynchronization(Synchronization sync) {
        List<Synchronization> list = synchronizations.get();
        if (list == null) {
            throw new IllegalStateException(
                    "Synchronization not active — call initSynchronization() first");
        }
        list.add(sync);
    }

    /**
     * 取出并清空同步点列表。manager 在 commit/rollback 时调用,触发回调。
     */
    public static List<Synchronization> clearSynchronization() {
        List<Synchronization> list = synchronizations.get();
        synchronizations.remove();
        return list == null ? Collections.emptyList() : list;
    }

    // ============ 资源句柄绑定(支持多资源,XA 场景) ============

    public static Map<Object, Object> getResourceMap() {
        return resources.get();
    }

    public static boolean hasResource(Object key) {
        Map<Object, Object> map = resources.get();
        return map != null && map.containsKey(key);
    }

    public static Object getResource(Object key) {
        Map<Object, Object> map = resources.get();
        return map == null ? null : map.get(key);
    }

    public static void bindResource(Object key, Object value) {
        Map<Object, Object> map = resources.get();
        if (map == null) {
            map = new HashMap<>();
            resources.set(map);
        }
        map.put(key, value);
    }

    public static Object unbindResource(Object key) {
        Map<Object, Object> map = resources.get();
        if (map == null) {
            return null;
        }
        Object value = map.remove(key);
        if (map.isEmpty()) {
            resources.remove();
        }
        return value;
    }

    // ============ 挂起 / 恢复 (REQUIRES_NEW / NOT_SUPPORTED 场景) ============

    /**
     * 挂起当前事务:把 status + resources + xid 压栈保存,清空当前 ThreadLocal。
     * 返回的 {@link SuspendedResources} 在 {@link #resumeSuspended} 时回传。
     */
    public static SuspendedResources suspend() {
        TransactionStatus status = currentStatus.get();
        Map<Object, Object> resMap = resources.get();
        List<Synchronization> syncs = synchronizations.get();
        String xid = currentXid.get();

        currentStatus.remove();
        resources.remove();
        synchronizations.remove();
        currentXid.remove();

        if (status == null && resMap == null && xid == null) {
            return null;
        }
        return new SuspendedResources(status, resMap, syncs, xid);
    }

    /**
     * 恢复挂起的事务:把栈中的状态还原到 ThreadLocal。
     */
    public static void resumeSuspended(SuspendedResources suspended) {
        if (suspended == null) {
            return;
        }
        if (suspended.status != null) {
            currentStatus.set(suspended.status);
        }
        if (suspended.resources != null) {
            resources.set(suspended.resources);
        }
        if (suspended.synchronizations != null) {
            synchronizations.set(suspended.synchronizations);
        }
        if (suspended.xid != null) {
            currentXid.set(suspended.xid);
        }
    }

    // ============ global xid (Phase 4 XA / edap-pg-proxy 用) ============

    public static String getCurrentXid() {
        return currentXid.get();
    }

    public static void bindCurrentXid(String xid) {
        currentXid.set(xid);
    }

    public static void unbindCurrentXid() {
        currentXid.remove();
    }

    // ============ 全部清理(拦截器 finally 块用) ============

    /**
     * 一次性清空所有 ThreadLocal——拦截器 finally 块或测试 tearDown 调用,
     * 避免线程复用导致的上下文泄漏。
     */
    public static void clear() {
        currentStatus.remove();
        synchronizations.remove();
        resources.remove();
        currentXid.remove();
        suspendedXids.remove();
    }

    /**
     * 挂起资源快照——suspend() 返回,resumeSuspended() 传入。
     */
    public static final class SuspendedResources {
        private final TransactionStatus status;
        private final Map<Object, Object> resources;
        private final List<Synchronization> synchronizations;
        private final String xid;

        SuspendedResources(TransactionStatus status,
                           Map<Object, Object> resources,
                           List<Synchronization> synchronizations,
                           String xid) {
            this.status = status;
            this.resources = resources;
            this.synchronizations = synchronizations;
            this.xid = xid;
        }

        public TransactionStatus getStatus()         { return status; }
        public Map<Object, Object> getResources()    { return resources; }
        public List<Synchronization> getSynchronizations() { return synchronizations; }
        public String getXid()                       { return xid; }
    }
}
