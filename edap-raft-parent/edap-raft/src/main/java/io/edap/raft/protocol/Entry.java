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
 * 一条日志条目，对应 etcd raft 的 {@code raftpb.Entry}。
 */
public class Entry {

    private final long      index;
    private final long      term;
    private final EntryType type;
    private final byte[]    data;

    public Entry(long index, long term, EntryType type, byte[] data) {
        this.index = index;
        this.term = term;
        this.type = type;
        this.data = data == null ? new byte[0] : data;
    }

    public long getIndex() {
        return index;
    }

    public long getTerm() {
        return term;
    }

    public EntryType getType() {
        return type;
    }

    public byte[] getData() {
        return data;
    }

    /**
     * 复制一条日志
     */
    public Entry copy() {
        return new Entry(index, term, type, data);
    }

    @Override
    public String toString() {
        return "Entry{index=" + index + ", term=" + term + ", type=" + type + ", dataLen=" + data.length + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Entry)) {
            return false;
        }
        Entry other = (Entry) o;
        return index == other.index && term == other.term && type == other.type && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return (int) (index * 31 + term) * 31 + type.hashCode();
    }
}
