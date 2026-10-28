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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * etcd {@code quorum} 包测试里的公共脚手架。
 *
 * <p>Go 里这些辅助函数直接写在各个 {@code *_test.go} 里，
 * Java 侧统一收敛到本类，方法名与 Go 保持一致，便于逐条对照。</p>
 */
public final class QuorumTestSupport {

    private QuorumTestSupport() {
    }

    /**
     * 把 long 数组转成 list
     */
    public static List<Long> longs(long[] values) {
        Long[] boxed = new Long[values.length];
        for (int i = 0; i < values.length; i++) {
            boxed[i] = values[i];
        }
        return Arrays.asList(boxed);
    }

    /**
     * 构造「节点 id -> 已确认位置」的映射，{@code idxs} 里的 -1 表示该节点没有确认信息。
     */
    public static MapAckIndexer lookuper(long[] voters, long[] idxs) {
        Map<Long, Index> acked = new TreeMap<>();
        for (int i = 0; i < voters.length; i++) {
            if (idxs[i] >= 0) {
                acked.put(voters[i], new Index(idxs[i]));
            }
        }
        return new MapAckIndexer(acked);
    }

    /**
     * 把某个成员的确认位置替换掉，其余成员保持不变。
     */
    public static MapAckIndexer overlay(MajorityConfig c, MapAckIndexer l, long id, long idx) {
        Map<Long, Index> overlaid = new TreeMap<>();
        for (Long iid : c.ids()) {
            if (iid == id) {
                overlaid.put(iid, new Index(idx));
            } else {
                Index cur = l.ackedIndex(iid);
                if (cur != null) {
                    overlaid.put(iid, cur);
                }
            }
        }
        return new MapAckIndexer(overlaid);
    }

    /**
     * {@code MajorityConfig.CommittedIndex} 的另一种「笨」实现，
     * 用来交叉验证主实现的结果。
     *
     * <p>移植自 etcd 的 {@code alternativeMajorityCommittedIndex}。</p>
     */
    public static Index alternativeMajorityCommittedIndex(MajorityConfig c, MapAckIndexer l) {
        if (c.size() == 0) {
            return Index.INFINITY;
        }
        Map<Long, Index> idToIdx = new TreeMap<>();
        for (Long id : c.ids()) {
            Index idx = l.ackedIndex(id);
            if (idx != null) {
                idToIdx.put(id, idx);
            }
        }
        // 建立「位置 -> 确认了该位置或更高位置的成员数」的映射
        Map<Long, Integer> idxToVotes = new TreeMap<>();
        for (Index idx : idToIdx.values()) {
            idxToVotes.put(idx.value(), 0);
        }
        for (Index idx : idToIdx.values()) {
            for (Map.Entry<Long, Integer> entry : idxToVotes.entrySet()) {
                if (entry.getKey() > idx.value()) {
                    continue;
                }
                idxToVotes.put(entry.getKey(), entry.getValue() + 1);
            }
        }
        // 找出达到多数派的最大位置
        int q = c.size() / 2 + 1;
        long maxQuorumIdx = 0;
        for (Map.Entry<Long, Integer> entry : idxToVotes.entrySet()) {
            if (entry.getValue() >= q && entry.getKey() > maxQuorumIdx) {
                maxQuorumIdx = entry.getKey();
            }
        }
        return new Index(maxQuorumIdx);
    }
}
