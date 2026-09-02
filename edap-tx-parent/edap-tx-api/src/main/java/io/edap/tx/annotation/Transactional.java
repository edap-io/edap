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
 * 声明一个方法(或类)需要事务包裹——edap 事务子系统的业务侧入口注解。
 *
 * <p>拦截器在方法入口调用 {@link io.edap.tx.EdapTransactionManager#getTransaction}
 * 解析为 {@link TransactionDefinition},在方法出口按返回/异常
 * 触发 commit / rollback。</p>
 *
 * <p><b>类级别 vs 方法级别</b>:与 Spring 同构——方法注解覆盖类注解,未指定字段从类注解继承,
 * 类也未指定则用 {@link TransactionDefinition} 的默认值({@link Propagation#REQUIRED} +
 * {@link Isolation#DEFAULT})。</p>
 *
 * <p><b>为什么独立于 edap-protobuf 注解</b>:edap-protobuf 注解生成的是 wire 层元数据
 * (用于 gRPC / eRPC),{@code @Transactional} 是容器侧 AOP 元数据,两类用途不同,
 * 解耦避免生成代码污染运行期事务配置。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Transactional {

    /**
     * 事务传播模型。默认 {@link Propagation#REQUIRED}——最常见的语义(有则复用,无则开新)。
     */
    Propagation propagation() default Propagation.REQUIRED;

    /**
     * 事务隔离级别。默认 {@link Isolation#DEFAULT}——使用底层数据源默认设置。
     */
    Isolation isolation() default Isolation.DEFAULT;

    /**
     * 事务超时(秒)。默认 -1 表示不超时(由底层数据源决定)。
     *
     * <p>实际生效依赖资源实现:本地 JDBC 事务通常下到 {@code Statement.setQueryTimeout},
     * XA 资源下到协调器。</p>
     */
    int timeout() default -1;

    /**
     * 是否只读事务。默认 false。
     *
     * <p>只读标记是 hint,实现层可据此跳过脏检查/锁升级。误标 false 但实际只读不会出错,
     * 反之可能拒绝写入。</p>
     */
    boolean readOnly() default false;

    /**
     * 事务名(用于监控/日志关联)。默认空串表示自动生成(类名.方法名)。
     */
    String name() default "";

    /**
     * 触发回滚的异常类集合。默认空——按 {@link TransactionDefinition#shouldRollbackOn}
     * 的默认规则(RuntimeException / Error 回滚,checked 不回滚)。
     *
     * <p>这里用 {@link Class}[] 而非 {@code Throwable[]}:写业务异常类名更直观,
     * 框架内部对实例做 {@code instanceof} 判断。</p>
     */
    Class<? extends Throwable>[] rollbackFor() default {};

    /**
     * 不触发回滚的异常类集合(即便默认规则会触发)。默认空。
     */
    Class<? extends Throwable>[] noRollbackFor() default {};

    /**
     * 目标 {@link io.edap.tx.EdapTransactionManager} 在容器中的 bean 名称。
     *
     * <p>默认 {@code ""} —— 由容器选择"默认 tm"(通常是唯一 DataSource 对应的 tm,
     * 或多 ds 时由 {@code @Primary} DataSource 决定)。语义与
     * {@code @ManualTransaction(transactionManager=...)} 完全相同;详见该注解字段文档。</p>
     */
    String transactionManager() default "";
}
