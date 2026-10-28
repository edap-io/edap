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

package io.edap.raft.confchange;

import io.edap.raft.RaftException;
import io.edap.raft.protocol.ConfChangeSingle;
import io.edap.raft.protocol.ConfChangeType;
import io.edap.raft.quorum.JointConfig;
import io.edap.raft.quorum.MajorityConfig;
import io.edap.raft.tracker.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 成员变更的执行者，等价于 etcd 的 {@code confchange.Changer}。
 *
 * <p>etcd 的 Changer 同时维护配置（{@code tracker.Config}）和每个节点的复制进度
 * （{@code tracker.ProgressMap}），这里只维护配置部分，成员集合按下标与 etcd 对齐：</p>
 * <ul>
 *     <li>生效中的投票成员放在 {@link JointConfig} 的第一半，即 etcd 的 Voters[0]（incoming）；</li>
 *     <li>联合配置期间保留的旧投票成员放在第二半，即 etcd 的 Voters[1]（outgoing）；</li>
 *     <li>Java 的 JointConfig 两个访问器的命名与 etcd 相反，这里一律按下标对齐。</li>
 * </ul>
 *
 * <p>etcd 的 {@code tracker.Config.LearnersNext}（挂起中的 learner）对应
 * {@link Config#getLearnersNext()}：联合配置期间把某个投票成员降级为 learner 时，
 * 不能直接加进 learners（会与旧配置里的投票成员身份冲突），先记在这里，
 * 等旧配置退出后再转正。</p>
 *
 * <p>所有操作都作用在构造时配置的副本上，Changer 自身不可变；
 * etcd 中返回 error 的地方，这里抛出 {@link RaftException}。</p>
 */
public class Changer {

    /** 本节点 id */
    private final long   id;

    /** 当前配置，所有操作都在它的副本上进行 */
    private final Config trackerConfig;

    /**
     * 构造一个成员变更执行器。
     *
     * @param id            本节点 id
     * @param trackerConfig 当前配置，通常是引导阶段的空配置
     */
    public Changer(long id, Config trackerConfig) {
        if (trackerConfig == null) {
            throw new RaftException("tracker config must not be null");
        }
        this.id = id;
        this.trackerConfig = trackerConfig;
    }

    /**
     * 本节点 id。
     */
    public long getId() {
        return id;
    }

    /**
     * 当前配置。
     */
    public Config getTrackerConfig() {
        return trackerConfig;
    }

    /**
     * 进入联合配置，并把给定的节点加进生效中的投票成员。
     *
     * <p>等价于 etcd 的 {@code EnterJoint(autoLeave, ADD_NODE(ids...))}：先把生效配置
     * 复制成保留的旧配置，再把变更作用到生效配置上，于是从
     * {@code (1 2 3)&&()} 变成 {@code (1 2 3)&&(1 2 3)}。</p>
     *
     * @param autoLeave 条件允许时是否由 raft 自动退出联合配置
     * @param ids       要加进投票成员的节点 id
     * @return 变更后的配置
     */
    public Config enterJoint(boolean autoLeave, long... ids) {
        long[] nodeIds = ids == null ? new long[0] : ids;
        List<ConfChangeSingle> changes = new ArrayList<>(nodeIds.length);
        for (long nodeId : nodeIds) {
            changes.add(new ConfChangeSingle(ConfChangeType.ADD_NODE, nodeId));
        }
        return enterJoint(autoLeave, changes);
    }

    /**
     * 进入联合配置，并按给定的变更列表调整生效中的成员。
     *
     * <p>与 etcd 的 {@code EnterJoint(autoLeave, ccs...)} 完全等价，
     * 变更作用在生效配置上，保留的旧配置在联合状态结束前不动。</p>
     *
     * @param autoLeave 条件允许时是否由 raft 自动退出联合配置
     * @param changes   要作用到生效配置上的变更
     * @return 变更后的配置
     */
    public Config enterJoint(boolean autoLeave, List<ConfChangeSingle> changes) {
        State st = copy();
        if (isJoint(st)) {
            throw new RaftException("config is already joint");
        }
        if (st.active.isEmpty()) {
            // 允许往空配置里加节点（引导、测试），但空配置不能进入联合状态
            throw new RaftException("can't make a zero-voter config joint");
        }
        // 清空保留的旧配置，并把生效配置复制过去
        st.retained.clear();
        st.retained.addAll(st.active);

        apply(st, changes == null ? new ArrayList<ConfChangeSingle>() : changes);
        st.autoLeave = autoLeave;
        return checkAndReturn(st);
    }

    /**
     * 退出联合配置。
     *
     * <p>保留的旧配置被丢弃，生效配置成为唯一的决策者；挂起中的 learner
     * （{@code learnersNext}）在这一步转正。</p>
     *
     * @return 变更后的配置
     */
    public Config leaveJoint() {
        State st = copy();
        if (!isJoint(st)) {
            throw new RaftException("can't leave a non-joint config");
        }
        // 挂起中的 learner 转正
        st.learners.addAll(st.learnersNext);
        st.learnersNext.clear();
        st.retained.clear();
        st.autoLeave = false;
        return checkAndReturn(st);
    }

    /**
     * 简单协议变更：一次性应用若干条变更。
     *
     * <p>生效配置里的投票成员最多只能变化一个，否则必须走联合配置；
     * 联合状态下不允许做简单变更。</p>
     *
     * @param changes 要应用的变更
     * @return 变更后的配置
     */
    public Config simple(ConfChangeSingle... changes) {
        return simple(changes == null ? new ArrayList<ConfChangeSingle>() : Arrays.asList(changes));
    }

    /**
     * 简单协议变更的内部实现。
     */
    private Config simple(List<ConfChangeSingle> changes) {
        State st = copy();
        if (isJoint(st)) {
            throw new RaftException("can't apply simple config change in joint config");
        }
        // 记下变更前的投票成员，稍后比较
        Set<Long> before = new TreeSet<>(st.active);
        apply(st, changes);

        // 相对变更前，生效配置里的投票成员最多只能变化一个
        if (symdiff(before, st.active) > 1) {
            throw new RaftException("more than one voter changed without entering joint config");
        }
        return checkAndReturn(st);
    }

    /**
     * 把一组变更作用到生效中的配置上。
     *
     * <p>投票成员的变更一律作用在生效配置（etcd 的 Voters[0]）上，
     * 保留的旧配置（etcd 的 Voters[1]）在联合状态结束前不会变动。</p>
     */
    private void apply(State st, List<ConfChangeSingle> changes) {
        for (ConfChangeSingle cc : changes) {
            if (cc == null) {
                throw new RaftException("conf change must not be null");
            }
            if (cc.getNodeId() == 0) {
                // etcd 用 nodeId=0 表示这条变更被放弃，直接跳过
                continue;
            }
            ConfChangeType type = cc.getType();
            if (type == null) {
                throw new RaftException("unexpected conf type null");
            }
            switch (type) {
                case ADD_NODE:
                    makeVoter(st, cc.getNodeId());
                    break;
                case ADD_LEARNER_NODE:
                    makeLearner(st, cc.getNodeId());
                    break;
                case REMOVE_NODE:
                    remove(st, cc.getNodeId());
                    break;
                case UPDATE_NODE:
                    // 只更新节点元数据（地址等），不影响配置
                    break;
                default:
                    throw new RaftException("unexpected conf type " + type);
            }
        }
        if (st.active.isEmpty()) {
            throw new RaftException("removed all voters");
        }
    }

    /**
     * 把节点变成生效配置里的投票成员。
     */
    private void makeVoter(State st, long id) {
        st.learners.remove(id);
        st.learnersNext.remove(id);
        st.active.add(id);
    }

    /**
     * 把节点变成 learner。
     *
     * <p>如果节点还在保留的旧配置里，它不能直接变成 learner（否则同一个节点
     * 同时是投票成员和 learner），先挂起到 {@code learnersNext}，
     * 等旧配置退出后才算正式的 learner。</p>
     */
    private void makeLearner(State st, long id) {
        st.active.remove(id);
        st.learners.remove(id);
        if (st.retained.contains(id)) {
            // 还在旧配置里当投票成员，先挂起
            st.learnersNext.add(id);
        } else {
            st.learners.add(id);
        }
    }

    /**
     * 从生效配置里移除节点。
     *
     * <p>节点如果还在保留的旧配置里，旧配置里的那份先留着，
     * 等联合状态结束后一并清掉。</p>
     */
    private void remove(State st, long id) {
        st.active.remove(id);
        st.learners.remove(id);
        st.learnersNext.remove(id);
    }

    /**
     * 校验配置自洽，不自洽时抛出异常；自洽则返回配置。
     */
    private Config checkAndReturn(State st) {
        // learner 不能同时是生效配置里的投票成员
        for (Long id : st.learners) {
            if (st.active.contains(id)) {
                throw new RaftException(id + " is in Learners and Voters[0]");
            }
            // learner 也不能同时是旧配置里的投票成员
            if (st.retained.contains(id)) {
                throw new RaftException(id + " is in Learners and Voters[1]");
            }
        }
        // 挂起中的 learner 必须还在旧配置里，否则旧配置退出后它会凭空消失
        for (Long id : st.learnersNext) {
            if (!st.retained.contains(id)) {
                throw new RaftException(id + " is in LearnersNext, but not Voters[1]");
            }
            if (st.learners.contains(id)) {
                throw new RaftException(id + " is in LearnersNext, but is already a learner");
            }
        }
        if (!isJoint(st)) {
            // 非联合状态时不允许残留旧配置，也不允许 autoLeave
            if (!st.retained.isEmpty()) {
                throw new RaftException("cfg.Voters[1] must be nil when not joint");
            }
            if (!st.learnersNext.isEmpty()) {
                throw new RaftException("cfg.LearnersNext must be nil when not joint");
            }
            if (st.autoLeave) {
                throw new RaftException("AutoLeave must be false when not joint");
            }
        }
        return toConfig(st);
    }

    /**
     * 复制当前配置，得到一份可随意改写的工作副本。
     */
    private State copy() {
        State st = new State();
        JointConfig voters = trackerConfig.voters;
        if (voters != null) {
            // 第一半是生效配置（etcd 的 incoming），第二半是保留的旧配置（etcd 的 outgoing）
            st.active.addAll(voters.getIncoming().ids());
            st.retained.addAll(voters.getOutgoing().ids());
        }
        if (trackerConfig.learners != null) {
            st.learners.addAll(trackerConfig.learners);
        }
        if (trackerConfig.learnersNext != null) {
            st.learnersNext.addAll(trackerConfig.learnersNext);
        }
        st.autoLeave = trackerConfig.autoLeave;
        return st;
    }

    /**
     * 把工作副本还原成配置。
     */
    private Config toConfig(State st) {
        // JointConfig 的两个半边按下标与 etcd 对齐：第一半生效配置，第二半保留的旧配置
        JointConfig voters = JointConfig.of(MajorityConfig.of(st.active), MajorityConfig.of(st.retained));
        Config cfg = new Config(voters, new LinkedHashSet<>(st.learners),
                new LinkedHashSet<>(st.learnersNext), st.autoLeave);
        cfg.setId(trackerConfig.getId());
        return cfg;
    }

    /**
     * 是否处于联合配置：保留的旧配置非空即是。
     */
    private static boolean isJoint(State st) {
        return !st.retained.isEmpty();
    }

    /**
     * 两个集合的对称差个数，即 {@code len((l - r) ∪ (r - l))}。
     */
    private static int symdiff(Set<Long> l, Set<Long> r) {
        int n = 0;
        for (Long id : l) {
            if (!r.contains(id)) {
                n++;
            }
        }
        for (Long id : r) {
            if (!l.contains(id)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 操作过程中的配置快照。
     */
    private static final class State {

        /** 生效中的投票成员，对应 etcd 的 Voters[0] */
        private final Set<Long> active = new TreeSet<>();

        /** 联合配置中保留的旧投票成员，对应 etcd 的 Voters[1] */
        private final Set<Long> retained = new TreeSet<>();

        /** 已经生效的 learner，对应 etcd 的 Config.Learners */
        private final Set<Long> learners = new TreeSet<>();

        /** 挂起中的 learner，对应 etcd 的 Config.LearnersNext */
        private final Set<Long> learnersNext = new TreeSet<>();

        /** 联合配置是否自动退出 */
        private boolean autoLeave;
    }
}
