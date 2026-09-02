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

/**
 * 事务管理器接口——edap 事务子系统的核心抽象。
 *
 * <p>由 edap 容器实现此接口的 bean 注入到拦截链,负责:</p>
 * <ol>
 *   <li>根据 {@link TransactionDefinition} 的传播模型做决策矩阵</li>
 *   <li>开新 / 复用 / 挂起 / 抛异常</li>
 *   <li>commit / rollback 时调用同步点回调</li>
 * </ol>
 *
 * <p>实现层:</p>
 * <ul>
 *   <li>{@code DefaultEdapTransactionManager}(edap-tx 模块提供)—— 默认实现,基于 {@link TxScope} 单 ThreadLocal</li>
 *   <li>{@code DataSourceTransactionManager}(edap-tx-jdbc 提供)—— JDBC 单连接事务</li>
 *   <li>未来: Seata / XA / TCC 等分布式事务管理器</li>
 * </ul>
 *
 * <p><b>wrapper 集成契约</b>:由 ASM 生成的 proxy 字节码调用本接口方法,
 * 完整生命周期为 {@code getTransaction(def)} → 业务方法体 → {@code commit(status)} /
 * {@code rollback(status)}。wrapper 在 finally 块守卫重复 commit/rollback:
 * 业务方 {@code ctx.commit()} 后 status 已 {@link TransactionStatus#markCompleted()},
 * wrapper 跳过第二次 commit。</p>
 */
public interface EdapTransactionManager {

    /**
     * 根据传播模型做决策,获取当前事务状态。
     *
     * <p>决策矩阵(7×3=21 个场景):</p>
     * <table border="1">
     *   <tr><th>当前状态 \ propagation</th><th>REQUIRED</th><th>REQUIRES_NEW</th><th>NESTED</th>
     *       <th>SUPPORTS</th><th>NOT_SUPPORTED</th><th>MANDATORY</th><th>NEVER</th></tr>
     *   <tr><td>无 tx</td><td>开新</td><td>开新</td><td>开新</td>
     *       <td>非事务</td><td>非事务</td><td>异常</td><td>非事务</td></tr>
     *   <tr><td>有 tx</td><td>复用+nesting+1</td><td>挂起+开新</td><td>复用+savepoint</td>
     *       <td>复用</td><td>挂起+非事务</td><td>复用</td><td>异常</td></tr>
     * </table>
     *
     * @param definition 事务定义(由 @Transactional 注解解析)
     * @return 事务状态,可能为"非事务"(SUPPORTS+无外层 tx 时 {@code resource=null})
     * @throws TransactionException 状态非法(MANDATORY 无 tx / NEVER 有 tx 等)
     */
    TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException;

    /**
     * 提交事务。
     *
     * <p>嵌套场景(REQUIRED 内层调用):只在最外层(计数==1)真正 commit,
     * 内层只 decrement 计数并跳过实际 commit。</p>
     *
     * <p>已 {@link TransactionStatus#markCompleted()} 的事务再次调用抛
     * {@code IllegalTransactionStateException}。wrapper 在 finally 块用
     * {@link TransactionStatus#isCompleted()} 守卫避免重复 commit。</p>
     */
    void commit(TransactionStatus status) throws TransactionException;

    /**
     * 回滚事务。
     *
     * <p>嵌套场景:即使只内层抛异常,也会触发全部 rollback(REQUIRED 语义)。
     * NESTED 场景:回滚到当前 savepoint,外层不受影响。</p>
     */
    void rollback(TransactionStatus status) throws TransactionException;

    /**
     * 当前线程是否存在活跃事务(由 ThreadLocal 状态判断,与具体实现无关)。
     */
    boolean hasResource();
}