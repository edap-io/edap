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

package io.edap.raft.log;

import io.edap.raft.protocol.Entry;

/**
 * 唯一标识一条 raft 日志，等价于 etcd 的 {@code entryID}。
 *
 * <p>每条日志都属于一个「签发它的任期」，同一个任期内 leader 不会签发两条
 * index 相同的日志，所以 (term, index) 就能唯一确定一条日志。</p>
 */
public final class EntryId {

    private final long term;
    private final long index;

    public EntryId(long term, long index) {
        this.term = term;
        this.index = index;
    }

    /**
     * Go: {@code pbEntryID} —— 取出一条日志的 id。
     */
    public static EntryId of(Entry entry) {
        return new EntryId(entry.getTerm(), entry.getIndex());
    }

    public long getTerm() {
        return term;
    }

    public long getIndex() {
        return index;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EntryId)) {
            return false;
        }
        EntryId other = (EntryId) o;
        return term == other.term && index == other.index;
    }

    @Override
    public int hashCode() {
        return (int) (term * 31 + index);
    }

    @Override
    public String toString() {
        return "entryID{term=" + term + ", index=" + index + "}";
    }
}
