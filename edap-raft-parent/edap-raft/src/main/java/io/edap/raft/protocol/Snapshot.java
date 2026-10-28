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

import java.util.Arrays;

/**
 * 状态机快照，等价于 etcd 的 {@code raftpb.Snapshot}。
 *
 * <p>{@code metadata.index} 之前的日志已经被压缩进 {@code data}，
 * 落后太多的 follower 通过 MsgSnap 追平。</p>
 */
public class Snapshot {

    private final byte[]          data;
    private final SnapshotMetadata metadata;

    public Snapshot(byte[] data, SnapshotMetadata metadata) {
        this.data = data == null ? new byte[0] : data;
        this.metadata = metadata;
    }

    public byte[] getData() {
        return data;
    }

    public SnapshotMetadata getMetadata() {
        return metadata;
    }

    /**
     * 快照点处的日志 index
     */
    public long getLastIncludedIndex() {
        return metadata == null ? 0 : metadata.getIndex();
    }

    /**
     * 快照点处的日志任期
     */
    public long getLastIncludedTerm() {
        return metadata == null ? 0 : metadata.getTerm();
    }

    /**
     * 快照点处生效的成员配置
     */
    public ConfState getConfState() {
        return metadata == null ? null : metadata.getConfState();
    }

    @Override
    public String toString() {
        return "Snapshot{lastIncludedIndex=" + getLastIncludedIndex()
                + ", lastIncludedTerm=" + getLastIncludedTerm() + ", dataLen=" + data.length + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Snapshot)) {
            return false;
        }
        Snapshot other = (Snapshot) o;
        return Arrays.equals(data, other.data)
                && (metadata == null ? other.metadata == null : metadata.equals(other.metadata));
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(data) + (metadata == null ? 0 : metadata.hashCode());
    }
}
