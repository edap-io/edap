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

package io.edap.raft.protocol;

/**
 * 统一抽象 v1 与 v2 两种成员变更，等价于 etcd 的 {@code raftpb.ConfChangeI}。
 */
public interface ConfChangeI {

    /**
     * 转成 v2，恒返回非 null
     */
    ConfChangeV2 asV2();

    /**
     * 转成 v1，不是 v1 时返回 null
     */
    ConfChange asV1();
}
