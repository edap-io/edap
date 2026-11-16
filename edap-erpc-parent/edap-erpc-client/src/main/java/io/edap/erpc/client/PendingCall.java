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

import java.util.concurrent.CompletableFuture;

/**
 * 一个在途调用（requestId → 等待中的响应）。
 *
 * <p>★ 响应帧的 {@code requestId} 是<b>唯一</b>的配对依据（一条连接上多个请求并发在途），
 * 所以在途表必须是 {@code ConcurrentHashMap} 且<b>先注册后写</b> ——
 * 否则响应可能先于注册到达，被丢弃，调用方永久等待。</p>
 */
final class PendingCall {

    /** 帧头里的 requestId —— 响应帧原样回填。 */
    final int requestId;

    /** 响应载荷（RESPONSE 帧）或异常（ERROR 帧 / 连接断开 / 超时）。 */
    final CompletableFuture<byte[]> future = new CompletableFuture<>();

    PendingCall(int requestId) {
        this.requestId = requestId;
    }
}
