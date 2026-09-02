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

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 单事务运行时快照——不可变,聚合了"当前活跃事务"全部 ThreadLocal 状态。
 *
 * <p><b>设计动机</b>:替代原 {@code TransactionSynchronizationManager} 的 6 个散装
 * ThreadLocal(status / synchronizations / resources / xid / suspendedXids /
 * wrapperDepth),压成单个对象 + 单个 ThreadLocal。挂起/恢复由
 * {@link TxScope#swap(TxSnapshot)} 原子交换实现,不再需要额外 ThreadLocal 栈。</p>
 *
 * <p><b>字段语义</b>:</p>
 * <ul>
 *   <li>{@link #status}——当前活跃事务状态;非事务帧可为 null(SUPPORTS+无外层 tx 等)</li>
 *   <li>{@link #synchronizations}——同步回调列表;null 表示"未初始化",空 list 表示"已初始化但无回调"</li>
 *   <li>{@link #resources}——多资源句柄映射(XA 场景下多个 connection 按 DataSource key 分别绑)</li>
 *   <li>{@link #xid}——分布式事务全局 ID(Phase 4 XA 用);本地事务通常 null</li>
 *   <li>{@link #context}——{@link TransactionContext} 业务侧入口;manager 不读,只用于
 *       {@link TransactionContext#current()} 静态查询的缓存</li>
 * </ul>
 *
 * <p><b>不可变性</b>:所有字段 final,共享安全;manager 改状态时构造新 snapshot 替换
 * ThreadLocal 中的旧对象引用。</p>
 */
public final class TxSnapshot {

    private final TransactionStatus status;
    private final List<Synchronization> synchronizations;
    private final Map<Object, Object> resources;
    private final String xid;
    private final TransactionContext context;

    private TxSnapshot(TransactionStatus status,
                       List<Synchronization> synchronizations,
                       Map<Object, Object> resources,
                       String xid,
                       TransactionContext context) {
        this.status = status;
        //null 保留为"未初始化"语义,空 list 表示"已初始化但无回调" —— 区分必须保留
        this.synchronizations = synchronizations;
        this.resources = resources;
        this.xid = xid;
        this.context = context;
    }

    /**
     * 构造空 snapshot——"无活跃事务"基线状态。
     *
     * <p>{@link TxScope#clear()} 后,ThreadLocal 取此值;
     * 也用于 REQUIRES_NEW/NOT_SUPPORTED 路径的"suspend 到空"。</p>
     */
    public static TxSnapshot empty() {
        return new TxSnapshot(null, null, null, null, null);
    }

    /**
     * 在当前 snapshot 基础上派生新 snapshot(只改一个字段,其余继承)。
     * 字段为 null 时保留原值;字段非 null 时替换。
     */
    public TxSnapshot withStatus(TransactionStatus newStatus) {
        return new TxSnapshot(newStatus, synchronizations, resources, xid, context);
    }

    public TxSnapshot withSynchronizations(List<Synchronization> newSyncs) {
        return new TxSnapshot(status, newSyncs, resources, xid, context);
    }

    public TxSnapshot withResources(Map<Object, Object> newResources) {
        return new TxSnapshot(status, synchronizations, newResources, xid, context);
    }

    public TxSnapshot withXid(String newXid) {
        return new TxSnapshot(status, synchronizations, resources, newXid, context);
    }

    public TxSnapshot withContext(TransactionContext newContext) {
        return new TxSnapshot(status, synchronizations, resources, xid, newContext);
    }

    public TransactionStatus status()                          { return status; }
    public List<Synchronization> synchronizations()            { return synchronizations; }
    public Map<Object, Object> resources()                     { return resources; }
    public String xid()                                        { return xid; }
    public TransactionContext context()                        { return context; }

    /**
     * 当前 snapshot 是否代表"有活跃事务"——
     * 即 status 不为 null 且 status 有真实 resource。
     */
    public boolean hasTransaction() {
        return status != null && status.hasResource();
    }

    /**
     * 当前 snapshot 是否持有事务(即使 resource 为 null 也算)——
     * 区别于 {@link #hasTransaction()},用于判断"是否在某个事务上下文中(SUPPORTS 等
     * 非事务帧 status 不为 null 但 resource 为 null)"。
     */
    public boolean hasStatus() {
        return status != null;
    }

    /**
     * 取得 synchronizations 列表;null 时返回空 list(避免外部 NPE)。
     */
    public List<Synchronization> synchronizationsOrEmpty() {
        return synchronizations == null ? Collections.emptyList() : synchronizations;
    }

    /**
     * 取得 resources 映射;null 时返回空 map(避免外部 NPE)。
     */
    public Map<Object, Object> resourcesOrEmpty() {
        return resources == null ? Collections.emptyMap() : resources;
    }

    /**
     * 构造或返回已存在的 resources map;null 时新建 HashMap 返回(供 manager bindResource 时用).
     * 返回的 map 由调用方填充后通过 {@link #withResources(Map)} 重建 snapshot.
     */
    public Map<Object, Object> resourcesOrNew() {
        return resources == null ? new HashMap<>() : resources;
    }
}