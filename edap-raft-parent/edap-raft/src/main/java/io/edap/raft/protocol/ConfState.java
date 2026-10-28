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

import io.edap.raft.GoFormat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 集群成员配置，等价于 etcd 的 {@code raftpb.ConfState}。
 *
 * <p>只记录节点 id，节点地址由应用侧自己维护。</p>
 */
public class ConfState {

    /** 生效配置里的投票节点 */
    private final List<Long> voters;
    /** 生效配置里的 learner（非投票成员） */
    private final List<Long> learners;
    /** 联合配置里即将退出的旧投票节点 */
    private final List<Long> votersOutgoing;
    /** 旧配置退出后要转成 learner 的节点 */
    private final List<Long> learnersNext;
    /** 联合配置是否自动退出 */
    private final boolean    autoLeave;

    public ConfState(List<Long> voters, List<Long> learners, List<Long> votersOutgoing,
                     List<Long> learnersNext, boolean autoLeave) {
        this.voters = copyOf(voters);
        this.learners = copyOf(learners);
        this.votersOutgoing = copyOf(votersOutgoing);
        this.learnersNext = copyOf(learnersNext);
        this.autoLeave = autoLeave;
    }

    private static List<Long> copyOf(List<Long> src) {
        if (src == null || src.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(src));
    }

    public List<Long> getVoters() {
        return voters;
    }

    public List<Long> getLearners() {
        return learners;
    }

    public List<Long> getVotersOutgoing() {
        return votersOutgoing;
    }

    public List<Long> getLearnersNext() {
        return learnersNext;
    }

    public boolean isAutoLeave() {
        return autoLeave;
    }

    /**
     * 与 etcd 一致的可读描述。
     *
     * <p>etcd 用 {@code %v} 打印 {@code raftpb.ConfState} 这个结构体，得到的是
     * {@code {Voters:[1 2] VotersOutgoing:[] Learners:[] LearnersNext:[] AutoLeave:false}}，
     * 这里保持一致。</p>
     */
    @Override
    public String toString() {
        return "{Voters:" + GoFormat.goSlice(voters)
                + " VotersOutgoing:" + GoFormat.goSlice(votersOutgoing)
                + " Learners:" + GoFormat.goSlice(learners)
                + " LearnersNext:" + GoFormat.goSlice(learnersNext)
                + " AutoLeave:" + autoLeave + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ConfState)) {
            return false;
        }
        ConfState other = (ConfState) o;
        return autoLeave == other.autoLeave
                && voters.equals(other.voters)
                && learners.equals(other.learners)
                && votersOutgoing.equals(other.votersOutgoing)
                && learnersNext.equals(other.learnersNext);
    }

    @Override
    public int hashCode() {
        int result = voters.hashCode();
        result = 31 * result + learners.hashCode();
        result = 31 * result + votersOutgoing.hashCode();
        result = 31 * result + learnersNext.hashCode();
        result = 31 * result + (autoLeave ? 1 : 0);
        return result;
    }
}
