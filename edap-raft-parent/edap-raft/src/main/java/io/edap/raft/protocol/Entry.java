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

    /**
     * 请求 id，0 表示这条条目没有对应的客户端请求。
     *
     * <p>异步提案靠它把「提交」和「客户端的 future」对上：请求 id 随条目
     * 一起复制到各个节点，任何节点都能在条目被提交时找到对应的调用方。</p>
     *
     * <p>不能用「index → future」的方式：客户端向 follower 提案时
     * follower 会转发给 leader，本地拿不到 index；条目被新 leader 截断覆盖后，
     * 同一个 index 会对应完全不同的提案，按 index 匹配会误报「提交成功」。</p>
     */
    private final long      requestId;

    public Entry(long index, long term, EntryType type, byte[] data) {
        this(index, term, type, data, 0L);
    }

    public Entry(long index, long term, EntryType type, byte[] data, long requestId) {
        this.index = index;
        this.term = term;
        this.type = type;
        this.data = data == null ? new byte[0] : data;
        this.requestId = requestId;
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
     * 请求 id，0 表示这条条目没有对应的客户端请求。
     */
    public long getRequestId() {
        return requestId;
    }

    /**
     * 复制一条日志
     */
    public Entry copy() {
        return new Entry(index, term, type, data, requestId);
    }

    @Override
    public String toString() {
        return "Entry{index=" + index + ", term=" + term + ", type=" + type
                + ", requestId=" + requestId + ", dataLen=" + data.length + "}";
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
        return index == other.index && term == other.term && type == other.type
                && requestId == other.requestId && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return (int) (index * 31 + term) * 31 + type.hashCode();
    }
}
