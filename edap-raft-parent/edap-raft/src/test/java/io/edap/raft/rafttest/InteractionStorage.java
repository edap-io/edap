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

package io.edap.raft.rafttest;

import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.HardState;
import io.edap.raft.protocol.Snapshot;
import io.edap.raft.storage.MemoryStorage;
import io.edap.raft.storage.Storage;

import java.util.List;
import java.util.function.Supplier;

/**
 * 交互测试用的存储，等价于 etcd rafttest 的 {@code snapOverrideStorage}。
 *
 * <p>底层是一个 {@link MemoryStorage}，但 {@link #snapshot()} 被改写成
 * 「返回节点最近一次快照」：etcd 里节点应用日志时会维护一条快照历史
 * （{@code History}），每次问存储要快照都拿到历史的最后一条。
 * 这样一来，快照里的数据是「已经应用到状态机的内容」，而不是
 * MemoryStorage 内部那个只在压缩时才更新的快照。</p>
 */
public class InteractionStorage implements Storage {

    /** 底层真正的存储 */
    private final MemoryStorage memory;

    /**
     * 快照来源。
     *
     * <p>节点还没加入环境时为 null，这时退回用 {@link MemoryStorage} 自己的快照
     * （etcd 在这个时机读的是 {@code InitialState()} 里的成员配置，
     * 两者的成员配置是一致的）。</p>
     */
    private Supplier<Snapshot> snapshotSupplier;

    public InteractionStorage(MemoryStorage memory) {
        this.memory = memory;
    }

    /**
     * 指定快照的来源，通常是节点自己的快照历史
     */
    public void setSnapshotSupplier(Supplier<Snapshot> snapshotSupplier) {
        this.snapshotSupplier = snapshotSupplier;
    }

    @Override
    public HardState initialState() {
        return memory.initialState();
    }

    @Override
    public List<Entry> entries(long lo, long hi, long maxSize) {
        return memory.entries(lo, hi, maxSize);
    }

    @Override
    public Entry term(long index) {
        return memory.term(index);
    }

    @Override
    public long lastIndex() {
        return memory.lastIndex();
    }

    @Override
    public long firstIndex() {
        return memory.firstIndex();
    }

    @Override
    public Snapshot snapshot() {
        Supplier<Snapshot> supplier = snapshotSupplier;
        if (supplier == null) {
            return memory.snapshot();
        }
        return supplier.get();
    }

    /**
     * 保存硬状态
     */
    public void setHardState(HardState st) {
        memory.setHardState(st);
    }

    /**
     * 应用快照
     */
    public void applySnapshot(Snapshot snap) {
        memory.applySnapshot(snap);
    }

    /**
     * 压缩日志，丢弃 firstIndex 之前的条目
     */
    public void compact(long newFirstIndex) {
        memory.compact(newFirstIndex);
    }

    /**
     * 追加日志条目
     */
    public void append(List<Entry> entries) {
        memory.append(entries);
    }

    /**
     * 底层的内存存储
     */
    public MemoryStorage getMemory() {
        return memory;
    }
}
