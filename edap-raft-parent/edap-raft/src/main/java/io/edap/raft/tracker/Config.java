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

import io.edap.raft.quorum.JointConfig;
import io.edap.raft.quorum.MajorityConfig;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 当前跟踪的集群配置，等价于 etcd 的 {@code tracker.Config}。
 *
 * <p>字段直接开放出来，方便上层（选举、配置变更）就地替换；
 * learners 与 voters 不允许有交集。</p>
 *
 * <p>{@link #learnersNext} 是“挂起中的 learner”：联合配置期间把某个投票成员降级为
 * learner 时，不能直接加进 {@link #learners}（会与旧配置里的投票成员身份冲突），
 * 先记在这里，等旧配置退出后再转正。</p>
 */
public class Config {

    /** 所属 raft 节点的 id，对应 etcd 的 {@code Config.ID} */
    public long           id;

    /** 投票成员配置，可能是一个联合配置（新老两套成员同时生效） */
    public JointConfig voters;
    /** 联合配置是否在条件允许时由 raft 自动退出 */
    public boolean       autoLeave;
    /** 当前生效的 learner（非投票成员） */
    public Set<Long>     learners;
    /** 挂起中的 learner，等旧配置退出后转正 */
    public Set<Long>     learnersNext;

    /**
     * 构造一份空配置。
     */
    public Config() {
        this.voters = JointConfig.empty();
        this.learners = new LinkedHashSet<>();
        this.learnersNext = new LinkedHashSet<>();
        this.autoLeave = false;
    }

    /**
     * 构造一份完整配置。
     *
     * @param voters    投票成员配置
     * @param learners  learner 集合
     * @param autoLeave 联合配置是否自动退出
     */
    public Config(JointConfig voters, Set<Long> learners, boolean autoLeave) {
        this(voters, learners, null, autoLeave);
    }

    /**
     * 构造一份完整配置。
     *
     * @param voters       投票成员配置
     * @param learners     learner 集合
     * @param learnersNext 挂起中的 learner 集合
     * @param autoLeave    联合配置是否自动退出
     */
    public Config(JointConfig voters, Set<Long> learners, Set<Long> learnersNext, boolean autoLeave) {
        this.voters = voters == null ? JointConfig.empty() : voters;
        this.learners = learners == null ? new LinkedHashSet<>() : new LinkedHashSet<>(learners);
        this.learnersNext = learnersNext == null ? new LinkedHashSet<>() : new LinkedHashSet<>(learnersNext);
        this.autoLeave = autoLeave;
    }

    /**
     * 所属 raft 节点的 id。
     */
    public long getId() {
        return id;
    }

    public Config setId(long id) {
        this.id = id;
        return this;
    }

    /**
     * 投票成员配置。
     */
    public JointConfig getVoters() {
        return voters;
    }

    /**
     * 联合配置是否自动退出。
     */
    public boolean isAutoLeave() {
        return autoLeave;
    }

    /**
     * 当前生效的 learner 集合（只读视图）。
     */
    public Set<Long> getLearners() {
        return learners == null ? Collections.emptySet() : Collections.unmodifiableSet(learners);
    }

    /**
     * 挂起中的 learner 集合（只读视图）。
     */
    public Set<Long> getLearnersNext() {
        return learnersNext == null ? Collections.emptySet() : Collections.unmodifiableSet(learnersNext);
    }

    /**
     * 是否处于联合配置：即将退出的那半边投票成员非空即是。
     */
    public boolean isJoint() {
        return voters != null && voters.getOutgoing().size() > 0;
    }

    /**
     * 复制一份与自身内容相同、但不共享内存的配置，等价于 Go 的 {@code Clone}。
     *
     * <p>投票成员配置本身是不可变的，直接共享；两个 learner 集合各复制一份。</p>
     */
    public Config clone() {
        Config c = new Config();
        c.voters = this.voters;
        c.autoLeave = this.autoLeave;
        c.learners = this.learners == null ? null : new LinkedHashSet<>(this.learners);
        c.learnersNext = this.learnersNext == null ? null : new LinkedHashSet<>(this.learnersNext);
        return c;
    }

    /**
     * 按配置内容比较，等价于 Go 里对 {@code tracker.Config} 做深比较。
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Config)) {
            return false;
        }
        Config other = (Config) o;
        return id == other.id
                && autoLeave == other.autoLeave
                && java.util.Objects.equals(voters, other.voters)
                && getLearners().equals(other.getLearners())
                && getLearnersNext().equals(other.getLearnersNext());
    }

    /**
     * 与 {@link #equals(Object)} 配套的散列值。
     */
    @Override
    public int hashCode() {
        int result = Long.hashCode(id);
        result = 31 * result + (voters == null ? 0 : voters.hashCode());
        result = 31 * result + getLearners().hashCode();
        result = 31 * result + getLearnersNext().hashCode();
        result = 31 * result + (autoLeave ? 1 : 0);
        return result;
    }

    /**
     * 与 etcd 一致的可读描述：{@code voters=... learners=... learners_next=... autoleave}。
     *
     * <p>etcd 的 learners / learnersNext 是 map，为空时是 nil，nil 的 map 不会被打印出来；
     * Java 用空集合表示“没有 learner”，因此这里也只在集合非空时打印，
     * 保证输出与 etcd 完全一致。</p>
     */
    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("voters=").append(voters);
        if (learners != null && !learners.isEmpty()) {
            buf.append(" learners=").append(MajorityConfig.of(learners));
        }
        if (learnersNext != null && !learnersNext.isEmpty()) {
            buf.append(" learners_next=").append(MajorityConfig.of(learnersNext));
        }
        if (autoLeave) {
            buf.append(" autoleave");
        }
        return buf.toString();
    }
}
