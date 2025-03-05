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

package io.edap.rpc.client.loadbalance;

import io.edap.rpc.LoadBalancer;
import io.edap.rpc.RpcInvoker;

/**
 * 随机选取的负载均衡器，主要应用场景为无状态服务的负载均衡场景，该均衡器选择不关心函数的入参，
 * 只需关心服务匹配即可。
 */
public class RandomLoadBalancer implements LoadBalancer<Object> {

    @Override
    public RpcInvoker select(Object req) {
        return null;
    }
}
