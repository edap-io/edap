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
 * 加权选取的负载均衡器 —— 按节点的权重分配流量。
 *
 * <p>权重来自注册中心（{@link io.edap.erpc.client.ErpcNode#getWeight()}）：
 * 注册中心把权重改掉，下一次选取就用新权重。</p>
 */
public class WeightedLoadBalancer implements LoadBalancer<Object> {

    private final Supplier<List<ErpcNodePool>> pools;

    public WeightedLoadBalancer(Supplier<List<ErpcNodePool>> pools) {
        this.pools = pools;
    }

    @Override
    public RpcInvoker select(Object req) {
        List<ErpcNodePool> list = pools.get();
        if (list == null || list.isEmpty()) {
            throw new ErpcException("当前没有可用的 eRPC 服务节点");
        }
        int total = 0;
        for (ErpcNodePool pool : list) {
            total += pool.node().getWeight();
        }
        int pick = ThreadLocalRandom.current().nextInt(total);
        for (ErpcNodePool pool : list) {
            pick -= pool.node().getWeight();
            if (pick < 0) {
                return pool;
            }
        }
        return list.get(list.size() - 1);
    }
}
