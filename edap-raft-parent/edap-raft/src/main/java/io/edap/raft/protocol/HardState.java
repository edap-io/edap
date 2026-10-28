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

/**
 * 需要持久化的 Raft 状态，等价于 etcd 的 {@code raftpb.HardState}。
 *
 * <p>term / vote / commit 三者要么一起变化，要么一起不变，
 * 这样一次写盘就能把三者原子地落下去。</p>
 */
public class HardState {

    private final long term;
    private final long vote;
    private final long commit;

    public HardState(long term, long vote, long commit) {
        this.term = term;
        this.vote = vote;
        this.commit = commit;
    }

    public long getTerm() {
        return term;
    }

    public long getVote() {
        return vote;
    }

    public long getCommit() {
        return commit;
    }

    @Override
    public String toString() {
        return "HardState{term=" + term + ", vote=" + vote + ", commit=" + commit + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof HardState)) {
            return false;
        }
        HardState other = (HardState) o;
        return term == other.term && vote == other.vote && commit == other.commit;
    }

    @Override
    public int hashCode() {
        return (int) (term * 31 + vote) * 31 + (int) commit;
    }
}
