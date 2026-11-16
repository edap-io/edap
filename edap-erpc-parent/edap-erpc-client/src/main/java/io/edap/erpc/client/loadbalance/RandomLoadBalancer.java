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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 随机选取的负载均衡器，主要应用场景为无状态服务的负载均衡场景，该均衡器选择不关心函数的入参，
 * 只需关心服务匹配即可。
 *
 * <p>★ <b>节点集合是活的</b>：构造参数是「当前池列表」的<b>供应者</b>而不是一份快照 ——
 * 注册中心推了新节点 / 摘了节点，这里下一次 {@link #select(Object)} 就用新的集合。</p>
 */
public class RandomLoadBalancer implements LoadBalancer<Object> {

    private final Supplier<List<ErpcNodePool>> pools;

    /**
     * @param pools 当前节点池列表的供应者（由 {@code ErpcNodeRegistry} 提供）
     */
    public RandomLoadBalancer(Supplier<List<ErpcNodePool>> pools) {
        this.pools = pools;
    }

    @Override
    public RpcInvoker select(Object req) {
        List<ErpcNodePool> list = pools.get();
        if (list == null || list.isEmpty()) {
            throw new ErpcException("当前没有可用的 eRPC 服务节点");
        }
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }
}
