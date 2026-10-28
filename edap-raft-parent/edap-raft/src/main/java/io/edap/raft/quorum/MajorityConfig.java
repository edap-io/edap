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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * 用多数派做决策的投票成员集合，等价于 etcd 的 {@code quorum.MajorityConfig}。
 *
 * <p>etcd 用 {@code map[uint64]struct{}} 表示，这里用排序的 {@code TreeSet} 承载，
 * 保证遍历顺序确定（输出、日志、编码都稳定）。</p>
 */
public final class MajorityConfig {

    private final NavigableSet<Long> ids;

    private MajorityConfig(Collection<Long> ids) {
        TreeSet<Long> sorted = new TreeSet<>();
        for (Long id : ids) {
            if (id == null) {
                throw new RaftException("voter id must not be null");
            }
            sorted.add(id);
        }
        this.ids = Collections.unmodifiableNavigableSet(sorted);
    }

    /**
     * 用一组投票节点 id 构造，重复 id 自动去重
     */
    public static MajorityConfig of(Collection<Long> ids) {
        if (ids == null) {
            throw new RaftException("voter ids must not be null");
        }
        return new MajorityConfig(ids);
    }

    /**
     * 空的多数派配置
     */
    public static MajorityConfig empty() {
        return new MajorityConfig(Collections.emptyList());
    }

    /**
     * 该 id 是否是投票成员
     */
    public boolean contains(long id) {
        return ids.contains(id);
    }

    /**
     * 投票成员数量，多数派数量为 size/2 + 1
     */
    public int size() {
        return ids.size();
    }

    /**
     * 按 id 升序排列的投票成员列表
     */
    public List<Long> slice() {
        return new ArrayList<>(ids);
    }

    /**
     * 投票成员的只读集合（按 id 升序）
     */
    public Set<Long> ids() {
        return ids;
    }

    /**
     * 计算在给定确认信息下的已提交位置，等价于 etcd 的 {@code CommittedIndex}。
     *
     * <p>把各成员已确认的位置放进数组排序后取第 {@code n/2+1} 大的那个；
     * 尚未确认的成员按 0 计入，排在最前面，因此不会抬高提交位置。</p>
     */
    public Index committedIndex(AckedIndexer l) {
        if (l == null) {
            throw new RaftException("acked indexer must not be null");
        }
        int n = size();
        if (n == 0) {
            // 空配置对联合仲裁要表现得像另一个半边一样，因此返回无穷大
            return Index.INFINITY;
        }

        // 已确认的位置从右往左填，未填的槽位保持 0（表示还没报告的成员）
        long[] srt = new long[n];
        int i = n - 1;
        for (Long id : ids) {
            Index idx = l.ackedIndex(id);
            if (idx != null) {
                srt[i] = idx.value();
                i--;
            }
        }
        Arrays.sort(srt);

        // 从右往左数 n/2+1 个，取到的就是被多数派确认的位置
        int pos = n - (n / 2 + 1);
        return new Index(srt[pos]);
    }

    /**
     * 根据各成员的赞成/反对票给出投票结果，等价于 etcd 的 {@code VoteResult}。
     *
     * <p>赞成票达到多数即获胜；反对票加未投票达到多数即失败；否则待定。
     * 空配置按约定直接获胜，这样联合仲裁的半个空配置表现得像普通多数派。</p>
     */
    public VoteResult voteResult(Map<Long, Boolean> votes) {
        if (votes == null) {
            throw new RaftException("votes must not be null");
        }
        int n = size();
        if (n == 0) {
            return VoteResult.VOTE_WON;
        }

        int votedCnt = 0;
        int missing = 0;
        for (Long id : ids) {
            Boolean v = votes.get(id);
            if (v == null) {
                // 没有投过票的成员
                missing++;
                continue;
            }
            if (v) {
                votedCnt++;
            }
        }

        int q = n / 2 + 1;
        if (votedCnt >= q) {
            return VoteResult.VOTE_WON;
        }
        if (votedCnt + missing >= q) {
            return VoteResult.VOTE_PENDING;
        }
        return VoteResult.VOTE_LOST;
    }

    /**
     * 生成各成员确认位置的多行 ASCII 进度图，等价于 etcd 的 {@code Describe}。
     *
     * <p>每行一个成员，{@code x} 的个数表示该成员确认位置在全体中的排名，
     * {@code ?} 表示还没有确认信息。</p>
     */
    public String describe(AckedIndexer l) {
        if (l == null) {
            throw new RaftException("acked indexer must not be null");
        }
        int n = size();
        if (n == 0) {
            return "<empty majority quorum>";
        }

        List<Tup> info = new ArrayList<>(n);
        for (Long id : ids) {
            Index idx = l.ackedIndex(id);
            info.add(new Tup(id, idx == null ? new Index(0) : idx, idx != null));
        }

        // 先按确认位置排序，位置相同时按 id 排序
        info.sort((a, b) -> {
            int c = a.idx.compareTo(b.idx);
            return c != 0 ? c : Long.compare(a.id, b.id);
        });

        // 填充 bar：第 i 大的确认位置拥有长度 i 的进度条，位置相同则进度条相同
        for (int i = 1; i < info.size(); i++) {
            if (info.get(i - 1).idx.compareTo(info.get(i).idx) < 0) {
                info.get(i).bar = i;
            } else {
                info.get(i).bar = info.get(i - 1).bar;
            }
        }

        // 再按 id 排序输出
        info.sort((a, b) -> Long.compare(a.id, b.id));

        StringBuilder buf = new StringBuilder();
        buf.append(" ".repeat(n)).append("    idx\n");
        for (Tup t : info) {
            if (!t.ok) {
                buf.append("?").append(" ".repeat(n));
            } else {
                buf.append("x".repeat(t.bar)).append(">").append(" ".repeat(n - t.bar));
            }
            buf.append(' ').append(pad(t.idx.value(), 5)).append("    (id=")
                    .append(Long.toHexString(t.id)).append(")\n");
        }
        return buf.toString();
    }

    /**
     * 左补空格到指定宽度，等价于 Go 的 {@code %5d}
     */
    private static String pad(long value, int width) {
        String s = Long.toString(value);
        if (s.length() >= width) {
            return s;
        }
        return " ".repeat(width - s.length()) + s;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MajorityConfig)) {
            return false;
        }
        return ids.equals(((MajorityConfig) o).ids);
    }

    @Override
    public int hashCode() {
        return ids.hashCode();
    }

    /**
     * 打印成 {@code (1 2 3)} 的形式，id 用十进制，与 etcd 保持一致
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder("(");
        boolean first = true;
        for (Long id : ids) {
            if (!first) {
                buf.append(' ');
            }
            first = false;
            buf.append(id);
        }
        return buf.append(')').toString();
    }

    /**
     * describe 时用的临时记录：一个成员及其确认位置
     */
    private static final class Tup {

        private final long    id;
        private final Index   idx;
        /** 是否查到了确认位置 */
        private final boolean ok;
        /** 该成员进度条的长度 */
        private int           bar;

        private Tup(long id, Index idx, boolean ok) {
            this.id = id;
            this.idx = idx;
            this.ok = ok;
        }
    }
}
