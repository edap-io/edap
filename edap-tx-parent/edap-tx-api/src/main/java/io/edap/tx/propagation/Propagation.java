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

package io.edap.tx.propagation;

/**
 * 事务传播模型——定义业务方法被嵌套调用时,事务如何与外层事务协作。
 *
 * <p>完整 7 种语义,与 Spring {@code Propagation} 对齐(便于开发者迁移)但实现独立。
 * 详见 TX_DESIGN.md §2.1。</p>
 *
 * <table border="1">
 *   <caption>传播决策矩阵</caption>
 *   <tr><th>值</th><th>语义</th><th>当前无 tx</th><th>当前有 tx</th></tr>
 *   <tr><td>{@link #REQUIRED}</td><td>有则复用、无则新建(默认)</td><td>开新</td><td>复用(计数+1)</td></tr>
 *   <tr><td>{@link #REQUIRES_NEW}</td><td>永远开新,挂起当前</td><td>开新</td><td>挂起当前、开新</td></tr>
 *   <tr><td>{@link #NESTED}</td><td>嵌套(savepoint)</td><td>开新</td><td>复用 + savepoint</td></tr>
 *   <tr><td>{@link #SUPPORTS}</td><td>有则用、无则不用</td><td>非事务</td><td>复用</td></tr>
 *   <tr><td>{@link #NOT_SUPPORTED}</td><td>永远非事务</td><td>非事务</td><td>挂起当前、非事务</td></tr>
 *   <tr><td>{@link #MANDATORY}</td><td>必须有,否则异常</td><td>抛异常</td><td>复用</td></tr>
 *   <tr><td>{@link #NEVER}</td><td>必须无,否则异常</td><td>非事务</td><td>抛异常</td></tr>
 * </table>
 */
public enum Propagation {

    /**
     * 默认值。有则复用、无则新建。
     *
     * <p>嵌套 REQUIRED 调用使用 {@link io.edap.tx.TransactionStatus#incrementNesting()}
     * 计数,commit 只在最外层(计数==1)真正提交,内层只 decrement;
     * rollback 则全部 rollback。</p>
     */
    REQUIRED,

    /**
     * 永远开新事务,挂起当前事务。
     *
     * <p>挂起 = 把当前 tx 状态(resource + status)压栈到 ThreadLocal,
     * 恢复时取出来。</p>
     */
    REQUIRES_NEW,

    /**
     * 有则嵌套(savepoint),无则新建。
     *
     * <p>需要底层支持 savepoint(JDBC {@code Connection.setSavepoint()})。
     * 底层不支持时,默认降级为 {@link #REQUIRED} + WARN;业务侧明确要求支持时
     * 抛 {@code NestedTransactionNotSupportedException}。</p>
     */
    NESTED,

    /**
     * 有则用、无则不用。语义上等同于"非强制事务"——既不要求当前必须有,
     * 也不强制要求当前必须没有。
     */
    SUPPORTS,

    /**
     * 永远非事务,挂起当前事务。
     */
    NOT_SUPPORTED,

    /**
     * 必须有当前事务,否则抛 {@code IllegalTransactionStateException}。
     */
    MANDATORY,

    /**
     * 必须无当前事务,否则抛 {@code IllegalTransactionStateException}。
     */
    NEVER
}
