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

package io.edap.erpc.client;

import io.edap.erpc.codec.ErpcCodec;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 节点表 —— 把注册中心推来的节点列表变成连接池，并随变更增删。
 *
 * <p>★ <b>这是「节点列表不写死在客户端」这条要求的落点</b>：</p>
 * <ul>
 *   <li>构造时订阅 {@link ErpcNodeProvider}，注册中心一有节点就建池；</li>
 *   <li>注册中心说节点没了 ⇒ 关掉那个节点的池（在途调用随之失败，不会挂死）；</li>
 *   <li>注册中心说权重变了 ⇒ 池不动（权重是负载均衡层的事，见
 *       {@link io.edap.erpc.client.loadbalance}）。</li>
 * </ul>
 *
 * <p>★ 变更回调跑在注册中心自己的线程上 ⇒ 这里只做「建池 / 关池 / 换快照」，
 * 不做连接（连接是懒建的，见 {@link ErpcNodePool#next()}），不阻塞注册中心。</p>
 */
public final class ErpcNodeRegistry implements Closeable {

    private final ErpcNodeProvider provider;
    private final ErpcCodec serializer;
    private final ErpcClientOptions options;

    /** 节点 id → 连接池。增删在 {@code lock} 内做。 */
    private final Map<String, ErpcNodePool> pools = new HashMap<>();

    /** 当前池的<b>不可变快照</b> —— 负载均衡层无锁读它。 */
    private volatile List<ErpcNodePool> snapshot = Collections.emptyList();

    private final Object lock = new Object();

    public ErpcNodeRegistry(ErpcNodeProvider provider, ErpcCodec serializer, ErpcClientOptions options) {
        this.provider = provider;
        this.serializer = serializer;
        this.options = options;
        provider.subscribe(this::onNodesChanged);
    }

    /**
     * 注册中心的节点变更回调。
     */
    private void onNodesChanged(List<ErpcNode> nodes) {
        synchronized (lock) {
            Set<String> live = new HashSet<>();
            if (nodes != null) {
                for (ErpcNode node : nodes) {
                    if (node == null) {
                        continue;
                    }
                    live.add(node.id());
                    if (!pools.containsKey(node.id())) {
                        pools.put(node.id(), new ErpcNodePool(node, serializer, options));
                    }
                }
            }
            // 节点从注册中心消失了 ⇒ 关掉它的池（在途调用随之失败）
            Iterator<Map.Entry<String, ErpcNodePool>> it = pools.entrySet().iterator();
            List<ErpcNodePool> removed = new ArrayList<>();
            while (it.hasNext()) {
                Map.Entry<String, ErpcNodePool> e = it.next();
                if (!live.contains(e.getKey())) {
                    it.remove();
                    removed.add(e.getValue());
                }
            }
            snapshot = Collections.unmodifiableList(new ArrayList<>(pools.values()));
            for (ErpcNodePool pool : removed) {
                pool.close();
            }
        }
    }

    /**
     * 当前所有节点的连接池（不可变快照）。
     */
    public List<ErpcNodePool> pools() {
        return snapshot;
    }

    /**
     * 当前节点数。
     */
    public int nodeCount() {
        return snapshot.size();
    }

    @Override
    public void close() {
        synchronized (lock) {
            for (ErpcNodePool pool : pools.values()) {
                pool.close();
            }
            pools.clear();
            snapshot = Collections.emptyList();
        }
    }
}
