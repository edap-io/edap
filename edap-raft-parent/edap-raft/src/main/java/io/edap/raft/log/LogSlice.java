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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一段合法的 raft 日志切片，等价于 etcd 的 {@code logSlice}。
 *
 * <p>每个切片都放在某个 leader 任期的语境里看。这个任期不一定等于切片里
 * 条目的任期，因为 leader 的日志里既有它自己任期产生的条目，也有更早任期的条目。</p>
 *
 * <p>两个任期相同的切片一定是一致的：同一个 index 上不会出现两条不同的日志。
 * 反过来不成立：任期不同的两个切片可能既有相同的部分也有分歧的部分。</p>
 *
 * <p>一个格式正确的切片满足：</p>
 * <ol>
 *     <li>{@code entries[i].index == prev.index + 1 + i}（连续）</li>
 *     <li>{@code prev.term <= entries[0].term}（任期不回退）</li>
 *     <li>{@code entries[i-1].term <= entries[i].term}</li>
 *     <li>{@code entries[len-1].term <= term}</li>
 * </ol>
 */
public final class LogSlice {

    /** 包含这些条目的 leader 任期 */
    private final long       term;
    /** 紧挨在这些条目之前的那条日志的 id */
    private final EntryId    prev;
    /** 连续的日志条目 */
    private final List<Entry> entries;

    public LogSlice(long term, EntryId prev, List<Entry> entries) {
        this.term = term;
        this.prev = prev;
        this.entries = entries == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public long getTerm() {
        return term;
    }

    public EntryId getPrev() {
        return prev;
    }

    public List<Entry> getEntries() {
        return entries;
    }

    /**
     * Go: {@code lastIndex} —— 最后一条条目的 index；没有条目时就是 prev 的 index。
     */
    public long lastIndex() {
        return prev.getIndex() + entries.size();
    }

    /**
     * Go: {@code lastEntryID} —— 最后一条条目的 id；没有条目时就是 prev。
     */
    public EntryId lastEntryId() {
        if (!entries.isEmpty()) {
            return EntryId.of(entries.get(entries.size() - 1));
        }
        return prev;
    }

    /**
     * Go: {@code valid} —— 判断这个切片是不是格式正确的日志切片。
     *
     * @return null 表示合法；否则返回不合法的原因
     */
    public String valid() {
        EntryId p = prev;
        for (Entry entry : entries) {
            EntryId id = EntryId.of(entry);
            if (id.getTerm() < p.getTerm() || id.getIndex() != p.getIndex() + 1) {
                return "leader term " + term + ": entries " + p + " and " + id + " not consistent";
            }
            p = id;
        }
        if (term < p.getTerm()) {
            return "leader term " + term + ": entry " + p + " has a newer term";
        }
        return null;
    }

    @Override
    public String toString() {
        return "logSlice{term=" + term + ", prev=" + prev + ", entries=" + entries.size() + "}";
    }
}
