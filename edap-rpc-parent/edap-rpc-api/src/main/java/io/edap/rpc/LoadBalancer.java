/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.rpc;

/**
 * RPC调用的负载均衡器，用来计算在多个提供者时负载均衡的算法，如果是有状态的服务则计算请求应该分配到哪个节点
 */
public interface LoadBalancer<T> {
    /**
     * 根据权重以及函数的入参获取一个Rpc的执行器
     * @param req 函数的入参
     * @return 返回Rpc的执行器
     */
    RpcInvoker select(T req);
}
