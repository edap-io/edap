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

import java.util.List;

/**
 * **注册中心适配 SPI** —— eRPC 客户端的节点列表从这里来。
 *
 * <p>★ <b>节点列表不能在 {@link ErpcClient} 构造时定死</b>：</p>
 * <ul>
 *   <li>构造时可能一个节点都还没有（注册中心还没同步）；</li>
 *   <li>注册中心的节点列表随时会变（扩缩容、摘节点、换权重）——
 *       客户端必须跟着变，而不是等进程重启。</li>
 * </ul>
 *
 * <p>所以客户端只持有<b>一个 provider</b>，不持有节点列表：</p>
 * <pre>{@code
 *   public interface ErpcNodeProvider {
 *       List<ErpcNode> nodes();                       // 拉一次当前列表
 *       void subscribe(NodeChangeListener listener);  // 订阅变更（注册中心推）
 *   }
 * }</pre>
 *
 * <p><b>实现约定</b>：</p>
 * <ul>
 *   <li>{@link #nodes()} 必须是<b>线程安全</b>的快照语义 —— 返回当前节点的不可变列表。</li>
 *   <li>{@link #subscribe(NodeChangeListener)} 收到变更后<b>必须</b>回调 listener，
 *       且回调的是<b>变更后的完整列表</b>（不是增量）。</li>
 *   <li>provider 自己负责与注册中心的连接与重连；eRPC 客户端不碰这些细节。</li>
 * </ul>
 *
 * <p>内置实现：{@link StaticErpcNodeProvider}（固定列表，测试与静态部署用）。</p>
 */
public interface ErpcNodeProvider {

    /**
     * 拉取当前的节点列表。
     *
     * @return 节点快照（不可变；没有节点时返回空列表而不是 null）
     */
    List<ErpcNode> nodes();

    /**
     * 订阅节点变更。
     *
     * <p>实现方通常在订阅时<b>立即</b>回调一次当前列表，让客户端不用额外
     * 先调一次 {@link #nodes()}。</p>
     *
     * @param listener 变更回调
     */
    void subscribe(NodeChangeListener listener);
}
