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

package io.edap.tx.annotation;

import io.edap.tx.TransactionDefinition;
import io.edap.tx.isolation.Isolation;
import io.edap.tx.propagation.Propagation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明一个方法需要事务包裹,但 commit / rollback 由业务方显式控制 —— 区别于
 * {@link Transactional @Transactional} 在方法出口由 wrapper 自动 commit。
 *
 * <p><b>使用模式</b>:wrapper 在方法入口调
 * {@link io.edap.tx.EdapTransactionManager#getTransaction} 开事务并 bind
 * {@link io.edap.tx.TransactionContext},业务方法体通过
 * {@link io.edap.tx.TransactionContext#current()} 拿到 ctx,
 * 自己决定 commit 或 rollback。wrapper 在 finally 兜底 —— 业务方忘 commit
 * 时,finally 检查 {@code status.isCompleted()} 后改走 rollback。</p>
 *
 * <p><b>典型场景</b>:多步写入需要根据中间结果决定 commit / rollback 的业务逻辑,
 * 不适合 wrapper 自动 commit 的场景。</p>
 *
 * <p><b>为什么独立于 {@code @Transactional}</b>:语义差异显著(业务方控制 vs wrapper 控制),
 * 共用注解会让方法出口的自动 commit 逻辑变得条件复杂,增加 bug 面。</p>
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ManualTransaction {

    /**
     * 事务传播模型。默认 {@link Propagation#REQUIRED}。
     */
    Propagation propagation() default Propagation.REQUIRED;

    /**
     * 事务隔离级别。默认 {@link Isolation#DEFAULT}。
     */
    Isolation isolation() default Isolation.DEFAULT;

    /**
     * 事务超时(秒)。默认 -1。
     */
    int timeout() default -1;

    /**
     * 是否只读事务。默认 false。
     */
    boolean readOnly() default false;

    /**
     * 事务名(用于监控/日志关联)。默认空串。
     */
    String name() default "";

    /**
     * 目标 {@link io.edap.tx.EdapTransactionManager} 在容器中的 bean 名称。
     *
     * <p>默认 {@code ""} —— 由容器选择"默认 tm"。容器在 {@code AppContext.start}
     * 期自动为每个 {@code DataSource} bean 创建一个
     * {@link io.edap.tx.jdbc.DataSourceTransactionManager},bean 名遵循
     * 约定 {@code transactionManager_<DataSourceBeanName>}。
     * 例如 {@code DataSource} bean 名 {@code "main"} → tm bean 名 {@code "transactionManager_main"}。</p>
     *
     * <p>单 DataSource 时不指定名走默认 tm;多 DataSource 时若不指定名且无
     * {@code @Primary} DataSource,wrapper 入口会 fail-fast 抛
     * {@link IllegalStateException} —— 多 ds 场景下必须明确指定 tm 名。</p>
     *
     * <p>语义与 {@link Transactional#transactionManager()} 完全相同,
     * 业务方挑一个用,不影响最终路由。</p>
     */
    String transactionManager() default "";
}