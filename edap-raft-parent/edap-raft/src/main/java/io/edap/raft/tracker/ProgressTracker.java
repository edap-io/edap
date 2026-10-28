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

import io.edap.raft.protocol.ConfState;
import io.edap.raft.quorum.AckedIndexer;
import io.edap.raft.quorum.Index;
import io.edap.raft.quorum.VoteResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;

/**
 * 跟踪当前生效的配置以及各节点的复制进度，等价于 etcd 的 {@code tracker.ProgressTracker}。
 *
 * <p>它把三样东西放在一起：</p>
 * <ul>
 *     <li>{@link Config}：当前生效的成员配置（投票成员、learner、是否自动退出联合配置）；</li>
 *     <li>{@link ProgressMap}：每个节点的复制进度（match/next 等）；</li>
 *     <li>{@link #votes}：本轮选举中各投票节点的赞成/反对票。</li>
 * </ul>
 *
 * <p>各字段都是可变的：成员变更（{@code confchange}）会直接替换 {@link #config}
 * 与 {@link #progress}，因此上层可以就地把配置切换到新的一份。</p>
 */
public class ProgressTracker {

    /** 当前生效的成员配置 */
    public Config              config;
    /** 各节点的复制进度 */
    public ProgressMap         progress;
    /** 本轮选举中各投票节点的票，true 表示赞成 */
    public Map<Long, Boolean>  votes;
    /** 在途消息窗口的条数上限 */
    public int                 maxInflight;
    /** 在途消息窗口的字节数上限，0 表示不限制 */
    public long                maxInflightBytes;

    /**
     * 构造一个空的跟踪器，配置与进度都是空的。
     */
    public ProgressTracker() {
        this.config = new Config();
        this.progress = new ProgressMap();
        this.votes = new HashMap<>();
        this.maxInflight = Inflights.DEFAULT_SIZE;
        this.maxInflightBytes = 0;
    }

    /**
     * 初始化一个跟踪器，等价于 Go 的 {@code MakeProgressTracker}。
     *
     * @param maxInflight      在途消息窗口的条数上限
     * @param maxInflightBytes 在途消息窗口的字节数上限，0 表示不限制
     */
    public static ProgressTracker makeProgressTracker(int maxInflight, long maxInflightBytes) {
        ProgressTracker p = new ProgressTracker();
        p.maxInflight = maxInflight;
        p.maxInflightBytes = maxInflightBytes;
        return p;
    }

    /**
     * 当前生效的成员配置。
     */
    public Config getConfig() {
        return config;
    }

    /**
     * 各节点的复制进度。
     */
    public ProgressMap getProgress() {
        return progress;
    }

    /**
     * 以当前生效的配置生成一份集群配置快照，等价于 Go 的 {@code ConfState}。
     */
    public ConfState confState() {
        return new ConfState(
                config.voters.getIncoming().slice(),
                sorted(config.getLearners()),
                config.voters.getOutgoing().slice(),
                sorted(config.getLearnersNext()),
                config.autoLeave);
    }

    /**
     * 当前配置里是否只有一个投票成员（即只有 leader 自己）。
     */
    public boolean isSingleton() {
        return config.voters.getIncoming().size() == 1 && config.voters.getOutgoing().size() == 0;
    }

    /**
     * 该 id 是否是当前配置的投票成员（联合配置下两半都算）。
     */
    public boolean isVoter(long id) {
        return config.voters.contains(id);
    }

    /**
     * 根据投票成员已确认的位置算出当前已提交的最大日志 index，
     * 等价于 Go 的 {@code Committed}。
     */
    public Index committed() {
        return config.voters.committedIndex(new MatchAckIndexer(progress));
    }

