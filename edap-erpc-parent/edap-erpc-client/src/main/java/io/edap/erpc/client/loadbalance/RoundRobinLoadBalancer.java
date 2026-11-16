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

package io.edap.erpc.client.loadbalance;

import io.edap.erpc.ErpcException;
import io.edap.erpc.LoadBalancer;
import io.edap.erpc.RpcInvoker;
import io.edap.erpc.client.ErpcNodePool;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 轮询选取的负载均衡器 —— 请求在多个节点之间均匀轮流。
 *
 * <p>★ 节点集合是<b>活</b>的：每次 {@link #select(Object)} 都重新取当前列表，
 * 所以注册中心增删节点后无需重建均衡器。</p>
 */
public class RoundRobinLoadBalancer implements LoadBalancer<Object> {

    private final Supplier<List<ErpcNodePool>> pools;
    private final AtomicInteger cursor = new AtomicInteger();

    public RoundRobinLoadBalancer(Supplier<List<ErpcNodePool>> pools) {
        this.pools = pools;
    }

    @Override
    public RpcInvoker select(Object req) {
        List<ErpcNodePool> list = pools.get();
        if (list == null || list.isEmpty()) {
            throw new ErpcException("当前没有可用的 eRPC 服务节点");
        }
        int idx = Math.floorMod(cursor.getAndIncrement(), list.size());
        return list.get(idx);
    }
}
