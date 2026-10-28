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
import java.util.Set;
import java.util.TreeSet;

/**
 * 由两个（可能重叠的）多数派配置组成的联合配置，等价于 etcd 的 {@code quorum.JointConfig}。
 *
 * <p>etcd 的类型是 {@code [2]MajorityConfig}，即两个多数派配置组成的定长数组：
 * {@code c[0]} 是<b>生效（incoming）</b>的那一半，{@code c[1]} 是<b>即将退出（outgoing）</b>的那一半。
 * 联合仲裁要求<b>两边各自</b>都达到多数，这是 Raft 论文 §6 成员变更的安全前提。</p>
 *
 * <p>对应 etcd 中 {@code confchange} 包的辅助函数：</p>
 * <pre>
 * func incoming(voters quorum.JointConfig) quorum.MajorityConfig { return voters[0] }
 * func outgoing(voters quorum.JointConfig) quorum.MajorityConfig { return voters[1] }
 * </pre>
 */
public final class JointConfig {

    /**
     * 生效（incoming）的那一半，对应 etcd 的 c[0]。
     *
     * <p>成员变更期间，这是变更完成后的目标配置（老成员已在其中被移除）。</p>
     */
    private final MajorityConfig incoming;

    /**
     * 即将退出（outgoing）的那一半，对应 etcd 的 c[1]。
     *
     * <p>成员变更期间，这是变更前的旧配置（老成员仍在其中）。
     * 非联合配置时为空。</p>
     */
    private final MajorityConfig outgoing;

    private JointConfig(MajorityConfig incoming, MajorityConfig outgoing) {
        this.incoming = incoming;
        this.outgoing = outgoing;
    }

    /**
     * 用“生效的那一半”和“即将退出的那一半”构造联合配置。
     *
     * <p>顺序与 etcd 的 {@code c[0]}、{@code c[1]} 一致：先传生效的（incoming），再传退出的（outgoing）。</p>
     *
     * @param incoming 生效的多数派配置，对应 c[0]
     * @param outgoing 即将退出的多数派配置，对应 c[1]，非联合配置时传 {@link MajorityConfig#empty()}
     */
    public static JointConfig of(MajorityConfig incoming, MajorityConfig outgoing) {
        if (incoming == null || outgoing == null) {
            throw new RaftException("joint config halves must not be null");
        }
        return new JointConfig(incoming, outgoing);
    }

    /**
     * 空的联合配置，两半都是空的。
     */
    public static JointConfig empty() {
        return new JointConfig(MajorityConfig.empty(), MajorityConfig.empty());
    }

    /**
     * 生效（incoming）的那一半，对应 etcd 的 {@code c[0]}。
     */
    public MajorityConfig getIncoming() {
        return incoming;
    }

    /**
     * 即将退出（outgoing）的那一半，对应 etcd 的 {@code c[1]}。
     *
     * <p>联合配置下非空；非联合配置（普通多数派配置）时为空。</p>
     */
    public MajorityConfig getOutgoing() {
        return outgoing;
    }

    /**
     * 联合配置中的全部投票成员（两半的并集），对应 etcd 的 {@code IDs()}。
     */
    public Set<Long> ids() {
        TreeSet<Long> all = new TreeSet<>(incoming.ids());
        all.addAll(outgoing.ids());
        return Collections.unmodifiableSet(all);
    }

    /**
     * 该 id 是否属于联合配置（两半的并集）。
     */
    public boolean contains(long id) {
        return incoming.contains(id) || outgoing.contains(id);
    }

    /**
     * 联合配置中的投票成员数量（两半的并集）。
     */
    public int size() {
        return ids().size();
    }

    /**
     * 生成各成员确认位置的多行 ASCII 进度图，成员取两半的并集。
     *
     * <p>对应 etcd 的 {@code Describe(l)}：{@code MajorityConfig(c.IDs()).Describe(l)}。</p>
     */
    public String describe(AckedIndexer l) {
        return MajorityConfig.of(ids()).describe(l);
    }

    /**
     * 计算联合仲裁下的已提交位置，等价于 etcd 的 {@code CommittedIndex(l)}。
     *
     * <p>位置在两边都算提交，因此取两边的较小值。
     * 空的那一半返回 {@link Index#INFINITY}，因此半边为空的联合配置表现得跟普通多数派一致。</p>
     */
    public Index committedIndex(AckedIndexer l) {
        Index idx0 = incoming.committedIndex(l);
        Index idx1 = outgoing.committedIndex(l);
        return idx0.compareTo(idx1) <= 0 ? idx0 : idx1;
    }

    /**
     * 根据各成员的赞成/反对票给出投票结果，等价于 etcd 的 {@code VoteResult(votes)}。
     *
     * <p>两边结论一致时取该结论；只要有一边失败就是失败；
     * 一边成功一边待定则整体待定。</p>
     */
    public VoteResult voteResult(Map<Long, Boolean> votes) {
        VoteResult r1 = incoming.voteResult(votes);
        VoteResult r2 = outgoing.voteResult(votes);

        if (r1 == r2) {
            // 两边结论一致
            return r1;
        }
        if (r1 == VoteResult.VOTE_LOST || r2 == VoteResult.VOTE_LOST) {
            // 只要有一边失败，最终只能是失败
            return VoteResult.VOTE_LOST;
        }
        // 一边成功一边待定，整体待定
        return VoteResult.VOTE_PENDING;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof JointConfig)) {
            return false;
        }
        JointConfig other = (JointConfig) o;
        return incoming.equals(other.incoming) && outgoing.equals(other.outgoing);
    }

    @Override
    public int hashCode() {
        return 31 * incoming.hashCode() + outgoing.hashCode();
    }

    /**
     * 打印成 {@code (1 2)&&(3 4)} 的形式，与 etcd 保持一致。
     *
     * <p>只有退出的那一半非空时才是联合配置，此时打印两半；
     * 否则就是普通的多数派配置，只打印生效的那一半。</p>
     */
    @Override
    public String toString() {
        if (outgoing.size() > 0) {
            return incoming.toString() + "&&" + outgoing.toString();
        }
        return incoming.toString();
    }
}
