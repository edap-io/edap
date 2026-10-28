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

package io.edap.raft.quorum;

/**
 * Raft 日志位置，等价于 etcd 的 {@code quorum.Index}。
 *
 * <p>etcd 用 uint64 承载，这里用 long 表示，比较时按无符号数处理，
 * 以保持与上游一致的语义。</p>
 */
public final class Index implements Comparable<Index> {

    /**
     * 无穷大位置，等价于 etcd 的 {@code math.MaxUint64}，
     * 用于表示“空的多数派配置”这类没有上界的情形。
     */
    public static final Index INFINITY = new Index(Long.MAX_VALUE);

    private final long value;

    public Index(long value) {
        this.value = value;
    }

    /**
     * 取日志位置
     */
    public long value() {
        return value;
    }

    /**
     * 按无符号数比较两个位置，与 etcd 的 uint64 语义一致
     */
    @Override
    public int compareTo(Index o) {
        return Long.compareUnsigned(value, o.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Index)) {
            return false;
        }
        return value == ((Index) o).value;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }

    /**
     * 无穷大位置打印成 {@code ∞}，其余打印成十进制，与 etcd 保持一致
     */
    @Override
    public String toString() {
        return value == Long.MAX_VALUE ? "∞" : Long.toString(value);
    }
}
