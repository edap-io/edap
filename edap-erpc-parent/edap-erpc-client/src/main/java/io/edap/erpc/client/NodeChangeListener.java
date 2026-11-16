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
 * 注册中心节点变更的回调。
 *
 * <p>★ <b>回调线程是注册中心自己的线程</b> —— 实现方不要做耗时操作，
 * 也不要去回调 {@link ErpcClient} 的调用路径。</p>
 */
public interface NodeChangeListener {

    /**
     * 节点列表发生变化（新增 / 移除 / 权重变化）。
     *
     * @param nodes 变更后的<b>完整</b>节点列表（不是增量）
     */
    void onNodesChanged(List<ErpcNode> nodes);
}
