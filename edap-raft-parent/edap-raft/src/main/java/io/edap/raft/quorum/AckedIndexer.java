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

package io.edap.raft.quorum;

/**
 * 按投票节点 id 查询其已确认的最大日志位置，等价于 etcd 的 {@code quorum.AckedIndexer}。
 */
public interface AckedIndexer {

    /**
     * 查该投票节点已确认的最大 index；不在配置中（或尚未确认）时返回 null
     */
    Index ackedIndex(long voterId);
}