    /**
     * 按 id 升序遍历所有被跟踪的节点进度，等价于 Go 的 {@code Visit}。
     */
    public void visit(BiConsumer<Long, Progress> f) {
        for (Map.Entry<Long, Progress> entry : progress.entrySet()) {
            f.accept(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 从本地状态机角度看，仲裁是否仍然有效，等价于 Go 的 {@code QuorumActive}。
     *
     * <p>把每个投票节点是否最近活跃当作它的一票，要求多数票赞成。</p>
     */
    public boolean quorumActive() {
        Map<Long, Boolean> activeVotes = new HashMap<>();
        visit((id, pr) -> {
            if (pr.isLearner()) {
                return;
            }
            activeVotes.put(id, pr.isRecentActive());
        });
        return config.voters.voteResult(activeVotes) == VoteResult.VOTE_WON;
    }

    /**
     * 所有投票成员的 id（升序），联合配置下包含两半的并集。
     */
    public List<Long> voterNodes() {
        return new ArrayList<>(new TreeSet<>(config.voters.ids()));
    }

    /**
     * 所有 learner 的 id（升序），没有 learner 时返回空列表。
     */
    public List<Long> learnerNodes() {
        return sorted(config.getLearners());
    }

    /**
     * 清空投票记录，准备开始新一轮计票，等价于 Go 的 {@code ResetVotes}。
     */
    public void resetVotes() {
        this.votes = new HashMap<>();
    }

    /**
     * 记录某个节点的投票，等价于 Go 的 {@code RecordVote}。
     *
     * <p>第一次投票生效，之后的重复投票被忽略。</p>
     *
     * @param id 投票节点 id
     * @param v  true 表示赞成，false 表示反对
     */
    public void recordVote(long id, boolean v) {
        votes.putIfAbsent(id, v);
    }

    /**
     * 统计本轮选举的票数，等价于 Go 的 {@code TallyVotes}。
     *
     * @return 赞成票数、反对票数以及最终投票结果
     */
    public TallyResult tallyVotes() {
        int granted = 0;
        int rejected = 0;
        for (Map.Entry<Long, Progress> entry : progress.entrySet()) {
            Progress pr = entry.getValue();
            if (pr.isLearner()) {
                continue;
            }
            Boolean v = votes.get(entry.getKey());
            if (v == null) {
                // 该节点还没投票
                continue;
            }
            if (v) {
                granted++;
            } else {
                rejected++;
            }
        }
        VoteResult result = config.voters.voteResult(votes);
        return new TallyResult(granted, rejected, result);
    }

    /**
     * 把 id 集合整理成升序列表。
     */
    private static List<Long> sorted(Set<Long> ids) {
        return new ArrayList<>(new TreeSet<>(ids));
    }

    /**
     * 选举计票结果，等价于 Go 里 {@code TallyVotes} 的三个返回值。
     */
    public static final class TallyResult {

        /** 赞成票数 */
        public final int        granted;
        /** 反对票数 */
        public final int        rejected;
        /** 最终投票结果 */
        public final VoteResult result;

        /**
         * 构造一个计票结果。
         */
        public TallyResult(int granted, int rejected, VoteResult result) {
            this.granted = granted;
            this.rejected = rejected;
            this.result = result;
        }

        /**
         * 赞成票数。
         */
        public int getGranted() {
            return granted;
        }

        /**
         * 反对票数。
         */
        public int getRejected() {
            return rejected;
        }

        /**
         * 最终投票结果。
         */
        public VoteResult getResult() {
            return result;
        }

        @Override
        public String toString() {
            return "TallyResult{granted=" + granted + ", rejected=" + rejected + ", result=" + result + "}";
        }
    }

    /**
     * 把投票节点 id 映射到它的 match，等价于 Go 的 {@code matchAckIndexer}。
     */
    private static final class MatchAckIndexer implements AckedIndexer {

        /** 各节点的复制进度 */
        private final ProgressMap progress;

        private MatchAckIndexer(ProgressMap progress) {
            this.progress = progress;
        }

        @Override
        public Index ackedIndex(long voterId) {
            Progress pr = progress.get(voterId);
            if (pr == null) {
                return null;
            }
            return new Index(pr.getMatch());
        }
    }
}
