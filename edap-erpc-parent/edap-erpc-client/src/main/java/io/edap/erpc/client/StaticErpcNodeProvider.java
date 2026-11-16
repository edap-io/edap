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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 固定节点列表的 provider —— 测试与静态部署用。
 *
 * <p>它同时也是「注册中心适配 SPI」的<b>参考实现</b>：一个可变列表 +
 * 一个变更回调队列。接真实注册中心（nacos / consul / …）时照这个形状实现即可。</p>
 */
public class StaticErpcNodeProvider implements ErpcNodeProvider {

    private final List<ErpcNode> nodes = new CopyOnWriteArrayList<>();
    private final List<NodeChangeListener> listeners = new CopyOnWriteArrayList<>();

    public StaticErpcNodeProvider(List<ErpcNode> nodes) {
        setNodes(nodes);
    }

    public StaticErpcNodeProvider(ErpcNode... nodes) {
        setNodes(java.util.Arrays.asList(nodes));
    }

    @Override
    public List<ErpcNode> nodes() {
        return Collections.unmodifiableList(new ArrayList<>(nodes));
    }

    @Override
    public void subscribe(NodeChangeListener listener) {
        if (listener == null) {
            return;
        }
        listeners.add(listener);
        // 订阅即推一次当前列表 —— 客户端不用额外先拉一次
        listener.onNodesChanged(nodes());
    }

    /**
     * 替换节点列表并通知所有订阅者。
     */
    public void setNodes(List<ErpcNode> newNodes) {
        nodes.clear();
        if (newNodes != null) {
            nodes.addAll(newNodes);
        }
        List<ErpcNode> snapshot = nodes();
        for (NodeChangeListener listener : listeners) {
            listener.onNodesChanged(snapshot);
        }
    }

    /**
     * 追加一个节点并通知订阅者。
     */
    public void addNode(ErpcNode node) {
        nodes.add(node);
        List<ErpcNode> snapshot = nodes();
        for (NodeChangeListener listener : listeners) {
            listener.onNodesChanged(snapshot);
        }
    }

    /**
     * 移除一个节点并通知订阅者。
     */
    public void removeNode(ErpcNode node) {
        nodes.remove(node);
        List<ErpcNode> snapshot = nodes();
        for (NodeChangeListener listener : listeners) {
            listener.onNodesChanged(snapshot);
        }
    }
}
