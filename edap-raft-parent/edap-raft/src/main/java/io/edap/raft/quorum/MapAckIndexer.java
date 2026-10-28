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

import io.edap.raft.RaftException;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * 基于 {@code Map<Long, Index>} 的 {@link AckedIndexer} 实现，
 * 等价于 etcd 的 {@code quorum.mapAckIndexer}。
 */
public final class MapAckIndexer implements AckedIndexer {

    private final Map<Long, Index> acked;

    /**
     * 用一份“节点 id -> 已确认位置”的映射构造，构造后内部持有副本，外部修改不影响本对象
     */
    public MapAckIndexer(Map<Long, Index> acked) {
        if (acked == null) {
            throw new RaftException("acked index map must not be null");
        }
        Map<Long, Index> copy = new TreeMap<>();
        for (Map.Entry<Long, Index> entry : acked.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                throw new RaftException("acked index map must not contain null key or value");
            }
            copy.put(entry.getKey(), entry.getValue());
        }
        this.acked = Collections.unmodifiableMap(copy);
    }

    /**
     * 查该投票节点已确认的最大 index；不在映射中时返回 null
     */
    @Override
    public Index ackedIndex(long voterId) {
        return acked.get(voterId);
    }

    @Override
    public String toString() {
        return "MapAckIndexer" + acked;
    }
}
