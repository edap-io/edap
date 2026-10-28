/*
 * Copyright 2023 The edap Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.edap.raft;

/**
 * 只读请求的处理方式，等价于 etcd 的 {@code ReadOnlyOption}。
 */
public enum ReadOnlyOption {

    /**
     * 通过与多数派通信来保证线性一致性，是默认且推荐的方式。
     *
     * <p>leader 先记下当前提交位置，再广播一轮心跳，等多数派确认后
     * 才把读请求的确认位置返回给调用方。</p>
     */
    READ_ONLY_SAFE,

    /**
     * 依赖 leader 的租约来保证线性一致性，会受时钟漂移影响。
     *
     * <p>时钟漂移无界时 leader 可能持有过期的租约，因此这种模式下
     * ReadIndex 并不安全，必须同时开启 {@code CheckQuorum}。</p>
     */
    READ_ONLY_LEASE_BASED
}
