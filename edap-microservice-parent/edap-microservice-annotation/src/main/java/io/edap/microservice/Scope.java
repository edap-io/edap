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

package io.edap.microservice;

/**
 * Bean 生命周期作用域。
 *
 * <p>仅描述"实例如何被创建/共享"：
 * <ul>
 *   <li>{@link #SINGLETON} —— 容器内单例</li>
 *   <li>{@link #PROTOTYPE} —— 每次获取新建</li>
 * </ul>
 *
 * <p>分片语义不在此处：使用 {@code io.edap.protobuf.annotation.Sharded} 标注方法，
 * 分片实例数由容器根据部署配置（build.json / 集群拓扑）决定，不在注解里固化。</p>
 */
public enum Scope {
    SINGLETON,
    PROTOTYPE
}