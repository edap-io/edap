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

package io.edap.raft.storage;

import io.edap.raft.protocol.Entry;
import io.edap.raft.protocol.HardState;
import io.edap.raft.protocol.Snapshot;

import java.util.List;

/**
 * Raft 日志的只读存储 SPI，等价于 etcd raft 的 {@code Storage} 接口。
 *
 * <p>只暴露读侧能力。写盘由应用层在拿到 Ready 之后主动完成，
 * Raft 核心只依赖这里的读接口来恢复日志状态。</p>
 *
 * <p>实现约定：</p>
 * <ul>
 *     <li>返回的日志条目是只读的，调用方可以持有但不能修改</li>
 *     <li>查询不到日志时抛 {@link io.edap.raft.RaftException}，并且区分两种情况：
 *         index 落在占位条目之前说明日志已经被压缩掉（{@code ErrCompacted}），
 *         index 超出最后一条日志说明这条日志还不存在（{@code ErrUnavailable}）</li>
 *     <li>所有方法都在 Raft 核心线程上被串行调用，实现不需要自己加锁
 *         （{@link MemoryStorage} 仍然加了锁，因为它的写侧可能被应用线程并发调用）</li>
 * </ul>
 */
public interface Storage {

    /**
     * 上次持久化的 HardState，从未持久化过返回 null
     */
    HardState initialState();

    /**
     * 区间 [lo, hi) 的日志条目，总字节数不超过 maxSize。
     *
     * <p>等价于 etcd 的 {@code Storage.Entries(lo, hi, maxSize)}。只要区间非空
     * 就至少返回一条（etcd 的 {@code limitSize} 语义）。</p>
     *
     * @throws io.edap.raft.RaftException lo 落在已被压缩掉的区间里（{@code ErrCompacted}），
     *         或者区间里根本没有可用条目（{@code ErrUnavailable}）时抛出
     */
    List<Entry> entries(long lo, long hi, long maxSize);

    /**
     * 区间 [lo, hi) 的日志条目，不限制总字节数。
     *
     * @throws io.edap.raft.RaftException lo 落在已被压缩掉的区间里（{@code ErrCompacted}），
     *         或者区间里根本没有可用条目（{@code ErrUnavailable}）时抛出
     */
    default List<Entry> entries(long lo, long hi) {
        return entries(lo, hi, Long.MAX_VALUE);
    }

    /**
     * index 处的条目（只含 term）。
     *
     * <p>等价于 etcd 的 {@code Term(i) (uint64, error)}：查不到时不是返回 0，
     * 而是抛出异常，并且区分两种情况——index 落在占位条目之前说明日志已经被
     * 压缩掉（{@code ErrCompacted}），index 超出最后一条日志说明这条日志
     * 还不存在（{@code ErrUnavailable}）。</p>
     *
     * @throws io.edap.raft.RaftException index 查不到时抛出
     */
    Entry term(long index);

    /**
     * 最后一条日志的 index
     */
    long lastIndex();

    /**
     * 第一条日志的 index
     */
    long firstIndex();

    /**
     * 最近一次快照，没有返回 null
     */
    Snapshot snapshot();
}
