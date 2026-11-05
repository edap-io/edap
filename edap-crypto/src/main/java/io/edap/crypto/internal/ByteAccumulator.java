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

package io.edap.crypto.internal;

import java.util.Arrays;

/**
 * 可增长的字节累加器。
 *
 * <p>native 的 digest / HMAC 是<b>一次性</b> API（{@code digest0(alg, data, off, len)}），
 * 而 SPI 要求流式 —— 所以流式部分在 Java 侧累积，{@code digest()} 时一次性交给 native。</p>
 *
 * <p>不是线程安全的 —— 每个 session 独占一个。</p>
 */
public final class ByteAccumulator {

    private byte[] buf;

    private int len;

    public ByteAccumulator() {
        this(256);
    }

    public ByteAccumulator(int initialCapacity) {
        this.buf = new byte[Math.max(16, initialCapacity)];
    }

    public void append(byte[] data, int offset, int length) {
        if (length <= 0) {
            return;
        }
        ensureCapacity(len + length);
        System.arraycopy(data, offset, buf, len, length);
        len += length;
    }

    public int size() {
        return len;
    }

    public void reset() {
        len = 0;
    }

    /**
     * 返回当前内容的<b>精确长度</b>副本。
     */
    public byte[] toArray() {
        return Arrays.copyOf(buf, len);
    }

    /**
     * 返回内部缓冲（<b>不拷贝</b>）。
     *
     * <p>配合 {@link #size()} 一起用，可以把已累积的数据<b>零拷贝</b>交给 native ——
     * 避免 {@link #toArray()} 再拷一份完整负载。</p>
     *
     * <p><b>调用方约定</b>：只在同一线程、同一 session 内同步使用；不得持有该引用
     * 跨越 {@code append()}（{@code append} 可能触发扩容换数组）。</p>
     */
    public byte[] buffer() {
        return buf;
    }

    private void ensureCapacity(int required) {
        if (required <= buf.length) {
            return;
        }
        int cap = buf.length;
        while (cap < required) {
            cap = cap + (cap >> 1) + 16;
        }
        buf = Arrays.copyOf(buf, cap);
    }
}
