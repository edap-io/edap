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

package io.edap.microservice.annotation;

import io.edap.microservice.Scope;

import java.lang.annotation.*;

/**
 * 标记一个类为 Edap 容器管理的微服务实现 Bean。
 *
 * <p>与 {@code @ProtoService}（位于 {@code io.edap.protobuf.annotation}，标在 proto 生成的
 * 服务接口上）配对使用：{@code @ProtoService} 标接口，本注解标实现类。</p>
 *
 * <pre>{@code
 * @ProtoService                              // proto 生成的接口
 * public interface UserService {
 *     User getById(long id);
 * }
 *
 * @MicroServiceBean                          // 手写实现
 * public class UserServiceImpl implements UserService {
 *     public User getById(long id) { ... }
 * }
 * }</pre>
 *
 * <p>与 {@code @Bean}（资源 Bean）的语义差异：本注解专门标识「微服务的实现」，
 * 走 ClusterShardRouter + 协议路由；{@code @Bean} 是普通资源（如 DB 连接池），由
 * BeanContainer 直接管理，不参与 RPC 路由。</p>
 *
 * <p>当前阶段 Bean 标记只有两个：
 * <ul>
 *   <li>{@code @MicroServiceBean} —— 微服务实现（绑定 @ProtoService）</li>
 *   <li>{@code @Bean} —— 普通资源 Bean</li>
 * </ul>
 * </p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
@Inherited
@Documented
public @interface MicroServiceBean {
    String name() default "";

    Scope scope() default Scope.SINGLETON;
}