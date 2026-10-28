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

package io.edap.raft.tracker;

import io.edap.raft.RaftException;
import io.edap.raft.protocol.ConfState;
import io.edap.raft.quorum.AckedIndexer;
import io.edap.raft.quorum.Index;
import io.edap.raft.quorum.MajorityConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;

/**
 * 节点 id 到 {@link Progress} 的映射，等价于 etcd 的 {@code tracker.ProgressMap}。
 *
 * <p>内部用 {@link TreeMap} 保证遍历顺序稳定（按 id 升序），
 * 这和 etcd 里排序后再遍历的行为一致。</p>
 */
public final class ProgressMap {

    /** 节点 id -> 复制进度 */
    private final TreeMap<Long, Progress> progressMap = new TreeMap<>();

    /**
     * 构造一份空的进度表。
     */
    public ProgressMap() {
    }

    /**
     * 取指定节点的复制进度，不存在时返回 null。
     */
    public Progress get(long id) {
        return progressMap.get(id);
    }

    /**
     * 放入（或替换）指定节点的复制进度。
     *
     * @param id       节点 id
     * @param progress 复制进度，不能为 null
     * @throws io.edap.raft.RaftException progress 为 null 时抛出
     */
    public void put(long id, Progress progress) {
        if (progress == null) {
            throw new RaftException("progress must not be null");
        }
        progressMap.put(id, progress);
    }

    /**
     * 移除指定节点的复制进度。
     *
     * @return 被移除的复制进度，不存在时返回 null
     */
    public Progress remove(long id) {
        return progressMap.remove(id);
    }

    /**
     * 按 id 升序遍历所有复制进度。
     */
    public void visit(BiConsumer<Long, Progress> visitor) {
        for (Map.Entry<Long, Progress> entry : progressMap.entrySet()) {
            visitor.accept(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 进度表的所有条目（按 id 升序，只读视图）。
     */
    public Set<Map.Entry<Long, Progress>> entrySet() {
        return Collections.unmodifiableSet(progressMap.entrySet());
    }

    /**
     * 根据各投票节点已确认的位置算出当前已提交的最大 index。
     *
     * <p>取所有投票节点 match 的多数派位置；尚未回应的节点按 0 计入。
     * 没有投票节点时返回 {@link Index#INFINITY}，与 etcd 的空配置行为一致。</p>
     */
    public Index committedIndex(AckedIndexer acker) {
        return MajorityConfig.of(voters()).committedIndex(acker);
    }

    /**
     * 以当前跟踪的成员生成一份集群配置快照。
     */
    public ConfState confState() {
        return new ConfState(new ArrayList<>(voters()), new ArrayList<>(learners()),
                Collections.emptyList(), Collections.emptyList(), false);
    }

    /**
     * 当前所有投票节点的 id（升序）。
     */
    public Set<Long> voters() {
        Set<Long> ids = new LinkedHashSet<>();
        for (Map.Entry<Long, Progress> entry : progressMap.entrySet()) {
            if (!entry.getValue().isLearner()) {
                ids.add(entry.getKey());
            }
        }
        return ids;
    }

    /**
     * 当前所有 learner 节点的 id（升序）。
     */
    public Set<Long> learners() {
        Set<Long> ids = new LinkedHashSet<>();
        for (Map.Entry<Long, Progress> entry : progressMap.entrySet()) {
            if (entry.getValue().isLearner()) {
                ids.add(entry.getKey());
            }
        }
        return ids;
    }

    /**
     * 跟踪的节点总数。
     */
    public int size() {
        return progressMap.size();
    }

    /**
     * 清空进度表。
     */
    public void clear() {
        progressMap.clear();
    }

    /**
     * 与 etcd 一致的可读描述：按 id 升序每行一个节点。
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        for (Map.Entry<Long, Progress> entry : progressMap.entrySet()) {
            buf.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        return buf.toString();
    }
}
