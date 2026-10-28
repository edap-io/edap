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
 * 快照的元数据，等价于 etcd 的 {@code raftpb.SnapshotMetadata}。
 */
public class SnapshotMetadata {

    private final ConfState confState;
    private final long      index;
    private final long      term;

    public SnapshotMetadata(ConfState confState, long index, long term) {
        this.confState = confState;
        this.index = index;
        this.term = term;
    }

    public ConfState getConfState() {
        return confState;
    }

    public long getIndex() {
        return index;
    }

    public long getTerm() {
        return term;
    }

    @Override
    public String toString() {
        return "SnapshotMetadata{index=" + index + ", term=" + term + ", confState=" + confState + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SnapshotMetadata)) {
            return false;
        }
        SnapshotMetadata other = (SnapshotMetadata) o;
        return index == other.index && term == other.term
                && (confState == null ? other.confState == null : confState.equals(other.confState));
    }

    @Override
    public int hashCode() {
        int result = confState == null ? 0 : confState.hashCode();
        result = 31 * result + (int) (index * 31 + term);
        return result;
    }
}
