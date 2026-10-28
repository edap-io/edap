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

package io.edap.raft;

/**
 * 不需要持久化的状态，等价于 etcd 的 {@code SoftState}。
 *
 * <p>只有 leader id 与当前角色。角色变化时上层通常要据此重建连接池、
 * 切换服务地址等。</p>
 */
public class SoftState {

    /** 当前 leader 的 id，没有 leader 时是 0 */
    private final long      lead;

    /** 当前角色 */
    private final RaftRole  raftState;

    public SoftState(long lead, RaftRole raftState) {
        this.lead = lead;
        this.raftState = raftState;
    }

    /**
     * 当前 leader 的 id，没有 leader 时是 0
     */
    public long getLead() {
        return lead;
    }

    /**
     * 当前角色
     */
    public RaftRole getRaftState() {
        return raftState;
    }

    @Override
    public String toString() {
        return "SoftState{lead=" + lead + ", raftState=" + raftState + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SoftState)) {
            return false;
        }
        SoftState other = (SoftState) o;
        return lead == other.lead && raftState == other.raftState;
    }

    @Override
    public int hashCode() {
        return (int) (lead * 31 + raftState.hashCode());
    }
}
