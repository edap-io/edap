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

package io.edap.crypto.test;

import io.edap.crypto.internal.ByteAccumulator;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ByteAccumulator} —— 内容正确性 + 「搬运次数」这条性能不变量。
 */
public class ByteAccumulatorTest {

    /**
     * 内容正确：多次追加后 {@code toArray()} 等于全部追加内容的拼接。
     */
    @Test
    public void testContentPreserved() {
        ByteAccumulator accumulator = new ByteAccumulator();
        Random random = new Random(7L);
        byte[] expected = new byte[0];
        for (int i = 0; i < 20; i++) {
            byte[] chunk = new byte[1 + random.nextInt(500)];
            random.nextBytes(chunk);
            accumulator.append(chunk, 0, chunk.length);
            expected = concat(expected, chunk);
        }
        assertEquals(expected.length, accumulator.size());
        assertArrayEquals(expected, accumulator.toArray());
    }

    /**
     * ★ 第一次追加<b>按需分配</b>，不多给。
     *
     * <p>这条不是洁癖：native 的 digest / MAC 每次操作都要把整份载荷搬进累加器，
     * 以前从 256 字节起按 1.5 倍几何增长 ⇒ 追加 64KB 要扩容 13 次、
     * 把已经攒下的数据反复搬一遍（总搬运量约 3 倍载荷）。
     * 在 Solaris/SPARC 上实测，那几次多余的搬运与一次 SHA-256 本身同量级。</p>
     */
    @Test
    public void testFirstAppendAllocatesExactly() {
        ByteAccumulator accumulator = new ByteAccumulator();
        byte[] payload = new byte[65536];
        accumulator.append(payload, 0, payload.length);
        assertEquals(65536, accumulator.size());
        assertEquals(65536, accumulator.buffer().length,
                "第一次追加应按需分配（不多给 ⇒ 只搬一次）");
    }

    /**
     * 预置容量的构造方式依然可用，且不触发额外拷贝。
     */
    @Test
    public void testPreSizedAccumulator() {
        ByteAccumulator accumulator = new ByteAccumulator(1024);
        byte[] payload = new byte[1000];
        accumulator.append(payload, 0, payload.length);
        assertEquals(1000, accumulator.size());
        assertTrue(accumulator.buffer().length >= 1000);
        assertArrayEquals(payload, accumulator.toArray());
    }

    /**
     * 跨扩容追加（先大后小再大）内容不能错乱。
     */
    @Test
    public void testContentPreservedAcrossGrowth() {
        ByteAccumulator accumulator = new ByteAccumulator();
        byte[] big = new byte[10000];
        new Random(11L).nextBytes(big);
        accumulator.append(big, 0, big.length);
        byte[] small = new byte[17];
        new Random(13L).nextBytes(small);
        accumulator.append(small, 0, small.length);
        byte[] again = new byte[30000];
        new Random(17L).nextBytes(again);
        accumulator.append(again, 0, again.length);

        assertEquals(big.length + small.length + again.length, accumulator.size());
        assertArrayEquals(concat(concat(big, small), again), accumulator.toArray());
    }

    /**
     * {@code reset()} 只清长度，不清内容 —— 复用同一个缓冲。
     */
    @Test
    public void testResetKeepsBuffer() {
        ByteAccumulator accumulator = new ByteAccumulator();
        byte[] payload = new byte[5000];
        accumulator.append(payload, 0, payload.length);
        int capacity = accumulator.buffer().length;
        accumulator.reset();
        assertEquals(0, accumulator.size());
        assertEquals(capacity, accumulator.buffer().length, "reset 不该重新分配");
        assertEquals(0, accumulator.toArray().length);
    }

    /**
     * 追加 0 长度是合法的空操作。
     */
    @Test
    public void testEmptyAppendIsNoop() {
        ByteAccumulator accumulator = new ByteAccumulator();
        accumulator.append(new byte[10], 0, 0);
        assertEquals(0, accumulator.size());
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
