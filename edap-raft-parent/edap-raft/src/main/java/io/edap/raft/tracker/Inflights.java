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

package io.edap.raft.tracker;

import io.edap.raft.RaftException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 在途消息窗口，等价于 etcd 的 {@code tracker.Inflights}。
 *
 * <p>它限制发给某个 follower 但还没有被确认的 AppendEntries 消息：
 * 每发出一条消息就把该消息最后一条日志的 index 和总字节数记进窗口，
 * 收到确认后用 {@link #freeLE(long)} 把已经确认的部分释放掉。
 * 窗口满了说明对端消费不过来，leader 就要暂停向它发日志。</p>
 *
 * <p>内部是一个环形缓冲区，只保存每条在途消息的最后一个 index 与字节数，
 * 容量按需翻倍，但不会超过构造时给定的上限。</p>
 */
public final class Inflights {

    /** 默认在途窗口大小，与 etcd 的默认 MaxInflightMsgs 保持一致 */
    public static final int DEFAULT_SIZE = 256;

    /** 允许的在途消息条数上限 */
    private final int  size;
    /** 在途消息的总字节数上限，0 表示不限制 */
    private final long maxBytes;
    /** 环形缓冲区的起始位置 */
    private int        start;
    /** 当前在途消息条数 */
    private int        count;
    /** 当前在途消息的总字节数 */
    private long       bytes;
    /** 环形缓冲区，存放每条在途消息最后一个日志 index 与字节数 */
    private Inflight[] buffer;

    /**
     * 创建一个最多容纳 size 条在途消息、总字节数不超过 maxBytes 的窗口。
     *
     * <p>size 为 0 时窗口永远是满的（etcd 的语义），负数没有意义，直接拒绝。</p>
     *
     * @param size     窗口上限
     * @param maxBytes 在途消息的总字节数上限，0 表示不限制；这是一个软限制，
     *                 即允许单条消息把总量从小于 maxBytes 推到大于等于 maxBytes
     * @throws RaftException size 或 maxBytes 为负数时抛出
     */
    public Inflights(int size, long maxBytes) {
        if (maxBytes < 0) {
            throw new RaftException("maxBytes must not be negative: " + maxBytes);
        }
        if (size < 0) {
            throw new RaftException("size must not be negative: " + size);
        }
        this.size = size;
        this.maxBytes = maxBytes;
        this.buffer = new Inflight[0];
    }

    /**
     * 当前在途消息条数。
     */
    public int count() {
        return count;
    }

    /**
     * 当前在途消息的总字节数。
     */
    public long bytes() {
        return bytes;
    }

    /**
     * 窗口允许的在途消息条数上限。
     */
    public int size() {
        return size;
    }

    /**
     * 窗口是否已满，已满时不能再加入新的在途消息。
     *
     * <p>条数达到上限，或者总字节数达到上限（且设置了字节上限）时都算满。</p>
     */
    public boolean full() {
        return count == size || (maxBytes != 0 && bytes >= maxBytes);
    }

    /**
     * 登记一条新的在途消息。
     *
     * <p>index 是这条消息最后一条日志的 index，bytes 是这条消息携带的日志总字节数。
     * 调用前必须先确认 {@link #full()} 为 false，否则抛出
     * {@link RaftException}（对应 Go 里的 panic）。连续调用要求 index 单调递增。</p>
     *
     * @throws RaftException 窗口已满时抛出
     */
    public void add(long index, long bytes) {
        if (full()) {
            throw new RaftException("cannot add into a Full inflights");
        }
        int next = start + count;
        if (next >= size) {
            next -= size;
        }
        if (next >= buffer.length) {
            grow();
            if (next >= buffer.length) {
                // size 不是 2 的幂时倍增可能刚好盖不住目标位置，直接扩到够用
                Inflight[] newBuffer = new Inflight[Math.min(size, next + 1)];
                System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
                buffer = newBuffer;
            }
        }
        buffer[next] = new Inflight(index, bytes);
        count++;
        this.bytes += bytes;
    }

    /**
     * 释放所有 index 小于等于 to 的在途消息，等价于 Go 的 {@code FreeLE}。
     *
     * <p>to 小于窗口最左边的 index 时什么都不做（乱序到达的旧确认）。</p>
     */
    public void freeLE(long to) {
        if (count == 0 || to < buffer[start].index) {
            // 已经落在窗口左侧，不属于本次释放范围
            return;
        }
        int idx = start;
        int i = 0;
        long freed = 0;
        for (; i < count; i++) {
            if (to < buffer[idx].index) {
                // 找到第一条还没被确认的在途消息
                break;
            }
            freed += buffer[idx].bytes;
            idx++;
            if (idx >= size) {
                idx -= size;
            }
        }
        count -= i;
        bytes -= freed;
        start = idx;
        if (count == 0) {
            // 窗口空了，把起点复位，免得后面白白扩容
            start = 0;
        }
    }

    /**
     * 清空窗口，丢弃所有在途记录（状态机切换时调用）。
     */
    public void reset() {
        start = 0;
        count = 0;
        bytes = 0;
    }

    /**
     * 复制一份与自身内容相同、但不共享内存的窗口，等价于 Go 的 {@code Clone}。
     */
    @Override
    public Inflights clone() {
        Inflights ins = new Inflights(size, maxBytes);
        ins.start = start;
        ins.count = count;
        ins.bytes = bytes;
        ins.buffer = Arrays.copyOf(buffer, buffer.length);
        return ins;
    }

    /**
     * 扩容环形缓冲区，容量翻倍但不超过窗口上限。
     *
     * <p>按需扩容而不是一次性按上限分配，是为了兼顾单进程里成百上千个 raft 组的场景。</p>
     */
    private void grow() {
        int newSize = buffer.length * 2;
        if (newSize == 0) {
            newSize = 1;
        } else if (newSize > size) {
            newSize = size;
        }
        Inflight[] newBuffer = new Inflight[newSize];
        System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
        buffer = newBuffer;
    }

    @Override
    public String toString() {
        return "Inflights{count=" + count + ", bytes=" + bytes + ", size=" + size + "}";
    }

    /**
     * 测试专用：设置环形缓冲区的起始位置。
     *
     * <p>Go 的测试直接给 {@code Inflights} 的 {@code start} 字段赋值，
     * Java 侧用这个方法达到同样的效果。</p>
     */
    void setStart(int start) {
        this.start = start;
    }

    /**
     * 测试专用：取环形缓冲区的起始位置。
     */
    int getStart() {
        return start;
    }

    /**
     * 测试专用：按环形顺序取出窗口里的全部在途记录。
     *
     * <p>Go 的测试直接比较 {@code Inflights} 结构体里的 buffer，
     * Java 侧用这个方法把同样的信息取出来。</p>
     */
    List<Inflight> bufferEntries() {
        List<Inflight> entries = new ArrayList<>(count);
        int idx = start;
        for (int i = 0; i < count; i++) {
            entries.add(buffer[idx]);
            idx++;
            if (idx >= size) {
                idx -= size;
            }
        }
        return entries;
    }

    /**
     * 一条在途消息的记录，等价于 Go 里的 {@code inflight} 结构。
     */
    static final class Inflight {

        /** 消息内最后一条日志的 index */
        private final long index;
        /** 消息携带的日志总字节数 */
        private final long bytes;

        private Inflight(long index, long bytes) {
            this.index = index;
            this.bytes = bytes;
        }

        long getIndex() {
            return index;
        }

        long getBytes() {
            return bytes;
        }
    }
}
