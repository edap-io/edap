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

package io.edap.container.transactional;

import io.edap.tx.EdapTransactionManager;

/**
 * 按名称查找 {@link EdapTransactionManager} —— 由 {@link TransactionalBeanPostProcessor}
 * 注入到生成的 wrapper 字段中,运行时按方法注解的 {@code transactionManager()} 属性解析 tm。
 *
 * <p><b>为什么是接口而不是 {@code Function<String, EdapTransactionManager>}</b>:</p>
 * <ul>
 *   <li>避免类型擦除带来的 {@code apply(Object) → Object} + CHECKCAST —— 直接
 *       {@code resolve(String) → EdapTransactionManager} 字节码更干净</li>
 *   <li>语义比泛型 {@code Function} 明确 —— 这是框架契约,不是"任意外层回函数"</li>
 *   <li>容器内的默认实现就是一个查 {@code Map<String, EdapTransactionManager>} 的 lambda</li>
 * </ul>
 *
 * <p><b>name 传空串 {@code ""}</b> 表示"默认 tm" —— 容器在以下场景确定默认:</p>
 * <ul>
 *   <li>只有一个 DataSource bean → 自动成为默认</li>
 *   <li>多个 DataSource → DataSource bean 上有 {@code @Primary} 注解的那个对应 tm 是默认</li>
 *   <li>多 DataSource 且无 @Primary → 启动期报 {@code NoUniqueBeanException}(fail-fast)</li>
 * </ul>
 *
 * <p><b>name 找不到</b> → 必须抛异常(启动期可以 fail-fast;wrapper 运行时只能 rethrow)。
 * 不能 fallback 到默认,否则业务方以为路由到了目标 tm,实际开在错误 ds 上 —— 这种 bug 极难定位。</p>
 */
@FunctionalInterface
public interface TransactionManagerResolver {

    EdapTransactionManager resolve(String name);
}
